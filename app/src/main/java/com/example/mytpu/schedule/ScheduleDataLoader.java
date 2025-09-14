// ScheduleDataLoader.java
package com.example.mytpu.schedule;

import static android.content.ContentValues.TAG;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ScheduleDataLoader {
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private OkHttpClient client;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public interface ScheduleDataListener {
        void onDataLoaded(List<ScheduleActivity.LessonData> lessons);
        void onError(String message);
    }
    public ScheduleDataLoader() {
        this.client = new OkHttpClient();
    }

    public void loadTodaySchedule(String group, ScheduleDataListener listener, int timeoutSeconds) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "loadTodaySchedule is started!");
                Request request = new Request.Builder().url(API_URL).build();
                Response response = client.newCall(request).execute();
                String jsonData = response.body().string();

                List<ScheduleActivity.LessonData> lessons = parseSchedule(jsonData, group);

                // Переход в главный поток для обновления UI
                new Handler(Looper.getMainLooper()).post(() -> {
                    listener.onDataLoaded(lessons);
                });
            } catch (IOException | ParseException | JSONException e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    listener.onError(e.getMessage());
                });
            }

        });
    }

    private List<ScheduleActivity.LessonData> parseSchedule(String jsonData, String query)
            throws ParseException, JSONException {
        Log.d(TAG, "parseSchedule!");
        List<ScheduleActivity.LessonData> result = new ArrayList<>();
        JSONObject root = new JSONObject(jsonData);
        JSONArray faculties = root.getJSONArray("faculties");
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

        // Получаем текущую дату и день недели
        Calendar todayCal = Calendar.getInstance();
        todayCal.setFirstDayOfWeek(Calendar.MONDAY);
        todayCal.setMinimalDaysInFirstWeek(4);
        int currentWeek = todayCal.get(Calendar.WEEK_OF_YEAR);
        int currentYear = todayCal.get(Calendar.YEAR);

        // Получаем текущий день недели в формате JSON (1=пн, 7=вс)
        int currentCalendarDay = todayCal.get(Calendar.DAY_OF_WEEK);
        int currentJsonDay = convertCalendarDayToJsonDay(currentCalendarDay);
        Log.d(TAG, "Current JSON day: " + currentJsonDay + " (Calendar day: " + currentCalendarDay + ")");

        String normalizedQuery = normalizeString(query);
        Log.d(TAG, "normalizedQuery: " + normalizedQuery);

        for (int i = 0; i < faculties.length(); i++) {
            JSONObject faculty = faculties.getJSONObject(i);
            JSONArray groups = faculty.getJSONArray("groups");
            Log.d(TAG, "groups!");

            for (int j = 0; j < groups.length(); j++) {
                JSONObject group = groups.getJSONObject(j);
                String groupName = group.getString("name").trim();
                String normalizedGroupName = normalizeString(groupName);
                Log.d(TAG, "groupName: " + groupName);

                if (normalizedGroupName.equals(normalizedQuery)) {
                    Log.d(TAG, "Group matched: " + groupName);
                    JSONArray lessons = group.getJSONArray("lessons");

                    for (int k = 0; k < lessons.length(); k++) {
                        JSONObject lesson = lessons.getJSONObject(k);
                        JSONObject date = lesson.getJSONObject("date");
                        String dateStr = date.getString("start").trim();

                        // Проверяем неделю и год
                        Date lessonDate = sdf.parse(dateStr);
                        Calendar lessonCal = Calendar.getInstance();
                        lessonCal.setTime(lessonDate);
                        lessonCal.setFirstDayOfWeek(Calendar.MONDAY);
                        lessonCal.setMinimalDaysInFirstWeek(4);

                        int lessonWeek = lessonCal.get(Calendar.WEEK_OF_YEAR);
                        int lessonYear = lessonCal.get(Calendar.YEAR);

                        // Получаем день недели занятия
                        int lessonJsonDay = date.getInt("weekday");

                        // Проверяем совпадение недели, года и дня
                        if (lessonWeek == currentWeek &&
                                lessonYear == currentYear &&
                                lessonJsonDay == currentJsonDay) {

                            Log.d(TAG, "Adding lesson for date: " + dateStr);
                            ScheduleActivity.LessonData lessonData = parseLesson(lesson, groupName);
                            if (lessonData != null) {
                                result.add(lessonData);
                            }
                        }
                    }
                    break;
                }
            }
        }
        Log.d(TAG, "Total lessons found: " + result.size());
        return result;
    }

    // Преобразование формата дней недели
    private int convertCalendarDayToJsonDay(int calendarDay) {
        switch(calendarDay) {

            case Calendar.MONDAY:    return 1;
            case Calendar.TUESDAY:   return 2;
            case Calendar.WEDNESDAY: return 3;
            case Calendar.THURSDAY:  return 4;
            case Calendar.FRIDAY:    return 5;
            case Calendar.SATURDAY:  return 6;
            case Calendar.SUNDAY:    return 7;
            default: return -1;
        }
    }

    // Вынесенный метод нормализации
    private String normalizeString(String input) {
        return input.toUpperCase();
    }

    private ScheduleActivity.LessonData parseLesson(JSONObject lesson, String groupName)
            throws ParseException, JSONException {
        try {
            JSONObject date = lesson.getJSONObject("date");
            int weekday = date.getInt("weekday");

            // Упрощенная обработка даты
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
            Date lessonDate = sdf.parse(date.getString("start"));

            Calendar lessonCal = Calendar.getInstance();
            lessonCal.setTime(lessonDate);

            JSONObject time = lesson.getJSONObject("time");
            String startTime = time.getString("start");
            String endTime = time.getString("end");

            // Форматирование времени
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            Date startDate = timeFormat.parse(startTime);
            Date endDate = timeFormat.parse(endTime);

            Calendar startCal = Calendar.getInstance();
            startCal.setTime(lessonDate);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(startDate);
            int hours = calendar.get(Calendar.HOUR_OF_DAY);
            int minutes = calendar.get(Calendar.MINUTE);

            startCal.set(Calendar.HOUR_OF_DAY, hours);
            startCal.set(Calendar.MINUTE, minutes);

            Calendar endCal = Calendar.getInstance();
            endCal.setTime(lessonDate);
            endCal.set(Calendar.HOUR_OF_DAY, endDate.getHours());
            endCal.set(Calendar.MINUTE, endDate.getMinutes());

            return new ScheduleActivity.LessonData(
                    startCal.getTime(),
                    endCal.getTime(),
                    lesson.getString("subject"),
                    lesson.getString("type"),
                    lesson.optInt("subgroups", 0),
                    startTime + " - " + endTime,
                    getAudience(lesson.getJSONArray("audiences")),
                    getTeacher(lesson.getJSONArray("teachers")),
                    weekday,
                    0, // paraNumber можно опустить
                    groupName
            );
        } catch (ParseException | JSONException e) {
            Log.w(TAG, "Skipping invalid lesson: " + e.getMessage());
            return null;
        }
    }

    // Вспомогательные методы
    private String getAudience(JSONArray audiences) throws JSONException {
        if (audiences.length() == 0) return "";
        return audiences.getJSONObject(0).getString("name");
    }

    private String getTeacher(JSONArray teachers) throws JSONException {
        if (teachers.length() == 0) return "";
        return teachers.getJSONObject(0).getString("name");
    }
}