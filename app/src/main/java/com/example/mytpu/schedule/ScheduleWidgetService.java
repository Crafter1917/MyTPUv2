package com.example.mytpu.schedule;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ScheduleWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int widgetSize = intent.getIntExtra("widget_size", 2);
        int appWidgetId = intent.getIntExtra("appWidgetId", 0);
        return new ScheduleRemoteViewsFactory(getApplicationContext(), widgetSize, appWidgetId);
    }
}

class ScheduleRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
    private final Context context;
    private int widgetSize;
    private final int appWidgetId;
    private final List<WidgetDayItem> items = new ArrayList<>();

    ScheduleRemoteViewsFactory(Context context, int widgetSize, int appWidgetId) {
        this.context = context;
        this.widgetSize = widgetSize;
        this.appWidgetId = appWidgetId;
    }

    @Override
    public void onCreate() {
        loadData();
    }

    private void loadData() {
        items.clear();
        SharedPreferences prefs = context.getSharedPreferences("WidgetPrefs", Context.MODE_PRIVATE);
        String jsonData = prefs.getString("widget_data", "{}");

        // Проверка на пустое расписание
        if ("{}".equals(jsonData)) {
            // Очищаем сохранённые данные виджета
            prefs.edit()
                    .putString("widget_data", null)
                    .putString("current_group", null)
                    .putInt("saved_week", 0)
                    .putInt("saved_year", 0)
                    .apply();

            // Добавляем сообщение об отсутствии расписания
            items.add(new WidgetDayItem(0, "Нету", "",
                    Collections.singletonList(new WidgetLesson("", "", "Выберите группу в приложении или занятий нет", "", "", "", 0)),
                    false));
            return;
        }
        try {
            JSONObject widgetData = new JSONObject(jsonData);
            Calendar calendar = Calendar.getInstance();
            int currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
            int currentDayIndex = (currentDayOfWeek + 5) % 7;
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM", Locale.getDefault());
            String[] dayNames = context.getResources().getStringArray(R.array.week_days_short);

            for (int i = 0; i < 7; i++) {
                JSONObject dayData = widgetData.optJSONObject(String.valueOf(i));
                List<WidgetLesson> dayLessons = new ArrayList<>();
                String date = "";

                if (dayData != null) {
                    date = dayData.optString("date", "");
                    JSONArray lessons = dayData.optJSONArray("lessons");

                    if (lessons != null) {
                        List<JSONObject> lessonList = new ArrayList<>();
                        for (int j = 0; j < lessons.length(); j++) {
                            lessonList.add(lessons.getJSONObject(j));
                        }

                        lessonList.sort((o1, o2) -> {
                            String para1 = o1.optString("para", "0");
                            String para2 = o2.optString("para", "0");
                            return Integer.compare(parseParaNumber(para1), parseParaNumber(para2));
                        });

                        for (JSONObject lesson : lessonList) {
                            dayLessons.add(new WidgetLesson(
                                    lesson.optString("para", "?"),
                                    lesson.optString("time", ""),
                                    lesson.optString("subject", "Нет данных"),
                                    lesson.optString("audience", ""),
                                    lesson.optString("type", ""),        // Добавляем тип
                                    lesson.optString("teacher", ""),     // Добавляем преподавателя
                                    lesson.optInt("subgroups", 0)        // Добавляем подгруппу
                            ));
                        }
                    }
                }

                // Добавляем только дни с парами
                if (!dayLessons.isEmpty()) {
                    items.add(new WidgetDayItem(
                            i,
                            dayNames[i],
                            date,
                            dayLessons,
                            i == currentDayIndex
                    ));
                }
            }
        } catch (Exception e) {
            Log.e("WidgetError", "Data loading failed", e);
        }
    }

    private int parseParaNumber(String para) {
        try {
            String clean = para.replaceAll("\\D", "");
            return Integer.parseInt(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public RemoteViews getViewAt(int position) {
        WidgetDayItem item = items.get(position);
        int layoutId = R.layout.widget_day_item;
        if ("не загружено".equals(item.getDate())) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_day_medium);
            views.setTextViewText(R.id.day_header, item.getDayName());
            views.setTextViewText(R.id.day_date, item.getDate());
            views.setTextViewText(R.id.lesson_info, item.getLessons().get(0).getSubject());
            return views;
        }
        switch (widgetSize) {
            case 0: layoutId = R.layout.widget_day_small; break;
            case 1: layoutId = R.layout.widget_day_medium; break;
            case 2: layoutId = R.layout.widget_day_horizontal; break;
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);

        switch (widgetSize) {
            case 0:
                views.setTextViewText(R.id.day_header, item.getDayName());
                views.setTextViewText(R.id.day_date, item.getDate());

                StringBuilder lessonsSmall = new StringBuilder();
                for (int i = 0; i < item.getLessons().size(); i++) {
                    WidgetLesson lesson = item.getLessons().get(i);
                    lessonsSmall.append(lesson.getPara())
                            .append("\n")
                            .append(lesson.getSubject());
                    if (i < item.getLessons().size() - 1) {
                        lessonsSmall.append("\n\n");
                    }
                }
                views.setTextViewText(R.id.lesson_info, lessonsSmall.toString());
                break;

            case 1:
                views.setTextViewText(R.id.day_header, item.getDayName());
                views.setTextViewText(R.id.day_date, item.getDate());

                StringBuilder lessonsMedium = new StringBuilder();
                for (int i = 0; i < item.getLessons().size(); i++) {
                    WidgetLesson lesson = item.getLessons().get(i);
                    if (lesson.getSubgroups() > 0) {
                        lessonsMedium.append("Подгруппа: ")
                                .append(lesson.getSubgroups())
                                .append("\n");
                    }
                    lessonsMedium.append(lesson.getPara())
                            .append(": ")
                            .append(lesson.getTime())
                            .append(", ")
                            .append(lesson.getAudience())
                            .append("\n")
                            .append(lesson.getSubject());
                    if (i < item.getLessons().size() - 1) {
                        lessonsMedium.append("\n\n");
                    }
                }
                views.setTextViewText(R.id.lesson_info, lessonsMedium.toString());
                break;

// В методе getViewAt()
            case 2: // Горизонтальный виджет
                views.setTextViewText(R.id.day_header, item.getDayName());
                views.setTextViewText(R.id.day_date, item.getDate());

                StringBuilder fullInfoBuilder = new StringBuilder();
                for (int i = 0; i < item.getLessons().size(); i++) {
                    WidgetLesson lesson = item.getLessons().get(i);

                    // Добавляем информацию о подгруппе, если есть
                    if (lesson.getSubgroups() > 0) {
                        fullInfoBuilder.append("Подгруппа: ")
                                .append(lesson.getSubgroups())
                                .append("\n");
                    }

                    fullInfoBuilder.append(lesson.getPara())
                            .append(": ")
                            .append(lesson.getTime())
                            .append(", ").append("Ауд. ")
                            .append(lesson.getAudience())
                            .append(", ")
                            .append(lesson.getType())
                            .append("\n")
                            .append(lesson.getSubject());


                    // Добавляем преподавателя, если есть
                    if (!lesson.getTeacher().isEmpty()) {
                        fullInfoBuilder.append("\n")
                                .append(lesson.getTeacher());
                    }

                    if (i < item.getLessons().size() - 1) {
                        fullInfoBuilder.append("\n\n");
                    }
                }

                views.setTextViewText(R.id.lesson_info, fullInfoBuilder.toString());
                break;
        }

        if (item.isToday()) {
            views.setInt(R.id.widget_day_container, "setBackgroundResource", R.drawable.widget_day_background_today);
        } else {
            views.setInt(R.id.widget_day_container, "setBackgroundResource", R.drawable.widget_day_background);
        }

        return views;
    }

    @Override public int getCount() { return items.size(); }
    @Override public long getItemId(int position) { return position; }
    @Override public RemoteViews getLoadingView() { return null; }
    @Override public int getViewTypeCount() { return 4; }
    @Override public boolean hasStableIds() { return true; }

    @Override
    public void onDataSetChanged() {
        updateWidgetSize();
        loadData();
    }

    @Override public void onDestroy() {}

    private void updateWidgetSize() {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        widgetSize = getWidgetSize(minWidth);
    }

    private int getWidgetSize(int minWidthDp) {
        if (minWidthDp <= 150) return 0;
        if (minWidthDp <= 300) return 1;
        return 2;
    }
}

class WidgetDayItem {
    private final int dayIndex;
    private final String dayName;
    private final String date;
    private final List<WidgetLesson> lessons;
    private final boolean isToday;

    public WidgetDayItem(int dayIndex, String dayName, String date,
                         List<WidgetLesson> lessons, boolean isToday) {
        this.dayIndex = dayIndex;
        this.dayName = dayName;
        this.date = date;
        this.lessons = lessons;
        this.isToday = isToday;
    }

    public int getDayIndex() { return dayIndex; }
    public String getDayName() { return dayName; }
    public String getDate() { return date; }
    public List<WidgetLesson> getLessons() { return lessons; }
    public boolean isToday() { return isToday; }
}

class WidgetLesson {
    private final String para;
    private final String time;
    private final String subject;
    private final String audience;
    private final String type;
    private final String teacher;
    private final int subgroups;

    public WidgetLesson(String para, String time, String subject,
                        String audience, String type, String teacher, int subgroups) {
        this.para = para;
        this.time = time;
        this.subject = subject;
        this.audience = audience;
        this.type = type;
        this.teacher = teacher;
        this.subgroups = subgroups;
    }

    // Добавляем геттеры для всех полей
    public String getPara() { return para; }
    public String getTime() { return time; }
    public String getSubject() { return subject; }
    public String getAudience() { return audience; }
    public String getType() { return type; }
    public String getTeacher() { return teacher; }
    public int getSubgroups() { return subgroups; }
}
