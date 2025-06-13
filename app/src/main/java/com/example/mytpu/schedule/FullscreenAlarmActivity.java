package com.example.mytpu.schedule;

import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FullscreenAlarmActivity extends AppCompatActivity {
    private Ringtone ringtone;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_alarm);

        // Разрешения для работы на заблокированном экране
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        // Получаем данные о паре
        int paraNumber = getIntent().getIntExtra("para_number", 1);
        String subject = getIntent().getStringExtra("lesson_subject");
        String time = getIntent().getStringExtra("lesson_time");

        TextView info = findViewById(R.id.alarm_info);
        info.setText("Пара " + paraNumber + ": " + subject + "\nВремя: " + time);

        Button dismissButton = findViewById(R.id.dismiss_button);
        dismissButton.setOnClickListener(v -> {
            String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            String alarmKey = todayKey + "_" + paraNumber;

            SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean(alarmKey, true).apply();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(AlarmReceiver.NOTIFICATION_ID + paraNumber);

            // Останавливаем звук и закрываем активность
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
            }
            finish();
        });

        // Запускаем звук будильника
        playAlarmSound();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                "MyApp::AlarmWakeLock"
        );
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
    }

    private void playAlarmSound() {
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), alarmUri);
            ringtone.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }
}