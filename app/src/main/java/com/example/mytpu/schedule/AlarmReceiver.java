package com.example.mytpu.schedule;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    public static final String CHANNEL_ID = "alarm_channel";
    public static final int NOTIFICATION_ID = 100;

    @SuppressLint("NotificationTrampoline")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received at " + System.currentTimeMillis());

        // 1. Обработка действия "отключить"
        if (intent.getAction() != null && intent.getAction().equals("DISMISS_ALARM")) {
            handleDismissAction(context, intent);
            return;
        }

        // 2. Проверка включены ли уведомления
        if (!AlarmScheduler.areNotificationsEnabled(context)) {
            Log.d(TAG, "Notifications are disabled - skipping");
            return;
        }
        if (isDuplicateEvent(intent)) {
            Log.d(TAG, "Duplicate event detected, skipping");
            return;
        }
        int paraNumber = intent.getIntExtra("para_number", 1);

        // 3. Проверка не отключен ли уже будильник
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String alarmKey = todayKey + "_" + paraNumber + "_dismissed";

        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        if (prefs.getBoolean(alarmKey, false)) {
            Log.d(TAG, "Alarm was already dismissed for para " + paraNumber);
            return;
        }

        // 4. Проверка типа будильника
        boolean isFullscreenAlarm = intent.getBooleanExtra("is_fullscreen", false);

        // 5. Проверка времени пары
        long lessonStartTime = intent.getLongExtra("lesson_start_time", 0);
        if (lessonStartTime > 0 && System.currentTimeMillis() > lessonStartTime) {
            Log.d(TAG, "Lesson has already started, skipping notification");
            return;
        }

        // 6. Обработка только уведомлений
        if (!isFullscreenAlarm) {
            showEarlyNotification(context, intent);
        }
    }

    private void handleDismissAction(Context context, Intent intent) {
        int paraNumber = intent.getIntExtra("para_number", 1);

        // 1. Помечаем будильник как отключенный
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String alarmKey = todayKey + "_" + paraNumber + "_dismissed";
        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(alarmKey, true).apply();

        // 2. Отменяем все связанные будильники
        cancelAlarmsForLesson(context, paraNumber);

        // 3. Отменяем текущее уведомление
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID + paraNumber);
        }

        // 4. Закрываем активность будильника через broadcast
        Intent dismissIntent = new Intent("ALARM_DISMISSED");
        dismissIntent.putExtra("para_number", paraNumber);
        context.sendBroadcast(dismissIntent);

        // 5. Не создаем новую активность - просто закрываем уведомление
        Log.d(TAG, "Alarm dismissed from notification for para: " + paraNumber);
    }

    private void cancelAlarmsForLesson(Context context, int paraNumber) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // 1. Отмена уведомления
        Intent notificationIntent = new Intent(context, AlarmReceiver.class);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(
                context,
                paraNumber * 100,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(notificationPendingIntent);

        // 2. Отмена полноэкранного будильника
        Intent serviceIntent = new Intent(context, AlarmService.class);
        PendingIntent servicePendingIntent = PendingIntent.getService(
                context,
                paraNumber * 100 + 1,
                serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(servicePendingIntent);

        // 3. Гарантированно закрываем уведомление
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID + paraNumber);
        }
    }

    private boolean isDuplicateEvent(Intent intent) {
        long eventTime = intent.getLongExtra("event_time", 0);
        int paraNumber = intent.getIntExtra("para_number", -1);

        if (eventTime == 0) {
            // Добавляем метку времени для новых событий
            intent.putExtra("event_time", System.currentTimeMillis());
            return false;
        }

        // События считаются дублями если пришли в течение 1 секунды
        return (System.currentTimeMillis() - eventTime) < 1000;
    }

    private void showEarlyNotification(Context context, Intent intent) {
        int paraNumber = intent.getIntExtra("para_number", 1);
        String lessonTime = intent.getStringExtra("lesson_time");
        String lessonSubject = intent.getStringExtra("lesson_subject");

        // Защита от null значений
        if (lessonSubject == null || lessonTime == null) {
            Log.e(TAG, "Subject or time is null, skipping notification");
            return;
        }

        createNotificationChannel(context);

        // Создаем PendingIntent для отключения
        Intent dismissIntent = new Intent(context, AlarmReceiver.class);
        dismissIntent.setAction("DISMISS_ALARM");
        dismissIntent.putExtra("para_number", paraNumber);
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                paraNumber,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        @SuppressLint("NotificationTrampoline") NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ Скоро " + paraNumber + "-я пара!")
                .setContentText("Через 10 минут: " + lessonSubject + " в " + lessonTime)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_exit, "Отключить", dismissPendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID + paraNumber, builder.build());
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Уведомления о парах",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{100, 200, 100, 200});
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}