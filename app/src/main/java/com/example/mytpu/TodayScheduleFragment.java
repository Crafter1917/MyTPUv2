// TodayScheduleFragment.java
package com.example.mytpu;

import static android.content.ContentValues.TAG;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.mytpu.schedule.ScheduleActivity;
import com.example.mytpu.schedule.ScheduleCardHelper;
import com.example.mytpu.schedule.ScheduleDataLoader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TodayScheduleFragment extends Fragment {
    private LinearLayout scheduleContainer;
    private ProgressBar progressBar;
    private ScheduleDataLoader dataLoader;
    private String savedGroup;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataLoader = new ScheduleDataLoader();
        Log.d(TAG, "today is stared!");
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

        // Установим текущую дату в заголовок
        TextView tvTitle = view.findViewById(R.id.tvTitle);
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM", new Locale("ru"));
        String dateString = sdf.format(new Date());
        tvTitle.setText("Расписание на " + dateString);

        if (savedGroup != null && !savedGroup.isEmpty()) {
            loadTodaySchedule(savedGroup);
        } else {
            showNoGroupMessage();
        }

        return view;
    }

    private void loadTodaySchedule(String group) {

        progressBar.setVisibility(View.VISIBLE);
        dataLoader.loadTodaySchedule(group, new ScheduleDataLoader.ScheduleDataListener() {
            @Override
            public void onDataLoaded(List<ScheduleActivity.LessonData> lessons) {
                if (!isAdded()) return; // фрагмент больше не активен
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
                progressBar.setVisibility(View.GONE);
                showErrorMessage(message);
            }
        });

    }

    private void displayLessons(List<ScheduleActivity.LessonData> lessons) {
        scheduleContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        ColorManager colorManager = ColorManager.getInstance(requireContext(), false);

        for (ScheduleActivity.LessonData lesson : lessons) {
            View lessonCard = ScheduleCardHelper.createLessonCard(
                    requireContext(),
                    lesson.subgroups > 0 ? "Подгруппа: " + lesson.subgroups : "",
                    lesson.subject,
                    lesson.audience,
                    lesson.type,
                    lesson.teacher,
                    lesson.time,
                    colorManager  // Передаем ColorManager
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