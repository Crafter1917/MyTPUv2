package com.example.mytpu.moodle;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.MainActivity;
import com.example.mytpu.MyApplication;
import com.example.mytpu.R;
import com.example.mytpu.schedule.ScheduleActivity;

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

public class DashboardActivity extends AppCompatActivity {

    private static final String TAG = "DashboardActivity";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    private Button mailButton;
    private LinearLayout recentcoursesContainer;
    private TextView welcomeTextView;
    private LinearLayout coursesContainer;
    private OkHttpClient client;
    private TextView logTextView;
    private Button logoutButton;
    private Button scheduleButton;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor;
    private String token;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        mailButton = findViewById(R.id.button_mail);
        mailButton.setOnClickListener(v -> executor.execute(this::openMail));

        coursesContainer = findViewById(R.id.coursesContainer);
        welcomeTextView = findViewById(R.id.welcomeTextView);
        recentcoursesContainer = findViewById(R.id.recentcoursesContainer);
        client = ((MyApplication) getApplication()).getClient();

        logTextView = findViewById(R.id.logTextView);
        logoutButton = findViewById(R.id.logoutButton);
        scheduleButton = findViewById(R.id.button_schedule);

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

        logoutButton.setOnClickListener(v -> executor.execute(this::logout));
        scheduleButton.setOnClickListener(v -> executor.execute(this::schedule));
    }
    private void openMail() {
        runOnUiThread(() -> {
            Intent intent = new Intent(DashboardActivity.this, com.example.mytpu.mailTPU.MailActivity.class);
            startActivity(intent);
        });
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

    private void updateCoursesUI(List<Course> courses) {
        coursesContainer.removeAllViews();
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
        coursesContainer.removeAllViews();
        for (Course course : courses) {
            Button courseButton = new Button(this);
            courseButton.setText(course.getName());
            // В DashboardActivity
            courseButton.setOnClickListener(v -> {
                Intent intent = new Intent(DashboardActivity.this, CourseActivity.class);
                intent.putExtra("courseId", course.getId());
                intent.putExtra("courseName", course.getName());
                startActivity(intent);
            });
            coursesContainer.addView(courseButton);
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

    private void schedule() {
        startActivity(new Intent(this, ScheduleActivity.class));
        finish();
    }

    private void navigateToMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
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