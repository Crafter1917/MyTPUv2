package com.example.mytpu;
import static com.example.mytpu.R.id.dataPrevious;

import android.annotation.SuppressLint;
import android.view.View;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import android.animation.ObjectAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
public class ScheduleActivity extends AppCompatActivity {

    private static final String TAG = "ScheduleActivity";
    private static final String API_URL = "http://uti.tpu.ru/timetable_import.json";
    private LinearLayout scheduleContainer;
    private TextView dataPrevious, CurrectData, dataNext, lastUpdateTextView, compactCurrentDate;
    private OkHttpClient client;
    private ExecutorService executor;

    private int currentData, currentDatam ,selectedYear,selectedWeek, height, endDayStr, startDayStr, endMStr;
    private String jsonDataOfSite;
    private String startTime;
    private boolean cWN, ClearData = false;
    Map<String, LinearLayout> paraContainers = new HashMap<>();





    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
        ScrollView scrollView = findViewById(R.id.scrollView);
        LinearLayout fullWeekNavigation = findViewById(R.id.fullWeekNavigation);
        LinearLayout compactWeekNavigation = findViewById(R.id.compactWeekNavigation);
        fullWeekNavigation.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // Убираем слушатель, чтобы он сработал один раз
                fullWeekNavigation.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                height = fullWeekNavigation.getHeight();

                // Здесь можно использовать полученные размеры
            }
        });

        scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {

                if (scrollY > height * 2 && !cWN) {
                    // Скроллим вниз
                    animateViewTransition(fullWeekNavigation, compactWeekNavigation);
                    cWN = true;
                }  if (scrollY < height && cWN) {
                    // Скроллим вверх
                    animateViewTransition(compactWeekNavigation, fullWeekNavigation);
                    cWN = false;
                }
            }
        });

// Метод для анимации перехода между двумя View


        scheduleContainer = findViewById(R.id.scheduleContainer);
        client = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();

        // Устанавливаем текущую неделю и год
        Calendar calendar = Calendar.getInstance();
        selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
        selectedYear = calendar.get(Calendar.YEAR);
        currentData = calendar.get(Calendar.DATE);
        currentDatam = calendar.get(Calendar.MONTH)+1;

        dataPrevious = findViewById(R.id.dataPrevious);
        CurrectData = findViewById(R.id.CurrectData);
        compactCurrentDate = findViewById(R.id.compactCurrentDate);
        dataNext = findViewById(R.id.dataNext);


        // Настройка кнопок
        Button btnPreviousWeek = findViewById(R.id.btnPreviousWeek);
        Button btnCurrectWeek = findViewById(R.id.btnCurrentWeek);
        Button btnNextWeek = findViewById(R.id.btnNextWeek);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) ImageButton btnPreviousWeekI = findViewById(R.id.btnCompactPreviousWeekI);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"})ImageButton btnCurrectWeekI = findViewById(R.id.btnCompactCurrentWeekI);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"})ImageButton btnNextWeekI = findViewById(R.id.btnCompactNextWeekI);
        updateDates();

        EditText searchField = findViewById(R.id.searchField);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(searchField.getText().toString());
                return true;
            }
            return false;
        });

        btnPreviousWeekI.setOnClickListener(v -> {
            selectedWeek--;
            if (selectedWeek < 1) {
                selectedWeek = 52;
                selectedYear--;
            }
            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });
        btnCurrectWeekI.setOnClickListener(v ->{

            selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
            selectedYear = calendar.get(Calendar.YEAR);
            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });
        btnNextWeekI.setOnClickListener(v -> {
            selectedWeek++;
            if (selectedWeek > 52) {
                selectedWeek = 1;
                selectedYear++;
            }

            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });

        btnPreviousWeek.setOnClickListener(v -> {
            selectedWeek--;
            if (selectedWeek < 1) {
                selectedWeek = 52;
                selectedYear--;
            }
            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });
        btnCurrectWeek.setOnClickListener(v ->{

            selectedWeek = calendar.get(Calendar.WEEK_OF_YEAR);
            selectedYear = calendar.get(Calendar.YEAR);
            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });
        btnNextWeek.setOnClickListener(v -> {
            selectedWeek++;
            if (selectedWeek > 52) {
                selectedWeek = 1;
                selectedYear++;
            }

            ClearData = true;
            updateDates();
            parseSchedule(jsonDataOfSite);
        });

        // Загружаем расписание
        loadSchedule();
    }

    private void performSearch(String string) {
    }

    private void animateJellyEffect(View view) {
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.setAlpha(0f);

        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(500) // Длительность анимации
                .setInterpolator(new OvershootInterpolator(1.5f)) // Эффект "желе"
                .start();
    }

    // Модифицируем метод animateViewTransition
    private void animateViewTransition(View viewToHide, View viewToShow) {
        // Анимация для скрытия viewToHide
        ObjectAnimator hideAnimator = ObjectAnimator.ofFloat(viewToHide, "alpha", 1f, 0f);
        hideAnimator.setDuration(300); // Длительность анимации
        hideAnimator.start();

        // Анимация для показа viewToShow
        animateJellyEffect(viewToShow); // Применяем "желеобразную" анимацию

        // Устанавливаем видимость после завершения анимации
        hideAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToHide.setVisibility(View.GONE);
                viewToShow.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadSchedule() {
         // Очищаем контейнер перед загрузкой новых данных
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(API_URL)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        showError("Ошибка загрузки: " + response.code());
                        return;
                    }

                    jsonDataOfSite = response.body().string();
                    parseSchedule(jsonDataOfSite);
                }
            } catch (IOException e) {
                showError("Ошибка подключения: " + e.getMessage());
            }
        });
    }

    // Функция для конвертации номера дня недели в название
    private String getDayName(int weekday) {
        String[] days = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
        return (weekday >= 0 && weekday < days.length) ? days[weekday] : "Неизвестный день";
    }

    private String determineParaNumber(String time) {
        if (time.contains("08:30") || time.contains("10:05")) {
            return "1";
        } else if (time.contains("10:20") || time.contains("11:55")) {
            return "2";
        } else if (time.contains("12:45") || time.contains("14:20")) {
            return "3";
        } else if (time.contains("14:35") || time.contains("16:05")) {
            return "4";
        } else if (time.contains("16:20") || time.contains("17:75")) {
            return "5";
        } else {
            return "6";
        }
    }

    private boolean[] hasLessons = new boolean[7]; // Индекс 0 - понедельник, 6 - воскресенье

    private void parseSchedule(String jsonData) {
        runOnUiThread(() -> {
            try {
                Arrays.fill(hasLessons, false);
                JSONObject root = new JSONObject(jsonData);
                JSONArray faculties = root.optJSONArray("faculties");
                if (faculties == null) return;

                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                if (ClearData) {
                    for (int kkk = 1; kkk <= 6; kkk++) {
                        String containerIdkkk = "scheduleContainer" + getDayId(kkk);
                        LinearLayout containerkkk = findViewById(getResources().getIdentifier(containerIdkkk, "id", getPackageName()));
                        if (containerkkk == null) continue;

                        // Удаляем только динамические CardView с тегом
                        for (int i = containerkkk.getChildCount() - 1; i >= 0; i--) {
                            View child = containerkkk.getChildAt(i);
                            if (child instanceof CardView && "dynamicParaCard".equals(child.getTag())) {
                                containerkkk.removeViewAt(i);
                            }
                        }

                        Log.d("ClearData", "Cleared day " + kkk);
                    }

                    paraContainers.clear();
                    ClearData = false;
                }

                // Обработка JSON...
                for (int i = 0; i < faculties.length(); i++) {
                    JSONObject faculty = faculties.optJSONObject(i);
                    if (faculty == null) continue;

                    JSONArray groups = faculty.optJSONArray("groups");
                    if (groups == null) continue;

                    for (int j = 0; j < groups.length(); j++) {
                        JSONObject group = groups.optJSONObject(j);
                        if (group == null) continue;

                        String groupName = group.optString("name", "");
                        if (!"17В11".equals(groupName)) continue;

                        JSONArray lessons = group.optJSONArray("lessons");
                        if (lessons == null) continue;

                        for (int k = 0; k < lessons.length(); k++) {
                            JSONObject lesson = lessons.optJSONObject(k);
                            if (lesson == null) continue;

                            JSONObject dateObj = lesson.optJSONObject("date");
                            if (dateObj == null) continue;

                            int weekday = dateObj.optInt("weekday", -1);
                            String startDateStr = dateObj.optString("start", "");
                            String endDateStr = dateObj.optString("end", "");
                            try {
                                Date startDate = sdf.parse(startDateStr);
                                if (startDate == null) continue;

                                Calendar lessonCalendar = Calendar.getInstance();
                                lessonCalendar.setTime(startDate);
                                int lessonWeek = lessonCalendar.get(Calendar.WEEK_OF_YEAR);
                                int lessonYear = lessonCalendar.get(Calendar.YEAR);

                                // Фильтрация по выбранной неделе и году
                                if (lessonWeek != selectedWeek || lessonYear != selectedYear) {
                                    continue;
                                }
                            } catch (ParseException e) {
                                continue;
                            }

                            String subject = lesson.optString("subject", "Неизвестный предмет");
                            String lessonType = lesson.optString("type", "Неизвестный тип");
                            int subgroups = lesson.optInt("subgroups", 0);

                            JSONObject time = lesson.optJSONObject("time");
                            startTime = (time != null) ? time.optString("start", "00:00") : "00:00";
                            String endTime = (time != null) ? time.optString("end", "00:00") : "00:00";

                            String dayOfWeek = getDayName(weekday);
                            if ("Неизвестный день".equals(dayOfWeek)) continue;

                            JSONArray audiences = lesson.optJSONArray("audiences");
                            JSONArray teachers = lesson.optJSONArray("teachers");

                            String audience = "Неизвестная";
                            if (audiences != null && audiences.length() > 0) {
                                JSONObject audienceObj = audiences.optJSONObject(0);
                                if (audienceObj != null) audience = audienceObj.optString("name", "Неизвестная");
                            }

// Обработка преподавателя
                            String teacher = "Неизвестный";
                            if (teachers != null && teachers.length() > 0) {
                                JSONObject teacherObj = teachers.optJSONObject(0);
                                if (teacherObj != null) teacher = teacherObj.optString("name", "Неизвестный");
                            }
                            String subgroupsTpue;
                            if (subject != "Неизвестный предмет" || subject != null) {
                                hasLessons[weekday-1] = true;
                            }
                            // Форматирование времени
                            String timeText = startTime + " - " + endTime;
                            if (subgroups != 0) {
                                subgroupsTpue = "Подгруппа: "+subgroups;
                            } else subgroupsTpue ="";
                            // Вызываем метод с отдельными параметрами
                            addLessonToUI(subject, lessonType, subgroupsTpue, timeText, audience, teacher, weekday);
                            Log.d(TAG, "subject: "+subject+" lessonType: "+ lessonType+" subgroupsTpue: "+ subgroupsTpue+" timeText: "+ timeText+" audience: "+
                                    audience+" teacher: "+ teacher+" weekday: "+ weekday );
                            endDayStr = Integer.parseInt(endDateStr.split("\\.")[0]);
                            startDayStr = Integer.parseInt(startDateStr.split("\\.")[0]);
                            endMStr = Integer.parseInt(endDateStr.split("\\.")[1]);
                        }
                    }
                }
                updateDayVisibility(endDayStr, startDayStr, endMStr);
            } catch (JSONException e) {
                showError("Ошибка разбора данных: " + e.getMessage());
            }
        });
    }

    private void updateDayVisibility(int endDayStr, int startDayStr, int endMStr) {
        // Получаем текущий день недели (1 - воскресенье, 2 - понедельник, ..., 7 - суббота)
        Calendar calendar = Calendar.getInstance();
        int currentDay = calendar.get(Calendar.DAY_OF_WEEK);
        Log.d("currentData" , "currentData: " + currentData);
        // Приводим к нужному формату (в твоем коде: 1 - понедельник, ..., 6 - суббота)
        int adjustedDay = currentDay - 1;
        if (adjustedDay == 0) adjustedDay = 7; // Воскресенье
        int[] dayIds = {
                R.id.scheduleContainerMonday,
                R.id.scheduleContainerTuesday,
                R.id.scheduleContainerWednesday,
                R.id.scheduleContainerThursday,
                R.id.scheduleContainerFriday,
                R.id.scheduleContainerSaturday,
                R.id.scheduleContainerSunday
        };



        for (int i = 0; i < dayIds.length; i++) {

            // Находим LinearLayout для дня
            LinearLayout dayContainer = findViewById(dayIds[i]);
            if (dayContainer != null) {
                // Находим родительский CardView
                CardView dayCard = (CardView) dayContainer.getParent();
                if (dayCard != null) {
                    // Обновляем видимость CardView
                    dayCard.setVisibility(hasLessons[i] ? View.VISIBLE : View.GONE);
                    Log.d("ScheduleActivity", "Day " + i + " has lessons: " + hasLessons[i]);
                    Log.d("ScheduleActivity", "Day " + i + " visibility: " + (hasLessons[i] ? "VISIBLE" : "GONE"));
                    dayCard.setCardElevation(2f);
                    dayCard.setBackgroundResource(R.drawable.card);
                }
            }
        }



        Log.d("updateDayVisibility", "currentData: "+ currentData+ " endDayStr: "+endDayStr+" startDayStr: "+ startDayStr+" currentDatam: "+ currentDatam+" endMStr: "+ endMStr);
        if (adjustedDay >= 1 && adjustedDay <= 7) {
            LinearLayout todayContainer = findViewById(dayIds[adjustedDay - 1]);
            if (todayContainer != null) {
                CardView todayCard = (CardView) todayContainer.getParent();
                if (todayCard != null) {
                    if (currentData <= endDayStr && currentData >= startDayStr && endMStr == currentDatam) {

                    todayCard.setCardElevation(8f); // Увеличиваем "поднятие" для выделенного дня
                    todayCard.setBackgroundResource(R.drawable.border); // Устанавливаем рамку
                    }
                }
            }
        }

    }
    // Метод для добавления пары в UI
    private void addLessonToUI(String subject, String type, String subgroups, String time, String audience, String teacher, int weekday) {
        // Находим контейнер по дню недели
        String containerId = "scheduleContainer" + getDayId(weekday);
        LinearLayout container = findViewById(getResources().getIdentifier(containerId, "id", getPackageName()));
        if (container == null) return;
        // Формируем уникальный ключ для хранения контейнера пары
        String key = weekday + "_" + time;

        // Ищем lessonContainer только в рамках этого дня и времени
        LinearLayout lessonContainer = paraContainers.get(key);

        // Если не существует, создаем новый CardView для пары
        if (lessonContainer == null) {
            // Создаем новую CardView для пары (на основе XML-разметки)
            CardView paraCard = (CardView) LayoutInflater.from(this)
                    .inflate(R.layout.para_card_template, container, false);
            paraCard.setTag("dynamicParaCard"); // Добавляем тег

            // Находим контейнер для уроков внутри этой пары
            lessonContainer = paraCard.findViewById(R.id.LinearLayout_para);

            // Устанавливаем текст номера пары
            TextView paraText = paraCard.findViewById(R.id.para);
            paraText.setText(determineParaNumber(time) + " пара");

            // Добавляем новую CardView для пары в контейнер
            container.addView(paraCard);

            // Сохраняем ссылку на контейнер для этого времени
            paraContainers.put(key, lessonContainer);
        }

// Создаем новую CardView для урока (на основе XML-разметки)
        CardView lessonCard = (CardView) LayoutInflater.from(this)
                .inflate(R.layout.lesson_card_template, lessonContainer, false);

// Устанавливаем тег для идентификации
        lessonCard.setTag("lessonCard");

        // Заполняем данные
        TextView subgroupsTV = lessonCard.findViewById(R.id.para);
        TextView subjectTV = lessonCard.findViewById(R.id.subject);
        TextView timeTV = lessonCard.findViewById(R.id.time);
        TextView audiencesTV = lessonCard.findViewById(R.id.audiences);
        TextView lessonTypeTV = lessonCard.findViewById(R.id.lessonType);
        TextView teachersTV = lessonCard.findViewById(R.id.teachers);

        subgroupsTV.setText(subgroups);
        subjectTV.setText(subject);
        timeTV.setText(time);
        audiencesTV.setText(audience);
        lessonTypeTV.setText(type);
        teachersTV.setText(teacher);

        // Добавляем CardView в контейнер
        lessonContainer.addView(lessonCard);
    }
    // Вспомогательный метод для получения идентификатора дня
    private String getDayId(int weekday) {
        switch (weekday) {
            case 1: return "Monday";
            case 2: return "Tuesday";
            case 3: return "Wednesday";
            case 4: return "Thursday";
            case 5: return "Friday";
            case 6: return "Saturday";
            default: return "";
        }
    }

    private void updateDates() {
        // Получаем текущую дату для выбранной недели
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.WEEK_OF_YEAR, selectedWeek);
        calendar.set(Calendar.YEAR, selectedYear);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

        // Отображаем даты
        String currentDate = sdf.format(calendar.getTime());
        CurrectData.setText(currentDate);  // Текущая неделя
        compactCurrentDate.setText(currentDate);  // Текущая неделя

        // Предыдущая неделя
        calendar.add(Calendar.WEEK_OF_YEAR, -1);
        String previousDate = sdf.format(calendar.getTime());
        dataPrevious.setText(previousDate);

        // Следующая неделя
        calendar.add(Calendar.WEEK_OF_YEAR, 2);  // Добавляем 2 недели для следующей
        String nextDate = sdf.format(calendar.getTime());
        dataNext.setText(nextDate);
    }
    // Открытие расписания группы
    private void showGroupSchedule(GroupSchedule schedule) {
        Intent intent = new Intent(this, GroupScheduleActivity.class);
        intent.putExtra("schedule", schedule.getSchedule());
        startActivity(intent);
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.e(TAG, message);
        });
    }

    public static class GroupSchedule {
        private final String groupName;
        private final String schedule;

        public GroupSchedule(String groupName, String schedule) {
            this.groupName = groupName;
            this.schedule = schedule;
        }

        public String getGroupName() {
            return groupName;
        }

        public String getSchedule() {
            return schedule;
        }
    }
}