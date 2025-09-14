// ScheduleCardHelper.java
package com.example.mytpu.schedule;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.cardview.widget.CardView;
import com.example.mytpu.R;

public class ScheduleCardHelper {
    public static View createLessonCard(Context context, String subgroups,
                                        String subject, String audience,
                                        String type, String teacher, String time) {

        Log.d(TAG, "createLessonCard is created!");
        LayoutInflater inflater = LayoutInflater.from(context);
        View cardView = inflater.inflate(R.layout.card_lesson, null);

        TextView tvSubject = cardView.findViewById(R.id.tvSubject);
        TextView tvTime = cardView.findViewById(R.id.tvTime);
        TextView tvTeacher = cardView.findViewById(R.id.tvTeacher);
        TextView tvAudience = cardView.findViewById(R.id.tvAudience);
        TextView tvType = cardView.findViewById(R.id.tvType);

        // Устанавливаем данные
        tvSubject.setText(subject);
        tvTime.setText(time);
        tvTeacher.setText(teacher);
        tvAudience.setText(audience);
        tvType.setText(type);

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
