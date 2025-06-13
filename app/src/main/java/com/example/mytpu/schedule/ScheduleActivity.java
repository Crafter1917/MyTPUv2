package com.example.mytpu.schedule;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.accounts.Account;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.RingtoneManager;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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
    private AutoCompleteTextView searchField;
    private ImageButton btnSearchToggle;
    private boolean isLoading = false;
    private boolean[] hasLessons = new boolean[DAYS_IN_WEEK];
    private boolean clearData = false;
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
    private RecyclerView weekWheel;
    private WeekWheelAdapter wheelAdapter;
    private Button currentDateButton;
    private boolean isSearchExpanded = false;
    private int originalCardWidth;
    private TextView yearTextView;
    private boolean isKeyboardVisible = false;
    private final ConcurrentHashMap<String, List<LessonData>> groupedLessonsMap = new ConcurrentHashMap<>();
    private final Object lock = new Object(); // Добавить объект для синхронизации
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private List<CardView> dayCards = new ArrayList<>();
    private List<TextView> dayHeaders = new ArrayList<>();
    private List<LinearLayout> lessonContainers = new ArrayList<>();

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Исправленный код для обработки звука будильника
        if (requestCode == REQUEST_CODE_ALARM_SOUND && resultCode == RESULT_OK) {
            Uri ringtoneUri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (ringtoneUri != null) {
                SharedPreferences prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE);
                prefs.edit().putString("alarm_sound_uri", ringtoneUri.toString()).apply();
            }
        }
        else if (requestCode == ACCOUNT_REQUEST_CODE) {
            Log.d(TAG, "Returned from account addition flow");
            checkAccountWithRetry(10, 500);
        }
    }

    private void checkAccountWithRetry(int maxAttempts, long delayMillis) {
        new Handler().postDelayed(new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                if (hasGoogleAccount()) {
                    Log.d(TAG, "Account added successfully, syncing...");
                    syncWithGoogleCalendar();
                } else if (attempts < maxAttempts) {
                    attempts++;
                    Log.d(TAG, "Retry account check (" + attempts + "/" + maxAttempts + ")");
                    new Handler().postDelayed(this, delayMillis);
                } else {
                    Log.w(TAG, "No Google account added after flow");
                    runOnUiThread(() -> Toast.makeText(
                            ScheduleActivity.this,
                            "Аккаунт не был добавлен или не обнаружен",
                            Toast.LENGTH_SHORT
                    ).show());
                }
            }
        }, delayMillis);
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
        checkPermissions();
        setContentView(R.layout.activity_schedule);
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
        initHttpClient();
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
        if (jsonDataOfSite == null || jsonDataOfSite.isEmpty()) {
            loadSchedule(); // Загружаем данные только если их нет
        } else {
            processDataInBackground(jsonDataOfSite); // Обрабатываем сохраненные данные
        }
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

    // ScheduleActivity.java - дополнительно обновляем метод сохранения настроек

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

            // Безопасная проверка
            if (calendarFragment.isAddBreaksEnabled()) {
                editor.putBoolean("add_breaks", true);
            } else {
                editor.putBoolean("add_breaks", false);
            }
        }

        editor.apply();
        updateAlarms();
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
        try {
            AccountManager am = AccountManager.get(this);
            Pattern googlePattern = Pattern.compile(".*@gmail\\.com|.*@googlemail\\.com", Pattern.CASE_INSENSITIVE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.GET_ACCOUNTS) != PERMISSION_GRANTED) {
                    Log.w(TAG, "No GET_ACCOUNTS permission");
                    return false;
                }
            }

            Account[] accounts = am.getAccounts();
            for (Account account : accounts) {
                if (googlePattern.matcher(account.name).matches()) {
                    Log.d(TAG, "Found Google account: " + account.name);
                    return true;
                }
            }
            Log.w(TAG, "No Google accounts found");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Google accounts: " + e.getMessage());
            return false;
        }
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

        for (int i = -27; i <= 27; i++) { // 2 года назад и вперёд
            Calendar tempCal = (Calendar) cal.clone();
            tempCal.add(Calendar.WEEK_OF_YEAR, i);

            int weekNumber = tempCal.get(Calendar.WEEK_OF_YEAR);
            int year = tempCal.get(Calendar.YEAR);
            weeks.add(new WeekWheelAdapter.WeekItem(weekNumber, year));
        }
        return weeks;
    }

    private boolean eventExists(LessonData lesson) {
        String selection = Events.TITLE + " = ? AND " +
                Events.DTSTART + " = ? AND " +
                Events.DTEND + " = ?";

        String[] selectionArgs = {
                lesson.subject,
                String.valueOf(lesson.startTime.getTime()),
                String.valueOf(lesson.endTime.getTime())
        };

        try (Cursor cursor = getContentResolver().query(
                Events.CONTENT_URI,
                null,
                selection,
                selectionArgs,
                null
        )) {
            return cursor != null && cursor.getCount() > 0;
        }
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
            applySearch(lastSearch);
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
        saveSearchState(query);
        updateGroupsTextView(query);
        SharedPreferences.Editor editor = getSharedPreferences("schedule_prefs", MODE_PRIVATE).edit();
        editor.putString("saved_group", query);
        editor.apply();
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

    private void loadSchedule() {
        if (jsonDataOfSite != null || isFinishing()) return;

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
                });

            } catch (JSONException e) {
                Log.e(TAG, "Schedule parsing failed: " + e.getMessage());
                runOnUiThread(() -> showError("Ошибка расписания"));
            }
        });
    }

    private void redrawScheduleUI() {
        runOnUiThread(() -> {
            clearScheduleContainers();

            List<String> sortedKeys = new ArrayList<>(groupedLessonsMap.keySet());
            Collections.sort(sortedKeys, (k1, k2) -> {
                String[] p1 = k1.split("\\|");
                String[] p2 = k2.split("\\|");

                int dayCompare = Integer.compare(Integer.parseInt(p1[0]), Integer.parseInt(p2[0]));
                if (dayCompare != 0) return dayCompare;

                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
                    Date t1 = sdf.parse(p1[1].split(" - ")[0]);
                    Date t2 = sdf.parse(p2[1].split(" - ")[0]);
                    return t1.compareTo(t2);
                } catch (ParseException e) {
                    return 0;
                }
            });

            for (String key : sortedKeys) {
                addGroupedLessonsToUI(groupedLessonsMap.get(key));
            }

            updateDayVisibility();
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

        for (List<LessonData> lessons : groupedLessonsMap.values()) {
            for (LessonData lesson : lessons) {

                if (matchesCurrentSearch(lesson)) {
                    filtered.add(lesson);
                    Log.d(TAG, "Lesson ADDED: " + lesson.subject);
                }
            }
        }
        Log.d(TAG, "Total filtered lessons: " + filtered.size());
        return filtered;
    }

    private boolean matchesCurrentSearch(LessonData lesson) {
        if (currentSearchQuery.isEmpty()) return false; // Без запроса ничего не показываем

        // Точное совпадение для группы (без учета регистра)
        boolean groupMatch = lesson.group != null &&
                lesson.group.equalsIgnoreCase(currentSearchQuery);

        // Точное совпадение для преподавателя (без учета регистра)
        boolean teacherMatch = lesson.teacher != null &&
                lesson.teacher.equalsIgnoreCase(currentSearchQuery);

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
            if (lessonWeek != selectedWeek || lessonYear != selectedYear) {
                return null;
            }

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

        return now.get(Calendar.WEEK_OF_YEAR) == selected.get(Calendar.WEEK_OF_YEAR)
                && now.get(Calendar.YEAR) == selected.get(Calendar.YEAR);
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
            return 6; // Для вечерних пар
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
        if (executor != null) {
            executor.shutdownNow();
        }
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