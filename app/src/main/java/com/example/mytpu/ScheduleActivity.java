package com.example.mytpu;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.graphics.Rect;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.ViewCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.animation.ObjectAnimator;
import android.view.animation.OvershootInterpolator;

public class ScheduleActivity extends AppCompatActivity {
    private static final String TAG = "ScheduleActivity";
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private static final int DAYS_IN_WEEK = 7;
    private static final int WEEKS_IN_YEAR = 52;
    private static final int[] DAY_IDS = {
            R.id.scheduleContainerMonday,
            R.id.scheduleContainerTuesday,
            R.id.scheduleContainerWednesday,
            R.id.scheduleContainerThursday,
            R.id.scheduleContainerFriday,
            R.id.scheduleContainerSaturday,
            R.id.scheduleContainerSunday
    };
    private static final String SCHEDULE_FILE_NAME = "tpu_schedule.json";
    private File scheduleFile;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hourlyCheckRunnable;
    private AutoCompleteTextView searchField;
    private ImageButton btnSearchToggle;
    private static final String LOG_DIR = "app_logs";
    private static final String LOG_FILE = "crash_logs.txt";
    private static final int MAX_LOG_SIZE = 1024 * 1024; // 1MB
    private static final SimpleDateFormat LOG_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private boolean isLoading = false;
    private boolean[] hasLessons = new boolean[DAYS_IN_WEEK];
    private boolean clearData = false;
    private LinearLayout scheduleContainer;
    private TextView dataPrevious, currentData, dataNext, compactCurrentDate;
    private OkHttpClient client;
    private ExecutorService executor;
    private int selectedYear, selectedWeek;
    private String jsonDataOfSite;
    private boolean cWN;
    private final Map<String, LinearLayout> paraContainers = new HashMap<>();
    private ArrayAdapter<String> searchAdapter;
    private ArrayList<String> allGroups = new ArrayList<>();
    private ArrayList<String> allTeachers = new ArrayList<>();
    private String currentSearchQuery = "";
    private TextView groupsTextView;
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "SearchPrefs";
    private static final String KEY_LAST_SEARCH = "last_search";
    private static final String KEY_SEARCH_TYPE = "search_type";
    private ProgressBar progressBar;
    private CardView searchCard;
    private boolean isSearchExpanded = false;
    private int originalCardWidth;
    private boolean isKeyboardVisible = false;
    private final ConcurrentHashMap<String, List<LessonData>> groupedLessonsMap = new ConcurrentHashMap<>();
    private final Object lock = new Object(); // Добавить объект для синхронизации

    private static final int[] DAY_HEADER_IDS = {
            R.id.day_header1, // Понедельник
            R.id.day_header2, // Вторник
            R.id.day_header3, // Среда
            R.id.day_header4, // Четверг
            R.id.day_header5, // Пятница
            R.id.day_header6, // Суббота
            R.id.day_header7  // Воскресенье
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (!documentsDir.exists()) documentsDir.mkdirs();
        scheduleFile = new File(documentsDir, SCHEDULE_FILE_NAME);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        groupsTextView = findViewById(R.id.groups);
        searchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line);
        searchField = findViewById(R.id.searchField);
        progressBar = findViewById(R.id.progressBar);
        btnSearchToggle = findViewById(R.id.btnSearchToggle);
        searchField.setAdapter(searchAdapter);
        searchCard = findViewById(R.id.searchCard);

        restoreLastSearch();
        updateGroupsTextView(currentSearchQuery);
        initViews();
        setupScrollListener();
        setupWeekNavigationButtons();
        setupSearchField();
        initHttpClient();
        loadInitialWeek();

        setupSearchAutocomplete();
        updateSearchFieldBehavior();
        // Восстановление состояния
        if (savedInstanceState != null) {
            currentSearchQuery = savedInstanceState.getString("CURRENT_SEARCH", "");
            selectedWeek = savedInstanceState.getInt("SELECTED_WEEK");
            selectedYear = savedInstanceState.getInt("SELECTED_YEAR");
            hasLessons = savedInstanceState.getBooleanArray("HAS_LESSONS");
        }

        View rootView = findViewById(R.id.root_layout); // Замените на ваш корневой макет
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            // Получаем системные отступы
            int systemWindowInsetTop = insets.getSystemWindowInsetTop();
            int systemWindowInsetBottom = insets.getSystemWindowInsetBottom();
            int systemWindowInsetLeft = insets.getSystemWindowInsetLeft();
            int systemWindowInsetRight = insets.getSystemWindowInsetRight();

            // Применяем отступы к вашему макету
            v.setPadding(systemWindowInsetLeft, systemWindowInsetTop, systemWindowInsetRight, systemWindowInsetBottom);

            // Возвращаем insets для дальнейшей обработки
            return insets;
        });
        // Всегда загружайте JSON заново
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            logCrash(ex); // Только при вылете!
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }, 500); // Задержка для гарантированной записи
        });
        searchCard.post(() -> {
            originalCardWidth = searchCard.getWidth();
            setupKeyboardListener();
        });
        btnSearchToggle.setOnClickListener(v -> toggleSearch());
        setupKeyboardListener();
        // Остальная инициализация...

        if (jsonDataOfSite == null || jsonDataOfSite.isEmpty()) {
            loadSchedule(); // Загружаем данные только если их нет
        } else {
            processDataInBackground(jsonDataOfSite); // Обрабатываем сохраненные данные
        }
        hourlyCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkForUpdates();
                handler.postDelayed(this, 3600000); // Повтор каждые 60 минут
            }
        };

        loadInitialData();
    }

    private void logCrash(Throwable ex) {
        try {
            String logMessage = buildLogMessage(ex);
            writeLogToFile(logMessage);
        } catch (IOException e) {
            Log.e("Logs path", "Failed to write crash log", e);
        }
    }

    private void loadInitialData() {
        Log.d(TAG, "Начало загрузки начальных данных");
        String offlineData = loadOfflineSchedule();

        // 1. Сначала загружаем и показываем офлайн данные
        if (offlineData != null) {
            Log.d(TAG, "Используем офлайн данные");
            jsonDataOfSite = offlineData;
            processDataInBackgroundSync(offlineData);

            // Обновляем UI с офлайн данными
            runOnUiThread(() -> {
                    parseSchedule(offlineData);
            });
        }

        // 2. Затем в фоне проверяем обновления (если есть интернет)
        if (isNetworkAvailable()) {
            Log.d(TAG, "Начинаем проверку обновлений в фоне");
            checkForUpdates();
        } else if (offlineData == null) {
            Log.d(TAG, "Нет интернета и офлайн данных");
            showError("Нет подключения и локальных данных");
        }
    }

    private void checkForUpdates() {
        if (isLoading) {
            Log.d(TAG, "Проверка обновлений уже выполняется");
            return;
        }

        Log.d(TAG, "Начало проверки обновлений...");
        isLoading = true;

        executor.execute(() -> {
            try (Response response = client.newCall(new Request.Builder()
                    .url(API_URL)
                    .build()).execute()) {

                if (!response.isSuccessful()) {
                    Log.e(TAG, "Ошибка проверки обновлений: " + response.code());
                    return;
                }

                String onlineData = response.body().string();
                String currentHash = computeHash(onlineData);
                String savedHash = sharedPreferences.getString("schedule_hash", "");

                if (!currentHash.equals(savedHash)) {
                    Log.d(TAG, "Обнаружены изменения в расписании");

                    // Сохраняем новые данные
                    saveScheduleToFile(onlineData);

                    runOnUiThread(() -> {
                        // Показываем уведомление
                        showUpdateNotification();

                        // Обновляем данные только если они действительно изменились
                        if (!onlineData.equals(jsonDataOfSite)) {
                            jsonDataOfSite = onlineData;
                            Toast.makeText(ScheduleActivity.this, "Расписание обновлено", Toast.LENGTH_SHORT).show();

                            // Перезагружаем расписание, если есть активный поиск
                            if (!currentSearchQuery.isEmpty()) {
                                parseSchedule(onlineData);
                            }
                        }
                    });
                } else {
                    Log.d(TAG, "Локальные данные актуальны");
                }

            } catch (IOException e) {
                Log.e(TAG, "Ошибка сети при проверке обновлений: " + e.getMessage());
            } finally {
                isLoading = false;
            }
        });
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void showUpdateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "schedule_updates",
                    "Обновления расписания",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "schedule_updates")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Обновление расписания")
                .setContentText("Доступно новое расписание")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat.from(this).notify(1, builder.build());
    }
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void showNetworkErrorNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "network_errors")
                .setContentTitle("Нет подключения")
                .setContentText("Используются офлайн данные")
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(this).notify(2, builder.build());
    }
    private void processDataInBackgroundSync(String jsonData) {
        try {
            JSONObject root = new JSONObject(jsonData);
            JSONArray faculties = root.optJSONArray("faculties");
            collectSearchData(faculties != null ? faculties : new JSONArray());

            runOnUiThread(() -> {
                updateSearchAdapter();
                updateGroupsTextView(currentSearchQuery);
                if (!currentSearchQuery.isEmpty()) parseSchedule(jsonData);
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON Error: ", e);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }


    @Override
    protected void onResume() {
        super.onResume();
        handler.post(hourlyCheckRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hourlyCheckRunnable);
    }

    // Хеширование данных
    private String computeHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Ошибка хеширования", e);
            return "";
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

    private void saveHash(String data) {
        String hash = computeHash(data);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("schedule_hash", hash);
        editor.apply();
    }

    private void saveScheduleToFile(String jsonData) {
        executor.execute(() -> {
            try {
                FileOutputStream fos = new FileOutputStream(scheduleFile);
                fos.write(jsonData.getBytes());
                fos.close();
                saveHash(jsonData);

                Log.d(TAG, "Файл успешно сохранён");
                runOnUiThread(() ->
                        Toast.makeText(this, "Расписание обновлено", Toast.LENGTH_SHORT).show());

            } catch (IOException e) {
                Log.e(TAG, "Ошибка сохранения: " + e.getMessage());
            }
        });
    }

    private String loadOfflineSchedule() {
        Log.d(TAG, "Попытка загрузки офлайн расписания из: " + scheduleFile.getAbsolutePath());

        if (!scheduleFile.exists()) {
            Log.w(TAG, "Файл расписания не существует");
            return null;
        }

        try {
            FileInputStream fis = new FileInputStream(scheduleFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            Log.d(TAG, "Успешная загрузка офлайн данных");
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка чтения файла: " + e.getMessage());
            return null;
        }
    }

    private String buildLogMessage(Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);

        return String.format(
                "%s [%d] %s: %s\n%s",
                LOG_DATE_FORMAT.format(new Date()),
                android.os.Process.myPid(),
                "CRASH",
                ex.getMessage(),
                sw.toString()
        );
    }

    private synchronized void writeLogToFile(String message) throws IOException {
        File logDir = new File(getFilesDir(), LOG_DIR);
        Log.d("Logs path", "Logs path: " + logDir.getAbsolutePath());
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw new IOException("Failed to create log directory");
        }

        File logFile = new File(logDir, LOG_FILE);
        if (logFile.length() > MAX_LOG_SIZE) {
            rotateLogs(logDir);
        }

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.append(message);
            writer.append("\n\n");
        }
    }

    private void rotateLogs(File logDir) {
        File current = new File(logDir, LOG_FILE);
        File backup = new File(logDir, "crash_logs_old.txt");

        if (backup.exists() && !backup.delete()) {
            Log.w(TAG, "Failed to delete old backup log");
        }

        if (!current.renameTo(backup)) {
            Log.w(TAG, "Failed to rotate log file");
        }
    }


    private void toggleSearch() {
        if (isSearchExpanded) {
            collapseSearch();
        } else {
            expandSearch();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("CURRENT_SEARCH", currentSearchQuery);
        outState.putInt("SELECTED_WEEK", selectedWeek);
        outState.putInt("SELECTED_YEAR", selectedYear);
        outState.putBooleanArray("HAS_LESSONS", hasLessons);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        currentSearchQuery = savedInstanceState.getString("CURRENT_SEARCH", "");
        jsonDataOfSite = savedInstanceState.getString("JSON_DATA");
        selectedWeek = savedInstanceState.getInt("SELECTED_WEEK");
        selectedYear = savedInstanceState.getInt("SELECTED_YEAR");
        hasLessons = savedInstanceState.getBooleanArray("HAS_LESSONS");

        if (hasLessons == null) hasLessons = new boolean[DAYS_IN_WEEK];

        // Применяем фильтр после восстановления
        if (!currentSearchQuery.isEmpty()) {
            applySearch(currentSearchQuery);
        }
    }

    private void setupKeyboardListener() {
        final View activityRootView = getWindow().getDecorView().findViewById(android.R.id.content);
        activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            activityRootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = activityRootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;

            // Новый расчет видимости клавиатуры
            boolean isKeyboardNowVisible = keypadHeight > screenHeight * 0.15;

            if (isKeyboardNowVisible != isKeyboardVisible) {
                isKeyboardVisible = isKeyboardNowVisible;

                if (isKeyboardVisible) {
                    if (!isSearchExpanded) expandSearch();
                } else {
                    if (isSearchExpanded) collapseSearch();
                }
            }
        });
    }

    private void expandSearch() {
        if (isSearchExpanded) return;
        isSearchExpanded = true;

        searchField.post(() -> {
            ValueAnimator anim = ValueAnimator.ofInt(
                    searchCard.getWidth(),
                    getScreenWidth() - dpToPx(32)
            );

            anim.addUpdateListener(valueAnimator -> {
                int val = (Integer) valueAnimator.getAnimatedValue();
                ViewGroup.LayoutParams params = searchCard.getLayoutParams();
                params.width = val;
                searchCard.setLayoutParams(params);
            });

            anim.setDuration(300);
            anim.start();

            searchField.setVisibility(View.VISIBLE);
            searchField.postDelayed(() -> showKeyboard(), 150);
        });
    }

    private void collapseSearch() {
        if (!isSearchExpanded) return;
        isSearchExpanded = false;

        hideKeyboard();

        searchField.post(() -> {
            ValueAnimator anim = ValueAnimator.ofInt(
                    searchCard.getWidth(),
                    originalCardWidth
            );

            anim.addUpdateListener(valueAnimator -> {
                int val = (Integer) valueAnimator.getAnimatedValue();
                ViewGroup.LayoutParams params = searchCard.getLayoutParams();
                params.width = val;
                searchCard.setLayoutParams(params);
            });

            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    searchField.setVisibility(View.GONE);
                    searchField.clearFocus();
                }
            });

            anim.setDuration(300);
            anim.start();
        });
    }

    // Вспомогательные методы
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void showKeyboard() {
        if (searchField.requestFocus()) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private int getScreenWidth() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.widthPixels;
    }

    private void updateSearchFieldBehavior() {
        searchField = findViewById(R.id.searchField);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
        searchField.setMaxLines(1);
        searchField.setSingleLine(true);
    }

    private void setupSearchAutocomplete() {
        searchField = findViewById(R.id.searchField);
        searchField.setThreshold(1);
        searchField.setAdapter(searchAdapter);

        searchField.setOnItemClickListener((parent, view, position, id) -> {
            String selectedItem = (String) parent.getItemAtPosition(position);
            applySearch(selectedItem);
            Log.d(TAG, "Выбрано из списка: " + selectedItem);

            // Скрываем клавиатуру
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        });

        searchField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                Log.d(TAG, "Поле поиска получило фокус");
                if (allGroups.isEmpty() && allTeachers.isEmpty()) {
                    loadSchedule();
                } else {
                    updateSearchAdapter();
                }
            }
        });
    }

    private void applySearch(String query) {
        currentSearchQuery = query.trim().toLowerCase();
        saveSearchState(query);
        updateGroupsTextView(query);

        runOnUiThread(() -> {
            clearScheduleContainers();
            paraContainers.clear();
        });

        if (jsonDataOfSite != null && !jsonDataOfSite.isEmpty()) {
            parseSchedule(jsonDataOfSite);
        }
    }

    private void updateGroupsTextView(String query) {
        runOnUiThread(() -> {
            if (query == null || query.isEmpty()) {
                groupsTextView.setVisibility(View.GONE);
                return;
            }

            String displayText = "";
            // Проверяем группы без учета регистра
            boolean isGroup = false;
            for (String group : allGroups) {
                if (group.equalsIgnoreCase(query)) {
                    isGroup = true;
                    break;
                }
            }

            if (isGroup) {
                displayText = "Расписание для группы: " + query;
            } else if (allTeachers.contains(query.toLowerCase())) {
                displayText = "Расписание преподавателя: " + query;
            }

            groupsTextView.setText(displayText);
            groupsTextView.setVisibility(TextUtils.isEmpty(displayText) ? View.GONE : View.VISIBLE);
        });
    }

    private void restoreLastSearch() {
        String lastSearch = sharedPreferences.getString(KEY_LAST_SEARCH, "");
        if (!lastSearch.isEmpty()) {
            currentSearchQuery = lastSearch.toLowerCase();
            searchField.setText(lastSearch);
            updateGroupsTextView(lastSearch);
            // Добавить задержку для завершения инициализации
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (jsonDataOfSite != null) {
                    parseSchedule(jsonDataOfSite);
                }
            }, 500);
        }
    }

    private void saveSearchState(String query) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_LAST_SEARCH, query);
        String type = allGroups.contains(query) ? "group" :
                allTeachers.contains(query) ? "teacher" : "";
        editor.putString(KEY_SEARCH_TYPE, type);
        editor.apply();
    }

    private void collectSearchData(JSONArray faculties) throws JSONException {
        Set<String> uniqueGroups = new HashSet<>();
        Set<String> uniqueTeachers = new HashSet<>();

        for (int i = 0; i < faculties.length(); i++) {
            JSONObject faculty = faculties.getJSONObject(i);
            JSONArray groups = faculty.getJSONArray("groups");

            for (int j = 0; j < groups.length(); j++) {
                JSONObject group = groups.getJSONObject(j);
                String groupName = group.getString("name").trim().toLowerCase();
                uniqueGroups.add(groupName);

                JSONArray lessons = group.getJSONArray("lessons");
                for (int k = 0; k < lessons.length(); k++) {
                    JSONObject lesson = lessons.getJSONObject(k);
                    JSONArray teachers = lesson.optJSONArray("teachers");

                    if (teachers != null) {
                        for (int t = 0; t < teachers.length(); t++) {
                            String teacher = teachers.getJSONObject(t)
                                    .getString("name")
                                    .trim()
                                    .toLowerCase();
                            uniqueTeachers.add(teacher);
                        }
                    }
                }
            }
        }

        allGroups.clear();
        allGroups.addAll(uniqueGroups);
        Collections.sort(allGroups);

        allTeachers.clear();
        allTeachers.addAll(uniqueTeachers);
        Collections.sort(allTeachers);
    }

    private void updateSearchAdapter() {
        if (searchAdapter == null) return;

        runOnUiThread(() -> {
            List<String> suggestions = new ArrayList<>();

            // Добавляем группы
            for (String group : allGroups) {
                if (group.toLowerCase().contains(currentSearchQuery.toLowerCase())) {
                    suggestions.add(group);
                }
            }

            // Добавляем преподавателей
            for (String teacher : allTeachers) {
                if (teacher.toLowerCase().contains(currentSearchQuery.toLowerCase())) {
                    suggestions.add(teacher);
                }
            }

            // Сортируем и обновляем адаптер
            Collections.sort(suggestions);
            suggestions.addAll(allGroups);
            suggestions.addAll(allTeachers);
            searchAdapter.clear();
            searchAdapter.addAll(suggestions);
            searchAdapter.notifyDataSetChanged();

            Log.d(TAG, "Обновление адаптера. Найдено совпадений: " + suggestions.size());
        });
    }

    private void initViews() {
        scheduleContainer = findViewById(R.id.scheduleContainer);
        dataPrevious = findViewById(R.id.dataPrevious);
        currentData = findViewById(R.id.CurrectData);
        compactCurrentDate = findViewById(R.id.compactCurrentDate);
        dataNext = findViewById(R.id.dataNext);
    }

    private void setupScrollListener() {
        ScrollView scrollView = findViewById(R.id.scrollView);
        LinearLayout fullWeekNavigation = findViewById(R.id.fullWeekNavigation);
        LinearLayout compactWeekNavigation = findViewById(R.id.compactWeekNavigation);

        fullWeekNavigation.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                fullWeekNavigation.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int height = fullWeekNavigation.getHeight();

                scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (scrollY > height * 2 && !cWN) {
                        animateViewTransition(fullWeekNavigation, compactWeekNavigation);
                        cWN = true;
                    } else if (scrollY < height && cWN) {
                        animateViewTransition(compactWeekNavigation, fullWeekNavigation);
                        cWN = false;
                    }
                });
            }
        });
    }

    private void setupWeekNavigationButtons() {
        setupWeekButton(R.id.btnPreviousWeek, R.id.btnCompactPreviousWeekI, -1);
        setupWeekButton(R.id.btnCurrentWeek, R.id.btnCompactCurrentWeekI, 0);
        setupWeekButton(R.id.btnNextWeek, R.id.btnCompactNextWeekI, 1);
    }

    private void setupWeekButton(int regularButtonId, int compactButtonId, int weekDelta) {
        View.OnClickListener listener = v -> {
            if (weekDelta != 0) {
                selectedWeek += weekDelta;
                adjustWeekBounds();
            } else {
                Calendar calendar = Calendar.getInstance();
                selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
                selectedYear = calendar.get(Calendar.YEAR);
            }
            clearData = true;
            updateDates();
            updateDayDates(); // Добавить вызов здесь
            if (jsonDataOfSite != null) {
                parseSchedule(jsonDataOfSite);
            }
        };
        findViewById(regularButtonId).setOnClickListener(listener);
        ((ImageButton) findViewById(compactButtonId)).setOnClickListener(listener);
    }

    private void adjustWeekBounds() {
        if (selectedWeek < 1) {
            selectedWeek = WEEKS_IN_YEAR;
            selectedYear--;
        } else if (selectedWeek > WEEKS_IN_YEAR) {
            selectedWeek = 1;
            selectedYear++;
        }
    }

    private void setupSearchField() {
        //loadSchedule();
        EditText searchField = findViewById(R.id.searchField);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
    }

    private void initHttpClient() {
        client = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();
    }

    private void loadInitialWeek() {
        Calendar calendar = Calendar.getInstance();
        selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
        selectedYear = calendar.get(Calendar.YEAR);
        updateDates();
    }

    private void animateViewTransition(View viewToHide, View viewToShow) {
        ObjectAnimator hideAnimator = ObjectAnimator.ofFloat(viewToHide, "alpha", 1f, 0f);
        hideAnimator.setDuration(300);
        hideAnimator.start();

        animateJellyEffect(viewToShow);

        hideAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToHide.setVisibility(View.GONE);
                viewToShow.setVisibility(View.VISIBLE);
            }
        });
    }

    private void animateJellyEffect(View view) {
        view.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(500)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .start();
    }
    private void processAndShowOfflineData(String offlineData) {
        jsonDataOfSite = offlineData;
        processDataInBackgroundSync(offlineData);
        runOnUiThread(() -> {
            if (!currentSearchQuery.isEmpty()) {
                parseSchedule(offlineData);
            }
        });
    }
    private void loadSchedule() {
        if (jsonDataOfSite != null) return; // Не загружать если уже есть данные
        showLoading(true);
        if (!isNetworkAvailable()) {
            String offlineData = loadOfflineSchedule();
            if (offlineData != null) {
                processAndShowOfflineData(offlineData);
            }
            return;
        }
        executor.execute(() -> {
            try (Response response = client.newCall(new Request.Builder()
                    .url(API_URL)
                    .build()).execute()) {

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> showError("Ошибка загрузки: " + response.code()));
                    return;
                }

                // Получаем данные только один раз
                String responseData = response.body().string();
                jsonDataOfSite = responseData;

                // Обрабатываем данные и сохраняем
                processDataInBackground(responseData);
                saveScheduleToFile(responseData);

                runOnUiThread(() -> {
                    updateGroupsTextView(currentSearchQuery);
                    updateSearchAdapter();
                });

            } catch (IOException e) {
                runOnUiThread(() -> showError("Ошибка подключения: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> showLoading(false));
            }
        });
    }

    private void processDataInBackground(String jsonData) {
        executor.execute(() -> {
            try {
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.optJSONArray("faculties");

                // Всегда вызываем collectSearchData, даже если faculties null
                collectSearchData(faculties != null ? faculties : new JSONArray());

                // Обновляем адаптер в UI потоке после сбора данных
                runOnUiThread(() -> {
                    updateSearchAdapter();
                    updateGroupsTextView(currentSearchQuery);
                    Log.d(TAG, "Адаптер обновлен. Элементов: " + searchAdapter.getCount());
                });

                // Парсим расписание только если есть поисковый запрос
                if (!currentSearchQuery.isEmpty()) {
                    parseSchedule(jsonData);
                }

            } catch (JSONException e) {
                Log.e(TAG, "JSON Error: ", e);
                runOnUiThread(() -> showError("Ошибка данных: " + e.getMessage()));
            }
        });
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> {
            isLoading = show;
            if (progressBar != null) {
                progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void updateDayVisibility() {
        runOnUiThread(() -> {
            Calendar calendar = Calendar.getInstance();
            int currentDay = (calendar.get(Calendar.DAY_OF_WEEK) - 2);
            if (currentDay < 0) currentDay = 6;

            for (int i = 0; i < DAY_IDS.length; i++) {
                LinearLayout dayContainer = findViewById(DAY_IDS[i]);
                if (dayContainer != null) {
                    // Проверяем наличие уроков
                    boolean hasLessonsInDay = false;
                    for (int j = 0; j < dayContainer.getChildCount(); j++) {
                        View child = dayContainer.getChildAt(j);
                        if (child instanceof CardView && "dynamicParaCard".equals(child.getTag())) {
                            hasLessonsInDay = true;
                            break;
                        }
                    }
                    hasLessons[i] = hasLessonsInDay;

                    // Обновляем видимость родительского CardView
                    ViewParent parent = dayContainer.getParent();
                    if (parent instanceof CardView) {
                        CardView dayCard = (CardView) parent;
                        dayCard.setVisibility(hasLessonsInDay ? View.VISIBLE : View.GONE);

                        // Обновляем стиль для текущего дня
                        boolean isToday = (i == currentDay) && isCurrentWeek();
                        dayCard.setCardElevation(isToday ? 8f : 2f);
                        dayCard.setBackgroundResource(isToday ? R.drawable.border : R.drawable.card);
                    }
                }
            }
        });
    }

    @SuppressLint("DefaultLocale")
    private void parseSchedule(String jsonData) {
        executor.execute(() -> { // Заменяем new Thread(...).start() на executor.execute
            try {
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.optJSONArray("faculties");

                runOnUiThread(() -> {
                    if (clearData) {
                        clearScheduleContainers();
                        clearData = false;
                    }
                });

                if (faculties != null) {
                    synchronized (lock) {
                        groupedLessonsMap.clear();
                    }
                    Arrays.fill(hasLessons, false);

                    for (int i = 0; i < faculties.length(); i++) {
                        JSONObject faculty = faculties.optJSONObject(i);
                        JSONArray groups = faculty != null ? faculty.optJSONArray("groups") : null;
                        if (groups == null) continue;

                        for (int j = 0; j < groups.length(); j++) {
                            JSONObject group = groups.optJSONObject(j);
                            if (group == null) continue;

                            processGroup(group);
                        }
                    }

                    runOnUiThread(() -> {
                        List<String> sortedKeys = new ArrayList<>(groupedLessonsMap.keySet());
                        Collections.sort(sortedKeys, (k1, k2) -> {
                            String[] parts1 = k1.split("\\|");
                            String[] parts2 = k2.split("\\|");
                            int dayCompare = Integer.compare(Integer.parseInt(parts1[0]), Integer.parseInt(parts2[0]));
                            if (dayCompare != 0) return dayCompare;
                            return parts1[1].compareTo(parts2[1]);
                        });

                        for (String key : sortedKeys) {
                            addGroupedLessonsToUI(groupedLessonsMap.get(key));
                        }
                        updateDayVisibility();
                        updateDayDates();
                    });
                }
            } catch (JSONException e) {
                runOnUiThread(() -> showError("Ошибка разбора данных: " + e.getMessage()));
            }
        });
    }

    private void addGroupedLessonsToUI(List<LessonData> lessons) {
        // Проверяем, что список уроков не null и не пустой
        if (lessons == null || lessons.isEmpty()) return;

        // Сортируем уроки по времени начала
        Collections.sort(lessons, (l1, l2) -> {
            String time1 = l1.time.split(" - ")[0];
            String time2 = l2.time.split(" - ")[0];
            return time1.compareTo(time2);
        });

        runOnUiThread(() -> {
            LessonData firstLesson = lessons.get(0);
            LinearLayout container = getDayContainer(firstLesson.weekday);
            if (container == null) return;

            String uniqueKey = firstLesson.weekday + "|" + firstLesson.time;

            // Проверяем, не добавлена ли уже эта пара
            if (!paraContainers.containsKey(uniqueKey)) {
                CardView paraCard = createParaCard(firstLesson.time);
                paraCard.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                container.addView(paraCard);
                LinearLayout lessonContainer = paraCard.findViewById(R.id.LinearLayout_para);
                paraContainers.put(uniqueKey, lessonContainer);

                // Добавляем все уроки для этой пары
                for (LessonData lesson : lessons) {
                    View lessonView = createLessonCard(
                            lesson.subgroups > 0 ? "Подгруппа: " + lesson.subgroups : "",
                            lesson.subject,
                            lesson.time,
                            lesson.audience,
                            lesson.type,
                            lesson.teacher
                    );
                    lessonView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    ));
                    lessonContainer.addView(lessonView);
                }
            }
        });
    }

    private void updateDayDates() {
        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM", new Locale("ru")); // Формат: "21 мая"
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.YEAR, selectedYear);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        for (int i = 0; i < DAY_HEADER_IDS.length; i++) {
            TextView dateHeader = findViewById(DAY_HEADER_IDS[i]);
            if (dateHeader != null) {
                String dateString = sdf.format(cal.getTime());
                dateHeader.setText(dateString);
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
    }

    private void clearScheduleContainers() {
        runOnUiThread(() -> {
            for (int i = 1; i <= DAYS_IN_WEEK; i++) {
                LinearLayout container = getDayContainer(i);
                if (container != null) {
                    // Удаляем только уроки
                    for (int j = container.getChildCount() - 1; j >= 0; j--) {
                        View child = container.getChildAt(j);
                        if (child instanceof CardView && "dynamicParaCard".equals(child.getTag())) {
                            container.removeViewAt(j);
                        }
                    }
                }
            }
            paraContainers.clear();
            updateDayVisibility(); // Обновляем видимость
        });
    }

    private void processGroup(JSONObject group) throws JSONException {
        String groupName = group.optString("name", "").trim();
        boolean isGroupMatch = groupName.equalsIgnoreCase(currentSearchQuery);

        JSONArray lessons = group.getJSONArray("lessons");
        for (int k = 0; k < lessons.length(); k++) {
            JSONObject lesson = lessons.getJSONObject(k);

            if (isGroupMatch || checkTeacherInLesson(lesson)) {
                LessonData lessonData = processLesson(lesson);
                if (lessonData != null) {
                    String key = lessonData.weekday + "|" + lessonData.time;
                    if (!groupedLessonsMap.containsKey(key)) {
                        groupedLessonsMap.put(key, new ArrayList<>());
                    }
                    groupedLessonsMap.get(key).add(lessonData);
                }
            }
        }
    }

    private boolean checkTeacherInLesson(JSONObject lesson) throws JSONException {
        if (currentSearchQuery.isEmpty()) return false;

        JSONArray teachers = lesson.optJSONArray("teachers");
        if (teachers == null) return false;

        String query = currentSearchQuery.toLowerCase().trim();

        for (int t = 0; t < teachers.length(); t++) {
            JSONObject teacher = teachers.getJSONObject(t);
            String teacherName = teacher.getString("name")
                    .toLowerCase()
                    .trim();

            if (teacherName.contains(query)) {
                return true;
            }
        }
        return false;
    }

    private LessonData processLesson(JSONObject lesson) throws JSONException {
        JSONObject dateObj = lesson.optJSONObject("date");
        if (dateObj == null) return null;

        String startDateStr = dateObj.optString("start", "");
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
            Date startDate = sdf.parse(startDateStr);
            if (startDate == null) return null;

            Calendar lessonCalendar = Calendar.getInstance();
            lessonCalendar.setTime(startDate);

            Calendar selectedCalendar = Calendar.getInstance();
            selectedCalendar.set(Calendar.WEEK_OF_YEAR, selectedWeek);
            selectedCalendar.set(Calendar.YEAR, selectedYear);

            if (lessonCalendar.get(Calendar.WEEK_OF_YEAR) != selectedCalendar.get(Calendar.WEEK_OF_YEAR) ||
                    lessonCalendar.get(Calendar.YEAR) != selectedCalendar.get(Calendar.YEAR)) {
                return null;
            }

            int weekday = dateObj.optInt("weekday", -1);
            if (weekday < 1 || weekday > DAYS_IN_WEEK) return null;

            JSONObject time = lesson.optJSONObject("time");
            String timeText = (time != null ? time.optString("start", "00:00") : "00:00") + " - " +
                    (time != null ? time.optString("end", "00:00") : "00:00");

            return new LessonData(
                    lesson.optString("subject", "Неизвестный предмет"),
                    lesson.optString("type", "Неизвестный тип"),
                    lesson.optInt("subgroups", 0),
                    timeText,
                    getAudience(lesson.optJSONArray("audiences")),
                    getTeacher(lesson.optJSONArray("teachers")),
                    weekday
            );
        } catch (ParseException e) {
            return null;
        }
    }

    private String getAudience(JSONArray audiences) {
        if (audiences == null || audiences.length() == 0) return "Неизвестная";
        JSONObject audienceObj = audiences.optJSONObject(0);
        return audienceObj != null ? audienceObj.optString("name", "Неизвестная") : "Неизвестная";
    }

    private String getTeacher(JSONArray teachers) {
        if (teachers == null || teachers.length() == 0) return "Неизвестный";
        JSONObject teacherObj = teachers.optJSONObject(0);
        return teacherObj != null ? teacherObj.optString("name", "Неизвестный") : "Неизвестный";
    }

    private void addLessonToUI(LessonData data) {
        runOnUiThread(() -> {
            LinearLayout container = getDayContainer(data.weekday);
            if (container == null) return;

            // Уникальный ключ урока
            String uniqueKey = data.weekday + "|" +
                    data.time + "|" +
                    data.subject.toLowerCase() + "|" +
                    data.teacher.toLowerCase();

            if (!paraContainers.containsKey(uniqueKey)) {
                CardView paraCard = createParaCard(data.time);
                paraCard.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                container.addView(paraCard);
                LinearLayout lessonContainer = paraCard.findViewById(R.id.LinearLayout_para);
                paraContainers.put(uniqueKey, lessonContainer);

                View lessonView = createLessonCard(
                        data.subgroups > 0 ? "Подгруппа: " + data.subgroups : "",
                        data.subject,
                        data.time,
                        data.audience,
                        data.type,
                        data.teacher
                );
                lessonView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                lessonContainer.addView(lessonView);
            }
        });
    }

    private CardView createParaCard(String time) {
        CardView card = (CardView) LayoutInflater.from(this).inflate(R.layout.para_card_template, null);
        ((TextView) card.findViewById(R.id.para)).setText(determineParaNumber(time) + " пара");
        card.setTag("dynamicParaCard"); // Убедитесь, что тег установлен
        return card;
    }

    private CardView createLessonCard(String subgroups, String subject, String time,
                                      String audience, String type, String teacher) {
        CardView card = (CardView) LayoutInflater.from(this).inflate(R.layout.lesson_card_template, null);
        ((TextView) card.findViewById(R.id.para)).setText(subgroups);
        ((TextView) card.findViewById(R.id.subject)).setText(subject);
        ((TextView) card.findViewById(R.id.time)).setText(time);
        ((TextView) card.findViewById(R.id.audiences)).setText(audience);
        ((TextView) card.findViewById(R.id.lessonType)).setText(type);
        ((TextView) card.findViewById(R.id.teachers)).setText(teacher);
        return card;
    }

    private LinearLayout getDayContainer(int weekday) {
        int id = DAY_IDS[weekday - 1];
        LinearLayout container = findViewById(id);
        if (container == null) {
            Log.e("GetDayContainer", "Container for day " + weekday + " not found (ID: " + id + ")");
        }
        return container;
    }


    private boolean isCurrentWeek() {
        Calendar now = Calendar.getInstance();
        Calendar selected = Calendar.getInstance();
        selected.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        selected.set(Calendar.YEAR, selectedYear);

        return now.get(Calendar.WEEK_OF_YEAR) == selected.get(Calendar.WEEK_OF_YEAR)
                && now.get(Calendar.YEAR) == selected.get(Calendar.YEAR);
    }


    private void updateDates() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.YEAR, selectedYear);

        currentData.setText(sdf.format(cal.getTime()));
        compactCurrentDate.setText(currentData.getText());

        cal.add(Calendar.WEEK_OF_YEAR, -1);
        dataPrevious.setText(sdf.format(cal.getTime()));

        cal.add(Calendar.WEEK_OF_YEAR, 2);
        dataNext.setText(sdf.format(cal.getTime()));
    }

    private String determineParaNumber(String time) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            Date startTime = sdf.parse(time.split(" - ")[0]);

            if (startTime.before(sdf.parse("10:05"))) return "1";
            if (startTime.before(sdf.parse("11:55"))) return "2";
            if (startTime.before(sdf.parse("14:20"))) return "3";
            if (startTime.before(sdf.parse("16:05"))) return "4";
            if (startTime.before(sdf.parse("17:50"))) return "5";
            return "6";
        } catch (ParseException e) {
            return "?";
        }
    }

    private void performSearch() {
        String query = searchField.getText().toString().trim();
        currentSearchQuery = query.toLowerCase();

        if ((jsonDataOfSite == null || jsonDataOfSite.isEmpty()) && !isLoading) {
            loadSchedule();
        } else if (jsonDataOfSite != null) {
            executor.execute(() -> { // Используем executor для обработки
                runOnUiThread(() -> {
                    clearScheduleContainers();
                    paraContainers.clear();
                });
                parseSchedule(jsonDataOfSite);
            });
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void showError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
        // Сохраняем данные при выходе
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_LAST_SEARCH, currentSearchQuery);
        editor.apply();
    }

    private static class LessonData {
        String subject;
        String type;
        int subgroups;
        String time;
        String audience;
        String teacher;
        int weekday;

        LessonData(String subject, String type, int subgroups, String time,
                   String audience, String teacher, int weekday) {
            this.subject = subject;
            this.type = type;
            this.subgroups = subgroups;
            this.time = time;
            this.audience = audience;
            this.teacher = teacher;
            this.weekday = weekday;
        }
    }


    public class ScheduleWorker extends Worker {
        public ScheduleWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            new OkHttpClient().newCall(new Request.Builder()
                    .url("http://uti.tpu.ru/timetable_import.json")
                    .build()).enqueue(new Callback() {
                // Убираем @NonNull аннотации из параметров
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("ScheduleWorker", "Ошибка проверки", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String data = response.body().string();
                    String hash = computeHash(data);
                    SharedPreferences prefs = getApplicationContext()
                            .getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE);

                    if (!hash.equals(prefs.getString("schedule_hash", ""))) {
                        showNotification(getApplicationContext());
                    }
                }
            });
            return Result.success();
        }
    }

    private void showNotification(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "schedule_updates",
                    "Обновления расписания",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.getApplicationContext(), "schedule_updates")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Обновление расписания")
                .setContentText("Доступно новое расписание")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (ActivityCompat.checkSelfPermission(this.getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(this.getApplicationContext()).notify(1, builder.build());
    }
}