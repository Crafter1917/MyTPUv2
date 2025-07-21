package com.example.mytpu.schedule;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static com.example.mytpu.schedule.ScheduleActivity.REQUEST_CODE_ALARM_SOUND;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.fragment.app.Fragment;

import com.example.mytpu.R;

public class AlarmSettingsFragment extends Fragment {
    private TimePicker timePicker;
    private Button btnSelectSound;
    private Switch switchAlarm, switchNotifications;
    private int alarmHours = 0;
    private int alarmMinutes = 30;
    private boolean alarmEnabled = true;
    private boolean notificationsEnabled = true;
    private String selectedSoundTitle = "Выбрать звук"; // Добавляем переменную для хранения названия

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {


        View view = inflater.inflate(R.layout.fragment_alarm_settings, container, false);

        timePicker = view.findViewById(R.id.timePicker);
        btnSelectSound = view.findViewById(R.id.btnSelectSound);
        switchAlarm = view.findViewById(R.id.switchAlarm);
        switchNotifications = view.findViewById(R.id.switchNotifications);

        // Настройка TimePicker
        timePicker.setIs24HourView(true);
        timePicker.setHour(0);
        timePicker.setMinute(30);

        // Загрузка сохраненных настроек
        SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        int minutes = prefs.getInt("alarm_minutes", 30);
        int hours = minutes / 60;
        minutes = minutes % 60;

        // Сохраняем в переменные
        alarmHours = hours;
        alarmMinutes = minutes;
        alarmEnabled = prefs.getBoolean("alarm_enabled", true);
        notificationsEnabled = prefs.getBoolean("notifications_enabled", true);

        // Устанавливаем значения
        timePicker.setHour(alarmHours);
        timePicker.setMinute(alarmMinutes);
        switchAlarm.setChecked(alarmEnabled);
        switchNotifications.setChecked(notificationsEnabled);
        selectedSoundTitle = prefs.getString("alarm_sound_title", "Выбрать звук");
        btnSelectSound.setText(selectedSoundTitle); // Устанавливаем текст кнопки
        // Обновляем переменные при изменении
        timePicker.setOnTimeChangedListener((view1, hourOfDay, minute) -> {
            alarmHours = hourOfDay;
            alarmMinutes = minute;
        });

        switchAlarm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alarmEnabled = isChecked;
        });

        switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            notificationsEnabled = isChecked;
        });
        btnSelectSound.setOnClickListener(v -> selectAlarmSound());

        return view;
    }

    // Обновленные геттеры
    public int getHours() {
        return alarmHours;
    }

    public int getMinutes() {
        return alarmMinutes;
    }

    public boolean isAlarmEnabled() {
        return alarmEnabled;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    // AlarmSettingsFragment.java
    private void selectAlarmSound() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, selectedSoundTitle);

        SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        String soundUri = prefs.getString("alarm_sound_uri", null);

        if (soundUri != null) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(soundUri));
        }

        startActivityForResult(intent, REQUEST_CODE_ALARM_SOUND);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_ALARM_SOUND) {
            SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
            if (resultCode == RESULT_OK && data != null) {
                Uri ringtoneUri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                if (ringtoneUri != null) {
                    Ringtone ringtone = RingtoneManager.getRingtone(requireContext(), ringtoneUri);
                    selectedSoundTitle = ringtone.getTitle(requireContext());
                    prefs.edit()
                            .putString("alarm_sound_uri", ringtoneUri.toString())
                            .putString("alarm_sound_title", selectedSoundTitle)
                            .apply();
                } else {
                    selectedSoundTitle = "Без звука";
                    prefs.edit()
                            .remove("alarm_sound_uri")
                            .putString("alarm_sound_title", selectedSoundTitle)
                            .apply();
                }
            }
            btnSelectSound.setText(selectedSoundTitle);
        }
    }
}
