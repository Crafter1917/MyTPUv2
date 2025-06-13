package com.example.mytpu.schedule;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TimePicker;

import androidx.fragment.app.Fragment;

import com.example.mytpu.R;

public class CalendarSettingsFragment extends Fragment {
    private TimePicker reminderPicker;
    private Switch switchWeeklySync;
    private Switch switchAddBreaks;
    // Добавленные переменные для хранения состояния
    private int reminderHours = 0;
    private int reminderMinutes = 30;
    private boolean breakNotifications = false;
    private boolean weeklySync = false;

    @SuppressLint("MissingInflatedId")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calendar_settings, container, false);

        reminderPicker = view.findViewById(R.id.reminderPicker);
        switchAddBreaks = view.findViewById(R.id.switchAddBreaks);
        switchWeeklySync = view.findViewById(R.id.switchWeeklySync);
        SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        boolean addBreaks = prefs.getBoolean("add_breaks", true); // по умолчанию включено
        switchAddBreaks.setChecked(addBreaks);
        if (reminderPicker == null) {
            throw new RuntimeException("TimePicker not found in layout");
        }

        // Настройка TimePicker
        reminderPicker.setIs24HourView(true);
        reminderPicker.setHour(0);
        reminderPicker.setMinute(30);

        // Загрузка сохраненных настроек
        int minutes = prefs.getInt("calendar_reminder_minutes", 30);
        int hours = minutes / 60;
        minutes = minutes % 60;

        // Сохраняем значения в локальные переменные
        reminderHours = hours;
        reminderMinutes = minutes;
        breakNotifications = prefs.getBoolean("break_notifications", false);
        weeklySync = prefs.getBoolean("weekly_sync", false);

        reminderPicker.setHour(reminderHours);
        reminderPicker.setMinute(reminderMinutes);
        switchWeeklySync.setChecked(weeklySync);

        reminderPicker.setOnTimeChangedListener((view1, hourOfDay, minute) -> {
            reminderHours = hourOfDay;
            reminderMinutes = minute;
        });
        switchWeeklySync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            weeklySync = isChecked;
        });

        return view;
    }

    // Геттеры используют локальные переменные
    public int getHours() {
        return reminderHours;
    }

    public int getMinutes() {
        return reminderMinutes;
    }

    public boolean isAddBreaksEnabled() {
        return switchAddBreaks != null && switchAddBreaks.isChecked(); // Проверка на null
    }

    public boolean isWeeklySyncEnabled() {
        return weeklySync;
    }
}