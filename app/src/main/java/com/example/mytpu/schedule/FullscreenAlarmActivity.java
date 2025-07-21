package com.example.mytpu.schedule;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FullscreenAlarmActivity extends AppCompatActivity {
    private static final String TAG = "FullscreenAlarmActivity";
    private Ringtone ringtone;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().hasExtra("dismiss")) {
            int paraNumber = getIntent().getIntExtra("para_number", 1);
            dismissAlarm(paraNumber);
            return;
        }
        // Установка параметров окна
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_fullscreen_alarm);

        // Остальной код активности...
        int paraNumber = getIntent().getIntExtra("para_number", 1);
        String subject = getIntent().getStringExtra("lesson_subject");
        String time = getIntent().getStringExtra("lesson_time");

        if (subject == null) {
            finish();
            return;
        }

        TextView info = findViewById(R.id.alarm_info);
        info.setText("Пара " + paraNumber + ": " + subject + "\nВремя: " + time);

        Button dismissButton = findViewById(R.id.dismiss_button);
        dismissButton.setOnClickListener(v -> dismissAlarm(paraNumber));

        // Запуск звука будильника
        startAlarmSound();

        // Удерживаем WakeLock
        acquireWakeLock();
    }

    // FullscreenAlarmActivity.java
    private void startAlarmSound() {
        SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
        String soundUriString = prefs.getString("alarm_sound_uri", null);

        // Если пользователь выбрал "Без звука"
        if ("Без звука".equals(prefs.getString("alarm_sound_title", ""))) {
            return; // Не воспроизводим звук
        }

        Uri alarmUri = null;
        if (soundUriString != null) {
            alarmUri = Uri.parse(soundUriString);
        } else {
            // Используем стандартный звук если пользовательский не выбран
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
        }

        ringtone = RingtoneManager.getRingtone(this, alarmUri);
        if (ringtone != null) {
            ringtone.play();
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK |
                            PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "MyApp::AlarmWakeLock"
            );
            wakeLock.acquire(10 * 60 * 1000L); // 10 минут
        }
    }

    private void dismissAlarm(int paraNumber) {
        // 1. Помечаем будильник как отключенный
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String alarmKey = todayKey + "_" + paraNumber + "_dismissed";

        SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean(alarmKey, true).apply();

        // 2. Отменяем все связанные будильники
        cancelAlarmsForLesson(paraNumber);

        // 3. Останавливаем сервис
        stopService(new Intent(this, AlarmService.class));

        // 4. Останавливаем звук и освобождаем ресурсы
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 5. Завершаем активность
        finish();
        overridePendingTransition(0, R.anim.slide_out_bottom);
    }

    // В обработчике broadcast
    private final BroadcastReceiver dismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int paraNumber = intent.getIntExtra("para_number", -1);
            if (paraNumber != -1) {
                dismissAlarm(paraNumber);
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("ALARM_DISMISSED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dismissReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(dismissReceiver);
    }

    // Новый метод для отмены будильников
    private void cancelAlarmsForLesson(int paraNumber) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        // 1. Уведомление
        Intent notificationIntent = new Intent(this, AlarmReceiver.class);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(
                this,
                paraNumber * 100,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(notificationPendingIntent);

        // 2. Полноэкранный будильник
        Intent serviceIntent = new Intent(this, AlarmService.class);
        PendingIntent servicePendingIntent = PendingIntent.getService(
                this,
                paraNumber * 100 + 1,
                serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(servicePendingIntent);
    }

    @Override
    protected void onDestroy() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}