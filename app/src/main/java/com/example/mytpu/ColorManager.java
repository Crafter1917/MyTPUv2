package com.example.mytpu;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.TypedValue; // Добавлен импорт
import androidx.core.content.ContextCompat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ColorManager {
    private static final String PREFS_NAME = "AppColors";
    private static ColorManager instance;
    private final Context context;
    private final boolean isNightMode;
    private final Map<String, Integer> colorCache = Collections.synchronizedMap(new HashMap<>());

    public static synchronized ColorManager getInstance(Context context, boolean isNightMode) {
        if (instance == null) {
            instance = new ColorManager(context.getApplicationContext(), isNightMode);
        }
        return instance;
    }

    public ColorManager(Context context, boolean isNightMode) {
        this.context = context;
        this.isNightMode = isNightMode;
    }

    public int getColor(String colorName) {
        String cacheKey = (isNightMode ? "NIGHT_" : "DAY_") + colorName;
        if (colorCache.containsKey(cacheKey)) {
            return colorCache.get(cacheKey);
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = (isNightMode ? "NIGHT_" : "DAY_") + colorName;

        int color;
        if (prefs.contains(key)) {
            color = prefs.getInt(key, ContextCompat.getColor(context, android.R.color.black));
        } else {
            // Пытаемся получить цвет по имени ресурса
            int resId = context.getResources().getIdentifier(
                    colorName, "color", context.getPackageName()
            );

            if (resId != 0) {
                color = ContextCompat.getColor(context, resId);
            } else {
                // Пробуем получить через атрибут
                int attrId = context.getResources().getIdentifier(
                        colorName, "attr", context.getPackageName()
                );

                if (attrId != 0) {
                    TypedValue typedValue = new TypedValue();
                    context.getTheme().resolveAttribute(attrId, typedValue, true);

                    // Проверяем тип данных атрибута
                    if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                            typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        color = typedValue.data;
                    } else if (typedValue.resourceId != 0) {
                        color = ContextCompat.getColor(context, typedValue.resourceId);
                    } else {
                        Log.e("ColorManager", "Attribute is not a color: " + colorName);
                        color = ContextCompat.getColor(context, android.R.color.black);
                    }
                } else {
                    Log.e("ColorManager", "Color resource not found: " + colorName);
                    color = ContextCompat.getColor(context, android.R.color.black);
                }
            }
        }

        colorCache.put(cacheKey, color);
        return color;
    }

    public void clearCache() {
        colorCache.clear();
    }
}