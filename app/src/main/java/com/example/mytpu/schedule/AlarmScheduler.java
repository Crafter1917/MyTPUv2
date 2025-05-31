package com.example.mytpu.schedule;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import com.example.mytpu.R;

import java.util.Calendar;
import java.util.List;

public class AlarmScheduler {
    private static final String TAG = "AlarmScheduler";
    private static final String PREFS_NAME = "AlarmPrefs";
    public static final String KEY_ALARM_SETTINGS = "alarm_settings";

    @SuppressLint("ScheduleExactAlarm")
    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    public static void scheduleAlarms(Context context, List<ScheduleActivity.LessonData> lessons, boolean[] selectedParas) {
        cancelAllAlarms(context);

        for (ScheduleActivity.LessonData lesson : lessons) {
            if (lesson.paraNumber >= 1 && lesson.paraNumber <= 6 && selectedParas[lesson.paraNumber - 1]) {
                // Установка будильника за 10 минут до пары
                Calendar alarmTime = Calendar.getInstance();
                alarmTime.setTime(lesson.startTime);
                alarmTime.add(Calendar.MINUTE, -10);

                scheduleParaAlarm(context, alarmTime, lesson.paraNumber);
            }
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    private static void scheduleParaAlarm(Context context, Calendar alarmTime, int paraNumber) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "Нет разрешения на точные будильники");
                return;
            }
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("para_number", paraNumber);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                paraNumber,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                alarmTime.getTimeInMillis(),
                pendingIntent
        );
    }

    public static void cancelAllAlarms(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int i = 1; i <= 6; i++) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    i,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);
        }
    }

    public static boolean[] getSavedSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String settings = prefs.getString(KEY_ALARM_SETTINGS, "false,false,false,false,false,false,false");
        String[] parts = settings.split(",", 7); // Явно указываем лимит

        boolean[] result = new boolean[7];
        for (int i = 0; i < 7; i++) {
            result[i] = Boolean.parseBoolean(parts[i]);
        }
        return result;
    }
}