package com.example.mytpu.schedule;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmService extends Service {
    private static final String TAG = "AlarmService";
    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "alarm_service_channel";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AlarmService started");

        // Создаем канал уведомлений для Android 8.0+
        createNotificationChannel();

        // Запускаем активность будильника
        startAlarmActivity(intent);

        // Останавливаем сервис после запуска активности
        stopSelf();

        return START_STICKY;
    }

    private void startAlarmActivity(Intent intent) {
        int paraNumber = intent.getIntExtra("para_number", 1);
        if (intent.getBooleanExtra("activity_launched", false)) {
            Log.d(TAG, "Activity already launched, skipping");
            return;
        }
        intent.putExtra("activity_launched", true);
        Log.d(TAG, "Starting alarm activity for para: " + paraNumber);

        // 1. Проверка, не отключен ли уже будильник
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String alarmKey = todayKey + "_" + paraNumber + "_dismissed";

        SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
        if (prefs.getBoolean(alarmKey, false)) {
            Log.d(TAG, "Alarm already dismissed, skipping activity start");
            return;
        }

        // 2. Проверка времени пары
        long lessonStartTime = intent.getLongExtra("lesson_start_time", 0);
        if (lessonStartTime > 0 && System.currentTimeMillis() > lessonStartTime) {
            Log.d(TAG, "Lesson has already started, skipping activity");
            return;
        }

        // 3. Запускаем активность
        Intent alarmIntent = new Intent(this, FullscreenAlarmActivity.class);
        alarmIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                        Intent.FLAG_ACTIVITY_NO_HISTORY
        );
        alarmIntent.putExtras(intent.getExtras());
        startActivity(alarmIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alarm Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}