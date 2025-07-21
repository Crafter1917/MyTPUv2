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

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class AlarmScheduler {
    private static final String TAG = "AlarmScheduler";
    private static final String PREFS_NAME = "AlarmPrefs";


    public static void scheduleAlarms(Context context, List<ScheduleActivity.LessonData> lessons, int minutesBefore) {
        Log.d(TAG, "Starting alarm scheduling...");

        // Сбрасываем флаги отключения для нового дня
        resetDismissedAlarms(context);

        cancelAllAlarms(context);

        if (lessons == null || lessons.isEmpty()) {
            Log.d(TAG, "No lessons to schedule alarms");
            return;
        }

        // Фильтрация уроков на сегодня
        List<ScheduleActivity.LessonData> todayLessons = getFirstLessonsForToday(lessons);
        if (todayLessons.isEmpty()) {
            Log.d(TAG, "No lessons found for today");
            return;
        }

        Log.d(TAG, "Found " + todayLessons.size() + " lessons for today");
        for (ScheduleActivity.LessonData lesson : todayLessons) {
            scheduleLessonAlarm(context, lesson);
        }
    }

    public static void resetDismissedAlarms(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String lastResetDay = prefs.getString("last_reset_day", "");

        // Сбрасываем флаги только при смене дня
        if (!todayKey.equals(lastResetDay)) {
            SharedPreferences.Editor editor = prefs.edit();

            // Удаляем все флаги отключения
            for (String key : prefs.getAll().keySet()) {
                if (key.endsWith("_dismissed")) {
                    editor.remove(key);
                    Log.d(TAG, "Removed dismissed alarm flag: " + key);
                }
            }

            // Сохраняем текущий день как день последнего сброса
            editor.putString("last_reset_day", todayKey);
            editor.apply();
            Log.d(TAG, "Reset dismissed alarms for new day: " + todayKey);
        }
    }

    private static List<ScheduleActivity.LessonData> getFirstLessonsForToday(List<ScheduleActivity.LessonData> lessons) {
        Calendar today = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault());
        int currentDayOfWeek = today.get(Calendar.DAY_OF_WEEK);
        int currentWeek = today.get(Calendar.WEEK_OF_YEAR);
        int currentYear = today.get(Calendar.YEAR);

        Log.d(TAG, String.format(Locale.getDefault(),
                "Current date: %tF, day=%d, week=%d, year=%d",
                today, currentDayOfWeek, currentWeek, currentYear));

        ScheduleActivity.LessonData firstLesson = null;

        for (ScheduleActivity.LessonData lesson : lessons) {
            Calendar lessonCal = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault());
            lessonCal.setTime(lesson.startTime);

            Log.d(TAG, String.format(Locale.getDefault(),
                    "Checking lesson: %s at %tR (day=%d, week=%d, year=%d)",
                    lesson.subject, lesson.startTime,
                    lessonCal.get(Calendar.DAY_OF_WEEK),
                    lessonCal.get(Calendar.WEEK_OF_YEAR),
                    lessonCal.get(Calendar.YEAR)));

            // Проверка совпадения дня недели, недели и года
            if (lessonCal.get(Calendar.DAY_OF_WEEK) == currentDayOfWeek &&
                    lessonCal.get(Calendar.WEEK_OF_YEAR) == currentWeek &&
                    lessonCal.get(Calendar.YEAR) == currentYear) {

                if (firstLesson == null || lesson.startTime.before(firstLesson.startTime)) {
                    firstLesson = lesson;
                    Log.d(TAG, "New first lesson found: " + lesson.subject);
                }
            }
        }

        return firstLesson != null ? List.of(firstLesson) : List.of();
    }

    @SuppressLint("ScheduleExactAlarm")
    private static void scheduleLessonAlarm(Context context, ScheduleActivity.LessonData lesson) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Проверка разрешения для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "Cannot schedule exact alarms: permission missing");
                return;
            }
        }

        // Получаем время будильника из настроек
        SharedPreferences alarmPrefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        int alarmMinutesBefore = alarmPrefs.getInt("alarm_minutes", 30); // 30 минут по умолчанию

        // 1. Полноэкранный будильник (время: начало пары - alarmMinutesBefore)
        Calendar fullscreenTime = Calendar.getInstance();
        fullscreenTime.setTime(lesson.startTime);
        fullscreenTime.add(Calendar.MINUTE, -alarmMinutesBefore);

        // Защита от установки в прошлом
        if (fullscreenTime.getTimeInMillis() < System.currentTimeMillis()) {
            Log.d(TAG, "Adjusting fullscreen alarm to future");
            fullscreenTime.setTimeInMillis(System.currentTimeMillis() + 1000); // +1 секунда
        }

        Log.d(TAG, "Fullscreen alarm time: "
                + fullscreenTime.get(Calendar.HOUR_OF_DAY) + ":"
                + fullscreenTime.get(Calendar.MINUTE)
                + " for " + lesson.subject);

        Intent fullscreenIntent = new Intent(context, AlarmService.class);
        fullscreenIntent.putExtra("para_number", lesson.paraNumber);
        fullscreenIntent.putExtra("lesson_time", lesson.time);
        fullscreenIntent.putExtra("lesson_subject", lesson.subject);
        fullscreenIntent.putExtra("lesson_audience", lesson.audience);
        fullscreenIntent.putExtra("lesson_start_time", lesson.startTime.getTime()); // Добавлено


        PendingIntent fullscreenPendingIntent = PendingIntent.getService(
                context,
                lesson.paraNumber * 100 + 1,
                fullscreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        // Установка будильника
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fullscreenTime.getTimeInMillis(),
                    fullscreenPendingIntent
            );
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    fullscreenTime.getTimeInMillis(),
                    fullscreenPendingIntent
            );
        }

        // 2. Предварительное уведомление (за 1 минут до полноэкранного будильника)
        Calendar notificationTime = (Calendar) fullscreenTime.clone();
        notificationTime.add(Calendar.MINUTE, -10);

        // Защита от установки в прошлом
        if (notificationTime.getTimeInMillis() < System.currentTimeMillis()) {
            Log.d(TAG, "Skipping past notification for: " + lesson.subject);
            return;
        }

        Log.d(TAG, "Notification time: "
                + notificationTime.get(Calendar.HOUR_OF_DAY) + ":"
                + notificationTime.get(Calendar.MINUTE)
                + " for " + lesson.subject);

        // Для уведомления используем BroadcastReceiver
        Intent notificationIntent = new Intent(context, AlarmReceiver.class);
        notificationIntent.putExtra("para_number", lesson.paraNumber);
        notificationIntent.putExtra("lesson_time", lesson.time);
        notificationIntent.putExtra("lesson_subject", lesson.subject);
        notificationIntent.putExtra("lesson_audience", lesson.audience);
        notificationIntent.putExtra("is_fullscreen", false);
        notificationIntent.putExtra("lesson_start_time", lesson.startTime.getTime());

        // Уникальный requestCode с использованием даты и номера пары
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        int uniqueRequestCode = (todayKey + lesson.paraNumber).hashCode() & 0xffff;

        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(
                context,
                uniqueRequestCode,  // Уникальный код
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Установка будильника для уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    notificationTime.getTimeInMillis(),
                    notificationPendingIntent
            );
        } else {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    notificationTime.getTimeInMillis(),
                    notificationPendingIntent
            );
        }
    }

    public static void cancelAllAlarms(Context context) {
        Log.d(TAG, "Canceling all alarms");
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        for (int i = 1; i <= 6; i++) {
            // 1. Уведомление
            Intent notificationIntent = new Intent(context, AlarmReceiver.class);
            PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(
                    context,
                    i * 100,
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(notificationPendingIntent);

            // 2. Полноэкранный будильник
            Intent intent = new Intent(context, AlarmReceiver.class);
            intent.putExtra("cancel_all", true);

            // Отменяем все совпадающие PendingIntent
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);
        }
    }

    public static boolean isAlarmEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("alarm_enabled", true);
    }

    public static boolean areNotificationsEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("notifications_enabled", true);
    }
}