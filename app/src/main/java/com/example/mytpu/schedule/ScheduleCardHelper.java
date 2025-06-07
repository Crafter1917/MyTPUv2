// ScheduleCardHelper.java
package com.example.mytpu.schedule;

import static android.content.ContentValues.TAG;

import android.content.Context;
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

        tvSubject.setText(subject);
        tvTime.setText(time);
        tvTeacher.setText(teacher);
        tvAudience.setText(audience);

        return cardView;
    }
}
