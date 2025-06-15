package com.example.mytpu;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {
    protected boolean isNightMode = false;
    protected ColorManager colorManager;
    private boolean isReceiverRegistered = false;

    private final BroadcastReceiver colorUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Пересоздаем ColorManager с актуальным режимом темы
            SharedPreferences prefs = getSharedPreferences("AppColors", MODE_PRIVATE);
            isNightMode = prefs.getBoolean("isNightMode", false);
            colorManager = ColorManager.getInstance(context, isNightMode);

            // Очищаем кэш и применяем новые цвета
            colorManager.clearCache();
            applyCustomColors();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Получаем текущий режим темы
        SharedPreferences prefs = getSharedPreferences("AppColors", MODE_PRIVATE);
        isNightMode = prefs.getBoolean("isNightMode", false);
        colorManager = ColorManager.getInstance(this, isNightMode);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter("COLORS_UPDATED");

            int flags = Context.RECEIVER_NOT_EXPORTED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(colorUpdateReceiver, filter, flags);
            } else {
                registerReceiver(colorUpdateReceiver, filter);
            }

            isReceiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isReceiverRegistered) {
            unregisterReceiver(colorUpdateReceiver);
            isReceiverRegistered = false;
        }
    }

    protected abstract void applyCustomColors();
}