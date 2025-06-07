package com.example.mytpu.schedule;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.mytpu.R;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    private static final String CHANNEL_ID = "alarm_channel";
    private static final int NOTIFICATION_ID = 100;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received at " + System.currentTimeMillis());

        // Проверяем включены ли уведомления
        if (!AlarmScheduler.areNotificationsEnabled(context)) {
            Log.d(TAG, "Notifications are disabled - skipping");
            return;
        }

        int paraNumber = intent.getIntExtra("para_number", 1);
        String lessonTime = intent.getStringExtra("lesson_time");
        String lessonSubject = intent.getStringExtra("lesson_subject");
        String lessonAudience = intent.getStringExtra("lesson_audience");

        Log.d(TAG, String.format("Preparing notification for para %d: %s at %s",
                paraNumber, lessonSubject, lessonTime));

        // Проверка настройки "Только для текущего поиска"
        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        boolean forCurrentSearch = prefs.getBoolean("forCurrentSearch", false);

        if (forCurrentSearch) {
            SharedPreferences searchPrefs = context.getSharedPreferences("SearchPrefs", Context.MODE_PRIVATE);
            String currentSearch = searchPrefs.getString("last_search", "");
            if (currentSearch.isEmpty()) {
                Log.d(TAG, "Current search is empty - skipping notification");
                return;
            }
        }

        // Создаем уведомление
        createNotification(context, paraNumber, lessonSubject, lessonTime, lessonAudience);

        // Запускаем полноэкранную активность будильника
        Intent alarmIntent = new Intent(context, FullscreenAlarmActivity.class);
        alarmIntent.putExtras(intent.getExtras());
        alarmIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        context.startActivity(alarmIntent);
    }

    private void createNotification(Context context, int paraNumber, String subject, String time, String audience) {
        createNotificationChannel(context);

        // Используем стандартный звук будильника
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) {
            // Если нет стандартного звука будильника, используем уведомление
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        String soundUriString = prefs.getString("alarm_sound_uri", null);

        if (soundUriString != null) {
            alarmSound = Uri.parse(soundUriString);
        } else {
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ Скоро " + paraNumber + "-я пара!")
                .setContentText(subject + " в " + time + ", ауд. " + audience)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(alarmSound)
                .setAutoCancel(true);

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(NOTIFICATION_ID + paraNumber, builder.build());
        Log.d(TAG, "Notification shown for para " + paraNumber);
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Уведомления о парах",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Уведомления о начале пар");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{100, 200, 100, 200});

            // Устанавливаем звук по умолчанию для канала
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            channel.setSound(alarmSound, null);

            NotificationManager notificationManager =
                    context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}