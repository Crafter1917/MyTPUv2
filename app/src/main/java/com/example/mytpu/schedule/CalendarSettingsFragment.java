package com.example.mytpu.schedule;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.mytpu.R;
import com.google.android.gms.common.AccountPicker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CalendarSettingsFragment extends Fragment {
    private static final String TAG = "CalendarSettings";
    private static final int PERMISSION_REQUEST_ACCOUNTS = 201;

    private TimePicker reminderPicker;
    private Switch switchWeeklySync;
    private Switch switchAddBreaks;
    private TextView tvAccountStatus;
    private Button btnManageAccount;

    private int reminderHours = 0;
    private int reminderMinutes = 30;
    private boolean breakNotifications = false;
    private boolean weeklySync = false;

    // Activity result launcher for account picker
    private final ActivityResultLauncher<Intent> accountPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    // Сбрасываем статус "выхода" при успешном выборе
                    SharedPreferences prefs = requireContext().getSharedPreferences("AccountPrefs", Context.MODE_PRIVATE);
                    prefs.edit().putBoolean("is_logged_out", false).apply();

                    // Обновляем статус
                    updateAccountStatus();

                    // Автоматически включаем синхронизацию
                    if (switchWeeklySync != null) {
                        switchWeeklySync.setChecked(true);
                    }
                }
            }
    );

    @SuppressLint("MissingInflatedId")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calendar_settings, container, false);

        reminderPicker = view.findViewById(R.id.reminderPicker);
        switchAddBreaks = view.findViewById(R.id.switchAddBreaks);
        switchWeeklySync = view.findViewById(R.id.switchWeeklySync);
        tvAccountStatus = view.findViewById(R.id.tvAccountStatus);
        btnManageAccount = view.findViewById(R.id.btnManageAccount);

        SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);

        // Настройка TimePicker
        reminderPicker.setIs24HourView(true);

        // Загрузка сохраненных настроек
        int minutes = prefs.getInt("calendar_reminder_minutes", 30);
        int hours = minutes / 60;
        minutes = minutes % 60;

        reminderHours = hours;
        reminderMinutes = minutes;
        breakNotifications = prefs.getBoolean("add_breaks", true);
        weeklySync = prefs.getBoolean("weekly_sync", false);

        reminderPicker.setHour(reminderHours);
        reminderPicker.setMinute(reminderMinutes);
        switchAddBreaks.setChecked(breakNotifications);
        switchWeeklySync.setChecked(weeklySync);

        reminderPicker.setOnTimeChangedListener((view1, hourOfDay, minute) -> {
            reminderHours = hourOfDay;
            reminderMinutes = minute;
        });

        switchWeeklySync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            weeklySync = isChecked;
            // Если включаем синхронизацию без аккаунта - показать предупреждение
            if (isChecked && !hasGoogleAccount()) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Аккаунт не найден")
                        .setMessage("Для синхронизации необходим аккаунт Google")
                        .setPositiveButton("Добавить", (d, w) -> showAddAccountDialog())
                        .setNegativeButton("Отмена", (d, w) -> switchWeeklySync.setChecked(false))
                        .show();
            }
        });

        // Настройка кнопки управления аккаунтом
        btnManageAccount.setOnClickListener(v -> showAccountManagementDialog());

        // Обновление статуса аккаунта
        updateAccountStatus();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAccountStatus();
    }

    private void updateAccountStatus() {
        SharedPreferences prefs = requireContext().getSharedPreferences("AccountPrefs", Context.MODE_PRIVATE);
        boolean isLoggedOut = prefs.getBoolean("is_logged_out", false);

        if (isLoggedOut) {
            tvAccountStatus.setText("Вы вышли из аккаунта Google");
            btnManageAccount.setText("Войти в аккаунт");
            return;
        }

        if (hasGoogleAccount()) {
            tvAccountStatus.setText("Аккаунт Google подключен");
            btnManageAccount.setText("Управление аккаунтом");
        } else {
            tvAccountStatus.setText("Аккаунт Google не подключен");
            btnManageAccount.setText("Добавить аккаунт");
        }
    }

    private void showAccountManagementDialog() {
        Context context = getContext();
        if (context == null) return;

        List<String> options = new ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Управление аккаунтом Google");

        if (hasGoogleAccount()) {
            options.add("Добавить новый аккаунт");
            options.add("Выбрать из существующих");
            options.add("Выйти из аккаунта");
            options.add("Отмена");
        } else {
            options.add("Добавить аккаунт");
            options.add("Выбрать из существующих");
            options.add("Отмена");
        }

        builder.setItems(options.toArray(new String[0]), (dialog, which) -> {
            String selected = options.get(which);

            if (selected.equals("Выбрать из существующих")) {
                showAccountPicker();
            }
            else if (selected.equals("Добавить аккаунт") || selected.equals("Добавить новый аккаунт")) {
                addNewAccount();
            }
            else if (selected.equals("Отключить синхронизацию и удалить события")) {
                unsyncCalendar();
                disableSyncSettings();
            }
            else if (selected.equals("Выйти из аккаунта")) {
                logoutFromAccount();
            }
        });

        try {
            builder.show();
        } catch (WindowManager.BadTokenException e) {
            Log.e(TAG, "Error showing account dialog", e);
        }
    }


    // Показывает стандартный пикер аккаунтов Google
    private void showAccountPicker() {
        try {
            Intent intent = AccountPicker.newChooseAccountIntent(
                    null,
                    null,
                    new String[]{"com.google"},
                    false,
                    null,
                    null,
                    null,
                    null
            );
            accountPickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            // Fallback для старых устройств
            Toast.makeText(
                    requireContext(),
                    "Функция выбора аккаунта недоступна",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    // Прямое добавление нового аккаунта
    private void addNewAccount() {
        try {
            Intent intent = new Intent(Settings.ACTION_ADD_ACCOUNT);
            intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"});
            accountPickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    requireContext(),
                    "Не удалось открыть добавление аккаунта",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    // Выход из аккаунта (без удаления из системы)
    private void logoutFromAccount() {
        // Сохраняем информацию о выходе
        SharedPreferences prefs = requireContext().getSharedPreferences("AccountPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("is_logged_out", true).apply();

        // Отключаем синхронизацию
        disableSyncSettings();

        // Обновляем статус
        updateAccountStatus();

        Toast.makeText(
                requireContext(),
                "Вы вышли из аккаунта. События останутся в календаре.",
                Toast.LENGTH_LONG
        ).show();
    }

    private void showAddAccountDialog() {
        Context context = getContext();
        if (context == null) return;

        // Проверка разрешений
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {

            // Запрашиваем разрешение с правильным requestCode
            requestPermissions(
                    new String[]{Manifest.permission.GET_ACCOUNTS},
                    PERMISSION_REQUEST_ACCOUNTS
            );
            return;
        }

        showAccountPicker();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_ACCOUNTS) {
            Context context = getContext();
            if (context == null) return;

            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                showAccountPicker();
            } else {
                Toast.makeText(
                        context,
                        "Требуется разрешение для работы с аккаунтами",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }
    }

    private void unsyncCalendar() {
        new Thread(() -> {
            try {
                deleteAllAppEvents();
                requireActivity().runOnUiThread(() -> Toast.makeText(
                        requireContext(),
                        "События удалены из календаря",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> Toast.makeText(
                        requireContext(),
                        "Ошибка удаления: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        }).start();
    }

    private void deleteAllAppEvents() throws Exception {
        ContentResolver cr = requireContext().getContentResolver();
        Uri uri = CalendarContract.Events.CONTENT_URI;
        String selection = Events.DESCRIPTION + " LIKE ?";
        String[] selectionArgs = new String[]{"%Синхронизировано через MyApp%"};

        int deletedRows = cr.delete(uri, selection, selectionArgs);
        Log.i(TAG, "Удалено событий: " + deletedRows);
    }

    private void disableSyncSettings() {
        SharedPreferences prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("weekly_sync", false).apply();

        requireActivity().runOnUiThread(() -> {
            Toast.makeText(requireContext(), "Синхронизация отключена", Toast.LENGTH_SHORT).show();
            switchWeeklySync.setChecked(false);
        });
    }

    private boolean hasGoogleAccount() {
        return AccountUtils.hasGoogleAccount(requireContext());
    }

    // Геттеры
    public int getHours() {
        return reminderHours;
    }

    public int getMinutes() {
        return reminderMinutes;
    }

    public boolean isAddBreaksEnabled() {
        return switchAddBreaks != null && switchAddBreaks.isChecked();
    }

    public boolean isWeeklySyncEnabled() {
        return weeklySync;
    }
}