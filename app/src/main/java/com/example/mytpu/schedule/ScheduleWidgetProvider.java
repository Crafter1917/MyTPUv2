package com.example.mytpu.schedule;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class ScheduleWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "ScheduleWidgetProvider";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    // ScheduleWidgetProvider.java
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        updateAppWidget(context, appWidgetManager, appWidgetId);
        // Принудительное обновление данных
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.schedule_list);
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
    }

    private void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int size = getWidgetSize(minWidth);

        Intent intent = new Intent(context, ScheduleWidgetService.class);
        intent.putExtra("widget_size", size);
        intent.putExtra("appWidgetId", appWidgetId); // Добавлено
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))); // Фикс для кэширования

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_schedule);
        views.setRemoteAdapter(R.id.schedule_list, intent);
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private int getWidgetSize(int minWidthDp) {
        // Уточняем размеры под современные устройства
        if (minWidthDp < 100) return 0; // Маленький (1x1)
        if (minWidthDp < 250) return 1; // Средний (2x2)
        return 2; // Широкий (3x2 и более)
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        Bundle extras = intent.getExtras();
        if (extras != null) {
            int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.schedule_list);
        }
    }
}
