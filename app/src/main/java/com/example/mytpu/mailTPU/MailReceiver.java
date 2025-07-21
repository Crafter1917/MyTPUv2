package com.example.mytpu.mailTPU;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.Date;
import java.util.concurrent.TimeUnit;

// MailReceiver.java
public class MailReceiver extends BroadcastReceiver {
    private static final String TAG = "MailReceiver";
    private static final long INTERVAL_SECONDS = 15; // Интервал проверки
    private boolean isCertificateError(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("mail_prefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("cert_error", false);
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm triggered at " + new Date());
        if (!hasValidCredentials(context)) {
            Log.d(TAG, "No valid credentials, skipping mail check");
            return;
        }
        if (intent.getAction() != null && intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
            Log.d(TAG, "Device rebooted, scheduling initial mail check");
            scheduleMailWorker(context, 15); // Первая проверка через 15 сек
            scheduleNextAlarm(context, INTERVAL_SECONDS);
            return;
        }
        if (isCertificateError(context)) {
            Log.d(TAG, "Certificate error active, skipping mail check");
            return;
        }
        // Запускаем немедленную проверку
        enqueueMailWorker(context);

        // Планируем следующую проверку
        scheduleNextAlarm(context, INTERVAL_SECONDS);
    }
    private boolean hasValidCredentials(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return prefs.contains("username") && prefs.contains("password");
        } catch (Exception e) {
            return false;
        }
    }
    private void enqueueMailWorker(Context context) {
        // Создаем данные с указанием папки
        Data inputData = new Data.Builder()
                .putString("folder", "INBOX") // Явно указываем INBOX
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                "mailCheck",
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                        .setInputData(inputData)
                        .build()
        );
    }

    private void scheduleMailWorker(Context context, long delaySeconds) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                "mailCheck",
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                        .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                        .build()
        );
    }

    void scheduleNextAlarm(Context context, long delaySECONDS) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntent = new Intent(context, MailReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySECONDS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );
        } else {
            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );
        }
        Log.d(TAG, "следущая проверка уведомлений через " + delaySECONDS + " сек");
    }
}
