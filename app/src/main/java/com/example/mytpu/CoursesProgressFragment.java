package com.example.mytpu;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.moodle.CourseActivity;
import com.example.mytpu.moodle.DashboardActivity;
import com.example.mytpu.moodle.DashboardActivity.Course;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CoursesProgressFragment extends Fragment {
    private static final String TAG = "CoursesProgressFragment";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    private boolean isDestroyed = false;
    private boolean isExpanded = false;
    private static final String CACHE_KEY = "courses_progress_cache";
    private static final long CACHE_EXPIRATION = 30 * 60 * 1000; // 30 минут
    private static final int NETWORK_TIMEOUT = 15; // секунд
    private RecyclerView coursesRecyclerView;
    private ProgressBar loadingProgressBar;
    private ProgressBar overallProgressBar;
    private TextView overallProgressText;
    private TextView coursesCountText;
    private ImageView expandIcon;
    private LinearLayout headerContainer;

    private OkHttpClient client;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = ((MyApplication) requireActivity().getApplication()).getClient();
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(NETWORK_TIMEOUT, TimeUnit.SECONDS)
                .build();
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Error accessing secure storage", e);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_courses_progress, container, false);

        coursesRecyclerView = view.findViewById(R.id.coursesRecyclerView);
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar);
        overallProgressBar = view.findViewById(R.id.overallProgressBar);
        overallProgressText = view.findViewById(R.id.overallProgressText);
        coursesCountText = view.findViewById(R.id.coursesCountText);
        expandIcon = view.findViewById(R.id.expandIcon);
        headerContainer = view.findViewById(R.id.headerContainer);

        // Исправленная строка: добавлен null для слушателя
        coursesRecyclerView.setAdapter(new CourseProgressAdapter(new ArrayList<>(), null));
        coursesRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        headerContainer.setOnClickListener(v -> toggleExpansion());
        loadCoursesProgress();

        return view;
    }

    private void toggleExpansion() {
        isExpanded = !isExpanded;

        if (isExpanded) {
            expandIcon.setRotation(180); // Поворачиваем иконку
            coursesRecyclerView.setVisibility(View.VISIBLE);
            coursesRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        } else {
            expandIcon.setRotation(0);
            coursesRecyclerView.setVisibility(View.GONE);
            coursesRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
            ));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCoursesProgress();
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
        if (executor != null) {
            executor.shutdownNow(); // Останавливаем все потоки
        }
    }

    private void loadCoursesProgress() {
        String token = sharedPreferences.getString("token", null);
        if (token == null) return;

        loadingProgressBar.setVisibility(View.VISIBLE);

        // Пытаемся загрузить из кэша
        List<Course> cachedCourses = loadFromCache();
        if (cachedCourses != null && !cachedCourses.isEmpty()) {
            updateUI(cachedCourses);
            loadingProgressBar.setVisibility(View.GONE);
        }

        executor.execute(() -> {
            // Проверяем уничтожение фрагмента перед началом работы
            if (isDestroyed || !isAdded()) return;

            try {
                JSONObject userInfo = getSiteInfo(token);
                if (userInfo == null || isDestroyed || !isAdded()) return;

                int userId = userInfo.getInt("userid");
                List<Course> courses = getCourses(token, userId);

                // Проверяем уничтожение перед сохранением в кэш
                if (isDestroyed || !isAdded()) return;
                saveToCache(courses);

                // Проверяем уничтожение перед обновлением UI
                if (getActivity() == null || isDestroyed || !isAdded()) return;

                requireActivity().runOnUiThread(() -> {
                    // Двойная проверка перед обновлением UI
                    if (getActivity() == null || isDestroyed || !isAdded()) return;

                    loadingProgressBar.setVisibility(View.GONE);
                    updateUI(courses);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading courses", e);

                // Проверяем уничтожение перед показом кэша
                if (getActivity() == null || isDestroyed || !isAdded()) return;

                // Показываем кэш при ошибке
                if (cachedCourses != null && !cachedCourses.isEmpty()) {
                    requireActivity().runOnUiThread(() -> {
                        if (getActivity() == null || isDestroyed || !isAdded()) return;

                        loadingProgressBar.setVisibility(View.GONE);
                        updateUI(cachedCourses);
                    });
                } else {
                    requireActivity().runOnUiThread(() -> {
                        if (getActivity() == null || isDestroyed || !isAdded()) return;

                        loadingProgressBar.setVisibility(View.GONE);
                        coursesCountText.setText("Ошибка загрузки");
                    });
                }
            }
        });
    }

    private List<Course> loadFromCache() {
        String cacheJson = sharedPreferences.getString(CACHE_KEY, null);
        if (cacheJson == null) return null;

        long lastUpdate = sharedPreferences.getLong(CACHE_KEY + "_time", 0);
        if (System.currentTimeMillis() - lastUpdate > CACHE_EXPIRATION) {
            return null; // Кэш устарел
        }

        try {
            JSONArray jsonArray = new JSONArray(cacheJson);
            return parseCourses(jsonArray);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing cache", e);
            return null;
        }
    }

    private void saveToCache(List<Course> courses) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (Course course : courses) {
                JSONObject jsonCourse = new JSONObject();
                jsonCourse.put("name", course.getName());
                jsonCourse.put("id", course.getId());
                jsonCourse.put("progress", course.getProgress());
                jsonArray.put(jsonCourse);
            }

            sharedPreferences.edit()
                    .putString(CACHE_KEY, jsonArray.toString())
                    .putLong(CACHE_KEY + "_time", System.currentTimeMillis())
                    .apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving cache", e);
        }
    }

    private JSONObject getSiteInfo(String token) throws IOException, JSONException {
        try {
            HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "core_webservice_get_site_info")
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request request = new Request.Builder().url(url).build();

            // Добавляем проверку прерывания перед выполнением запроса
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            try (Response response = client.newCall(request).execute()) {
                // Проверка на прерывание потока после выполнения
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }

                String responseBody = response.body().string();
                return new JSONObject(responseBody);
            }
        } catch (InterruptedException e) {
            // Ловим прерывание и возвращаем null вместо выброса исключения
            Log.d(TAG, "Request interrupted", e);
            return null;
        }
    }

    private List<Course> getCourses(String token, int userId) throws IOException, JSONException {
        try {
            HttpUrl coursesUrl = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "core_enrol_get_users_courses")
                    .addQueryParameter("userid", String.valueOf(userId))
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request coursesRequest = new Request.Builder().url(coursesUrl).build();
            try (Response response = client.newCall(coursesRequest).execute()) {
                String responseBody = response.body().string();

                // Проверка на ошибку API
                if (responseBody.trim().startsWith("{")) {
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("exception")) {
                        throw new IOException("Moodle error: " + json.optString("message", "Unknown error"));
                    }
                }

                return parseCourses(new JSONArray(responseBody));
            }
        } catch (JSONException e) {
            throw new IOException("Invalid response format", e);
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
            courses.add(new Course(name, "", id, progress));
        }
        return courses;
    }

    private void updateUI(List<Course> courses) {
        if (isDestroyed) return;
        if (courses == null || courses.isEmpty()) {
            coursesCountText.setText("Нет активных курсов");
            overallProgressBar.setProgress(0);
            overallProgressText.setText("Общий прогресс: 0%");
            coursesRecyclerView.setVisibility(View.GONE);
            return;
        }

        float totalProgress = 0;
        for (Course course : courses) {
            totalProgress += course.getProgress();
        }
        float averageProgress = totalProgress / courses.size();
        int roundedProgress = Math.round(averageProgress);

        overallProgressBar.setProgress(roundedProgress);
        overallProgressText.setText(String.format(Locale.getDefault(), "Общий прогресс: %d%%", roundedProgress));
        coursesCountText.setText(String.format(Locale.getDefault(), "Курсов: %d", courses.size()));

        // Обновляем адаптер с передачей слушателя
        CourseProgressAdapter adapter = new CourseProgressAdapter(courses, course -> {
            try {
                Intent intent = new Intent(requireActivity(), CourseActivity.class);
                intent.putExtra("courseId", course.getId());
                intent.putExtra("courseName", course.getName());
                startActivity(intent);
            } catch (Exception e) {
                Log.e("CoursesProgressFragment", "Error opening course", e);
                Toast.makeText(requireContext(), "Ошибка открытия курса", Toast.LENGTH_SHORT).show();
            }
        });

        coursesRecyclerView.setAdapter(adapter);
    }

    private static class CourseProgressAdapter extends RecyclerView.Adapter<CourseProgressAdapter.ViewHolder> {
        private final List<Course> courses;
        private final OnCourseClickListener listener; // Добавлено поле
        public interface OnCourseClickListener {
            void onCourseClick(Course course);
        }
        public CourseProgressAdapter(List<Course> courses, OnCourseClickListener listener) {
            this.courses = courses;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_course_progress, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Course course = courses.get(position);
            if (course == null) return;

            holder.courseName.setText(course.getName() != null ? course.getName() : "Без названия");

            int progress = Math.max(0, Math.min(course.getProgress(), 100));
            holder.progressBar.setProgress(progress);
            holder.progressText.setText(String.format(Locale.getDefault(), "%d%%", progress));

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCourseClick(course);
                }
            });
        }

        @Override
        public int getItemCount() {
            return courses.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView courseName;
            ProgressBar progressBar;
            TextView progressText;

            public ViewHolder(View view) {
                super(view);
                courseName = view.findViewById(R.id.courseName);
                progressBar = view.findViewById(R.id.progressBar);
                progressText = view.findViewById(R.id.progressText);
            }
        }
    }
}