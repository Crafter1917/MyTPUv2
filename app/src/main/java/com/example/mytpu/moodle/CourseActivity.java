package com.example.mytpu.moodle;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

import com.example.mytpu.MainScreen;
import com.example.mytpu.MyApplication;
import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
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
    private TextView courseTitle;
    private ProgressBar progressBar;
    private RecyclerView contentRecyclerView;
    private OkHttpClient client;
    private String token;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course);

        initializeViews();

        int courseId = getIntent().getIntExtra("courseId", -1);
        String courseName = getIntent().getStringExtra("courseName");

        if (courseId == -1 || courseName == null) {
            Toast.makeText(this, "Ошибка: данные курса не получены", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        courseTitle.setText(courseName);

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
        courseTitle = findViewById(R.id.courseTitle);
        progressBar = findViewById(R.id.progressBar);
        contentRecyclerView = findViewById(R.id.contentRecyclerView);
    }

    private void setupRecyclerView() {
        contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        contentAdapter = new CourseContentAdapter(new ArrayList<>());
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

    private List<CourseContentAdapter.CourseSection> parseCourseSections(JSONArray sections) throws JSONException {
        List<CourseContentAdapter.CourseSection> courseSections = new ArrayList<>();

        for (int i = 0; i < sections.length(); i++) {

            JSONObject section = sections.getJSONObject(i);
            JSONArray modules = section.getJSONArray("modules");
            List<ModuleAdapter.CourseModule> courseModules = new ArrayList<>();

            for (int j = 0; j < modules.length(); j++) {
                JSONObject module = modules.getJSONObject(j);
                int cmid = module.optInt("coursemodule", module.getInt("id")); // ✅
                int instanceId = module.getInt("instance");
                int courseId = getIntent().getIntExtra("courseId", -1); // Получаем courseId из Intent

                String modName = module.getString("modname");

                ModuleAdapter.CourseModule courseModule = new ModuleAdapter.CourseModule(
                        cmid,
                        instanceId,
                        module.getString("name"),
                        modName,
                        module.optString("url", ""),
                        module.optString("description", ""),
                        courseId // Добавляем courseId в конструктор

                );
                courseModules.add(courseModule);
                Log.d("parseCourseSections",
                        String.format("Added CourseModule: cmid=%d, instanceId=%d, name='%s', modName='%s', courseId=%d",
                                cmid,
                                instanceId,
                                module.getString("name"),
                                modName,
                                courseId));
            }
            courseSections.add(new CourseContentAdapter.CourseSection(section.getString("name"), courseModules));
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