package com.example.mytpu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {
    private boolean isReceiverRegistered = false;
    private final BroadcastReceiver colorUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ColorManager.getInstance(context, false).clearCache();
            applyCustomColors();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter("COLORS_UPDATED");

            // Для Android 12+ используем новые флаги
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(colorUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
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