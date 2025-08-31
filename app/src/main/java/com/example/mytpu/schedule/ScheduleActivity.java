package com.example.mytpu.schedule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import android.accounts.Account;
import android.app.ActivityManager;
import java.io.FileNotFoundException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.example.mytpu.schedule.CookieManager;
import com.example.mytpu.schedule.TPUScheduleParser;
import java.security.MessageDigest;
import android.util.Base64;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.Manifest;
import android.accounts.AccountManager;
import android.content.pm.PackageManager;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import androidx.core.app.ActivityCompat;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Environment;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.graphics.Rect;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import java.io.File;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.example.mytpu.R;
import com.google.android.gms.common.AccountPicker;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class ScheduleActivity extends AppCompatActivity {
    private static final String TAG = "ScheduleActivity";
    private static final int DAYS_IN_WEEK = 7;
    private static final int PERMISSION_REQUEST_CALENDAR = 101;
    private static final int ACCOUNT_REQUEST_CODE = 102;
    public static final int REQUEST_CODE_ALARM_SOUND = 1001;
    private LinearLayout scheduleContainer;
    private int lastSelectedWeek = -1;
    private boolean isScrollingRight = true;
    private int lastWheelWeek = -1;
    private int lastWheelYear = -1;
    private CookieManager cookieManager;
    private AutoCompleteTextView searchField;
    private ImageButton btnSearchToggle;
    private boolean isLoading = false;
    private boolean[] hasLessons = new boolean[DAYS_IN_WEEK];
    private boolean clearData = false;
    private OkHttpClient client;
    private ExecutorService executor;
    private int selectedYear, selectedWeek;
    private String jsonDataOfSite;
    private static final String SCHEDULE_URL = "https://rasp.tpu.ru/gruppa_43908/2025/1/view.html";
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private static final String BASE_URL = "https://rasp.tpu.ru/";
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
    private static final String SCHEDULE_FILE = "schedule.json";
    private static final String HASH_PREFS = "ScheduleHashes";
    private static final long CHECK_INTERVAL = 4 * 60 * 60 * 1000; // 4 часа
    private static final String LAST_CHECK_KEY = "last_check";
    private static final String FULL_HASH_KEY = "full_hash";
    private static final String GROUP_HASH_PREFIX = "group_hash_";
    private ProgressBar progressBar;
    private CardView searchCard;
    private RecyclerView weekWheel;
    private WeekWheelAdapter wheelAdapter;
    private Button currentDateButton;
    private boolean isSearchExpanded = false;
    private int originalCardWidth;
    private TextView yearTextView;
    private boolean isKeyboardVisible = false;
    private final ConcurrentHashMap<String, List<LessonData>> groupedLessonsMap = new ConcurrentHashMap<>();
    private final Object lock = new Object(); // Добавить объект для синхронизации
    private List<CardView> dayCards = new ArrayList<>();
    private List<TextView> dayHeaders = new ArrayList<>();
    private List<LinearLayout> lessonContainers = new ArrayList<>();
    private static final String SOURCE_UTI = "УТИ ТПУ";
    private static final String SOURCE_TPU = "ТПУ";
    private static final String SOURCE_PREFS = "SourcePrefs";
    private static final String KEY_SOURCE = "source";
    static final int REQUEST_CAPTCHA = 1002;
    private Spinner sourceSpinner;
    private String currentSource = SOURCE_UTI;
    private AlertDialog captchaDialog;
    private TPUScheduleParser tpuParser;
    private List<TPUScheduleParser.School> tpuSchools;
    private List<TPUScheduleParser.Group> tpuGroups;
    private Handler checkHandler = new Handler();
    private Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkForScheduleUpdates();
            checkHandler.postDelayed(this, CHECK_INTERVAL);
        }
    };

    private void stopScheduleChecker() {
        checkHandler.removeCallbacks(checkRunnable);
    }
    private void updateGroupsSpinner(List<TPUScheduleParser.Group> groups) {
        runOnUiThread(() -> {
            Spinner groupsSpinner = findViewById(R.id.groupsSpinner);
            if (groupsSpinner == null) {
                Log.e(TAG, "Groups spinner is null!");
                return;
            }

            List<String> groupNames = new ArrayList<>();
            groupNames.add("Не выбрано"); // Добавляем пункт по умолчанию

            if (groups != null) {
                for (TPUScheduleParser.Group group : groups) {
                    groupNames.add(group.name);
                }
            }

            // Используем кастомный адаптер с marquee эффектом
            MarqueeSpinnerAdapter adapter = new MarqueeSpinnerAdapter(this, groupNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            groupsSpinner.setAdapter(adapter);

            groupsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position > 0 && groups != null && position <= groups.size()) {
                        String groupName = groupNames.get(position);
                        currentSearchQuery = groupName;
                        updateGroupsTextView(groupName);
                        loadScheduleForGroup(groups.get(position - 1).id);
                    } else {
                        currentSearchQuery = "";
                        updateGroupsTextView("");
                        // Очищаем расписание
                        clearScheduleContainers();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        });
    }

    private void saveSchoolsAndGroups(List<TPUScheduleParser.School> schools) {
        try {
            SharedPreferences prefs = getSharedPreferences("TPUData", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            JSONArray schoolsArray = new JSONArray();
            for (TPUScheduleParser.School school : schools) {
                JSONObject schoolObj = new JSONObject();
                schoolObj.put("id", school.id);
                schoolObj.put("name", school.name);
                schoolsArray.put(schoolObj);
            }

            editor.putString("schools", schoolsArray.toString());
            editor.apply();
            Log.d(TAG, "Schools saved successfully");
        } catch (JSONException e) {
            Log.e(TAG, "Error saving schools", e);
        }
    }

    private List<TPUScheduleParser.School> loadSchools() {
        SharedPreferences prefs = getSharedPreferences("TPUData", MODE_PRIVATE);
        String schoolsJson = prefs.getString("schools", "");

        List<TPUScheduleParser.School> schools = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(schoolsJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                TPUScheduleParser.School school = new TPUScheduleParser.School();
                school.id = obj.getString("id");
                school.name = obj.getString("name");
                schools.add(school);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading schools", e);
        }

        return schools;
    }

    private void loadScheduleForGroup(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            Log.e(TAG, "Group ID is null or empty");
            return;
        }

        new Thread(() -> {
            try {
                int academicYear = AcademicCalendar.getAcademicYear(new Date());
                Log.d(TAG, "Loading schedule for group: " + groupId + ", academic year: " + academicYear);

                List<TPUScheduleParser.Schedule> yearSchedule = tpuParser.getFullYearSchedule(groupId, academicYear);

                // Пропускаем ненужные операции если данные уже есть
                if (yearSchedule == null || yearSchedule.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(ScheduleActivity.this,
                            "Не удалось загрузить расписание", Toast.LENGTH_SHORT).show());
                    return;
                }

                // Сразу обновляем интерфейс с полученными данными
                runOnUiThread(() -> {
                    // Очищаем предыдущие данные
                    clearScheduleContainers();
                    groupedLessonsMap.clear();

                    // Парсим расписание напрямую
                    parseTPUSchedule(yearSchedule);

                    // Обновляем интерфейс
                    updateDayDates();
                    redrawScheduleUI();
                    updateAlarms();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading schedule", e);
                runOnUiThread(() -> Toast.makeText(ScheduleActivity.this,
                        "Ошибка загрузки расписания: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void parseTPUSchedule(List<TPUScheduleParser.Schedule> schedules) {
        Log.d(TAG, "Starting parseTPUSchedule with " + schedules.size() + " weeks");
        groupedLessonsMap.clear();
        Arrays.fill(hasLessons, false);

        int totalLessons = 0;
        int convertedLessons = 0;

        for (TPUScheduleParser.Schedule weekSchedule : schedules) {
            int weekLessons = weekSchedule.days.stream().mapToInt(List::size).sum();
            Log.d(TAG, "Processing week " + weekSchedule.weekNumber + " with " + weekLessons + " lessons");

            for (int dayIndex = 0; dayIndex < weekSchedule.days.size(); dayIndex++) {
                List<TPUScheduleParser.Lesson> dayLessons = weekSchedule.days.get(dayIndex);
                for (TPUScheduleParser.Lesson lesson : dayLessons) {
                    totalLessons++;
                    try {
                        LessonData lessonData = convertTPULessonToLessonData(lesson, dayIndex + 1,
                                weekSchedule.weekNumber, weekSchedule.year);
                        if (lessonData != null) {
                            String key = lessonData.weekday + "|" + lessonData.time;
                            if (!groupedLessonsMap.containsKey(key)) {
                                groupedLessonsMap.put(key, new ArrayList<>());
                            }
                            Log.d(TAG, "Week " + weekSchedule.weekNumber + " day " + dayIndex +
                                    " lessons: " + dayLessons.size());
                            groupedLessonsMap.get(key).add(lessonData);
                            hasLessons[dayIndex] = true;
                            convertedLessons++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error converting lesson", e);
                    }
                }
            }
        }

        Log.d(TAG, "Finished parseTPUSchedule. Total lessons: " + totalLessons +
                ", converted lessons: " + convertedLessons +
                ", groupedLessonsMap size: " + groupedLessonsMap.size());
    }

    private LessonData convertTPULessonToLessonData(TPUScheduleParser.Lesson tpuLesson,
                                                    int weekday, int weekNumber, int year) {
        try {
            if (tpuLesson.subject == null || tpuLesson.subject.trim().isEmpty() ||
                    tpuLesson.subject.equals("-") || tpuLesson.subject.equals("Не удалось расшифровать")) {
                return null;
            }

            // Парсим время
            String timeString = tpuLesson.time != null ? tpuLesson.time.replace("\n", " - ") : "";
            String startTime = "";
            String endTime = "";

            if (timeString.contains("-")) {
                String[] timeParts = timeString.split("-");
                startTime = timeParts[0].trim();
                endTime = timeParts[1].trim();
            } else {
                Pattern timePattern = Pattern.compile("(\\d{1,2}:\\d{2})");
                Matcher matcher = timePattern.matcher(timeString);
                List<String> times = new ArrayList<>();
                while (matcher.find()) {
                    times.add(matcher.group(1));
                }
                if (times.size() >= 2) {
                    startTime = times.get(0);
                    endTime = times.get(1);
                }
            }

            if (startTime.isEmpty() || endTime.isEmpty()) {
                return null;
            }

            // Создаем даты занятий
            Calendar startCal = AcademicCalendar.getDateForWeekAndDay(year, weekNumber, weekday);
            String[] startParts = startTime.split(":");
            startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startParts[0]));
            startCal.set(Calendar.MINUTE, Integer.parseInt(startParts[1]));

            Calendar endCal = AcademicCalendar.getDateForWeekAndDay(year, weekNumber, weekday);
            String[] endParts = endTime.split(":");
            endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endParts[0]));
            endCal.set(Calendar.MINUTE, Integer.parseInt(endParts[1]));

            // Определяем номер пары
            int paraNumber = determineParaNumber(startTime);

            return new LessonData(
                    startCal.getTime(),
                    endCal.getTime(),
                    tpuLesson.subject.trim(),
                    tpuLesson.type != null ? tpuLesson.type.trim() : "",
                    0, // subgroups - нужно получить из данных
                    startTime + " - " + endTime,
                    tpuLesson.location != null ? tpuLesson.location.trim() : "",
                    tpuLesson.teacher != null ? tpuLesson.teacher.trim() : "",
                    weekday,
                    paraNumber,
                    "" // group - будет установлено позже
            );
        } catch (Exception e) {
            Log.e(TAG, "Error converting lesson: " + e.getMessage());
            return null;
        }
    }
    private void checkForScheduleUpdates() {
        if (currentSearchQuery.isEmpty()) {
            Log.d(TAG, "checkForScheduleUpdates: currentSearchQuery is empty, skipping");
            return;
        }

        executor.execute(() -> {
            try {
                Log.d(TAG, "Starting schedule update check for group: " + currentSearchQuery);

                // 1. Проверяем обновление полного расписания
                String localFullHash = loadFullHash();
                String serverFullHash = fetchScheduleHash();
                Log.d(TAG, "Hash comparison: local=" + localFullHash + ", server=" + serverFullHash);

                if (serverFullHash == null || serverFullHash.equals(localFullHash)) {
                    Log.d(TAG, "No full schedule update needed");
                    return;
                }

                // 2. Загружаем новое расписание
                Log.d(TAG, "Full schedule update detected, fetching new schedule...");
                String newJson = fetchFullSchedule();
                if (newJson == null) {
                    Log.e(TAG, "Failed to fetch new schedule");
                    return;
                }

                // 3. Фильтруем для текущей группы
                List<LessonData> filteredLessons = filterLessonsForGroup(newJson, currentSearchQuery);
                String newGroupHash = calculateHash(filteredLessons.toString());
                Log.d(TAG, "Filtered lessons count: " + filteredLessons.size());

                // 4. Сравниваем с локальным хешем группы
                String localGroupHash = loadGroupHash(currentSearchQuery);
                Log.d(TAG, "Group hash comparison: local=" + localGroupHash + ", new=" + newGroupHash);

                if (!newGroupHash.equals(localGroupHash)) {
                    Log.d(TAG, "Group schedule changed, notifying user");
                    showUpdateNotification();
                    saveScheduleLocally(newJson);
                    saveHashes(serverFullHash, newGroupHash);
                } else {
                    Log.d(TAG, "Group schedule not changed, updating full hash only");
                    saveHashes(serverFullHash, localGroupHash);
                }

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
            }
        });
    }

    private String fetchScheduleHash() {
        try {
            Log.d(TAG, "Fetching schedule hash from: " + SCHEDULE_URL);

            // Загружаем cookies
            Set<String> cookies = cookieManager.loadCookies();
            Log.d(TAG, "Using cookies: " + Arrays.toString(cookies.toArray()));

            // Создаем запрос с правильным URL и cookies
            Request.Builder requestBuilder = new Request.Builder()
                    .url(SCHEDULE_URL + "?hash=1");

            // Добавляем cookies в заголовок
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (String cookie : cookies) {
                    cookieHeader.append(cookie).append("; ");
                }
                requestBuilder.header("Cookie", cookieHeader.toString());
                Log.d(TAG, "Sending cookies: " + cookieHeader.toString());
            }

            Request request = requestBuilder.build();
            Response response = client.newCall(request).execute();

            if (response.isSuccessful()) {
                String hash = response.body().string();
                Log.d(TAG, "Hash received: " + hash);
                return hash;
            } else {
                Log.e(TAG, "Hash fetch failed: " + response.code());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Hash fetch error", e);
            return null;
        }
    }

    private String fetchFullSchedule() {
        try {
            Request request = new Request.Builder()
                    .url(API_URL)
                    .build();

            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (Exception e) {
            Log.e(TAG, "Full schedule fetch error", e);
            return null;
        }
    }

    private List<LessonData> filterLessonsForGroup(String json, String group) {
        List<LessonData> result = new ArrayList<>();
        Log.d(TAG, "Filtering lessons for group: " + group);

        if (json == null || json.isEmpty()) {
            Log.e(TAG, "JSON is null or empty in filterLessonsForGroup");
            return result;
        }

        try {
            JSONObject root = new JSONObject(json);
            JSONArray faculties = root.optJSONArray("faculties");
            if (faculties == null) {
                Log.w(TAG, "No faculties found in JSON");
                return result;
            }

            for (int i = 0; i < faculties.length(); i++) {
                JSONObject faculty = faculties.getJSONObject(i);
                JSONArray groups = faculty.optJSONArray("groups");
                if (groups == null) continue;

                for (int j = 0; j < groups.length(); j++) {
                    JSONObject groupObj = groups.getJSONObject(j);
                    String groupName = groupObj.optString("name", "");
                    if (groupName.equalsIgnoreCase(group)) {
                        JSONArray lessons = groupObj.optJSONArray("lessons");
                        if (lessons == null) continue;

                        Log.d(TAG, "Found group: " + groupName + " with lessons: " + lessons.length());

                        for (int k = 0; k < lessons.length(); k++) {
                            LessonData lesson = parseLessonForGroup(lessons.getJSONObject(k), group);
                            if (lesson != null) {
                                result.add(lesson);
                                Log.v(TAG, "Added lesson: " + lesson.subject);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Filtering error", e);
        }

        Log.d(TAG, "Total filtered lessons: " + result.size());
        return result;
    }

    private LessonData parseLessonForGroup(JSONObject lesson, String groupName)
            throws JSONException, ParseException {
        JSONObject date = lesson.optJSONObject("date");
        if (date == null) return null;
        if (lesson.getString("subject").trim().isEmpty() ||
                lesson.getString("subject").equals("-")) {
            return null; // Пропускаем пустые уроки
        }
        String startDateStr = date.optString("start", "");
        if (startDateStr.isEmpty()) return null;
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        Date lessonDate = sdf.parse(startDateStr);

        JSONObject time = lesson.getJSONObject("time");
        String startTime = time.getString("start");
        String endTime = time.getString("end");

        // Создаем календарь для времени начала
        Calendar startCal = Calendar.getInstance();
        startCal.setTime(lessonDate);
        String[] startParts = startTime.split(":");
        startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startParts[0]));
        startCal.set(Calendar.MINUTE, Integer.parseInt(startParts[1]));

        // Создаем календарь для времени окончания
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(lessonDate);
        String[] endParts = endTime.split(":");
        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endParts[0]));
        endCal.set(Calendar.MINUTE, Integer.parseInt(endParts[1]));

        int paraNumber = determineParaNumber(startTime + " - " + endTime);

        return new LessonData(
                startCal.getTime(),
                endCal.getTime(),
                lesson.getString("subject"),
                lesson.getString("type"),
                lesson.optInt("subgroups", 0),
                startTime + " - " + endTime,
                getAudience(lesson.getJSONArray("audiences")),
                getTeacher(lesson.getJSONArray("teachers")),
                date.getInt("weekday"),
                paraNumber,
                groupName
        );
    }

    private String calculateHash(String input) {
        Log.d(TAG, "Calculating hash for input: " + input.substring(0, Math.min(input.length(), 100)) + "...");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hashString = Base64.encodeToString(hash, Base64.NO_WRAP);
            Log.d(TAG, "Calculated hash: " + hashString);
            return hashString;
        } catch (Exception e) {
            Log.e(TAG, "Hash calculation error", e);
            return "";
        }
    }

    private void saveHashes(String fullHash, String groupHash) {
        Log.d(TAG, "Saving hashes: fullHash=" + fullHash + ", groupHash=" + groupHash);
        SharedPreferences prefs = getSharedPreferences(HASH_PREFS, MODE_PRIVATE);
        prefs.edit()
                .putString(FULL_HASH_KEY, fullHash)
                .putString(GROUP_HASH_PREFIX + currentSearchQuery, groupHash)
                .putLong(LAST_CHECK_KEY, System.currentTimeMillis())
                .apply();
    }

    private String loadFullHash() {
        String hash = getSharedPreferences(HASH_PREFS, MODE_PRIVATE)
                .getString(FULL_HASH_KEY, "");
        Log.d(TAG, "Loaded full hash: " + hash);
        return hash;
    }

    private String loadGroupHash(String group) {
        String key = GROUP_HASH_PREFIX + group;
        String hash = getSharedPreferences(HASH_PREFS, MODE_PRIVATE)
                .getString(key, "");
        Log.d(TAG, "Loaded group hash for " + group + ": " + hash);
        return hash;
    }

    private void saveScheduleLocally(String json) {
        Log.d(TAG, "Saving schedule locally, length: " + json.length());
        try (FileOutputStream fos = openFileOutput(SCHEDULE_FILE, Context.MODE_PRIVATE)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Schedule saved successfully");
        } catch (Exception e) {
            Log.e(TAG, "Local save failed", e);
        }
    }

    private String loadLocalSchedule() {
        try {
            File file = new File(getFilesDir(), SCHEDULE_FILE);
            if (!file.exists()) {
                Log.d(TAG, "Local schedule file does not exist");
                return null;
            }

            try (FileInputStream fis = openFileInput(SCHEDULE_FILE)) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = fis.read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }
                return result.toString(StandardCharsets.UTF_8.name());
            }
        } catch (FileNotFoundException e) {
            Log.d(TAG, "Local schedule file not found", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Local schedule load failed", e);
            return null;
        }
    }

    private void showUpdateNotification() {
        // Создаем уведомление
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Intent intent = new Intent(this, ScheduleActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, "schedule_updates")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Обновление расписания")
                .setContentText("Расписание для " + currentSearchQuery + " было изменено")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        if (manager != null) {
            manager.notify(currentSearchQuery.hashCode(), notification);
        }
    }

    private void showAddAccountDialog() {
        Log.d(TAG, "Showing account picker dialog");
        try {
            Intent intent = AccountPicker.newChooseAccountIntent(
                    null, // Selected account (null для выбора по умолчанию)
                    null, // Допустимые аккаунты (null для всех)
                    new String[]{"com.google"}, // Только Google-аккаунты
                    false, // Всегда показывать диалог
                    null, // Текст описания
                    null, // Auth token type
                    null, // Required features
                    null  // Дополнительные параметры
            );
            startActivityForResult(intent, ACCOUNT_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Account picker not found", e);
            // Fallback для старых устройств
            Intent intent = new Intent(Settings.ACTION_ADD_ACCOUNT);
            intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"});
            startActivityForResult(intent, ACCOUNT_REQUEST_CODE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
        initHttpClient();
        checkPermissions();
        tpuSchools = loadSchools();
        if (tpuSchools != null && !tpuSchools.isEmpty()) {
            updateSchoolsSpinner(tpuSchools);
        }
        // Инициализируем CookieManager
        cookieManager = new CookieManager(this);
        tpuParser = new TPUScheduleParser(this);
// Убедитесь, что парсер инициализирован
        TPUScheduleParser parser = new TPUScheduleParser(this);
        parser.loadCookies(); // Загружаем сохраненные куки
        // Загрузка cookies
        tpuParser.loadCookiesFromPrefs();

        // Проверка источника данных
        if (SOURCE_TPU.equals(currentSource)) {
            loadTPUData();
        }
        scheduleContainer = findViewById(R.id.scheduleContainer);
        if (scheduleContainer == null) {
            Log.e(TAG, "scheduleContainer not found!");
            return;
        }
        final int callbackId = 42;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        jsonDataOfSite = loadLocalSchedule();

        if (jsonDataOfSite != null) {
            processDataInBackground(jsonDataOfSite);
        } else {
            loadSchedule(); // Загружаем с сервера
        }
        findViewById(R.id.btnSyncCalendar).setOnClickListener(v -> {
            checkPermission(
                    PERMISSION_REQUEST_CALENDAR,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.GET_ACCOUNTS // Добавляем разрешение для аккаунтов
            );
            if (currentSearchQuery.isEmpty()) {
                Toast.makeText(this, "Сначала выберите группу/преподавателя", Toast.LENGTH_SHORT).show();
                return;
            }
            syncWithGoogleCalendar();
        });
        findViewById(R.id.btnUnsyncCalendar).setOnClickListener(v -> unsyncCalendar());
        findViewById(R.id.btnAlarmSettings).setOnClickListener(v -> showAlarmDialog());
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (!documentsDir.exists()) documentsDir.mkdirs();
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        groupsTextView = findViewById(R.id.groups);
        searchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line);
        searchField = findViewById(R.id.searchField);
        progressBar = findViewById(R.id.progressBar);
        btnSearchToggle = findViewById(R.id.btnSearchToggle);
        searchField.setAdapter(searchAdapter);
        searchCard = findViewById(R.id.searchCard);
        restoreLastSearch(); // ← Добавьте вызов здесь
        updateGroupsTextView(currentSearchQuery);
        createDayCards();
        initViews();




        // Настройка Spinner выбора источника
        // Инициализация спиннеров
        Spinner sourceSpinner = findViewById(R.id.sourceSpinner);
        Spinner schoolsSpinner = findViewById(R.id.schoolsSpinner);
        Spinner groupsSpinner = findViewById(R.id.groupsSpinner);

// Установите адаптеры с пунктом "Не выбрано" по умолчанию
        List<String> sourceOptions = new ArrayList<>();
        sourceOptions.add("УТИ ТПУ");
        sourceOptions.add("ТПУ");
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sourceOptions);
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(sourceAdapter);

        List<String> defaultOptions = new ArrayList<>();
        defaultOptions.add("Не выбрано");
        ArrayAdapter<String> defaultAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, defaultOptions);
        defaultAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        schoolsSpinner.setAdapter(defaultAdapter);
        groupsSpinner.setAdapter(defaultAdapter);


        // Восстановление выбранного источника
        SharedPreferences sourcePrefs = getSharedPreferences(SOURCE_PREFS, MODE_PRIVATE);
        currentSource = sourcePrefs.getString(KEY_SOURCE, SOURCE_UTI);

        // Установка выбранного элемента
        int position = sourceAdapter.getPosition(currentSource);
        sourceSpinner.setSelection(position);

        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newSource = parent.getItemAtPosition(position).toString();
                if (!currentSource.equals(newSource)) {
                    currentSource = newSource;

                    SharedPreferences.Editor editor = getSharedPreferences(SOURCE_PREFS, MODE_PRIVATE).edit();
                    editor.putString(KEY_SOURCE, currentSource);
                    editor.apply();

                    if (SOURCE_TPU.equals(currentSource)) {
                        // Показываем спиннеры ТПУ, скрываем поиск УТИ
                        findViewById(R.id.tpuSpinnersContainer).setVisibility(View.VISIBLE);
                        findViewById(R.id.utiSearchContainer).setVisibility(View.GONE);

                        // Загружаем данные ТПУ
                        loadTPUData();
                    } else {
                        // Показываем поиск УТИ, скрываем спиннеры ТПУ
                        findViewById(R.id.tpuSpinnersContainer).setVisibility(View.GONE);
                        findViewById(R.id.utiSearchContainer).setVisibility(View.VISIBLE);

                        // Загружаем данные УТИ
                        loadScheduleFromUTI();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
// Принудительно обновляем интерфейс при запуске
        runOnUiThread(() -> {
            if (SOURCE_TPU.equals(currentSource)) {
                findViewById(R.id.tpuSpinnersContainer).setVisibility(View.VISIBLE);
                findViewById(R.id.utiSearchContainer).setVisibility(View.GONE);
            } else {
                findViewById(R.id.tpuSpinnersContainer).setVisibility(View.GONE);
                findViewById(R.id.utiSearchContainer).setVisibility(View.VISIBLE);
            }
        });



        loadInitialWeek();
        setupSearchAutocomplete();
        updateSearchFieldBehavior();
        if (savedInstanceState != null) {
            currentSearchQuery = savedInstanceState.getString("CURRENT_SEARCH", "");
            selectedWeek = savedInstanceState.getInt("SELECTED_WEEK");
            selectedYear = savedInstanceState.getInt("SELECTED_YEAR");
            hasLessons = savedInstanceState.getBooleanArray("HAS_LESSONS");
        }
        View rootView = findViewById(R.id.root_layout); // Замените на ваш корневой макет
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int systemWindowInsetTop = insets.getSystemWindowInsetTop();
            int systemWindowInsetBottom = insets.getSystemWindowInsetBottom();
            int systemWindowInsetLeft = insets.getSystemWindowInsetLeft();
            int systemWindowInsetRight = insets.getSystemWindowInsetRight();
            v.setPadding(systemWindowInsetLeft, systemWindowInsetTop, systemWindowInsetRight, systemWindowInsetBottom);
            return insets;
        });
        searchCard.post(() -> {
            originalCardWidth = searchCard.getWidth();
            setupKeyboardListener();
        });
        btnSearchToggle.setOnClickListener(v -> toggleSearch());
        setupKeyboardListener();
        new Thread(() -> {
            if (jsonDataOfSite == null || jsonDataOfSite.isEmpty()) {
                loadSchedule();
            }else {
                processDataInBackground(jsonDataOfSite); // Обрабатываем сохраненные данные
            }
        }).start();
        View rootLayout = findViewById(R.id.root_layout); // Убедитесь, что ID корневого макета правильный
        rootLayout.setOnClickListener(v -> {
            if (isSearchExpanded) {
                collapseSearch();
            }
        });
// В onCreate()
        setupWeekWheel();
        setupCurrentDateButton();
        setupSearchField();

    }

    private void loadGroupsForSchool(String schoolId) {
        Log.d(TAG, "Loading groups for school ID: " + schoolId);
        new Thread(() -> {
            try {
                List<TPUScheduleParser.Group> groups = tpuParser.getGroups(schoolId);
                Log.d(TAG, "Groups loaded: " + (groups != null ? groups.size() : 0) + " groups");

                if (groups == null || groups.isEmpty()) {
                    // Проверяем валидность куков
                    boolean isValid = tpuParser.checkCookiesValidity();
                    runOnUiThread(() -> {
                        if (!isValid) {
                            // Запускаем CaptchaActivity
                            Intent intent = new Intent(ScheduleActivity.this, CaptchaActivity.class);
                            startActivityForResult(intent, REQUEST_CAPTCHA);
                        } else {
                            Toast.makeText(ScheduleActivity.this,
                                    "Нет групп для выбранной школы",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                runOnUiThread(() -> {
                    updateGroupsSpinner(groups);
                });
            } catch (IOException e) {
                Log.e(TAG, "Error loading groups", e);
                runOnUiThread(() -> {
                    Toast.makeText(ScheduleActivity.this,
                            "Ошибка загрузки групп: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    updateGroupsSpinner(new ArrayList<TPUScheduleParser.Group>());
                });
            }
        }).start();
    }

    private void updateSchoolsSpinner(List<TPUScheduleParser.School> schools) {
        runOnUiThread(() -> {
            Spinner schoolsSpinner = findViewById(R.id.schoolsSpinner);
            if (schoolsSpinner == null) {
                Log.e(TAG, "Schools spinner is null!");
                return;
            }

            List<String> schoolNames = new ArrayList<>();
            schoolNames.add("Не выбрано"); // Добавляем пункт по умолчанию

            if (schools != null) {
                for (TPUScheduleParser.School school : schools) {
                    schoolNames.add(school.name);
                }
            }

            // Используем кастомный адаптер с marquee эффектом
            MarqueeSpinnerAdapter adapter = new MarqueeSpinnerAdapter(this, schoolNames);
            schoolsSpinner.setAdapter(adapter);

            // Обработчик выбора школы
            schoolsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position > 0 && schools != null && position <= schools.size()) {
                        // Загружаем группы выбранной школы
                        loadGroupsForSchool(schools.get(position - 1).id);
                    } else {
                        // Очищаем спиннер групп
                        updateGroupsSpinner(new ArrayList<TPUScheduleParser.Group>());
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        });
    }

    private void loadTPUData() {
        Log.d(TAG, "Loading TPU data");
        new Thread(() -> {
            try {
                tpuSchools = tpuParser.getSchools();
                Log.d(TAG, "Schools loaded: " + (tpuSchools != null ? tpuSchools.size() : 0) + " schools");

                // Сохраняем школы после успешной загрузки
                if (tpuSchools != null && !tpuSchools.isEmpty()) {
                    saveSchoolsAndGroups(tpuSchools);
                }

                runOnUiThread(() -> {
                    if (tpuSchools != null && !tpuSchools.isEmpty()) {
                        updateSchoolsSpinner(tpuSchools);
                    } else {
                        Toast.makeText(ScheduleActivity.this,
                                "Не удалось загрузить список школ",
                                Toast.LENGTH_SHORT).show();
                        updateSchoolsSpinner(new ArrayList<TPUScheduleParser.School>());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading TPU data", e);
                runOnUiThread(() -> {
                    Toast.makeText(ScheduleActivity.this,
                            "Ошибка загрузки данных: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    updateSchoolsSpinner(new ArrayList<TPUScheduleParser.School>());
                });
            }
        }).start();
    }

    private void unsyncCalendar() {
        new Thread(() -> {
            try {
                // Удаляем ВСЕ события приложения
                deleteAllAppEvents();
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Все события удалены из календаря",
                        Toast.LENGTH_LONG
                ).show());

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Ошибка удаления: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        }).start();
    }

    private void deleteAllAppEvents() throws Exception {
        ContentResolver cr = getContentResolver();
        Uri uri = CalendarContract.Events.CONTENT_URI;
        String selection = Events.DESCRIPTION + " LIKE ?";
        String[] selectionArgs = new String[]{"%Синхронизировано через MyApp%"};

        int deletedRows = cr.delete(uri, selection, selectionArgs);
        Log.i(TAG, "Удалено всех событий: " + deletedRows);
    }

    @SuppressLint("ScheduleExactAlarm")
    private void showAlarmDialog() {
        // Создаем кастомный диалог
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedDialog);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);

        // Находим все элементы
        TabLayout tabLayout = view.findViewById(R.id.tabLayout);
        ViewPager2 viewPager = view.findViewById(R.id.viewPager);
        Button saveButton = view.findViewById(R.id.saveButton);

        // Настраиваем ViewPager с вкладками
        SettingsPagerAdapter adapter = new SettingsPagerAdapter(this);
        viewPager.setAdapter(adapter);
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(position == 0 ? "Будильник" : "Календарь");
        }).attach();

        saveButton.setOnClickListener(v -> {
            // Сохраняем настройки из обоих фрагментов
            saveSettings(adapter);
            dialog.dismiss();
        });

        // Добавить перед dialog.show()
        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        dialog.getWindow().setAttributes(params);

        dialog.show();
    }

    private void saveSettings(SettingsPagerAdapter adapter) {
        SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Настройки будильника
        AlarmSettingsFragment alarmFragment = (AlarmSettingsFragment) adapter.getFragment(0);
        if (alarmFragment != null) {
            int minutesBefore = alarmFragment.getHours() * 60 + alarmFragment.getMinutes();
            editor.putInt("alarm_minutes", minutesBefore);
            editor.putBoolean("alarm_enabled", alarmFragment.isAlarmEnabled());
            editor.putBoolean("notifications_enabled", alarmFragment.isNotificationsEnabled());
        }

        // Настройки календаря
        CalendarSettingsFragment calendarFragment = (CalendarSettingsFragment) adapter.getFragment(1);
        if (calendarFragment != null) {
            int reminderMinutes = calendarFragment.getHours() * 60 + calendarFragment.getMinutes();
            editor.putInt("calendar_reminder_minutes", reminderMinutes);
            editor.putBoolean("weekly_sync", calendarFragment.isWeeklySyncEnabled());
            editor.putBoolean("add_breaks", calendarFragment.isAddBreaksEnabled());
        }

        editor.apply();
        updateAlarms();
    }

    private boolean isFullscreenAlarmActive() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            ComponentName topActivity = tasks.get(0).topActivity;
            return topActivity.getClassName().contains("FullscreenAlarmActivity");
        }
        return false;
    }

    @SuppressLint("ScheduleExactAlarm")
    private void updateAlarms() {
        Log.d(TAG, "Updating alarms...");

        SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
        boolean isAlarmEnabled = prefs.getBoolean("alarm_enabled", true);
        List<LessonData> lessonsToSchedule = getFilteredLessons();
        if (!isAlarmEnabled) {
            Log.d(TAG, "Alarm is disabled - canceling all alarms");
            AlarmScheduler.cancelAllAlarms(this);
            if (AlarmScheduler.isAlarmEnabled(this)) {

                int minutesBefore = prefs.getInt("alarm_minutes", 30);
                AlarmScheduler.scheduleAlarms(this, lessonsToSchedule, minutesBefore);
            }
            return;
        }
        if (isFullscreenAlarmActive()) {
            Log.d(TAG, "Skipping alarm update - fullscreen alarm active");
            return;
        }
        int minutesBefore = prefs.getInt("alarm_minutes", 30);
        boolean forCurrentSearch = prefs.getBoolean("forCurrentSearch", false);

        Log.d(TAG, String.format("Settings: minutesBefore=%d, forCurrentSearch=%b",
                minutesBefore, forCurrentSearch));
        Log.d(TAG, "Using filtered lessons: " + lessonsToSchedule.size() + " lessons");


        // Фильтруем уроки на сегодня
        List<LessonData> todayLessons = getTodayLessons(lessonsToSchedule);
        Log.d(TAG, "Today lessons: " + todayLessons.size() + " lessons");

        // Находим первое занятие дня
        LessonData firstLesson = null;
        for (LessonData lesson : todayLessons) {
            if (firstLesson == null || lesson.startTime.before(firstLesson.startTime)) {
                firstLesson = lesson;
                Log.d(TAG, "New first lesson: " + lesson.subject + " at " + lesson.time);
            }
        }

        if (firstLesson != null) {
            Log.d(TAG, "Scheduling alarm for first lesson: " + firstLesson.subject);
            AlarmScheduler.scheduleAlarms(this, List.of(firstLesson), minutesBefore);
        } else {
            Log.d(TAG, "No first lesson found for today");
            AlarmScheduler.cancelAllAlarms(this);
            if (AlarmScheduler.isAlarmEnabled(this)) {
                AlarmScheduler.scheduleAlarms(this, lessonsToSchedule, minutesBefore);
            }
        }
    }

    private List<LessonData> getTodayLessons(List<LessonData> lessons) {
        List<LessonData> todayLessons = new ArrayList<>();
        Calendar today = Calendar.getInstance();

        for (LessonData lesson : lessons) {
            Calendar lessonCal = Calendar.getInstance();
            lessonCal.setTime(lesson.startTime);

            boolean sameDay = lessonCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    lessonCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);

            if (sameDay) todayLessons.add(lesson);
        }
        return todayLessons;
    }

    private void animateScheduleTransition(boolean scrollRight) {
        int outAnim = scrollRight ? R.anim.slide_left_out : R.anim.slide_right_out;
        int inAnim = scrollRight ? R.anim.slide_right_in : R.anim.slide_left_in;

        // Сохраняем позицию скролла
        final ScrollView scrollView = findViewById(R.id.scrollView);
        final int scrollY = scrollView.getScrollY();

        // Анимация исчезновения
        Animation out = AnimationUtils.loadAnimation(this, outAnim);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                // Обновляем данные
                updateDates();
                updateDayDates();
                clearScheduleContainers();
                parseSchedule(jsonDataOfSite);

                // Анимация появления
                Animation in = AnimationUtils.loadAnimation(ScheduleActivity.this, inAnim);
                in.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        scrollView.post(() -> scrollView.scrollTo(0, scrollY));
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {}
                });
                scheduleContainer.startAnimation(in);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        scheduleContainer.startAnimation(out);
    }

    private void createDayCards() {
        Log.d(TAG, "Creating day cards...");

        scheduleContainer.removeAllViews();
        dayCards.clear();
        dayHeaders.clear();
        lessonContainers.clear();

        String[] days = getResources().getStringArray(R.array.week_days);

        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            // 1. Создаем CardView
            CardView card = new CardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, dpToPx(8), 0, dpToPx(8));
            card.setLayoutParams(cardParams);
            card.setCardElevation(dpToPx(4));
            card.setRadius(dpToPx(16));
            card.setContentPadding(0, 0, 0, 0); // Убираем стандартные отступы

            // 2. Создаем основной контейнер
            LinearLayout mainContainer = new LinearLayout(this);
            mainContainer.setOrientation(LinearLayout.VERTICAL);
            mainContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            mainContainer.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

            // 3. Создаем заголовок
            LinearLayout headerContainer = new LinearLayout(this);
            headerContainer.setOrientation(LinearLayout.HORIZONTAL);
            headerContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            // Название дня
            TextView dayTitle = new TextView(this);
            dayTitle.setId(View.generateViewId());
            dayTitle.setText(days[i]);
            dayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dayTitle.setTypeface(null, Typeface.BOLD);
            dayTitle.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            // Дата
            TextView dateText = new TextView(this);
            dateText.setId(View.generateViewId());
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1
            );
            dateText.setLayoutParams(dateParams);
            dateText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dateText.setTypeface(null, Typeface.BOLD);
            dateText.setPadding(0, 0, 0, dpToPx(8)); // Добавляем отступ снизу

            headerContainer.addView(dayTitle);
            headerContainer.addView(dateText);

            // 4. Контейнер для уроков
            LinearLayout lessonsContainer = new LinearLayout(this);
            lessonsContainer.setOrientation(LinearLayout.VERTICAL);
            lessonsContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            // Собираем всю структуру
            mainContainer.addView(headerContainer);
            mainContainer.addView(lessonsContainer);
            card.addView(mainContainer);

            // Сохраняем ссылки
            dayCards.add(card);
            dayHeaders.add(dateText);
            lessonContainers.add(lessonsContainer);

            // Стилизация
            scheduleContainer.addView(card);

            Log.d(TAG, "Added card for day: " + days[i]);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void setupWeekWheel() {
        weekWheel = findViewById(R.id.weekWheel);
        WheelLayoutManager layoutManager = new WheelLayoutManager(this);
        weekWheel.setLayoutManager(layoutManager);

        wheelAdapter = new WeekWheelAdapter();
        List<WeekWheelAdapter.WeekItem> weeks = generateWeeks();
        wheelAdapter.setWeeks(weeks);
        weekWheel.setAdapter(wheelAdapter);

        // Центрируем на текущей неделе при запуске
        weekWheel.post(() -> {
            int position = wheelAdapter.findCurrentWeekPosition();
            layoutManager.smoothScrollToPosition(weekWheel, null, position);

            List<WeekWheelAdapter.WeekItem> weeksList = wheelAdapter.getWeeks();
            WeekWheelAdapter.WeekItem selected = weeksList.get(position);
            selectedWeek = selected.weekNumber;
            selectedYear = selected.year;

            // Перевод 8dp в пиксели
            final int padding8dp = (int) (8 * getResources().getDisplayMetrics().density);

            View firstChild = weekWheel.getChildAt(0);
            if (firstChild != null) {
                int itemWidth = firstChild.getWidth();
                int recyclerWidth = weekWheel.getWidth();
                int sidePadding = (recyclerWidth / 2) - (itemWidth / 2);

                // Устанавливаем отступы: слева/справа – динамически, сверху/снизу – 8dp
                weekWheel.setPadding(sidePadding, padding8dp, sidePadding, padding8dp);
                weekWheel.setClipToPadding(false);
            } else {
                weekWheel.postDelayed(() -> {
                    View child = weekWheel.getChildAt(0);
                    if (child != null) {
                        int itemWidth = child.getWidth();
                        int recyclerWidth = weekWheel.getWidth();
                        int sidePadding = (recyclerWidth / 2) - (itemWidth / 2);

                        weekWheel.setPadding(sidePadding, padding8dp, sidePadding, padding8dp);
                        weekWheel.setClipToPadding(false);
                    }
                }, 50);
            }

            updateDates();
            updateDayDates();
            clearScheduleContainers();
            parseSchedule(jsonDataOfSite);
        });


        // Обновление при остановке прокрутки
        // Обновление при остановке прокрутки колеса недель
        weekWheel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    View centerView = layoutManager.findCenterView();
                    if (centerView == null) return;

                    int position = weekWheel.getChildAdapterPosition(centerView);
                    List<WeekWheelAdapter.WeekItem> weekList = wheelAdapter.getWeeks();

                    if (position >= 0 && position < weekList.size()) {
                        WeekWheelAdapter.WeekItem selected = weekList.get(position);

                        // Определяем направление скролла
                        if (lastSelectedWeek != -1) {
                            isScrollingRight = selected.weekNumber > lastSelectedWeek;
                        }
                        lastSelectedWeek = selected.weekNumber;

                        if (selected.weekNumber != selectedWeek || selected.year != selectedYear) {
                            // Запускаем анимацию
                            animateScheduleTransition(isScrollingRight);

                            selectedWeek = selected.weekNumber;
                            selectedYear = selected.year;
                            lastWheelWeek = selectedWeek;
                            lastWheelYear = selectedYear;
                            clearData = true;
                        }
                    }
                    Log.d(TAG, "Week changed - updating alarms");
                    updateAlarms();
                }
            }
        });

    }

    private void clearData() {
        jsonDataOfSite = null;
        currentSearchQuery = "";
        groupedLessonsMap.clear();
        clearScheduleContainers();
        updateGroupsTextView("");
    }

    private void checkPermission(int callbackId, String... permissionsId) {
        boolean permissions = true;
        for (String p : permissionsId) {
            permissions = permissions && ContextCompat.checkSelfPermission(this, p) == PERMISSION_GRANTED;
        }

        if (!permissions){
            ActivityCompat.requestPermissions(this, permissionsId, callbackId);
            syncWithGoogleCalendar();}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CALENDAR) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // Добавлена проверка через 500мс
                new Handler().postDelayed(() -> {
                    if (hasGoogleAccount()) {
                        syncWithGoogleCalendar();
                    } else {
                        showAddAccountDialog();
                    }
                }, 500);
            } else {
                Toast.makeText(this, "Требуются все разрешения", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();

        // Добавляем проверку разрешения для аккаунтов
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS)
                != PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.GET_ACCOUNTS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                != PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_CALENDAR);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                != PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALENDAR);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CALENDAR
            );
        } else {
            syncWithGoogleCalendar();
        }
    }

    private boolean hasGoogleAccount() {
        return AccountUtils.hasGoogleAccount(this);
    }

    public void syncWithGoogleCalendar() {

        // Проверяем разрешения для работы с аккаунтами
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.GET_ACCOUNTS},
                    PERMISSION_REQUEST_CALENDAR
            );
            return;
        }
        if (!AccountUtils.hasGoogleAccount(this)) {
            Toast.makeText(this, "Добавьте аккаунт Google в настройках", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentSearchQuery.isEmpty()) {
            Toast.makeText(this, "Выберите группу или преподавателя", Toast.LENGTH_SHORT).show();
            return;
        }
        // Добавьте эту проверку
        if (getGoogleCalendarId() == -1) {
            runOnUiThread(() -> Toast.makeText(
                    this,
                    "Основной календарь Google не найден",
                    Toast.LENGTH_SHORT
            ).show());
            return;
        }
        if (jsonDataOfSite == null || jsonDataOfSite.isEmpty()) {
            Toast.makeText(this, "Нет данных для синхронизации", Toast.LENGTH_SHORT).show();
            return;
        }
        if (groupedLessonsMap.isEmpty()) {
            Toast.makeText(this, "Нет данных для синхронизации", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.GET_ACCOUNTS},
                    PERMISSION_REQUEST_CALENDAR
            );
            return;
        }


        if (!hasGoogleAccount()) {
            Log.w(TAG, "No Google account, showing dialog");
            showAddAccountDialog();
            return;
        }


            // Пытаемся получить ID календаря с повторными попытками
            long calendarId = getGoogleCalendarIdWithRetry(5, 1000);
            if (calendarId == -1) {
                Log.e(TAG, "Primary Google calendar not found after retries");
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Календарь не найден")
                            .setMessage("Не удалось найти основной календарь Google. Добавьте аккаунт Google в настройках устройства.")
                            .setPositiveButton("Добавить аккаунт", (dialog, which) -> showAddAccountDialog())
                            .setNegativeButton("Отмена", null)
                            .show();
                });
                return;
            }

        new Thread(() -> {
            try {
                Log.d(TAG, "Starting calendar sync...");
                deleteExistingEventsForCurrentSearch();

                List<LessonData> lessonsToSync = getFilteredLessons();
                Log.d(TAG, "Lessons to sync: " + lessonsToSync.size());

                for (LessonData lesson : lessonsToSync) {
                        addEventToCalendar(lesson);
                        addBreakEventToCalendar(lesson);
                }
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Расписание синхронизировано с Google Календарем",
                        Toast.LENGTH_LONG
                ).show());

                // Запускаем синхронизацию
                AccountManager am = AccountManager.get(this);
                Account[] accounts = am.getAccountsByType("com.google");
                if (accounts.length > 0) {
                    Bundle extras = new Bundle();
                    extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                    extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                    ContentResolver.requestSync(accounts[0], CalendarContract.AUTHORITY, extras);
                }
                else {
                    Log.d(TAG, "ошибка calendar sync...");
                }

            } catch (Exception e) {
                Log.e(TAG, "Sync error", e);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Ошибка: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        }).start();
    }

    private long getGoogleCalendarIdWithRetry(int maxAttempts, long delayMillis) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long calendarId = getGoogleCalendarId();
            if (calendarId != -1) {
                return calendarId;
            }

            Log.d(TAG, "Calendar not found, attempt " + attempt + "/" + maxAttempts);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    private void addBreakEventToCalendar(LessonData lesson) {
        try {
            // Получаем настройки - нужно ли добавлять перерывы
            SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
            boolean addBreaks = prefs.getBoolean("add_breaks", true);

            if (!addBreaks) return; // если пользователь отключил перерывы

            // Перерыв начинается через 1 минуту после окончания пары и длится 5 минут
            Calendar breakStart = Calendar.getInstance();
            breakStart.setTime(lesson.startTime);
            breakStart.add(Calendar.MINUTE, 45); // Было 1

            // Конец перерыва через 5 мин после начала перерыва
            Calendar breakEnd = (Calendar) breakStart.clone();
            breakEnd.add(Calendar.MINUTE, 5);

            ContentResolver cr = getContentResolver();
            ContentValues values = new ContentValues();

            Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
            if (accounts.length == 0) return;

            String description = "Перерыв после пары: " + lesson.subject + "\n" +
                    "Синхронизировано через MyApp";

            long calendarId = getGoogleCalendarId();
            values.put(Events.CALENDAR_ID, calendarId);
            values.put(Events.TITLE, "Перемена");
            values.put(Events.DESCRIPTION, description);
            values.put(Events.EVENT_LOCATION, "");
            values.put(Events.DTSTART, breakStart.getTimeInMillis());
            values.put(Events.DTEND, breakEnd.getTimeInMillis());
            values.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            values.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE); // Свободное время

            Uri uri = cr.insert(Events.CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "Failed to insert break event");
                return;
            }

            // Устанавливаем напоминание за 0 минут
            ContentValues reminderValues = new ContentValues();
            reminderValues.put(CalendarContract.Reminders.MINUTES, 0); // Было 30
            reminderValues.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri));
            reminderValues.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);

            getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, reminderValues);


        } catch (Exception e) {
            Log.e(TAG, "Error adding break event: " + e.getMessage(), e);
        }
    }

    private void deleteExistingEventsForCurrentSearch() throws Exception {
        ContentResolver cr = getContentResolver();
        long calendarId = getGoogleCalendarId();

        Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
        if (accounts.length == 0) return;

        String accountName = accounts[0].name;
        String ownerAccount = accounts[0].name;

        // Удаляем ВСЕ события приложения (занятия и перерывы)
        String selection = Events.CALENDAR_ID + " = ? AND " +
                Events.OWNER_ACCOUNT + " = ? AND " +
                Events.ACCOUNT_NAME + " = ? AND " +
                "(" + Events.DESCRIPTION + " LIKE ? OR " +
                Events.TITLE + " = ?)"; // Добавляем удаление событий "Перемена"

        String[] selectionArgs = new String[]{
                String.valueOf(calendarId),
                ownerAccount,
                accountName,
                "%Синхронизировано через MyApp%",
                "Перемена" // Удаляем события перерывов
        };

        int deletedRows = cr.delete(Events.CONTENT_URI, selection, selectionArgs);
        Log.i(TAG, "Удалено событий: " + deletedRows);
    }

    private void addEventToCalendar(LessonData lesson) {
        try {

            ContentResolver cr = getContentResolver();
            ContentValues values = new ContentValues();

            // Получаем аккаунт пользователя
            Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
            if (accounts.length == 0) return;
            String description = "Группа: " + lesson.group + "\n" +
                    "Преподаватель: " + lesson.teacher + "\n" +
                    "Аудитория: " + lesson.audience + "\n" +
                    "Синхронизировано через MyApp";
            String timeZone = TimeZone.getDefault().getID();

            SimpleDateFormat logSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            logSdf.setTimeZone(TimeZone.getDefault());
            Log.d(TAG, "Adding event: " + lesson.subject +
                    " on " + logSdf.format(lesson.startTime) +
                    " to " + logSdf.format(lesson.endTime));
            long calendarId = getGoogleCalendarId();
            values.put(Events.CALENDAR_ID, calendarId);
                        // Устанавливаем время БЕЗ КОРРЕКЦИИ на часовой пояс
            long startMillis = lesson.startTime.getTime();
            long endMillis = lesson.endTime.getTime();
            values.put(Events.TITLE, lesson.subject);
            values.put(Events.DESCRIPTION, description);
            values.put(Events.EVENT_LOCATION, lesson.audience);
            values.put(Events.DTSTART, startMillis);
            values.put(Events.DTEND, endMillis);
            values.put(Events.EVENT_TIMEZONE, timeZone); // Используем ID зоны
            values.put(Events.ORGANIZER, "MyTPU Schedule");
            values.put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY);

            Uri uri = cr.insert(Events.CONTENT_URI, values);
            if (uri != null) {
                Log.i(TAG, "Event added: " + uri);
            } else {
                Log.e(TAG, "Failed to add event");
            }
            SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
            int reminderMinutes = prefs.getInt("calendar_reminder_minutes", 30);

            ContentValues reminderValues = new ContentValues();
            reminderValues.put(CalendarContract.Reminders.MINUTES, reminderMinutes);
            reminderValues.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri));
            reminderValues.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);

            getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, reminderValues);

        } catch (Exception e) {
            Log.e(TAG, "Error adding reminder: " + e.getMessage(), e);
        }
    }

    private List<WeekWheelAdapter.WeekItem> generateWeeks() {
        List<WeekWheelAdapter.WeekItem> weeks = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        for (int i = -52; i <= 52; i++) { // 2 года назад и вперёд
            Calendar tempCal = (Calendar) cal.clone();
            tempCal.add(Calendar.WEEK_OF_YEAR, i);

            int weekNumber = tempCal.get(Calendar.WEEK_OF_YEAR);
            int year = tempCal.get(Calendar.YEAR);
            weeks.add(new WeekWheelAdapter.WeekItem(weekNumber, year));
        }
        return weeks;
    }


    private void setupCurrentDateButton() {
        currentDateButton = findViewById(R.id.currentDateButton);
        currentDateButton.setOnClickListener(v -> {
            // Получаем текущую дату
            Calendar today = Calendar.getInstance();
            int currentWeek = today.get(Calendar.WEEK_OF_YEAR);
            int currentYear = today.get(Calendar.YEAR);

            // Находим позицию в адаптере
            int targetPosition = -1;
            List<WeekWheelAdapter.WeekItem> weeks = wheelAdapter.getWeeks();
            for (int i = 0; i < weeks.size(); i++) {
                WeekWheelAdapter.WeekItem item = weeks.get(i);
                if (item.weekNumber == currentWeek && item.year == currentYear) {
                    targetPosition = i;
                    break;
                }
            }

            if (targetPosition == -1) return;

            // Обновляем выбранные значения
            selectedWeek = currentWeek;
            selectedYear = currentYear;

            // Прокручиваем колесо
            WheelLayoutManager layoutManager = (WheelLayoutManager) weekWheel.getLayoutManager();
            layoutManager.smoothScrollToPosition(weekWheel, null, targetPosition);

            // Принудительно обновляем расписание
            updateDates();
            updateDayDates();
            clearScheduleContainers();
            parseSchedule(jsonDataOfSite);
        });
    }

    private void restoreLastSearch() {
        String lastSearch = sharedPreferences.getString(KEY_LAST_SEARCH, "");
        if (!lastSearch.isEmpty()) {
            String normalized = lastSearch.toLowerCase().trim();
            if (allGroups.contains(normalized) || allTeachers.contains(normalized)) {
                applySearch(lastSearch);
            } else {
                // Очищаем невалидное значение
                sharedPreferences.edit().remove(KEY_LAST_SEARCH).apply();
            }
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
        outState.putBoolean("isLoading", isLoading);
        outState.putString("CURRENT_SEARCH", currentSearchQuery);
        outState.putInt("SELECTED_WEEK", selectedWeek);
        outState.putInt("SELECTED_YEAR", selectedYear);
        outState.putBooleanArray("HAS_LESSONS", hasLessons);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume - updating alarms");
        updateAlarms();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (AlarmScheduler.isAlarmEnabled(this)) {
            updateAlarms();
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        isLoading = savedInstanceState.getBoolean("isLoading");
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

            // Скрываем клавиатуру и сворачиваем поиск
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
            collapseSearch(); // ← Добавьте эту строку
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
        if (query.matches("\\d+[а-яА-Я]\\d+")) {
            query = query.toUpperCase().charAt(0) + query.substring(1).toLowerCase();
        }
        currentSearchQuery = query.trim();
        saveSearchState(query);
        updateGroupsTextView(query);
        Log.d(TAG, "Applying search: " + query);
        Log.d(TAG, "Available groups: " + allGroups.toString());
        Log.d(TAG, "Available teachers: " + allTeachers.toString());
        runOnUiThread(() -> {
            clearScheduleContainers();
            paraContainers.clear();
        });

        // Проверка существования группы/преподавателя
        boolean isValid = allGroups.contains(query.toLowerCase()) ||
                allTeachers.contains(query.toLowerCase());

        if (!isValid) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Группа/преподаватель не найдена", Toast.LENGTH_SHORT).show();
                groupsTextView.setVisibility(View.GONE);
            });
            return;
        }

        if (jsonDataOfSite != null && !jsonDataOfSite.isEmpty()) {
            parseSchedule(jsonDataOfSite);
        }
        updateGroupHash();
    }

    private void updateGroupHash() {
        if (currentSearchQuery.isEmpty() || jsonDataOfSite == null) {
            Log.d(TAG, "updateGroupHash: currentSearchQuery or jsonDataOfSite is null");
            return;
        }

        executor.execute(() -> {
            try {
                Log.d(TAG, "Updating group hash for: " + currentSearchQuery);
                List<LessonData> lessons = filterLessonsForGroup(jsonDataOfSite, currentSearchQuery);
                Log.d(TAG, "Found lessons: " + lessons.size());

                if (lessons.isEmpty()) {
                    Log.w(TAG, "No lessons found for group: " + currentSearchQuery);
                    return;
                }

                String newHash = calculateHash(lessons.toString());
                Log.d(TAG, "New group hash: " + newHash);

                SharedPreferences prefs = getSharedPreferences(HASH_PREFS, MODE_PRIVATE);
                prefs.edit()
                        .putString(GROUP_HASH_PREFIX + currentSearchQuery, newHash)
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "Group hash update error", e);
            }
        });
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

    private void saveSearchState(String query) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_LAST_SEARCH, query);
        String type = allGroups.contains(query) ? "group" :
                allTeachers.contains(query) ? "teacher" : "";
        editor.putString(KEY_SEARCH_TYPE, type);
        editor.apply();
    }

    private void collectSearchData(JSONArray faculties) throws JSONException {
        allGroups.clear();
        allTeachers.clear();

        for (int i = 0; i < faculties.length(); i++) {
            JSONObject faculty = faculties.getJSONObject(i);
            JSONArray groups = faculty.getJSONArray("groups");

            for (int j = 0; j < groups.length(); j++) {
                JSONObject group = groups.getJSONObject(j);
                String groupName = group.getString("name").trim().toLowerCase();
                allGroups.add(groupName); // Добавляем в нижнем регистре

                JSONArray lessons = group.getJSONArray("lessons");
                for (int k = 0; k < lessons.length(); k++) {
                    JSONObject lesson = lessons.getJSONObject(k);
                    JSONArray teachers = lesson.optJSONArray("teachers");

                    if (teachers != null) {
                        for (int t = 0; t < teachers.length(); t++) {
                            String teacher = teachers.getJSONObject(t)
                                    .getString("name")
                                    .trim()
                                    .toLowerCase(); // В нижнем регистре
                            allTeachers.add(teacher);
                        }
                    }
                }
            }
        }
    }

    private void updateSearchAdapter() {
        if (searchAdapter == null) return;
        runOnUiThread(() -> {
            if (searchAdapter != null && allGroups != null && allTeachers != null) {
                List<String> suggestions = new ArrayList<>(allGroups);
                suggestions.addAll(allTeachers);
                Collections.sort(suggestions);

                searchAdapter.clear();
                searchAdapter.addAll(suggestions);
                searchAdapter.notifyDataSetChanged();
            }
        });
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
        weekWheel = findViewById(R.id.weekWheel);
        currentDateButton = findViewById(R.id.currentDateButton);
        yearTextView = findViewById(R.id.tvYear);

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
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.setMinimalDaysInFirstWeek(4);

        // Используем правильный расчет недели
        selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
        selectedYear = calendar.get(Calendar.YEAR);

        // Обновляем интерфейс
        updateDates();
        updateDayDates();
    }

    private void loadScheduleFromUTI() {
        if (jsonDataOfSite != null || isFinishing()) return;
        if (isFinishing()) return;
        if (jsonDataOfSite == null) {
            String cached = loadLocalSchedule();
            if (cached != null) {
                runOnUiThread(() -> {
                    jsonDataOfSite = cached;
                    processDataInBackground(cached);
                });
                return;
            }
        }

        // Если дошли сюда, значит нужно грузить с сервера
        showLoading(true);
        EditText searchField = findViewById(R.id.searchField);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        executor.execute(() -> {
            int retryCount = 0;
            final int MAX_RETRIES = 3;
            Response response = null;

            while (retryCount < MAX_RETRIES && !isFinishing()) {
                try {
                    Request request = new Request.Builder()
                            .url(API_URL)
                            .build();

                    response = client.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP error: " + response.code());
                    }

                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("Empty response body");

                    final String rawData = body.string();

                    // Валидация JSON перед сохранением
                    try {
                        new JSONObject(rawData); // Простая проверка структуры
                    } catch (JSONException e) {
                        throw new IOException("Invalid JSON format");
                    }

                    runOnUiThread(() -> {
                        jsonDataOfSite = rawData;
                        processDataInBackground(jsonDataOfSite);
                    });

                    break; // Успешная загрузка

                } catch (IOException e) {
                    retryCount++;
                    final String errorMsg = "Attempt " + retryCount + " failed: " + e.getMessage();
                    Log.e(TAG, errorMsg);

                    int finalRetryCount = retryCount;
                    runOnUiThread(() -> {
                        if (finalRetryCount >= MAX_RETRIES) {
                            showError("Ошибка загрузки данных");
                            showLoading(false);
                        }
                    });

                    if (retryCount < MAX_RETRIES) {
                        try { Thread.sleep(2000); }
                        catch (InterruptedException ie) { break; }
                    }
                } finally {
                    if (response != null) response.close();
                }
            }
        });
    }

    private void loadSchedule() {
        if (SOURCE_TPU.equals(currentSource)) {
            loadScheduleFromTPU();
        } else {
            loadScheduleFromUTI();
        }
    }


    private void loadScheduleFromTPU() {
        if (isFinishing()) return;
        showLoading(true);

        // Проверяем валидность cookies
        if (!tpuParser.checkCookiesValidity()) {
            // Запускаем активность для решения капчи
            Intent intent = new Intent(this, CaptchaActivity.class);
            startActivityForResult(intent, REQUEST_CAPTCHA);
            return;
        }

        // Инициализируем tpuGroups, если он null
        if (tpuGroups == null) {
            tpuGroups = new ArrayList<>();
        }

        // Если у нас уже есть выбранная группа, загружаем её расписание
        if (currentSearchQuery != null && !currentSearchQuery.isEmpty() && !currentSearchQuery.equals("Не выбрано")) {
            // Находим ID группы по имени
            for (TPUScheduleParser.Group group : tpuGroups) {
                if (group.name.equals(currentSearchQuery)) {
                    loadScheduleForGroup(group.id);
                    return;
                }
            }
        }

        // Если группа не выбрана, показываем выбор школы и группы
        loadTPUData();
    }

    // ScheduleActivity.java
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTCHA) {
            if (resultCode == RESULT_OK) {
                // Перезагружаем cookies
                tpuParser.loadCookiesFromPrefs();
                // Продолжаем загрузку расписания
                loadTPUData();
            }
        }
    }










    private void processDataInBackground(String jsonData) {
        executor.execute(() -> {
            try {
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.optJSONArray("faculties");

                // Сбор данных для поиска
                collectSearchData(faculties != null ? faculties : new JSONArray());

                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    updateSearchAdapter();
                    updateGroupsTextView(currentSearchQuery);
                    parseSchedule(jsonData); // Запуск парсинга после подготовки данных
                });

            } catch (JSONException e) {
                Log.e(TAG, "Data processing error: " + e.getMessage());
                runOnUiThread(() -> showError("Ошибка формата данных"));
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
        Calendar calendar = Calendar.getInstance();
        int currentDay = (calendar.get(Calendar.DAY_OF_WEEK) - 2);
        if (currentDay < 0) currentDay = 6;

        for (int i = 0; i < dayCards.size(); i++) {
            CardView card = dayCards.get(i);
            if (card == null) continue;

            boolean hasLesson = hasLessons[i];
            boolean isToday = (i == currentDay) && isCurrentWeek();

            card.setVisibility(hasLesson ? View.VISIBLE : View.GONE);

            if (isToday) {
                card.setBackgroundResource(R.drawable.border);
            } else {
                card.setBackgroundResource(R.drawable.borderweek);
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private void parseSchedule(String jsonData) {
        if (jsonData == null || jsonData.isEmpty()) {
            Log.e(TAG, "parseSchedule: empty data");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.getJSONArray("faculties");

                synchronized (lock) {
                    groupedLessonsMap.clear();
                    Arrays.fill(hasLessons, false);

                    for (int i = 0; i < faculties.length(); i++) {
                        JSONObject faculty = faculties.getJSONObject(i);
                        JSONArray groups = faculty.getJSONArray("groups");

                        for (int j = 0; j < groups.length(); j++) {
                            JSONObject group = groups.getJSONObject(j);
                            processGroup(group);
                        }
                    }
                }

                runOnUiThread(() -> {
                    updateDayDates();
                    redrawScheduleUI();
                    Log.d(TAG, "Schedule parsed - updating alarms");
                    updateAlarms();
                    saveDataForWidget();
                });

            } catch (JSONException e) {
                Log.e(TAG, "Schedule parsing failed: " + e.getMessage());
                runOnUiThread(() -> showError("Ошибка расписания"));
            }
        });
    }

    private void saveDataForWidget() {
        try {
            JSONObject widgetData = new JSONObject();
            Calendar calendar = Calendar.getInstance();
// Проверка текущей недели
            if (!isCurrentWeek()) {
                Log.d("WidgetData", "Not current week - skipping widget update");
                return;
            }
            // Устанавливаем начало недели на ПОНЕДЕЛЬНИК (важно для корректного расчета)
            calendar.setFirstDayOfWeek(Calendar.MONDAY);
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM", Locale.getDefault());
            Calendar now = Calendar.getInstance();
            int currentWeek = now.get(Calendar.WEEK_OF_YEAR);
            int currentYear = now.get(Calendar.YEAR);

            // Обрабатываем 7 дней недели (0=ПН, 6=ВС)
            for (int i = 0; i < 7; i++) {
                JSONArray lessonsArray = new JSONArray();
                int targetWeekday = i; // 0-пн, 1-вт, ... 6-вс

                // Собираем уроки для текущего дня недели
                for (List<LessonData> lessonList : groupedLessonsMap.values()) {
                    for (LessonData lesson : lessonList) {
                        // КОРРЕКТИРОВКА: преобразуем weekday урока (1-пн..7-вс → 0-пн..6-вс)
                        int lessonWeekday = lesson.weekday - 1;

                        if (lessonWeekday == targetWeekday && matchesCurrentSearch(lesson)) {
                            JSONObject lessonObj = new JSONObject();
                            lessonObj.put("para", lesson.paraNumber + " пара");
                            lessonObj.put("time", lesson.time);
                            lessonObj.put("subject", lesson.subject);
                            lessonObj.put("audience", lesson.audience);
                            lessonObj.put("type", lesson.type);
                            lessonObj.put("teacher", lesson.teacher);
                            lessonObj.put("subgroups", lesson.subgroups); // Добавляем подгруппу

                            lessonsArray.put(lessonObj);
                        }
                    }
                }

                if (lessonsArray.length() > 0) {
                    JSONObject dayData = new JSONObject();
                    dayData.put("date", dateFormat.format(calendar.getTime())); // Дата текущего дня в цикле
                    dayData.put("lessons", lessonsArray);
                    widgetData.put(String.valueOf(i), dayData); // Ключ: 0-6
                }

                // Переход к следующему дню
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }

            // Сохраняем все необходимые данные
            SharedPreferences prefs = getSharedPreferences("WidgetPrefs", MODE_PRIVATE);
            prefs.edit()
                    .putString("widget_data", widgetData.toString())
                    .putString("current_group", currentSearchQuery)
                    .putInt("saved_week", currentWeek)
                    .putInt("saved_year", currentYear)
                    .apply();

            // Обновляем виджет (с явным указанием компонента)
            Intent updateIntent = new Intent("UPDATE_WIDGET");
            updateIntent.setComponent(new ComponentName(this, ScheduleWidgetProvider.class));
            sendBroadcast(updateIntent);
            Log.d("WidgetData", "Widget data saved: " + widgetData.toString());
        } catch (JSONException e) {
            Log.e("WidgetError", "Error saving widget data", e);
        }
    }

    private void redrawScheduleUI() {
        runOnUiThread(() -> {
            clearScheduleContainers();

            // Сортируем уроки по времени
            List<String> sortedKeys = new ArrayList<>(groupedLessonsMap.keySet());
            Collections.sort(sortedKeys);

            for (String key : sortedKeys) {
                addGroupedLessonsToUI(groupedLessonsMap.get(key));
            }

            updateDayVisibility();
            saveDataForWidget(); // Сохраняем данные для виджета
        });
    }

    private void addGroupedLessonsToUI(List<LessonData> lessons) {
        if (lessons == null || lessons.isEmpty()) return;

        runOnUiThread(() -> {
            try {
                LessonData firstLesson = lessons.get(0);
                int dayIndex = firstLesson.weekday - 1;

                if (dayIndex < 0 || dayIndex >= lessonContainers.size()) return;

                LinearLayout container = lessonContainers.get(dayIndex);

                CardView paraCard = createParaCard(firstLesson.time, determineParaNumber(firstLesson.time));

                // Получаем контейнер из тегов
                LinearLayout lessonContainer = (LinearLayout) paraCard.getTag();

                for (LessonData lesson : lessons) {
                    CardView lessonCard = createLessonCard(
                            lesson.subgroups > 0 ? "Подгруппа: " + lesson.subgroups : "",
                            lesson.subject,
                            lesson.audience,
                            lesson.type,
                            lesson.teacher
                    );
                    lessonContainer.addView(lessonCard);
                }

                container.addView(paraCard);
                hasLessons[firstLesson.weekday - 1] = true;

            } catch (Exception e) {
                Log.e(TAG, "Error adding lessons: " + e.getMessage());
            }
        });
    }

    private List<LessonData> getFilteredLessons() {
        List<LessonData> filtered = new ArrayList<>();
        Log.d(TAG, "Filtering lessons for: " + currentSearchQuery);
        Log.d(TAG, "Total lessons in map: " + groupedLessonsMap.values().stream().mapToInt(List::size).sum());

        for (List<LessonData> lessons : groupedLessonsMap.values()) {
            for (LessonData lesson : lessons) {
                if (matchesCurrentSearch(lesson)) {
                    filtered.add(lesson);
                    Log.d(TAG, "Lesson ADDED: " + lesson.subject + " for group: " + lesson.group);
                }
            }
        }

        Log.d(TAG, "Total filtered lessons: " + filtered.size());
        return filtered;
    }

    private boolean matchesCurrentSearch(LessonData lesson) {
        if (currentSearchQuery.isEmpty()) return false;

        String normalizedQuery = currentSearchQuery.toLowerCase().trim();

        boolean groupMatch = lesson.group != null &&
                lesson.group.toLowerCase().trim().equals(normalizedQuery);

        boolean teacherMatch = lesson.teacher != null &&
                lesson.teacher.toLowerCase().trim().equals(normalizedQuery);

        Log.d(TAG, "Matching: " + normalizedQuery + " with group: " + lesson.group +
                ", teacher: " + lesson.teacher + ", result: " + (groupMatch || teacherMatch));

        return groupMatch || teacherMatch;
    }

    private void updateDayDates() {
        if (dayHeaders.isEmpty() || dayHeaders.size() < DAYS_IN_WEEK) return;

        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM", new Locale("ru"));
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.YEAR, selectedYear);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            if (dayHeaders.get(i) != null) {
                dayHeaders.get(i).setText(sdf.format(cal.getTime()));
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
    }

    private void clearScheduleContainers() {
        runOnUiThread(() -> {
            for (LinearLayout container : lessonContainers) {
                container.removeAllViews();
            }
            paraContainers.clear();
            updateDayVisibility();
        });
    }

    private void processGroup(JSONObject group) throws JSONException {
        String groupName = group.getString("name").trim();
        boolean groupMatch = groupName.equalsIgnoreCase(currentSearchQuery);

        JSONArray lessons = group.getJSONArray("lessons");

        for (int k = 0; k < lessons.length(); k++) {
            JSONObject lesson = lessons.getJSONObject(k);

            if (groupMatch || checkTeacherInLesson(lesson)) {
                LessonData lessonData = processLesson(lesson, groupName);
                if (lessonData != null) {
                    String key = lessonData.weekday + "|" + lessonData.time;
                    synchronized (lock) {
                        if (!groupedLessonsMap.containsKey(key)) {
                            groupedLessonsMap.put(key, new ArrayList<>());
                        }
                        groupedLessonsMap.get(key).add(lessonData);
                    }
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

            if (teacherName.equals(query)) { // Только точное совпадение
                return true;
            }
        }
        return false;
    }

    private LessonData processLesson(JSONObject lesson, String groupName) throws JSONException {
        try {
            JSONObject date = lesson.getJSONObject("date");
            int weekday = date.getInt("weekday");

            // Используем локальный часовой пояс устройства
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getDefault());

            String startDateStr = date.getString("start");
            Date lessonDate = sdf.parse(startDateStr);

            // Используем локальный календарь
            Calendar baseCal = Calendar.getInstance(TimeZone.getDefault());
            baseCal.setFirstDayOfWeek(Calendar.MONDAY);
            baseCal.setMinimalDaysInFirstWeek(4);
            baseCal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
            baseCal.set(Calendar.YEAR, selectedYear);
            baseCal.set(Calendar.DAY_OF_WEEK, getCalendarDayOfWeek(weekday));

            // Сбрасываем время
            baseCal.set(Calendar.HOUR_OF_DAY, 0);
            baseCal.set(Calendar.MINUTE, 0);
            baseCal.set(Calendar.SECOND, 0);
            baseCal.set(Calendar.MILLISECOND, 0);

            // Используем локальный календарь для проверки недели
            Calendar calLocal = Calendar.getInstance(TimeZone.getDefault());
            calLocal.setMinimalDaysInFirstWeek(4);
            calLocal.setFirstDayOfWeek(Calendar.MONDAY);
            calLocal.setTime(lessonDate);

            int lessonWeek = calLocal.get(Calendar.WEEK_OF_YEAR);
            int lessonYear = calLocal.get(Calendar.YEAR);

            // Проверка недели и года
            //if (lessonWeek != selectedWeek || lessonYear != selectedYear) {return null;}

            JSONObject time = lesson.getJSONObject("time");
            String startTime = time.getString("start");
            String endTime = time.getString("end");

            // Устанавливаем время начала (локальное)
            Calendar startCal = (Calendar) baseCal.clone();
            String[] startParts = startTime.split(":");
            startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startParts[0]));
            startCal.set(Calendar.MINUTE, Integer.parseInt(startParts[1]));

            // Устанавливаем время окончания (локальное)
            Calendar endCal = (Calendar) baseCal.clone();
            String[] endParts = endTime.split(":");
            endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endParts[0]));
            endCal.set(Calendar.MINUTE, Integer.parseInt(endParts[1]));

            // Проверка корректности временного интервала
            if (startCal.after(endCal)) {
                Log.w(TAG, "Invalid time range for lesson: " + lesson);
                return null;
            }

            int paraNumber = determineParaNumber(startTime + " - " + endTime);

            return new LessonData(
                    startCal.getTime(),
                    endCal.getTime(),
                    lesson.getString("subject"),
                    lesson.getString("type"),
                    lesson.optInt("subgroups", 0),
                    startTime + " - " + endTime,
                    getAudience(lesson.getJSONArray("audiences")),
                    getTeacher(lesson.getJSONArray("teachers")),
                    weekday,
                    paraNumber,
                    groupName
            );

        } catch (ParseException | JSONException e) {
            Log.w(TAG, "Skipping invalid lesson: " + e.getMessage());
            return null;
        }
    }

    // Вспомогательный метод для преобразования дня недели в формат Calendar
    private int getCalendarDayOfWeek(int ourWeekday) {
        switch (ourWeekday) {
            case 1: return Calendar.MONDAY;
            case 2: return Calendar.TUESDAY;
            case 3: return Calendar.WEDNESDAY;
            case 4: return Calendar.THURSDAY;
            case 5: return Calendar.FRIDAY;
            case 6: return Calendar.SATURDAY;
            case 7: return Calendar.SUNDAY;
            default: return Calendar.MONDAY;
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

    private CardView createParaCard(String time, int paraNumber) {
        // Создаем корневой CardView
        CardView card = new CardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dpToPx(4), 0, dpToPx(4));
        card.setLayoutParams(cardParams);
        card.setCardElevation(dpToPx(1));
        card.setRadius(dpToPx(6));
        card.setContentPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_background));

        // Основной контейнер
        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Заголовок пары
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dpToPx(4)); // Отступ снизу 4dp


        // Левая часть - номер пары
        LinearLayout leftPart = new LinearLayout(this);
        leftPart.setOrientation(LinearLayout.VERTICAL);
        leftPart.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView paraNumberView = new TextView(this);
        paraNumberView.setText("Пара " + paraNumber);
        paraNumberView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        paraNumberView.setTypeface(null, Typeface.BOLD);
        paraNumberView.setTextColor(ContextCompat.getColor(this,R.color.textSecondary));

        leftPart.addView(paraNumberView);

        // Правая часть - время
        LinearLayout rightPart = new LinearLayout(this);
        rightPart.setOrientation(LinearLayout.VERTICAL);
        rightPart.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        rightPart.setGravity(Gravity.END);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));

        rightPart.addView(timeView);

        header.addView(leftPart);
        header.addView(rightPart);

        // Контейнер для уроков
        LinearLayout lessonsContainer = new LinearLayout(this);
        lessonsContainer.setOrientation(LinearLayout.VERTICAL);
        lessonsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        lessonsContainer.setPadding(dpToPx(8), dpToPx(8), 0, 0);

        mainContainer.addView(header);
        mainContainer.addView(lessonsContainer);
        card.addView(mainContainer);
        card.setTag(lessonsContainer);
        return card;
    }

    private CardView createLessonCard(String subgroups, String subject,
                                      String audience, String type, String teacher) {
        CardView card = new CardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)); // Отступы 4dp со всех сторон
        card.setLayoutParams(params);
        card.setCardElevation(dpToPx(1));
        card.setRadius(dpToPx(4));
        card.setContentPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.lesson_card_background));

        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Верхняя часть
        LinearLayout topPart = new LinearLayout(this);
        topPart.setOrientation(LinearLayout.HORIZONTAL);
        topPart.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Левая часть
        LinearLayout leftPart = new LinearLayout(this);
        leftPart.setOrientation(LinearLayout.VERTICAL);
        leftPart.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        if (!subgroups.isEmpty()) {
            TextView subgroupsView = new TextView(this);
            subgroupsView.setText(subgroups);
            subgroupsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            subgroupsView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            subgroupsView.setTypeface(null, Typeface.BOLD);
            subgroupsView.setPadding(0, 0, 0, dpToPx(2));
            leftPart.addView(subgroupsView);
        }

        TextView subjectView = new TextView(this);
        subjectView.setText(subject);
        subjectView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subjectView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        subjectView.setTypeface(null, Typeface.BOLD);
        subjectView.setPadding(0, subgroups.isEmpty() ? 0 : dpToPx(2), 0, dpToPx(2));
        leftPart.addView(subjectView);

        if (!teacher.isEmpty()) {
            TextView teacherView = new TextView(this);
            teacherView.setText(teacher);
            teacherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            teacherView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            leftPart.addView(teacherView);
        }

        // Правая часть
        TextView audienceView = new TextView(this);
        audienceView.setText(audience);
        audienceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        audienceView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        audienceView.setGravity(Gravity.END);

        topPart.addView(leftPart);
        topPart.addView(audienceView);
        mainContainer.addView(topPart);

        // Нижняя часть
        if (!type.isEmpty()) {
            TextView typeView = new TextView(this);
            typeView.setText(type);
            typeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            typeView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            typeView.setPadding(0, dpToPx(4), 0, 0);
            mainContainer.addView(typeView);
        }

        card.addView(mainContainer);
        return card;
    }

    private boolean isCurrentWeek() {
        Calendar now = Calendar.getInstance();
        Calendar selected = Calendar.getInstance();
        selected.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        selected.set(Calendar.YEAR, selectedYear);

        return now.get(Calendar.WEEK_OF_YEAR) == selectedWeek &&
                now.get(Calendar.YEAR) == selectedYear;
    }

    private void updateDates() {
        if (yearTextView != null) {
            yearTextView.setText(String.valueOf(selectedYear));
        }
        updateDayDates();
    }

    private int determineParaNumber(String time) {
        try {
            String start = time.split(" - ")[0];
            int hour = Integer.parseInt(start.split(":")[0]);

            if (hour == 8) return 1;
            if (hour == 10) return 2;
            if (hour == 12) return 3;
            if (hour == 14) return 4;
            if (hour == 16) return 5;
            if (hour == 18) return 6;
            if (hour == 20) return 7;
            return 8; // Для вечерних пар
        } catch (Exception e) {
            return 0;
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
        stopScheduleChecker();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (tpuParser != null) {
            tpuParser.cleanupTempFiles();
        }
        // Отменяем все pending операции
        checkHandler.removeCallbacksAndMessages(null);
        // Сохраняем данные при выходе
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_LAST_SEARCH, currentSearchQuery);
        editor.apply();
    }

    private long getGoogleCalendarId() {
        Uri uri = CalendarContract.Calendars.CONTENT_URI;
        String[] projection = {CalendarContract.Calendars._ID};
        String selection = CalendarContract.Calendars.IS_PRIMARY + "=1";

        try (Cursor cursor = getContentResolver().query(uri, projection, selection, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                Log.e(TAG, "No primary calendar found");
                return -1;
            }

            return cursor.getLong(0);
        } catch (Exception e) {
            Log.e(TAG, "Error getting primary calendar ID", e);
        }
        return -1;
    }

    public static class LessonData implements Serializable {
        public Date startTime;
        Date endTime;
        public String subject;
        public String type;
        public int subgroups;
        public String time;
        public String audience;
        public String teacher;
        public int weekday;
        int paraNumber;
        public String group; // Новое поле

        public LessonData(Date startTime, Date endTime, String subject, String type,
                          int subgroups, String time, String audience, String teacher,
                          int weekday, int paraNumber, String group) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.subject = subject;
            this.type = type;
            this.subgroups = subgroups;
            this.time = time;
            this.audience = audience;
            this.teacher = teacher;
            this.weekday = weekday;
            this.paraNumber = paraNumber;
            this.group = group;
        }


        @Override
        public String toString() {
            return "LessonData{" +
                    "subject='" + subject + '\'' +
                    ", time='" + time + '\'' +
                    ", weekday=" + weekday +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    '}';
        }
    }
}