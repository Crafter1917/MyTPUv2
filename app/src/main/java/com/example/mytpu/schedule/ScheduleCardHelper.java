// ScheduleCardHelper.java
package com.example.mytpu.schedule;

import static android.content.ContentValues.TAG;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.cardview.widget.CardView;
import android.util.Log;

import com.example.mytpu.ColorManager;
import com.example.mytpu.R;

public class ScheduleCardHelper {
    private static ColorManager colorManager;

    public static View createLessonCard(Context context, String subgroups,
                                        String subject, String audience,
                                        String type, String teacher, String time, ColorManager colorManager) {

        Log.d(TAG, "createLessonCard is created!");
        LayoutInflater inflater = LayoutInflater.from(context);
        View cardView = inflater.inflate(R.layout.card_lesson, null);

        TextView tvSubject = cardView.findViewById(R.id.tvSubject);
        TextView tvTime = cardView.findViewById(R.id.tvTime);
        TextView tvTeacher = cardView.findViewById(R.id.tvTeacher);
        TextView tvAudience = cardView.findViewById(R.id.tvAudience);
        TextView tvType = cardView.findViewById(R.id.tvType);
        CardView card = cardView.findViewById(R.id.cardView);

        // Устанавливаем данные
        tvSubject.setText(subject);
        tvTime.setText(time);
        tvTeacher.setText(teacher);
        tvAudience.setText(audience);
        tvType.setText(type);
        // Применяем цвета
        card.setCardBackgroundColor(colorManager.getColor("lesson_card_background"));
        tvSubject.setTextColor(colorManager.getColor("text_primary"));
        tvTime.setTextColor(colorManager.getColor("text_secondary"));
        tvTeacher.setTextColor(colorManager.getColor("text_secondary"));
        tvAudience.setTextColor(colorManager.getColor("text_secondary"));
        tvType.setTextColor(colorManager.getColor("text_primary"));
        tvType.setBackgroundColor(colorManager.getColor("active_day_background"));
        // Настраиваем цвет в зависимости от типа занятия
        int color = getColorForLessonType(type);
        tvType.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));

        // Если есть информация о подгруппе, добавляем к названию предмета
        if (!subgroups.isEmpty()) {
            tvSubject.setText(subject + " (" + subgroups + ")");
        }

        return cardView;
    }
    private static int getColorForLessonType(String type) {
        if (type == null) return Color.LTGRAY;

        switch (type.toLowerCase()) {
            case "лекция":
                return Color.parseColor("#BBDEFB"); // голубой
            case "практика":
                return Color.parseColor("#C8E6C9"); // зеленый
            case "лабораторная":
                return Color.parseColor("#FFECB3"); // желтый
            default:
                return Color.LTGRAY;
        }
    }
}
