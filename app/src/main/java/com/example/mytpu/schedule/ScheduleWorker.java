package com.example.mytpu.schedule;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mytpu.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ScheduleWorker extends Worker {
    private static final String TAG = "ScheduleWorker";
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private static final String SCHEDULE_FILE_NAME = "tpu_schedule.json";
    private static final String PREFS_NAME = "SearchPrefs";
    private static final String KEY_SCHEDULE_HASH = "schedule_hash";

    private final OkHttpClient client = new OkHttpClient();
    private final File scheduleFile;

    public ScheduleWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        File documentsDir = context.getExternalFilesDir(null);
        scheduleFile = new File(documentsDir, SCHEDULE_FILE_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {Log.d(TAG, "Starting schedule update check...");
            String offlineData = loadOfflineSchedule();
            checkForUpdates(offlineData);
            return Result.success();
            } catch (Exception e) {
                return Result.retry(); // Повторить через некоторое время
        }
    }

    private void checkForUpdates(String currentData) {
        try (Response response = client.newCall(new Request.Builder()
                .url(API_URL)
                .build()).execute()) {

            if (!response.isSuccessful()) return;

            String onlineData = response.body().string();
            String currentHash = computeHash(onlineData);
            String savedHash = getSharedPreferences().getString(KEY_SCHEDULE_HASH, "");

            if (!currentHash.equals(savedHash)) {
                saveScheduleToFile(onlineData);
                showUpdateNotification();
            }
        } catch (IOException e) {
            Log.e(TAG, "Network error during update check: " + e.getMessage());
        }
    }

    private String loadOfflineSchedule() {
        try (FileInputStream fis = new FileInputStream(scheduleFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private void saveScheduleToFile(String jsonData) {
        try (FileOutputStream fos = new FileOutputStream(scheduleFile)) {
            fos.write(jsonData.getBytes());
            saveHash(jsonData);
            Log.d(TAG, "Schedule file updated successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error saving schedule: " + e.getMessage());
        }
    }

    private void saveHash(String data) {
        getSharedPreferences().edit()
                .putString(KEY_SCHEDULE_HASH, computeHash(data))
                .apply();
    }

    private String computeHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not found", e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void showUpdateNotification() {
        Context context = getApplicationContext();
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "schedule_updates",
                    "Schedule Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "schedule_updates")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.schedule_updated))
                .setContentText(context.getString(R.string.new_schedule_available))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        manager.notify(1, builder.build());
    }

    private SharedPreferences getSharedPreferences() {
        return getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}