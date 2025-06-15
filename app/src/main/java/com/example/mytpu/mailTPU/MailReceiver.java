package com.example.mytpu.mailTPU;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class MailReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent.getAction() != null && intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
            Log.d("MailReceiver", "Device rebooted, rescheduling alarms");
            // Перепланируем алармы после перезагрузки
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(context, MailReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    0,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            long firstTrigger = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstTrigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, firstTrigger, pi);
            }
            return;
        }

        Log.d("MailReceiver", "Alarm triggered at " + new Date());

        // 1. Запускаем воркер
        WorkManager.getInstance(context).enqueue(
                new OneTimeWorkRequest.Builder(MailCheckWorker.class).build()
        );

        // 2. Планируем следующий вызов через 1 минуту
        if (context instanceof MailActivity) {
            ((MailActivity) context).scheduleNextAlarm(1);
        } else {
            // Для случаев, когда активность не запущена
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent nextIntent = new Intent(context, MailReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    0,
                    nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            long nextTrigger = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            }
        }
    }
}
