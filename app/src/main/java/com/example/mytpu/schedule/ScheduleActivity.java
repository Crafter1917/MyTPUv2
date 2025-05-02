package com.example.mytpu.schedule;

import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Environment;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
import android.animation.ObjectAnimator;
import android.view.animation.OvershootInterpolator;
import com.example.mytpu.R;

public class ScheduleActivity extends AppCompatActivity {
    private static final String TAG = "ScheduleActivity";
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
    private int lastWheelWeek = -1;
    private int lastWheelYear = -1;
    private AutoCompleteTextView searchField;
    private ImageButton btnSearchToggle;
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
                    // Получаем центральный элемент
                    View centerView = layoutManager.findCenterView();
                    if (centerView == null) return;

                    int position = weekWheel.getChildAdapterPosition(centerView);
                    List<WeekWheelAdapter.WeekItem> weekList = wheelAdapter.getWeeks();

                    if (position >= 0 && position < weekList.size()) {
                        WeekWheelAdapter.WeekItem selected = weekList.get(position);

                        // Проверка, изменились ли неделя/год
                        if (selected.weekNumber != lastWheelWeek || selected.year != lastWheelYear) {
                            // Обновляем выбранные значения
                            selectedWeek = selected.weekNumber;
                            selectedYear = selected.year;
                            lastWheelWeek = selectedWeek;
                            lastWheelYear = selectedYear;
                            clearData = true;

                            // Сохраняем текущую позицию скролла
                            ScrollView scrollView = findViewById(R.id.scrollView);
                            int scrollY = scrollView.getScrollY();

                            // Обновляем расписание
                            updateDates();
                            updateDayDates();
                            clearScheduleContainers();
                            parseSchedule(jsonDataOfSite);

                            // Восстанавливаем позицию скролла
                            scrollView.post(() -> scrollView.scrollTo(0, scrollY));
                        }
                    }
                }
            }
        });

    }

    // Новые методы
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
        currentDateButton.setOnClickListener(v -> {
            List<WeekWheelAdapter.WeekItem> weeks = generateWeeks();
            wheelAdapter.setWeeks(weeks);

            int targetPosition = wheelAdapter.findCurrentWeekPosition();

            lastWheelWeek = selectedWeek;
            lastWheelYear = selectedYear;

            clearData = true;

            weekWheel.post(() -> {
                WheelLayoutManager layoutManager = (WheelLayoutManager) weekWheel.getLayoutManager();
                layoutManager.scrollToPosition(targetPosition);

                // Подождём один кадр, потом центрируем
                weekWheel.post(() -> {
                    layoutManager.snapToCenter(); // <- это вручную
                    updateDates();
                    updateDayDates();
                    clearScheduleContainers();
                    parseSchedule(jsonDataOfSite);
                });
            });
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
        dataPrevious = findViewById(R.id.dataPrevious);
        currentData = findViewById(R.id.currentData);
        compactCurrentDate = findViewById(R.id.compactCurrentDate);
        dataNext = findViewById(R.id.dataNext);
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
        if (jsonDataOfSite != null) return;
        showLoading(true);

        // Только загрузка данных без сохранения
        executor.execute(() -> {
            try (Response response = client.newCall(new Request.Builder()
                    .url(API_URL)
                    .build()).execute()) {

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> showError("Ошибка загрузки: " + response.code()));
                    return;
                }

                String responseData = response.body().string();
                jsonDataOfSite = responseData;

                runOnUiThread(() -> {
                    processDataInBackground(responseData);
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
                        // В updateDayVisibility() используем стандартные методы CardView
                        if (isToday) {
                            dayCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.activeDayBackground));
                            dayCard.setCardElevation(8f); // Используем elevation вместо stroke
                        } else {
                            dayCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.cardBackground));
                            dayCard.setCardElevation(2f);
                        }
                    }
                }}});
    }

    @SuppressLint("DefaultLocale")
    private void parseSchedule(String jsonData) {
        if (jsonData == null || jsonData.trim().isEmpty()) {
            Log.e(TAG, "JSON данных нет или пустой — пропускаем разбор расписания");
            return;
        }

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
                Animation anim = AnimationUtils.loadAnimation(this, R.anim.wheel_scroll_anim);
                paraCard.startAnimation(anim);
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
                    lessonView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wheel_scroll_anim));
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
        cal.set(Calendar.YEAR, selectedYear);
        cal.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        currentData.setText(sdf.format(cal.getTime()));
        compactCurrentDate.setText(currentData.getText());

        cal.add(Calendar.WEEK_OF_YEAR, -1);
        dataPrevious.setText(sdf.format(cal.getTime()));
        yearTextView.setText(String.valueOf(selectedYear));
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

    static class LessonData {
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
}