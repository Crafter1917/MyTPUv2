package com.example.mytpu;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

import yuku.ambilwarna.AmbilWarnaDialog;

public class SettingsActivity extends AppCompatActivity {
    private Map<String, Integer> dayColors = new HashMap<>();
    private Map<String, Integer> nightColors = new HashMap<>();
    private boolean isNightMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initDefaultColors();

        Switch themeSwitch = findViewById(R.id.themeSwitch);
        LinearLayout dayContainer = findViewById(R.id.dayColorsContainer);
        LinearLayout nightContainer = findViewById(R.id.nightColorsContainer);
        Button saveButton = findViewById(R.id.saveButton);
        Button resetButton = findViewById(R.id.resetButton);

        populateColorViews(dayContainer, dayColors);
        populateColorViews(nightContainer, nightColors);

        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isNightMode = isChecked;
            dayContainer.setVisibility(isChecked ? View.GONE : View.VISIBLE);
            nightContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        saveButton.setOnClickListener(v -> saveColors());
        resetButton.setOnClickListener(v -> showResetConfirmationDialog());
    }

    private void initDefaultColors() {
        SharedPreferences preferences = getSharedPreferences("AppColors", MODE_PRIVATE);

        int[] colorIds = {
                R.color.purple_500, R.color.purple_700, R.color.teal_200,
                R.color.white, R.color.black, R.color.colorAccent,
                R.color.cardBackground, R.color.primaryColor, R.color.lesson_card_background,
                R.color.textPrimary, R.color.primaryDarkColor, R.color.textSecondary,
                R.color.colorPrimary, R.color.linkColor, R.color.textHighlight,
                R.color.dark_background, R.color.grey_300, R.color.card_background,
                R.color.active_day_background, R.color.border_color, R.color.link_color
        };

        for (int colorId : colorIds) {
            String colorName = getResources().getResourceEntryName(colorId);
            int defaultColor = ContextCompat.getColor(this, colorId);

            dayColors.put(colorName, preferences.getInt("DAY_" + colorName, defaultColor));
            nightColors.put(colorName, preferences.getInt("NIGHT_" + colorName, defaultColor));
        }
    }

    private void populateColorViews(LinearLayout container, Map<String, Integer> colors) {
        LayoutInflater inflater = LayoutInflater.from(this);

        colors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(entry -> {
                    View colorItem = inflater.inflate(R.layout.color_item, container, false);
                    TextView colorName = colorItem.findViewById(R.id.colorName);
                    View colorPreview = colorItem.findViewById(R.id.colorPreview);

                    colorName.setText(entry.getKey());
                    colorPreview.setBackgroundColor(entry.getValue());

                    colorPreview.setOnClickListener(v -> showColorPicker(entry.getKey(), colors));
                    container.addView(colorItem);
                });
    }

    private void showColorPicker(String colorName, Map<String, Integer> colors) {
        int initialColor = colors.get(colorName);
        new AmbilWarnaDialog(this, initialColor, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                colors.put(colorName, color);
                updateColorPreviews();
            }

            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
                Toast.makeText(SettingsActivity.this, "Выбор цвета отменен", Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    private void updateColorPreviews() {
        LinearLayout dayContainer = findViewById(R.id.dayColorsContainer);
        LinearLayout nightContainer = findViewById(R.id.nightColorsContainer);

        updateContainerColors(dayContainer, dayColors);
        updateContainerColors(nightContainer, nightColors);
    }

    private void updateContainerColors(LinearLayout container, Map<String, Integer> colors) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                TextView nameView = group.findViewById(R.id.colorName);
                View preview = group.findViewById(R.id.colorPreview);
                String colorName = nameView.getText().toString();
                preview.setBackgroundColor(colors.get(colorName));
            }
        }
    }

    private void saveColors() {
        SharedPreferences preferences = getSharedPreferences("AppColors", MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();

        for (Map.Entry<String, Integer> entry : dayColors.entrySet()) {
            editor.putInt("DAY_" + entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, Integer> entry : nightColors.entrySet()) {
            editor.putInt("NIGHT_" + entry.getKey(), entry.getValue());
        }

        editor.apply();
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();

        // Отправляем широковещательное сообщение об обновлении цветов
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
        initDefaultColors();
        updateColorPreviews();
        saveColors(); // Сохраняем дефолтные значения
        Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}