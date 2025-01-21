package com.example.mytpu;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DashboardActivity extends AppCompatActivity {

    private static final String TAG = "DashboardActivity";
    private LinearLayout recentcoursesContainer;
    private TextView welcomeTextView;
    private LinearLayout coursesContainer;
    private OkHttpClient client;
    private TextView logTextView;
    private Button logoutButton;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor;
    private String sesskey;
    static final String LOGIN_URL = "https://stud.lms.tpu.ru/my";
    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        coursesContainer = findViewById(R.id.coursesContainer);
        welcomeTextView = findViewById(R.id.welcomeTextView);
        recentcoursesContainer = findViewById(R.id.recentcoursesContainer);
        client = ((MyApplication) getApplication()).getClient();

        logTextView = findViewById(R.id.logTextView);
        logoutButton = findViewById(R.id.logoutButton);

        // Настройка безопасного хранилища
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Toast.makeText(this, "Ошибка инициализации безопасного хранилища", Toast.LENGTH_LONG).show();
            return;
        }

        executor = Executors.newSingleThreadExecutor();

        // Проверка сохраненных данных для автовхода
        if (sharedPreferences.contains("username") && sharedPreferences.contains("password")) {
            executor.execute(this::autoLogin);
        } else {
            navigateToMainActivity();
        }

        // Настройка кнопки выхода
        logoutButton.setOnClickListener(v -> {
            executor.execute(this::logout);
        });
    }

    private void addLog(String message) {
        runOnUiThread(() -> logTextView.append(message + "\n"));
    }

    private void login(String username) {
        try {
            addLog("Начало логина для пользователя: " + username);

            Request getRequest = new Request.Builder()
                    .url(LOGIN_URL)
                    .build();

            try (Response getResponse = client.newCall(getRequest).execute()) {
                if (!getResponse.isSuccessful()) {
                    throw new IOException("Ошибка подключения: " + getResponse.code());
                }

                String responseBody = getResponse.body() != null ? getResponse.body().string() : "";
                if (responseBody.isEmpty()) {
                    throw new IOException("Получено пустое тело ответа.");
                }
                try {
                    Document document = Jsoup.parse(responseBody);
                    Element sesskeyElement = document.selectFirst("input[name=sesskey]");
                    if (sesskeyElement != null) {
                        sesskey = sesskeyElement.attr("value");
                        addLog("Sesskey извлечен: " + sesskey);
                    }

                    Element userElement = document.selectFirst("i.icon.slicon.slicon-user");
                    String userName = userElement != null ? userElement.attr("title") : "Имя не найдено";
                    Log.d(TAG, "Имя пользователя: " + userName);

                    Elements courseElements = document.select("li.list-group-item.list-group-item-action[data-parent-key=mycourses]").not("[data-key=courseindexpage]");
                    Elements recentCourseElements = document.select("div.recent-courses .card");



                    //курсы и недавние курсы
                    List<Course> courses = new ArrayList<>();
                    List<recentCourse> recentcourses = new ArrayList<>();
                    // недавние курсы
                    for (Element element : recentCourseElements) {
                        Element recentlinkElement = element.selectFirst("a");
                        Element recentcourseNameElement = element.selectFirst("span");

                        if (recentlinkElement != null && recentcourseNameElement != null ) {
                            String recentcourseName = recentcourseNameElement.text();
                            String recentcourseUrl = recentlinkElement.attr("href");
                            recentcourses.add(new recentCourse(recentcourseName, recentcourseUrl));
                            Log.d(TAG, "недавние Курс найден: " + recentcourseName + " - " + recentcourseUrl);
                        } else Log.d(TAG, "недавние Курс не найден ");
                    }
                    //курсы
                    for (Element element : courseElements) {
                        Element linkElement = element.selectFirst("a[href]");
                        Element courseNameElement = element.selectFirst("span.media-body");

                        if (linkElement != null && courseNameElement != null ) {
                            String courseName = courseNameElement.text();
                            String courseUrl = linkElement.attr("href");
                            courses.add(new Course(courseName, courseUrl));
                            Log.d(TAG, "Курс найден: " + courseName + " - " + courseUrl);
                        }
                    }

                    runOnUiThread(() -> updateUI(userName, courses, recentcourses));

                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки HTML: ", e);
                    runOnUiThread(() -> Toast.makeText(DashboardActivity.this, "Ошибка обработки данных.", Toast.LENGTH_SHORT).show());
                }


                            }
        } catch (IOException e) {
            addLog("Ошибка: " + e.getMessage());
        }
    }

    private void autoLogin() {
        String username = sharedPreferences.getString("username", null);
        String password = sharedPreferences.getString("password", null);

        if (username != null && password != null) {
            addLog("Выполняется автовход для пользователя: " + username);
            login(username);
        } else {
            navigateToMainActivity();
        }
    }

    private void logout() {
        executor.execute(() -> {
            try {
                addLog("Начинается выход из системы...");
                // URL выхода с использованием sesskey
                String logoutUrl = "https://stud.lms.tpu.ru/login/logout.php?sesskey=" + sesskey;

                // Создание запроса для выхода
                Request logoutRequest = new Request.Builder()
                        .url(logoutUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Connection", "keep-alive")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .header("Referer", "https://stud.lms.tpu.ru/my/")
                        .build();

                try (Response response = client.newCall(logoutRequest).execute()) {
                    if (response.isSuccessful()) {
                        addLog("Выход из системы успешно выполнен на сервере.");
                    } else {
                        addLog("Ошибка выхода из системы: " + response.code());
                    }
                }

            } catch (IOException e) {
                addLog("Ошибка при выполнении запроса выхода: " + e.getMessage());
            }

            // Очистка локальных данных
            sharedPreferences.edit().clear().apply();

            // Очистка куки, чтобы удалить сессионные данные
            client = new OkHttpClient.Builder()
                    .cookieJar(new CookieJar() {
                        @Override
                        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                            // Куки не сохраняются
                        }

                        @Override
                        public List<Cookie> loadForRequest(HttpUrl url) {
                            return new ArrayList<>();
                        }
                    })
                    .build();

            // Обновление UI
            runOnUiThread(() -> {
                Toast.makeText(this, "Выход выполнен. Данные удалены.", Toast.LENGTH_SHORT).show();
                navigateToMainActivity();
            });

            addLog("Локальные данные и куки очищены.");
        });
    }


    private void navigateToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }


    private void updateUI(String userName, List<Course> courses, List<recentCourse> recentcourses) {
        welcomeTextView.setText("Добро пожаловать, " + userName + "!");
        coursesContainer.removeAllViews();
        recentcoursesContainer.removeAllViews();

        // курсы
        if (courses.isEmpty()) {
            Log.d(TAG, "Список курсов пуст.");
        } else {
        for (Course course : courses) {
            Button courseButton = new Button(this);
            courseButton.setText(course.getName());
            courseButton.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(course.getUrl()));
                startActivity(browserIntent);
            });
            coursesContainer.addView(courseButton);
            Log.d(TAG,courses.toString());
        }}

        // недавние курсы
        if (recentcourses.isEmpty()) {
            Log.d(TAG, "Список недавних курсов пуст.");
        } else {
            for (recentCourse recentcourse : recentcourses) {
                Button recentcourseButton = new Button(this);
                recentcourseButton.setText(recentcourse.getName());
                recentcourseButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(recentcourse.getUrl()));
                    startActivity(browserIntent);
                });
                recentcoursesContainer.addView(recentcourseButton);
            }
        }

    }

    public static class Course {
        private final String name;
        private final String url;

        public Course(String name, String url) {
            this.name = name;
            this.url = url;
        }


        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class recentCourse {
        private final String name;
        private final String url;

        public recentCourse(String name, String url) {
            this.name = name;
            this.url = url;
        }


        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }
}
