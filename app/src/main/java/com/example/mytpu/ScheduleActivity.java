package com.example.mytpu;
import android.view.View;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
public class ScheduleActivity extends AppCompatActivity {

    private static final String TAG = "ScheduleActivity";
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";

    private LinearLayout scheduleContainer;
    private TextView lastUpdateTextView;
    private OkHttpClient client;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        scheduleContainer = findViewById(R.id.scheduleContainer);
        lastUpdateTextView = findViewById(R.id.lastUpdateTextView);
        client = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();
        loadSchedule();
    }

    private void loadSchedule() {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(API_URL)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        showError("Ошибка загрузки: " + response.code());
                        return;
                    }

                    String jsonData = response.body().string();
                    parseSchedule(jsonData);
                }
            } catch (IOException e) {
                showError("Ошибка подключения: " + e.getMessage());
            }
        });
    }
    // Функция для конвертации номера дня недели в название
    private String getDayName(int weekday) {
        String[] days = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
        return (weekday >= 0 && weekday < days.length) ? days[weekday] : "Неизвестный день";
    }
    private boolean[] hasLessons = new boolean[7]; // Индекс 0 - понедельник, 6 - воскресенье
    private void parseSchedule(String jsonData) {
        runOnUiThread(() -> {
            try {
                Arrays.fill(hasLessons, false);
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.optJSONArray("faculties");
                if (faculties == null) return;

                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                Calendar calendar = Calendar.getInstance();
                int currentWeek = calendar.get(Calendar.WEEK_OF_YEAR);
                int currentYear = calendar.get(Calendar.YEAR);

                // Обработка JSON...
                for (int i = 0; i < faculties.length(); i++) {
                    JSONObject faculty = faculties.optJSONObject(i);
                    if (faculty == null) continue;

                    JSONArray groups = faculty.optJSONArray("groups");
                    if (groups == null) continue;

                    for (int j = 0; j < groups.length(); j++) {
                        JSONObject group = groups.optJSONObject(j);
                        if (group == null) continue;

                        String groupName = group.optString("name", "");
                        if (!"17В11".equals(groupName)) continue;

                        JSONArray lessons = group.optJSONArray("lessons");
                        if (lessons == null) continue;

                        for (int k = 0; k < lessons.length(); k++) {
                            JSONObject lesson = lessons.optJSONObject(k);
                            if (lesson == null) continue;

                            JSONObject dateObj = lesson.optJSONObject("date");
                            if (dateObj == null) continue;

                            int weekday = dateObj.optInt("weekday", -1);
                            String startDateStr = dateObj.optString("start", "");
                            try {
                                Date startDate = sdf.parse(startDateStr);
                                if (startDate == null) continue;

                                Calendar lessonCalendar = Calendar.getInstance();
                                lessonCalendar.setTime(startDate);
                                int lessonWeek = lessonCalendar.get(Calendar.WEEK_OF_YEAR);
                                int lessonYear = lessonCalendar.get(Calendar.YEAR);

                                // Оставляем только занятия из текущей недели и года
                                if (lessonWeek != currentWeek || lessonYear != currentYear) {
                                    continue;
                                }
                            } catch (ParseException e) {
                                continue; // Ошибка парсинга — пропускаем занятие
                            }

                            String subject = lesson.optString("subject", "Неизвестный предмет");
                            String lessonType = lesson.optString("type", "Неизвестный тип");
                            int subgroups = lesson.optInt("subgroups", 0);

                            JSONObject time = lesson.optJSONObject("time");
                            String startTime = (time != null) ? time.optString("start", "00:00") : "00:00";
                            String endTime = (time != null) ? time.optString("end", "00:00") : "00:00";

                            String dayOfWeek = getDayName(weekday);
                            if ("Неизвестный день".equals(dayOfWeek)) continue;

                            JSONArray audiences = lesson.optJSONArray("audiences");
                            JSONArray teachers = lesson.optJSONArray("teachers");

                            String audience = "Неизвестная";
                            if (audiences != null && audiences.length() > 0) {
                                JSONObject audienceObj = audiences.optJSONObject(0);
                                if (audienceObj != null) audience = audienceObj.optString("name", "Неизвестная");
                            }

// Обработка преподавателя
                            String teacher = "Неизвестный";
                            if (teachers != null && teachers.length() > 0) {
                                JSONObject teacherObj = teachers.optJSONObject(0);
                                if (teacherObj != null) teacher = teacherObj.optString("name", "Неизвестный");
                            }
                            String subgroupsTpue;
                            if (subject != "Неизвестный предмет" || subject != null) {
                                hasLessons[weekday-1] = true;
                            }
                            // Форматирование времени
                            String timeText = startTime + " - " + endTime;
                            if (subgroups != 0) {
                                subgroupsTpue = "Подгруппа: "+subgroups;
                            } else subgroupsTpue ="";
                            // Вызываем метод с отдельными параметрами
                            addLessonToUI(subject, lessonType, subgroupsTpue, timeText, audience, teacher, weekday);;
                        }
                    }
                }
                updateDayVisibility();
            } catch (JSONException e) {
                showError("Ошибка разбора данных: " + e.getMessage());
            }
        });
    }
    private void updateDayVisibility() {
        int[] dayIds = {
                R.id.scheduleContainerMonday,
                R.id.scheduleContainerTuesday,
                R.id.scheduleContainerWednesday,
                R.id.scheduleContainerThursday,
                R.id.scheduleContainerFriday,
                R.id.scheduleContainerSaturday,
                R.id.scheduleContainerSunday
        };

        for (int i = 0; i < dayIds.length; i++) {

            // Находим LinearLayout для дня
            LinearLayout dayContainer = findViewById(dayIds[i]);
            if (dayContainer != null) {
                // Находим родительский CardView
                CardView dayCard = (CardView) dayContainer.getParent();
                if (dayCard != null) {
                    // Обновляем видимость CardView
                    dayCard.setVisibility(hasLessons[i] ? View.VISIBLE : View.GONE);
                    Log.d("ScheduleActivity", "Day " + i + " has lessons: " + hasLessons[i]);
                    Log.d("ScheduleActivity", "Day " + i + " visibility: " + (hasLessons[i] ? "VISIBLE" : "GONE"));
                }
            }
        }
    }
    // Метод для добавления пары в UI
    private void addLessonToUI(String subject, String type, String subgroups,
                               String time, String audience, String teacher, int weekday) {
        // Находим контейнер по дню недели
        String containerId = "scheduleContainer" + getDayId(weekday);
        LinearLayout container = findViewById(getResources().getIdentifier(
                containerId, "id", getPackageName()
        ));

        if (container == null) return;

        // Создаем новую CardView для урока (на основе XML-разметки)
        CardView lessonCard = (CardView) LayoutInflater.from(this)
                .inflate(R.layout.lesson_card_template, container, false);

        // Заполняем данные
        TextView subgroupsTV = lessonCard.findViewById(R.id.subgroups);
        TextView subjectTV = lessonCard.findViewById(R.id.subject);
        TextView timeTV = lessonCard.findViewById(R.id.time);
        TextView audiencesTV = lessonCard.findViewById(R.id.audiences);
        TextView lessonTypeTV = lessonCard.findViewById(R.id.lessonType);
        TextView teachersTV = lessonCard.findViewById(R.id.teachers);

        subgroupsTV.setText(subgroups);
        subjectTV.setText(subject);
        timeTV.setText(time);
        audiencesTV.setText(audience);
        lessonTypeTV.setText(type);
        teachersTV.setText(teacher);

        // Добавляем CardView в контейнер
        container.addView(lessonCard);
    }

    // Вспомогательный метод для получения идентификатора дня
    private String getDayId(int weekday) {
        switch (weekday) {
            case 1: return "Monday";
            case 2: return "Tuesday";
            case 3: return "Wednesday";
            case 4: return "Thursday";
            case 5: return "Friday";
            case 6: return "Saturday";
            default: return "";
        }
    }
        // Открытие расписания группы
    private void showGroupSchedule(GroupSchedule schedule) {
        Intent intent = new Intent(this, GroupScheduleActivity.class);
        intent.putExtra("schedule", schedule.getSchedule());
        startActivity(intent);
    }


    private void showError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.e(TAG, message);
        });
    }

    public static class GroupSchedule {
        private final String groupName;
        private final String schedule;

        public GroupSchedule(String groupName, String schedule) {
            this.groupName = groupName;
            this.schedule = schedule;
        }

        public String getGroupName() {
            return groupName;
        }

        public String getSchedule() {
            return schedule;
        }
    }
}