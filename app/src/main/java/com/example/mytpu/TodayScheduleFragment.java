// TodayScheduleFragment.java
package com.example.mytpu;


import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.schedule.ScheduleActivity;
import com.example.mytpu.schedule.ScheduleCardHelper;
import com.example.mytpu.schedule.ScheduleDataLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TodayScheduleFragment extends Fragment {
    private LinearLayout scheduleContainer;
    private ProgressBar progressBar;
    private ScheduleDataLoader dataLoader;
    private String savedGroup;
    private FrameLayout progressContainer; // Добавлено
    private SharedPreferences sharedPreferences; // Добавлено
    private static final String SCHEDULE_CACHE_KEY = "today_schedule_cache";
    private static final long CACHE_EXPIRATION = 30 * 60 * 1000; // 30 минут
    private static final int NETWORK_TIMEOUT = 15; // секунд

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataLoader = new ScheduleDataLoader();
        Log.d(TAG, "today is stared!");

        // Инициализация EncryptedSharedPreferences
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Error initializing secure preferences", e);
        }

        // Получаем сохраненную группу
        savedGroup = requireActivity().getSharedPreferences("schedule_prefs", 0)
                .getString("saved_group", null);

        // Сохраняем группу в SharedPreferences
        if (savedGroup != null) {
            SharedPreferences.Editor editor = requireActivity()
                    .getSharedPreferences("schedule_prefs", 0)
                    .edit();
            editor.putString("saved_group", savedGroup);
            editor.apply();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_today_schedule, container, false);
        scheduleContainer = view.findViewById(R.id.scheduleContainer);
        progressBar = view.findViewById(R.id.progressBar);
        progressContainer = view.findViewById(R.id.progressContainer); // Исправлено

        // Установим текущую дату в заголовок
        TextView tvTitle = view.findViewById(R.id.tvTitle);
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM", new Locale("ru"));
        String dateString = sdf.format(new Date());
        tvTitle.setText("Расписание на " + dateString);

        // Исправленный блок
        if (isLoggedIn()) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.progressContainer, new CoursesProgressFragment())
                    .commit();
        } else {
            progressContainer.setVisibility(View.GONE);
        }

        if (savedGroup != null && !savedGroup.isEmpty()) {
            loadTodaySchedule(savedGroup);
        } else {
            showNoGroupMessage();
        }

        return view;
    }

    // Добавленный метод
    private boolean isLoggedIn() {
        return sharedPreferences != null && sharedPreferences.contains("token");
    }

    private void loadTodaySchedule(String group) {
        progressBar.setVisibility(View.VISIBLE);

        // Пытаемся загрузить из кэша
        List<ScheduleActivity.LessonData> cachedLessons = loadScheduleFromCache();
        if (cachedLessons != null) {
            displayLessons(cachedLessons);
            progressBar.setVisibility(View.GONE);
        }

        dataLoader.loadTodaySchedule(group, new ScheduleDataLoader.ScheduleDataListener() {
            @Override
            public void onDataLoaded(List<ScheduleActivity.LessonData> lessons) {
                if (!isAdded()) return;

                // Сохраняем в кэш
                saveScheduleToCache(lessons);

                progressBar.setVisibility(View.GONE);
                if (lessons.isEmpty()) {
                    showNoLessonsMessage();
                } else {
                    displayLessons(lessons);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;

                // Показываем кэш при ошибке
                if (cachedLessons != null) {
                    displayLessons(cachedLessons);
                } else {
                    showErrorMessage(message);
                }
                progressBar.setVisibility(View.GONE);
            }
        }, NETWORK_TIMEOUT);
    }

    private List<ScheduleActivity.LessonData> loadScheduleFromCache() {
        String cacheJson = requireActivity()
                .getSharedPreferences("schedule_prefs", 0)
                .getString(SCHEDULE_CACHE_KEY, null);

        if (cacheJson == null) return null;

        long lastUpdate = requireActivity()
                .getSharedPreferences("schedule_prefs", 0)
                .getLong(SCHEDULE_CACHE_KEY + "_time", 0);

        if (System.currentTimeMillis() - lastUpdate > CACHE_EXPIRATION) {
            return null; // Кэш устарел
        }

        try {
            JSONArray jsonArray = new JSONArray(cacheJson);
            List<ScheduleActivity.LessonData> lessons = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonLesson = jsonArray.getJSONObject(i);

                // Временные значения для недостающих параметров
                Date startTime = new Date(); // текущая дата как заглушка
                Date endTime = new Date();
                int weekday = 0;
                int paraNumber = 0;
                String group = "";

                lessons.add(new ScheduleActivity.LessonData(
                        startTime,
                        endTime,
                        jsonLesson.getString("subject"),
                        jsonLesson.getString("type"),
                        jsonLesson.getInt("subgroups"),
                        jsonLesson.getString("time"),
                        jsonLesson.getString("audience"),
                        jsonLesson.getString("teacher"),
                        weekday,
                        paraNumber,
                        group
                ));
            }
            return lessons;
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing schedule cache", e);
            return null;
        }
    }

    private void saveScheduleToCache(List<ScheduleActivity.LessonData> lessons) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (ScheduleActivity.LessonData lesson : lessons) {
                JSONObject jsonLesson = new JSONObject();
                jsonLesson.put("subject", lesson.subject);
                jsonLesson.put("time", lesson.time);
                jsonLesson.put("audience", lesson.audience);
                jsonLesson.put("type", lesson.type);
                jsonLesson.put("teacher", lesson.teacher);
                jsonLesson.put("subgroups", lesson.subgroups);
                jsonArray.put(jsonLesson);
            }

            requireActivity()
                    .getSharedPreferences("schedule_prefs", 0)
                    .edit()
                    .putString(SCHEDULE_CACHE_KEY, jsonArray.toString())
                    .putLong(SCHEDULE_CACHE_KEY + "_time", System.currentTimeMillis())
                    .apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving schedule cache", e);
        }
    }

    private void displayLessons(List<ScheduleActivity.LessonData> lessons) {
        scheduleContainer.removeAllViews(); // Очищаем контейнер перед добавлением
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (ScheduleActivity.LessonData lesson : lessons) {
            View lessonCard = ScheduleCardHelper.createLessonCard(
                    requireContext(),
                    lesson.subgroups > 0 ? "Подгруппа: " + lesson.subgroups : "",
                    lesson.subject,
                    lesson.audience,
                    lesson.type,
                    lesson.teacher,
                    lesson.time
            );
            scheduleContainer.addView(lessonCard);
        }
    }

    private void showNoGroupMessage() {
        scheduleContainer.removeAllViews();
        TextView tv = new TextView(requireContext());
        tv.setText("Выберите группу в разделе Расписание");
        scheduleContainer.addView(tv);
    }

    private void showNoLessonsMessage() {
        scheduleContainer.removeAllViews();
        TextView tv = new TextView(requireContext());
        tv.setText("На сегодня занятий нет");
        scheduleContainer.addView(tv);
    }

    private void showErrorMessage(String message) {
        scheduleContainer.removeAllViews();
        TextView tv = new TextView(requireContext());
        tv.setText("Ошибка: " + message);
        scheduleContainer.addView(tv);
    }

}