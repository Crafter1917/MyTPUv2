package com.example.mytpu.mailTPU;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mytpu.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class MailCheckWorker extends Worker {
    private static final String TAG = "MailCheckWorker";
    public static final String CHANNEL_ID = "mail_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String PREFS_NAME = "mail_prefs";
    public static final String KEY_LAST_UIDS = "last_uids";

    public MailCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        createNotificationChannel();
    }

    @NonNull
    @Override
    public Result doWork() {
        MailActivity.initSharedPreferences(getApplicationContext());
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(getApplicationContext()).getClient();
        int maxAttempts = 2;
        int attempt = 0;
        boolean success = false;

        while (attempt < maxAttempts && !success) {
            attempt++;
            Log.d(TAG, "MailCheckWorker STARTED");
            try {
                Log.d(TAG, "Initializing client...");
                MailActivity.initSharedPreferences(getApplicationContext());
                if (client == null) {
                    Log.e(TAG, "OkHttpClient is null");
                    continue;
                }
                boolean sessionRestored = restoreSession();
                if (!sessionRestored) {
                    Log.e(TAG, "Failed to restore session");
                    continue;
                }
                JSONArray messages = RoundcubeAPI.fetchMessages(
                        getApplicationContext(),
                        1,
                        "INBOX"
                );
                Set<String> currentUids = extractUids(messages);
                Set<String> newUids = findNewEmails(currentUids);

                if (!newUids.isEmpty()) {
                    // Получаем детали новых писем
                    List<EmailPreview> emailPreviews = new ArrayList<>();
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject msg = messages.getJSONObject(i);
                        String uid = msg.getString("uid");
                        if (newUids.contains(uid)) {
                            try {
                                emailPreviews.add(new EmailPreview(msg));
                            } catch (JSONException e) {
                                Log.e(TAG, "Error creating email preview", e);
                            }
                        }
                    }

                    showNotification(emailPreviews);
                    saveLastUids(currentUids);
                }

                Log.d(TAG, "Received " + messages.length() + " messages");
                Log.d(TAG, "Current UIDs: " + currentUids);
                Log.d(TAG, "New UIDs: " + newUids);
                success = true;
                Log.d(TAG, "Session restored: " + sessionRestored);
                Log.d(TAG, "Fetched messages: " + messages.length());
                Log.d(TAG, "New emails found: " + newUids.size());
                return Result.success();

            } catch (Exception e) {
                Log.e(TAG, "Mail check error on attempt " + attempt, e);
                if (!restoreSession()) {
                    Log.e(TAG, "Failed to restore session after error");
                }
            }
        }

        if (!success) {
            Log.e(TAG, "Mail check failed after " + maxAttempts + " attempts");
            return Result.retry();
        }
        scheduleNextRun();
        return Result.success();
    }
    private void scheduleNextRun() {
        OneTimeWorkRequest nextRequest = new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork(
                        "mailCheck",
                        ExistingWorkPolicy.REPLACE,
                        nextRequest
                );
    }
    private boolean restoreSession() {
        Context context = getApplicationContext();
        MailActivity.initSharedPreferences(context);
        if (!MailActivity.isSessionValid(context)) {
            return MailActivity.forceReauthenticate(context);
        }
        return true;
    }


    private Set<String> extractUids(JSONArray messages) throws JSONException {
        Set<String> uids = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            uids.add(msg.getString("uid"));
        }
        return uids;
    }

    private Set<String> findNewEmails(Set<String> currentUids) {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Создаем копию для изменяемого Set
        Set<String> lastUids = new HashSet<>(
                prefs.getStringSet(KEY_LAST_UIDS, Collections.emptySet())
        );

        Set<String> newUids = new HashSet<>(currentUids);
        newUids.removeAll(lastUids);

        // Отладочная проверка
        Log.d(TAG, "Last UIDs: " + lastUids.size());
        Log.d(TAG, "New UIDs count: " + newUids.size());

        return newUids;
    }

    private void saveLastUids(Set<String> uids) {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        prefs.edit()
                .putStringSet(KEY_LAST_UIDS, uids)
                .apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Context context = getApplicationContext();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Почтовые уведомления",
                NotificationManager.IMPORTANCE_HIGH // Высокий приоритет
        );
        channel.setDescription("Уведомления о новых письмах");
        channel.enableLights(true);
        channel.setLightColor(Color.BLUE);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 300, 200, 300});
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(channel);
            Log.d(TAG, "Notification channel created with high importance");
        } else {
            // Пересоздаем канал для обновления настроек
            manager.deleteNotificationChannel(CHANNEL_ID);
            manager.createNotificationChannel(channel);
            Log.d(TAG, "Notification channel recreated");
        }
    }

    private void showNotification(List<EmailPreview> emails) {
        int newCount = emails.size();
        if (newCount == 0) return;

        Context context = getApplicationContext();

        // Проверка разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Notifications permission missing!");
                return;
            }
        }

        // Создаем интент для открытия приложения
        Intent intent = new Intent(context, MailActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Создаем базовое уведомление
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mail)
                .setContentTitle(newCount == 1 ? "Новое письмо" : "Новых писем: " + newCount)
                .setContentText(newCount == 1 ? "От: " + emails.get(0).from : "Нажмите для просмотра")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setVibrate(new long[]{0, 300, 200, 300});

        // Для Android 7.0+ используем расширенный стиль
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (newCount == 1) {
                EmailPreview email = emails.get(0);
                builder.setContentTitle(email.subject)
                        .setContentText("От: " + email.from);

                // Добавляем большую картинку для стиля
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                            .bigText(email.snippet)
                            .setBigContentTitle(email.subject)
                            .setSummaryText("От: " + email.from);

                    builder.setStyle(bigTextStyle);
                }
            } else {
                NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle()
                        .setBigContentTitle("Новых писем: " + newCount)
                        .setSummaryText("Почтовый сервис ТПУ");

                int maxLines = Math.min(emails.size(), 5);
                for (int i = 0; i < maxLines; i++) {
                    EmailPreview email = emails.get(i);

                    // Форматируем строку: [Отправитель] Тема (первые 30 символов)
                    String line = String.format(Locale.getDefault(),
                            "• %s: %s",
                            shortenSender(email.from),
                            shortenSubject(email.subject)
                    );

                    inboxStyle.addLine(line);
                }

                builder.setStyle(inboxStyle);
            }
        }

        // Уникальный ID для каждого уведомления
        int notificationId = (int) System.currentTimeMillis();

        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            manager.notify(notificationId, builder.build());
            Log.d(TAG, "Notification shown for " + newCount + " new emails");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show notification", e);
        }
    }

    private String shortenSender(String sender) {
        if (sender.length() > 20) {
            return sender.substring(0, 17) + "...";
        }
        return sender;
    }

    private String shortenSubject(String subject) {
        if (subject.length() > 30) {
            return subject.substring(0, 27) + "...";
        }
        return subject;
    }

    public static class EmailPreview {
        public final String subject;
        public final String from;
        public final String snippet;

        public EmailPreview(JSONObject msg) throws JSONException {
            this.subject = msg.optString("subject", "(без темы)");
            String fromto = msg.optString("fromto", "Неизвестный отправитель");
            this.from = MailActivity.extractFrom(fromto);

            // Безопасное получение snippet
            if (msg.has("snippet")) {
                this.snippet = msg.getString("snippet");
            } else {
                // Альтернативные варианты, если snippet отсутствует
                if (msg.has("body")) {
                    String body = msg.optString("body", "");
                    this.snippet = body.substring(0, Math.min(100, body.length())) + "...";
                } else {
                    this.snippet = "@текст доступен при открытии!@";
                }
            }
        }
    }
}
