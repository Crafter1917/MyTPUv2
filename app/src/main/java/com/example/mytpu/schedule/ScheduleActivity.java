package com.example.mytpu.schedule;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.example.mytpu.schedule.AlarmScheduler.KEY_ALARM_SETTINGS;

import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Build;
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
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.mytpu.R;

public class ScheduleActivity extends AppCompatActivity {
    private static final String TAG = "ScheduleActivity";
    private static final int DAYS_IN_WEEK = 7;
    private static final int PERMISSION_REQUEST_CALENDAR = 101;
    private static final int ACCOUNT_REQUEST_CODE = 102;
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
        scheduleContainer = findViewById(R.id.scheduleContainer);
        if (scheduleContainer == null) {
            Log.e(TAG, "scheduleContainer not found!");
            return;
        }
        final int callbackId = 42;

        findViewById(R.id.btnSyncCalendar).setOnClickListener(v -> checkPermission(callbackId, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR));
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
        setupWorkManager();
    }
    private void unsyncCalendar() {
        new Thread(() -> {
            try {
                deleteExistingEvents();
                runOnUiThread(() ->
                        Toast.makeText(this, "Все события удалены из календаря", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка удаления: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @SuppressLint("ScheduleExactAlarm")
    private void showAlarmDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_alarm, null);

        CheckBox[] paraCheckBoxes = {
                view.findViewById(R.id.para1),
                view.findViewById(R.id.para2),
                view.findViewById(R.id.para3),
                view.findViewById(R.id.para4),
                view.findViewById(R.id.para5),
                view.findViewById(R.id.para6)
        };

        CheckBox currentSearchOnly = view.findViewById(R.id.currentSearchOnly);
        Button saveButton = view.findViewById(R.id.saveButton);

        // Загрузка сохраненных настроек
        boolean[] savedSettings = AlarmScheduler.getSavedSettings(this);
        for (int i = 0; i < 6; i++) {
            paraCheckBoxes[i].setChecked(savedSettings[i]);
        }
        currentSearchOnly.setChecked(savedSettings[6]);

        builder.setView(view);
        AlertDialog dialog = builder.create();

        // В методе showAlarmDialog():
        // В методе showAlarmDialog() внутри saveButton.setOnClickListener:
        saveButton.setOnClickListener(v -> {
            boolean[] selectedParas = new boolean[6];
            for (int i = 0; i < 6; i++) {
                selectedParas[i] = paraCheckBoxes[i].isChecked();
            }
            boolean forCurrentSearch = currentSearchOnly.isChecked();

            // Сохраняем настройки
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_ALARM_SETTINGS,
                    TextUtils.join(",", Collections.singleton(selectedParas)) + "," + forCurrentSearch);
            editor.apply();

            // Существующий код установки будильников
            List<LessonData> allLessons = new ArrayList<>();
            for (List<LessonData> lessons : groupedLessonsMap.values()) {
                allLessons.addAll(lessons);
            }

            AlarmScheduler.scheduleAlarms(
                    ScheduleActivity.this,
                    allLessons,
                    selectedParas
            );
            dialog.dismiss();
        });

        dialog.show();
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
                }
            }
        });

    }

    private void checkPermission(int callbackId, String... permissionsId) {
        boolean permissions = true;
        for (String p : permissionsId) {
            permissions = permissions && ContextCompat.checkSelfPermission(this, p) == PERMISSION_GRANTED;
        }

        if (!permissions)
            ActivityCompat.requestPermissions(this, permissionsId, callbackId);
        syncWithGoogleCalendar();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CALENDAR) {
            if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
                syncWithGoogleCalendar();
            } else {
                Toast.makeText(this, "Требуется разрешение для работы с календарем", Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void deleteExistingEvents() throws Exception {
        ContentResolver cr = getContentResolver();
        Uri uri = Events.CONTENT_URI;
        String selection = Events.DESCRIPTION + " LIKE ?";
        String[] selectionArgs = new String[]{"%Синхронизировано через MyApp%"};

        int deletedRows = cr.delete(uri, selection, selectionArgs);
        Log.i(TAG, "Удалено событий: " + deletedRows);
    }
    private void syncWithGoogleCalendar() {


        if (groupedLessonsMap.isEmpty()) {
            Toast.makeText(this, "Нет данных для синхронизации", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                // Удаляем старые события
                deleteExistingEvents();

                // Добавляем новые события
                List<LessonData> allLessons = new ArrayList<>();
                for (List<LessonData> lessons : groupedLessonsMap.values()) {
                    allLessons.addAll(lessons);
                }

                for (LessonData lesson : allLessons) {
                    addEventToCalendar(lesson);
                }

                runOnUiThread(() ->
                        Toast.makeText(this, "Расписание синхронизировано с Google Календарем", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка синхронизации: " + e.getMessage(), Toast.LENGTH_LONG).show());
                Log.e(TAG,"Ошибка синхронизации: "+e);
            }
        }).start();
    }

    private void addEventToCalendar(LessonData lesson) {
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();

        values.put(Events.DTSTART, lesson.startTime.getTime());
        values.put(Events.DTEND, lesson.endTime.getTime());
        values.put(Events.TITLE, lesson.subject);
        values.put(Events.DESCRIPTION,
                "Аудитория: " + lesson.audience + "\n" +
                        "Преподаватель: " + lesson.teacher + "\n" +
                        "Тип занятия: " + lesson.type + "\n" +
                        "Синхронизировано через MyApp"); // Уникальный маркер
        values.put(Events.CALENDAR_ID, getPrimaryCalendarId());
        values.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());

        // Правило повтора
        Calendar cal = Calendar.getInstance();
        cal.setTime(lesson.startTime);
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=" + getDayAbbreviation(dayOfWeek) + ";COUNT=16");

        cr.insert(Events.CONTENT_URI, values);
    }

    private String getDayAbbreviation(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY: return "MO";
            case Calendar.TUESDAY: return "TU";
            case Calendar.WEDNESDAY: return "WE";
            case Calendar.THURSDAY: return "TH";
            case Calendar.FRIDAY: return "FR";
            case Calendar.SATURDAY: return "SA";
            case Calendar.SUNDAY: return "SU";
            default: return "MO";
        }
    }

    private long getPrimaryCalendarId() {
        Cursor cursor = getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.IS_PRIMARY + "=1",
                null,
                null
        );

        if (cursor != null && cursor.moveToFirst()) {
            long id = cursor.getLong(0);
            cursor.close();
            return id;
        }
        return 1; // fallback
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
            currentSearchQuery = lastSearch.toLowerCase();
            searchField.setText(lastSearch);
            updateGroupsTextView(lastSearch);
            // Немедленно применяем поиск при восстановлении
            if (jsonDataOfSite != null) {
                parseSchedule(jsonDataOfSite);
            }
        }
    }

    private void setupWorkManager() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ScheduleWorker.class, 15, TimeUnit.MINUTES
        ).build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                        "scheduleCheck",
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                );
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
        selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
        selectedYear = calendar.get(Calendar.YEAR);
        updateDates();
    }

    private void loadSchedule() {
        if (jsonDataOfSite != null || isFinishing()) return;

        showLoading(true);

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
        int activeColor = ContextCompat.getColor(this, R.color.active_day_background);
        int normalColor = ContextCompat.getColor(this, R.color.card_background);

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
                    updateAlarms(); // Добавьте эту строку
                    updateDayDates();
                    redrawScheduleUI();
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
    // ScheduleActivity.java
    @SuppressLint("ScheduleExactAlarm")
    private void updateAlarms() {
        List<LessonData> allLessons = new ArrayList<>();
        for (List<LessonData> lessons : groupedLessonsMap.values()) {
            allLessons.addAll(lessons);
        }

        boolean[] selectedParas = AlarmScheduler.getSavedSettings(this);
        AlarmScheduler.scheduleAlarms(this, allLessons, selectedParas);
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
                hasLessons[dayIndex] = true;

            } catch (Exception e) {
                Log.e(TAG, "Error adding lessons: " + e.getMessage());
            }
        });
    }

    private void updateDayDates() {
        if (dayHeaders.isEmpty() || dayHeaders.size() < DAYS_IN_WEEK) return;


        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM", new Locale("ru"));
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.YEAR, selectedYear);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        for (TextView header : dayHeaders) {
            if (header != null) {
                header.setText(sdf.format(cal.getTime()));
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
                LessonData lessonData = processLesson(lesson);
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

            if (teacherName.contains(query)) {
                return true;
            }
        }
        return false;
    }

    private LessonData processLesson(JSONObject lesson) throws JSONException {
        try {
            JSONObject date = lesson.getJSONObject("date");
            String startDateStr = date.getString("start");
            int weekday = date.getInt("weekday");

            // Парсинг даты
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
            Date lessonDate = sdf.parse(startDateStr);

            // Проверка совпадения недели
            Calendar lessonCal = Calendar.getInstance();
            lessonCal.setTime(lessonDate);

            Calendar selectedCal = Calendar.getInstance();
            selectedCal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
            selectedCal.set(Calendar.YEAR, selectedYear);

            if (lessonCal.get(Calendar.WEEK_OF_YEAR) != selectedCal.get(Calendar.WEEK_OF_YEAR) ||
                    lessonCal.get(Calendar.YEAR) != selectedCal.get(Calendar.YEAR)) {
                return null;
            }

            // Время занятия
            JSONObject time = lesson.getJSONObject("time");
            String startTime = time.getString("start");
            String endTime = time.getString("end");
            String lessonTime = startTime + " - " + endTime;
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
            Date startTimeD = timeFormat.parse(time.getString("start"));
            Date endTimeD = timeFormat.parse(time.getString("end"));

            int paraNumber = determineParaNumber(lessonTime);

            return new LessonData(
                    startTimeD,
                    endTimeD,
                    lesson.getString("subject"),
                    lesson.getString("type"),
                    lesson.optInt("subgroups", 0),
                    lessonTime,
                    getAudience(lesson.getJSONArray("audiences")),
                    getTeacher(lesson.getJSONArray("teachers")),
                    weekday,
                    paraNumber // Передаем номер пары
            );

        } catch (ParseException | JSONException e) {
            Log.w(TAG, "Skipping invalid lesson: " + e.getMessage());
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
        if ( yearTextView != null) {
            yearTextView.setText(String.valueOf(selectedYear));
        }

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, selectedYear);
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        updateDayDates();
    }

    private int determineParaNumber(String time) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            String startTimeStr = time.split(" - ")[0];
            Date startTime = sdf.parse(startTimeStr);

            Calendar cal = Calendar.getInstance();
            cal.setTime(startTime);

            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            // Расписание пар по времени
            if (hour == 8 && minute >= 30) return 1;
            if (hour == 10 && minute <= 5) return 1;

            if (hour == 10 && minute >= 20) return 2;
            if (hour == 11 && minute <= 35) return 2;

            if (hour == 12 && minute >= 45) return 3;
            if (hour == 14 && minute <= 20) return 3;

            if (hour == 14 && minute >= 35) return 4;
            if (hour == 16 && minute <= 10) return 4;

            if (hour == 16 && minute >= 20) return 5;
            if (hour == 17 && minute <= 55) return 5;
            return 6;

        } catch (ParseException e) {
            return 0; // Некорректное время
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

    static class LessonData {
        Date startTime;
        Date endTime;
        String subject;
        String type;
        int subgroups;
        String time;
        String audience;
        String teacher;
        int weekday;
        int paraNumber; // Добавленное поле

        LessonData(Date startTime, Date endTime, String subject, String type,
                   int subgroups, String time, String audience, String teacher,
                   int weekday, int paraNumber) {
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
        }
    }
}