package com.example.mytpu.moodle;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.example.mytpu.MainScreen;
import com.example.mytpu.MyApplication;
import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CourseActivity extends AppCompatActivity {
    private CourseContentAdapter contentAdapter;
    private static final String TAG = "CourseActivity";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    private SharedPreferences sharedPreferences;

    private ProgressBar progressBar;
    private RecyclerView contentRecyclerView;
    private OkHttpClient client;
    private String token;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course);

        Log.d("CourseActivity", "Starting CourseActivity");

        int courseId = getIntent().getIntExtra("courseId", -1);
        String courseName = getIntent().getStringExtra("courseName");

        Log.d("CourseActivity", "Received courseId: " + courseId);
        Log.d("CourseActivity", "Received courseName: " + courseName);

        if (courseId == -1 || courseName == null) {
            Toast.makeText(this, "Ошибка: данные курса не получены", Toast.LENGTH_SHORT).show();
            Log.e("CourseActivity", "Invalid course data");
            finish();
            return;
        }
        initializeViews();
        if (courseId == -1 || courseName == null) {
            Toast.makeText(this, "Ошибка: данные курса не получены", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        client = ((MyApplication) getApplication()).getClient();
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
            finish();
        }

        setupRecyclerView();
        loadCourseContent(courseId);
    }

    private void initializeViews() {
        progressBar = findViewById(R.id.progressBar);
        contentRecyclerView = findViewById(R.id.contentRecyclerView);
    }

    private void setupRecyclerView() {
        contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        contentAdapter = new CourseContentAdapter(new ArrayList<>(), token);
        contentRecyclerView.setAdapter(contentAdapter);
    }

    private void loadCourseContent(int courseId) {
        progressBar.setVisibility(View.VISIBLE);
        checkAuth();
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                        .addQueryParameter("wstoken", token)
                        .addQueryParameter("wsfunction", "core_course_get_contents")
                        .addQueryParameter("courseid", String.valueOf(courseId))
                        .addQueryParameter("moodlewsrestformat", "json")
                        .build();

                Request request = new Request.Builder().url(url).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP error: " + response.code());
                    }

                    String responseBody = response.body().string();
                    JSONArray sections = new JSONArray(responseBody);
                    List<CourseContentAdapter.CourseSection> courseSections = parseCourseSections(sections);

                    runOnUiThread(() -> {
                        contentAdapter.updateData(courseSections);
                        progressBar.setVisibility(View.GONE);
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                showError("Ошибка сети");
            } catch (JSONException e) {
                Log.e(TAG, "JSON parsing error", e);
                showError("Ошибка данных");
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
                showError("Неизвестная ошибка");
            }
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(CourseActivity.this, message, Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(View.GONE);
            finish();
        });
    }

    private List<CourseContentAdapter.CourseSection> parseCourseSections(JSONArray sectionsArray) throws JSONException {
        List<CourseContentAdapter.CourseSection> courseSections = new ArrayList<>();

        // Добавляем карточку курса из первого раздела
        if (sectionsArray.length() > 0) {
            JSONObject firstSection = sectionsArray.getJSONObject(0);
            String summary = firstSection.optString("summary", "");

            // Создаем специальный раздел для карточки курса
            List<ModuleAdapter.CourseModule> courseCardList = new ArrayList<>();
            ModuleAdapter.CourseModule courseCard = new ModuleAdapter.CourseModule(
                    0,
                    0,
                    "Course Card",
                    "course_card",
                    "",
                    summary,
                    getIntent().getIntExtra("courseId", -1)
            );
            courseCardList.add(courseCard);
            courseSections.add(new CourseContentAdapter.CourseSection("Course Card", courseCardList));
        }

        for (int i = 0; i < sectionsArray.length(); i++) {
            JSONObject section = sectionsArray.getJSONObject(i);
            JSONArray modules = section.optJSONArray("modules");
            String sectionName = section.optString("name", "Без названия");

            List<ModuleAdapter.CourseModule> courseModules = new ArrayList<>();

            if (modules != null) {
                for (int j = 0; j < modules.length(); j++) {
                    JSONObject module = modules.getJSONObject(j);
                    int cmid = module.optInt("coursemodule", module.getInt("id"));
                    int instanceId = module.getInt("instance");
                    int courseId = getIntent().getIntExtra("courseId", -1);
                    String modName = module.getString("modname");
                    String name = module.optString("name", "");
                    String description = module.optString("description", "");

                    // Для меток используем текст описания как основной контент
                    if ("label".equals(modName)) {
                        name = !TextUtils.isEmpty(description) ?
                                Jsoup.parse(description).text() :
                                "Текстовый блок";
                    }

                    ModuleAdapter.CourseModule courseModule = new ModuleAdapter.CourseModule(
                            cmid,
                            instanceId,
                            name,
                            modName,
                            module.optString("url", ""),
                            description,
                            courseId
                    );
                    courseModules.add(courseModule);
                }
            }

            courseSections.add(new CourseContentAdapter.CourseSection(sectionName, courseModules));
        }
        return courseSections;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void checkAuth() {
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Требуется повторная авторизация", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, MainScreen.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        }
    }
}