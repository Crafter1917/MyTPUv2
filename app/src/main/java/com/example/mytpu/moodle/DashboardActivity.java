package com.example.mytpu.moodle;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DashboardActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "DashboardActivity";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";

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
        setContentView(R.layout.activity_dashboard); // Только один вызов!

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

        // Связывание компонентов

        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);


        setContentView(R.layout.activity_dashboard);

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
        token = sharedPreferences.getString("token", null);
        Log.d("Token", "Token retrieved: " + token);
        if (token != null) {
            loadUserData();
        } else {
            navigateToMainActivity();
        }
    }


    private void loadUserData() {
        executor.execute(() -> {
            try {
                // Получение информации о пользователе
                JSONObject userInfo = getSiteInfo();
                if (userInfo != null) {
                    String userName = userInfo.optString("fullname", "Пользователь");
                    int userId = userInfo.getInt("userid");

                    // Получение списка курсов
                    JSONArray courses = getUserCourses(userId);
                    List<Course> courseList = parseCourses(courses);

                    runOnUiThread(() -> {
                        welcomeTextView.setText("Добро пожаловать, " + userName + "!");
                        updateCoursesUI(courseList);
                    });
                }
            } catch (JSONException e) {
                handleError("Ошибка формата данных", e);
            } catch (IOException e) {
                handleError("Ошибка сети", e);
            }
        });
    }
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_dashboard) {
            // Уже находимся на этом экране
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
            String url = "https://stud.lms.tpu.ru/course/view.php?id=" + id;
            courses.add(new Course(name, url, id));
        }
        return courses;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void updateCoursesUI(List<Course> courses) {
        coursesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

            // Основные учетные данные
            SharedPreferences mainPrefs = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            // Почтовые учетные данные в том же хранилище
            SharedPreferences mailPrefs = EncryptedSharedPreferences.create(
                    "mail_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            // Сохраняем токен Moodle и для почты
            token = mainPrefs.getString("token", null);
            mailPrefs.edit()
                    .putString("mail_token", token)
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "Error accessing secure storage", e);
            showToast("Ошибка доступа к хранилищу");
            finish();
        }
        for (Course course : courses) {
            // Инфлейтим кастомный макет вместо стандартной кнопки
            View courseItem = inflater.inflate(R.layout.item_course, coursesContainer, false);

            // Находим элементы
            TextView title = courseItem.findViewById(R.id.course_title);
            ImageView icon = courseItem.findViewById(R.id.course_icon);
            View container = courseItem.findViewById(R.id.course_container);

            // Устанавливаем данные
            title.setText(course.getName());

            // Иконка в зависимости от типа курса (пример)
            icon.setImageResource(R.drawable.ic_book);

            // Клик-лисенер
            container.setOnClickListener(v -> {
                Intent intent = new Intent(DashboardActivity.this, CourseActivity.class);
                intent.putExtra("courseId", course.getId());
                intent.putExtra("courseName", course.getName());
                startActivity(intent);
            });

            // Добавляем анимацию при нажатии
            container.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start();
                } else if (event.getAction() == MotionEvent.ACTION_UP ||
                        event.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                }
                return false;
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

        public Course(String name, String url, int id) {
            this.name = name;
            this.url = url;
            this.id = id;
        }

        public String getName() { return name; }
        public int getId() { return id; }
    }
}