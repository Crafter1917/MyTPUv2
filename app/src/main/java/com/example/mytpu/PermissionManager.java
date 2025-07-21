package com.example.mytpu;


import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PermissionManager {
    private static final String TAG = "PermissionManager";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_NAME = "permission_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final String KEY_PERMISSION_REQUESTED = "permission_requested";
    private static final String KEY_EXACT_ALARM_REQUESTED = "exact_alarm_requested";
    private static final String KEY_MANUFACTURER_SETTINGS_SHOWN = "manufacturer_settings_shown";

    // Список всех необходимых разрешений
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.USE_FULL_SCREEN_INTENT
    };

    public static boolean checkAllPermissions(Context context) {
        // Проверка основных разрешений
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        // Проверка специальных разрешений
        return checkSpecialPermissions(context);
    }

    private static boolean checkSpecialPermissions(Context context) {
        // Проверка точных будильников (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (!alarmManager.canScheduleExactAlarms()) {
                return false;
            }
        }

        // Проверка оптимизации батареи
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                return false;
            }
        }

        // Все проверки пройдены
        return true;
    }

    public static void requestPermissions(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true);
        boolean permissionRequested = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false);

        // Собираем только недостающие разрешения
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            if (isFirstLaunch || !permissionRequested) {
                // Первый запуск или еще не запрашивали
                ActivityCompat.requestPermissions(
                        activity,
                        permissionsToRequest.toArray(new String[0]),
                        PERMISSION_REQUEST_CODE
                );

                prefs.edit()
                        .putBoolean(KEY_PERMISSION_REQUESTED, true)
                        .putBoolean(KEY_FIRST_LAUNCH, false)
                        .apply();
            } else {
                // Уже запрашивали, но пользователь отказал - показать объяснение
                showPermissionExplanation(activity);
            }
        } else {
            // Все основные разрешения есть, проверяем специальные
            requestSpecialPermissions(activity);
        }
    }

    private static void requestSpecialPermissions(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean exactAlarmRequested = prefs.getBoolean(KEY_EXACT_ALARM_REQUESTED, false);
        boolean manufacturerSettingsShown = prefs.getBoolean(KEY_MANUFACTURER_SETTINGS_SHOWN, false);

        // Запрос разрешения на точные будильники
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
            if (!alarmManager.canScheduleExactAlarms() && !exactAlarmRequested) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                activity.startActivity(intent);
                prefs.edit().putBoolean(KEY_EXACT_ALARM_REQUESTED, true).apply();
                return;
            }
        }

        // Запрос игнорирования оптимизации батареи
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            }
        }

        // Показываем настройки производителя только один раз
        if (!manufacturerSettingsShown) {
            requestManufacturerPermissions(activity);
            prefs.edit().putBoolean(KEY_MANUFACTURER_SETTINGS_SHOWN, true).apply();
        }
    }

    static void requestManufacturerPermissions(Activity activity) {
        String manufacturer = Build.MANUFACTURER.toLowerCase();

        if (manufacturer.contains("xiaomi") || manufacturer.contains("oppo") ||
                manufacturer.contains("vivo") || manufacturer.contains("huawei")) {
            showManufacturerInfo(activity);
        }
        if (manufacturer.contains("xiaomi")) {
            openXiaomiAutoStart(activity);
        } else if (manufacturer.contains("oppo")) {
            openOppoAutoStart(activity);
        } else if (manufacturer.contains("vivo")) {
            openVivoAutoStart(activity);
        } else if (manufacturer.contains("huawei")) {
            openHuaweiProtectedApps(activity);
        }
    }
    private static void showManufacturerInfo(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Важно для работы приложения")
                .setMessage("Для корректной работы на вашем устройстве необходимо:\n\n1. Включить автозапуск\n2. Отключить оптимизацию батареи\n3. Дать все разрешения")
                .setPositiveButton("Открыть настройки", (dialog, which) -> {
                    requestManufacturerPermissions(activity);
                })
                .setNegativeButton("Позже", null)
                .show();
    }    private static void openXiaomiAutoStart(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            );
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    private static void openOppoAutoStart(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            );
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    private static void openVivoAutoStart(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            );
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    private static void openHuaweiProtectedApps(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
            );
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    private static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    private static void showPermissionExplanation(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Требуются разрешения")
                .setMessage("Для полной функциональности приложения необходимо предоставить все запрошенные разрешения. Некоторые функции могут работать некорректно без них.")
                .setPositiveButton("Настройки", (dialog, which) -> openAppSettings(activity))
                .setNegativeButton("Позже", null)
                .show();
    }

    public static void onRequestPermissionsResult(Activity activity, int requestCode,
                                                  @NonNull String[] permissions,
                                                  @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // Все разрешения предоставлены, запрашиваем специальные
                requestSpecialPermissions(activity);
            } else {
                // Не все разрешения предоставлены
                showPermissionExplanation(activity);
            }
        }
    }
}
