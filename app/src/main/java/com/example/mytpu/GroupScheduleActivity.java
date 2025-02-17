package com.example.mytpu;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class GroupScheduleActivity extends AppCompatActivity {

    private TextView scheduleTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_schedule);

        scheduleTextView = findViewById(R.id.scheduleTextView);

        // Получаем данные из Intent
        String schedule = getIntent().getStringExtra("schedule");

        // Отображаем расписание
        if (schedule != null) {
            scheduleTextView.setText(schedule);
        } else {
            scheduleTextView.setText("Расписание не найдено.");
        }
    }
}