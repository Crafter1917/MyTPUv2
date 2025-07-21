package com.example.mytpu.moodle;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.MainScreen;
import com.example.mytpu.MyApplication;
import com.example.mytpu.R;
import com.example.mytpu.portalTPU.PortalAuthHelper;
import com.example.mytpu.schedule.ScheduleActivity;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DashboardActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "DashboardActivity";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    private static final String MOODLE_LOGIN_URL = "https://stud.lms.tpu.ru/login/index.php";
    private static final String MOODLE_PROFILE_URL = "https://stud.lms.tpu.ru/my/";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "moodle_notifications";
    private static final long POLL_INTERVAL = TimeUnit.MINUTES.toMillis(5); // 5 минут
    private Handler notificationHandler;
    private Set<Integer> shownNotificationIds = new HashSet<>();

    // Добавленные элементы для Drawer
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ActionBarDrawerToggle drawerToggle;

    private LinearLayout recentcoursesContainer;
    private TextView welcomeTextView;
    private LinearLayout coursesContainer;
    private OkHttpClient client;
    private TextView logTextView;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor;
    private String token;
    private PortalAuthHelper portalAuthHelper;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Инициализация Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Настройка DrawerLayout
        drawerLayout = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this,
                drawerLayout,
                findViewById(R.id.toolbar),
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
        );

        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        coursesContainer = findViewById(R.id.coursesContainer);
        welcomeTextView = findViewById(R.id.welcomeTextView);
        recentcoursesContainer = findViewById(R.id.recentcoursesContainer);
        client = ((MyApplication) getApplication()).getClient();

        logTextView = findViewById(R.id.logTextView);
        portalAuthHelper = new PortalAuthHelper(this);

        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            token = sharedPreferences.getString("token", null);
        } catch (Exception e) {
            Log.e(TAG, "Error accessing secure storage", e);
            showToast("Ошибка доступа к безопасному хранилищу");
            finish();
        }

        executor = Executors.newSingleThreadExecutor();
        Log.d("Token", "Token retrieved: " + token);


        if (token != null) {
            loadUserData();
        } else {
            navigateToMainActivity();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }


    private void loadUserData() {
        executor.execute(() -> {
            try {
                // Получение информации о пользователе
                JSONObject userInfo = getSiteInfo();
                if (userInfo != null && userInfo.has("userid")) { // Исправлено
                    String userName = userInfo.optString("fullname", "Пользователь");
                    int userId = userInfo.getInt("userid");

                    // Получение списка курсов
                    JSONArray courses = getUserCourses(userId);
                    List<Course> courseList = parseCourses(courses);

                    // Получаем sesskey
                    String sesskey = getSesskey();
                    Log.d(TAG, "Полученный sesskey: " + sesskey);

                    // Сохраняем sesskey в SharedPreferences
                    sharedPreferences.edit().putString("sesskey", sesskey).apply();
                    runOnUiThread(() -> {
                        welcomeTextView.setText("Добро пожаловать, " + userName + "!");
                        updateCoursesUI(courseList);
                    });
                }
            } catch (JSONException e) {
                handleError("Ошибка формата данных", e);
            } catch (IOException e) {
                handleError("Ошибка сети", e);
            } catch (Exception e) {
                handleError("Ошибка при получении sesskey", e);
            }
        });
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Убираем очистку кук здесь!
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
    private String getSesskey() throws Exception {
        // Получаем логин и пароль из защищенного хранилища
        String username = sharedPreferences.getString("username", null);
        String password = sharedPreferences.getString("password", null);

        if (username == null || password == null) {
            throw new Exception("Логин или пароль не найдены");
        }

        // Шаг 1: Получаем страницу логина
        Request loginPageRequest = new Request.Builder()
                .url(MOODLE_LOGIN_URL)
                .build();

        try (Response response = client.newCall(loginPageRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка при загрузке страницы входа: " + response.code());
            }

            String html = response.body().string();
            Document doc = Jsoup.parse(html);

            // Проверяем, не авторизованы ли мы уже
            Element sesskeyElement = doc.selectFirst("input[name=sesskey]");
            if (sesskeyElement != null) {
                return sesskeyElement.attr("value");
            }

            // Если не авторизованы - получаем logintoken
            Element tokenElement = doc.selectFirst("input[name=logintoken]");
            if (tokenElement == null) {
                throw new Exception("Logintoken не найден. Возможно, изменилась структура страницы");
            }

            String logintoken = tokenElement.attr("value");

            // Шаг 2: Выполняем вход
            RequestBody formBody = new FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .add("logintoken", logintoken)
                    .add("anchor", "")
                    .build();

            Request loginRequest = new Request.Builder()
                    .url(MOODLE_LOGIN_URL)
                    .post(formBody)
                    .build();

            try (Response loginResponse = client.newCall(loginRequest).execute()) {
                // Проверяем редирект
                if (loginResponse.isRedirect()) {
                    String redirectUrl = loginResponse.header("Location");
                    if (redirectUrl != null) {
                        return getSesskeyFromUrl(redirectUrl);
                    }
                }

                if (!loginResponse.isSuccessful()) {
                    throw new IOException("Ошибка при входе: " + loginResponse.code());
                }

                // Шаг 3: Получаем страницу профиля
                return getSesskeyFromUrl(MOODLE_PROFILE_URL);
            }
        }
    }
    private String getSesskeyFromUrl(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка загрузки страницы: " + response.code());
            }

            String html = response.body().string();
            Document doc = Jsoup.parse(html);

            // Ищем sesskey в разных местах
            Element sesskeyElement = doc.selectFirst("input[name=sesskey]");
            if (sesskeyElement == null) {
                sesskeyElement = doc.selectFirst("[data-sesskey]");
            }

            if (sesskeyElement == null) {
                throw new IOException("Sesskey не найден на странице: " + url);
            }

            return sesskeyElement.attr("value") != null ?
                    sesskeyElement.attr("value") :
                    sesskeyElement.attr("data-sesskey");
        }
    }
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_dashboard) {

        } else if (id == R.id.nav_schedule) {
            startActivity(new Intent(this, ScheduleActivity.class));
        } else if (id == R.id.nav_mail) {
            startActivity(new Intent(this, com.example.mytpu.mailTPU.MailActivity.class));
        } else if (id == R.id.nav_logout) {
            executor.execute(this::logout);
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
    private JSONObject getSiteInfo() throws IOException, JSONException {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_webservice_get_site_info")
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            return new JSONObject(responseBody);
        }
    }

    private JSONArray getUserCourses(int userId) throws IOException, JSONException {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_enrol_get_users_courses")
                .addQueryParameter("userid", String.valueOf(userId))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            return new JSONArray(responseBody);
        }
    }

    private List<Course> parseCourses(JSONArray coursesJson) throws JSONException {
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < coursesJson.length(); i++) {
            JSONObject course = coursesJson.getJSONObject(i);
            String name = course.getString("fullname");
            int id = course.getInt("id");
            int progress = 0;
            if (course.has("progress") && !course.isNull("progress")) {
                progress = course.getInt("progress");
            }
            Log.d(TAG, "progress=" +progress);
            courses.add(new Course(name, "", id, progress));
        }
        return courses;
    }
    private void updateCoursesUI(List<Course> courses) {
        coursesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (Course course : courses) {
            View courseItem = inflater.inflate(R.layout.item_course, coursesContainer, false);

            TextView title = courseItem.findViewById(R.id.course_title);
            ImageView icon = courseItem.findViewById(R.id.course_icon);
            ProgressBar progressBar = courseItem.findViewById(R.id.progressBar); // Получаем ProgressBar
            TextView progressText = courseItem.findViewById(R.id.progressText); // Получаем TextView для процентов
            View container = courseItem.findViewById(R.id.course_container);

            title.setText(course.getName());
            icon.setImageResource(R.drawable.ic_book);

            // Устанавливаем прогресс
            int progress = course.getProgress();
            progressBar.setProgress(progress);
            progressText.setText(String.format(Locale.getDefault(), "%d%%", progress));

            container.setOnClickListener(v -> {
                Intent intent = new Intent(DashboardActivity.this, CourseActivity.class);
                intent.putExtra("courseId", course.getId());
                intent.putExtra("courseName", course.getName());
                startActivity(intent);
            });

            coursesContainer.addView(courseItem);
        }
    }

    private void logout() {
        try {
            // Отзыв токена через API
            HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "auth_email_request_logout")
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request request = new Request.Builder().url(url).build();
            client.newCall(request).execute().close();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка выхода: ", e);
        }

        // Очистка данных
        sharedPreferences.edit().clear().apply();
        clearCookies();

        runOnUiThread(() -> {
            showToast("Выход выполнен");
            navigateToMainActivity();
        });
    }

    private void clearCookies() {
        client = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {}

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        return new ArrayList<>();
                    }
                })
                .build();
    }

    private void handleError(String message, Exception e) {
        Log.e(TAG, message, e);
        runOnUiThread(() -> {
            showToast(message);
            if (e instanceof JSONException) navigateToMainActivity();
        });
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void navigateToMainActivity() {
        startActivity(new Intent(this, MainScreen.class));
        finish();
    }

    public static class Course {
        private final String name;
        private final String url;
        private final int id;
        private final int progress; // Добавляем поле прогресса
        public Course(String name, String url, int id, int progress) {
            this.name = name;
            this.url = url;
            this.id = id;
            this.progress = progress;
        }

        public String getName() { return name; }
        public int getId() { return id; }
        public int getProgress() { return progress; }
    }
}