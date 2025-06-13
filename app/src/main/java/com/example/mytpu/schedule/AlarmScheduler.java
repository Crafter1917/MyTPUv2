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

    @SuppressLint("ScheduleExactAlarm")
    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    public static void scheduleAlarms(Context context, List<ScheduleActivity.LessonData> lessons, int minutesBefore) {
        Log.d(TAG, "Starting alarm scheduling...");
        cancelAllAlarms(context);

        // Сбрасываем флаги отключения для сегодняшнего дня
        resetDismissedAlarms(context);

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
            scheduleLessonAlarm(context, lesson, minutesBefore);
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
    private static void scheduleLessonAlarm(Context context, ScheduleActivity.LessonData lesson, int minutesBefore) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "Cannot schedule exact alarms: permission missing");
                return;
            }
        }

        // Устанавливаем время срабатывания
        Calendar alarmTime = Calendar.getInstance();
        alarmTime.setTime(lesson.startTime);
        alarmTime.add(Calendar.MINUTE, -minutesBefore);

        Log.d(TAG, String.format(Locale.getDefault(),
                "Scheduling alarm for lesson: %s at %tR (alarm at %tR, %d minutes before)",
                lesson.subject, lesson.startTime, alarmTime, minutesBefore));
        if (alarmTime.getTimeInMillis() < System.currentTimeMillis()) {
            Log.d(TAG, "Skipping past alarm for: " + lesson.subject);
            return;
        }
        // Создаем интент для будильника
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("para_number", lesson.paraNumber);
        intent.putExtra("lesson_time", lesson.time);
        intent.putExtra("lesson_subject", lesson.subject);
        intent.putExtra("lesson_audience", lesson.audience);

        int requestCode = lesson.paraNumber; // Уникальный код для каждой пары

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Устанавливаем точный будильник
        alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                alarmTime.getTimeInMillis(),
                pendingIntent
        );
    }

    public static void cancelAllAlarms(Context context) {
        Log.d(TAG, "Canceling all alarms");
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
    private static void resetDismissedAlarms(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Удаляем все флаги для текущего дня
        String todayKey = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(todayKey)) {
                editor.remove(key);
            }
        }
        editor.apply();
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