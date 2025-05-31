package com.example.mytpu.mailTPU;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final String LOG_DIR = "AppLogs";
    private static final String LOG_FILE = "app_log.txt";
    private static final int MAX_LOG_SIZE = 2 * 1024 * 1024; // 2MB

    private static FileLogger instance;
    private final Context context;
    private BufferedWriter writer;

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new FileLogger(context.getApplicationContext());
        }
    }

    private FileLogger(Context context) {
        this.context = context;
        setupWriter();
    }

    private File getLogFile() {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File logDir = new File(documentsDir, LOG_DIR);

        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory");
                return null;
            }
        }
        return new File(logDir, LOG_FILE);
    }

    private void setupWriter() {
        try {
            File logFile = getLogFile();
            if (logFile == null) return;

            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File backup = new File(logFile.getParent(), "log_" + timestamp + ".txt");
                logFile.renameTo(backup);
            }

            writer = new BufferedWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            Log.e(TAG, "Error initializing file writer", e);
        }
    }

    public static void log(String tag, String message) {
        if (instance == null || instance.writer == null) return;

        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String logEntry = String.format("%s [%s] %s: %s\n",
                    timestamp,
                    Thread.currentThread().getName(),
                    tag,
                    message);

            instance.writer.write(logEntry);
            instance.writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing log to file", e);
        }
    }

    public static void close() {
        if (instance != null && instance.writer != null) {
            try {
                instance.writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing writer", e);
            }
        }
    }
}