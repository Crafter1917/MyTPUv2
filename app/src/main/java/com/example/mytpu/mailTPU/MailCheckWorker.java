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

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import okhttp3.OkHttpClient;

public class MailCheckWorker extends Worker {
    private static final String TAG = "MailCheckWorker";
    public static final String CHANNEL_ID = "mail_channel";
    public static final String PREFS_NAME = "mail_prefs";
    public static final String KEY_LAST_UIDS = "last_uids";
    public static final String KEY_LAST_UIDS_PREFIX = "last_uids_";

    private static final Object GLOBAL_AUTH_LOCK = new Object();
    private boolean isCertificateError() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("mail_prefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("cert_error", false);
    }

    private void markCertificateError() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("mail_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("cert_error", true).apply();
    }

    private boolean isCertificateException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SSLHandshakeException ||
                    cause instanceof CertificateException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
    public MailCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        createNotificationChannel();
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (SessionLock.LOCK) {
        try {
            if (isCertificateError()) {
                Log.w(TAG, "Certificate validation error, skipping mail check");
                return Result.success();
            }
            if (isAuthBlocked()) {
                Log.w(TAG, "Authentication blocked, skipping check");
                return Result.success();
            }
            synchronized (GLOBAL_AUTH_LOCK) {
                int maxAttempts = 10;
                int attempt = 0;
                boolean success = false;

                while (attempt < maxAttempts && !success) {
                    attempt++;
                    Log.d(TAG, "MailCheckWorker STARTED");
                    try {
                        Log.d(TAG, "Initializing client...");
                        MailActivity.initSharedPreferences(getApplicationContext());
                        if (!restoreSession()) {
                            Log.e(TAG, "Session restoration failed");
                            continue;
                        }

                        boolean sessionRestored = restoreSession();
                        if (!sessionRestored) {
                            Log.e(TAG, "Failed to restore session");
                            continue;
                        }
                        String folder = getInputData().getString("folder");

                        JSONArray messages = RoundcubeAPI.fetchMessages(
                                getApplicationContext(),
                                1,
                                folder // Используем полученную папку
                        );

                        Set<String> currentUids = extractUids(messages);
                        Set<String> newUids = findNewEmails(currentUids, folder); // Передаем папку

                        if (!newUids.isEmpty() && "INBOX".equals(folder)) { // Только для INBOX!
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
                            saveLastUids(currentUids, folder); // Сохраняем с указанием папки
                        }

                        Log.d(TAG, "Received " + messages.length() + " messages");
                        Log.d(TAG, "Current UIDs: " + currentUids);
                        Log.d(TAG, "New UIDs: " + newUids);
                        success = true;
                        Log.d(TAG, "Session restored: " + sessionRestored);
                        Log.d(TAG, "Fetched messages: " + messages.length());
                        Log.d(TAG, "New emails found: " + newUids.size());
                        return Result.success();

                    } // Замените существующий блок catch
                    catch (MailActivity.SessionExpiredException e) {
                        // Агрессивная очистка при истечении сессии
                        MailActivity.clearSessionData(getApplicationContext());
                        FileLogger.log(TAG, "Session expired: " + e.getMessage());
                    }
                    long delay = 5000 * (long) Math.pow(2, attempt);
                    Thread.sleep(delay);
                }
                return Result.retry();
            }
        } catch (Exception e) {
            // Обработка ошибок сертификата
            if (isCertificateException(e)) {
                markCertificateError();
            } else if (e instanceof MailActivity.SessionExpiredException) {
                handleAuthFailure(); // Блокируем при частых ошибках
            }
            scheduleDelayedRetry(15);
            return Result.retry();
        }
    }
    }
    private void handleAuthFailure() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("mail_prefs", Context.MODE_PRIVATE);
        int failures = prefs.getInt("auth_failures", 0) + 1;
        prefs.edit().putInt("auth_failures", failures).apply();

        if (failures > 3) {
            prefs.edit().putBoolean("auth_blocked", true).apply();
            Log.w(TAG, "Authentication blocked due to multiple failures");
        }
    }

    private boolean isAuthBlocked() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("mail_prefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("auth_blocked", false);
    }
    private void scheduleDelayedRetry(int SECONDS) {
        OneTimeWorkRequest retryWork = new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                .setInitialDelay(SECONDS, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork("mailRetry", ExistingWorkPolicy.REPLACE, retryWork);
    }

    private boolean restoreSession() {
        Context context = getApplicationContext();
        MailActivity.initSharedPreferences(context);
        MailActivity.refreshCsrfToken(context);
        if (!MailActivity.isSessionValid(context)) {
            Log.d(TAG, "Session invalid, forcing reauthentication");
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

    private Set<String> findNewEmails(Set<String> currentUids, String folder) {
        if (folder == null || folder.isEmpty()) {
            folder = "INBOX"; // Значение по умолчанию
        }
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String key = KEY_LAST_UIDS_PREFIX + folder;
        Set<String> lastUids = new HashSet<>(
                prefs.getStringSet(key, Collections.emptySet())
        );

        Set<String> newUids = new HashSet<>(currentUids);
        newUids.removeAll(lastUids);
        Log.d(TAG, "Last UIDs (" + folder + "): " + lastUids.size());
        Log.d(TAG, "New UIDs count (" + folder + "): " + newUids.size());
        return newUids;
    }

    private void saveLastUids(Set<String> uids, String folder) {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_LAST_UIDS_PREFIX + folder;

        prefs.edit()
                .putStringSet(key, uids)
                .apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Context context = getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Почтовые уведомления",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Уведомления о новых письмах");
        channel.enableLights(true);
        channel.setLightColor(Color.BLUE);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 300, 200, 300});
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    private void showNotification(List<EmailPreview> emails) {
        int newCount = emails.size();
        if (newCount == 0) return;
        if (emails == null || emails.isEmpty()) {
            Log.d(TAG, "No emails to notify");
            return;
        }
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