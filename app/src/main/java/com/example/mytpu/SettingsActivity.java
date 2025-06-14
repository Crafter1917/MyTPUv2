package com.example.mytpu;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import yuku.ambilwarna.AmbilWarnaDialog;

public class SettingsActivity extends AppCompatActivity {
    private final Map<String, Integer> dayColors = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Integer> nightColors = Collections.synchronizedMap(new HashMap<>());
    private boolean isNightMode = false;
    private SwitchCompat themeSwitch;
    private RecyclerView dayRecyclerView;
    private RecyclerView nightRecyclerView;
    private ColorAdapter dayAdapter;
    private ColorAdapter nightAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Инициализация Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        initDefaultColors();

        // Инициализация элементов
        themeSwitch = findViewById(R.id.themeSwitch);
        dayRecyclerView = findViewById(R.id.dayColorsRecyclerView);
        nightRecyclerView = findViewById(R.id.nightColorsRecyclerView);
        Button saveButton = findViewById(R.id.saveButton);
        Button resetButton = findViewById(R.id.resetButton);

        // Настройка RecyclerView
        dayRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        nightRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        dayAdapter = new ColorAdapter(dayColors, colorName ->
                showColorPicker(colorName, dayColors));
        nightAdapter = new ColorAdapter(nightColors, colorName ->
                showColorPicker(colorName, nightColors));

        dayRecyclerView.setAdapter(dayAdapter);
        nightRecyclerView.setAdapter(nightAdapter);

        // Восстановление состояния темы
        SharedPreferences prefs = getSharedPreferences("AppColors", MODE_PRIVATE);
        isNightMode = prefs.getBoolean("isNightMode", false);
        themeSwitch.setChecked(isNightMode);

        // Обновление видимости
        updateThemeVisibility();

        // Слушатель переключателя темы
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isNightMode = isChecked;
            updateThemeVisibility();
        });

        // Кнопки
        saveButton.setOnClickListener(v -> saveColors());
        resetButton.setOnClickListener(v -> showResetConfirmationDialog());
    }

    private void updateThemeVisibility() {
        runOnUiThread(() -> {
            if (isNightMode) {
                dayRecyclerView.setVisibility(View.GONE);
                findViewById(R.id.dayColorsLabel).setVisibility(View.GONE);
                nightRecyclerView.setVisibility(View.VISIBLE);
                findViewById(R.id.nightColorsLabel).setVisibility(View.VISIBLE);
            } else {
                dayRecyclerView.setVisibility(View.VISIBLE);
                findViewById(R.id.dayColorsLabel).setVisibility(View.VISIBLE);
                nightRecyclerView.setVisibility(View.GONE);
                findViewById(R.id.nightColorsLabel).setVisibility(View.GONE);
            }
        });
    }

    private void initDefaultColors() {
        SharedPreferences preferences = getSharedPreferences("AppColors", MODE_PRIVATE);

        // Получаем все цвета из ресурсов по имени
        Map<String, Integer> allColors = new HashMap<>();
        Field[] fields = R.color.class.getDeclaredFields();
        for (Field field : fields) {
            try {
                String colorName = field.getName();
                int colorId = field.getInt(null);
                allColors.put(colorName, ContextCompat.getColor(this, colorId));
            } catch (Exception e) {
                Log.e("ColorDebug", "Error loading color: " + field.getName(), e);
            }
        }

        // Добавляем цвета, которые могут отсутствовать в R.color
        addExtraColors(allColors);

        // Загружаем настройки
        for (Map.Entry<String, Integer> entry : allColors.entrySet()) {
            String colorName = entry.getKey();
            int defaultColor = entry.getValue();

            dayColors.put(colorName, preferences.getInt("DAY_" + colorName, defaultColor));
            nightColors.put(colorName, preferences.getInt("NIGHT_" + colorName, defaultColor));
        }
    }

    private void addExtraColors(Map<String, Integer> allColors) {
        // Системные цвета
        int[] systemColors = {
                android.R.color.background_light,
                android.R.color.background_dark,
                android.R.color.primary_text_light,
                android.R.color.primary_text_dark,
                android.R.color.secondary_text_light,
                android.R.color.secondary_text_dark
        };

        for (int colorId : systemColors) {
            try {
                String resourceName = getResources().getResourceEntryName(colorId);
                int colorValue = ContextCompat.getColor(this, colorId);
                allColors.put(resourceName, colorValue);
            } catch (Exception e) {
                Log.e("ColorDebug", "Error adding system color: " + colorId, e);
            }
        }

        // Атрибуты темы (получаем по именам)
        String[] themeColorAttrs = {
                "colorPrimary",
                "colorPrimaryDark",
                "colorAccent",
                "colorControlNormal",
                "colorControlActivated",
                "colorControlHighlight"
        };

        TypedValue typedValue = new TypedValue();
        Resources res = getResources();

        for (String attrName : themeColorAttrs) {
            try {
                // Получаем ID атрибута по имени
                int attrId = res.getIdentifier(attrName, "attr", getPackageName());

                if (attrId == 0) {
                    Log.e("ColorDebug", "Attribute not found: " + attrName);
                    continue;
                }

                // Разрешаем атрибут в цвет
                getTheme().resolveAttribute(attrId, typedValue, true);

                if (typedValue.resourceId != 0) {
                    String resourceName = res.getResourceEntryName(typedValue.resourceId);
                    int colorValue = ContextCompat.getColor(this, typedValue.resourceId);
                    allColors.put(resourceName, colorValue);
                } else if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                        typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    // Используем имя атрибута, если нет имени ресурса
                    allColors.put(attrName, typedValue.data);
                } else {
                    Log.e("ColorDebug", "Attribute is not a color: " + attrName);
                }
            } catch (Exception e) {
                Log.e("ColorDebug", "Error adding theme color: " + attrName, e);
            }
        }
    }

    private void showColorPicker(String colorName, Map<String, Integer> colors) {
        if (!colors.containsKey(colorName)) {
            Log.e("ColorPicker", "Color not found: " + colorName);
            return;
        }

        int initialColor = colors.get(colorName);
        new AmbilWarnaDialog(this, initialColor, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                // Обновляем в основном потоке
                new Handler(Looper.getMainLooper()).post(() -> {
                    colors.put(colorName, color);

                    if (colors == dayColors) {
                        dayAdapter.updateColors(dayColors);
                    } else {
                        nightAdapter.updateColors(nightColors);
                    }
                });
            }

            @Override
            public void onCancel(AmbilWarnaDialog dialog) {}
        }).show();
    }

    private void saveColors() {
        SharedPreferences preferences = getSharedPreferences("AppColors", MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();

        editor.putBoolean("isNightMode", isNightMode);

        // Синхронизированное копирование
        Map<String, Integer> safeDayColors = new HashMap<>(dayColors);
        Map<String, Integer> safeNightColors = new HashMap<>(nightColors);

        for (Map.Entry<String, Integer> entry : safeDayColors.entrySet()) {
            editor.putInt("DAY_" + entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, Integer> entry : safeNightColors.entrySet()) {
            editor.putInt("NIGHT_" + entry.getKey(), entry.getValue());
        }

        editor.apply();
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();

        // Сбрасываем кэш в ColorManager
        ColorManager.getInstance(this, isNightMode).clearCache();

        // Отправляем широковещательное сообщение
        sendBroadcast(new Intent("COLORS_UPDATED"));
    }

    private void showResetConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Сброс настроек")
                .setMessage("Вы уверены, что хотите сбросить все настройки цветов?")
                .setPositiveButton("Сбросить", (dialog, which) -> resetColorsToDefault())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void resetColorsToDefault() {
        SharedPreferences preferences = getSharedPreferences("AppColors", MODE_PRIVATE);
        preferences.edit().clear().apply();

        isNightMode = false;
        themeSwitch.setChecked(false);
        updateThemeVisibility();

        // Перезагружаем цвета по умолчанию
        initDefaultColors();

        // Обновляем адаптеры в UI-потоке
        new Handler(Looper.getMainLooper()).post(() -> {
            dayAdapter.updateColors(dayColors);
            nightAdapter.updateColors(nightColors);
        });

        saveColors();
        Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}