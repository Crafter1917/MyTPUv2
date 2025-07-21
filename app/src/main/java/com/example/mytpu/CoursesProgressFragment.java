package com.example.mytpu;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
    private LinearLayout coursesLinearLayout; // Заменяем RecyclerView на LinearLayout

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

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_courses_progress, container, false);

        // Изменяем инициализацию на coursesLinearLayout
        coursesLinearLayout = view.findViewById(R.id.coursesLinearLayout);
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar);
        overallProgressBar = view.findViewById(R.id.overallProgressBar);
        overallProgressText = view.findViewById(R.id.overallProgressText);
        coursesCountText = view.findViewById(R.id.coursesCountText);
        expandIcon = view.findViewById(R.id.expandIcon);
        headerContainer = view.findViewById(R.id.headerContainer);

        // Убираем настройки RecyclerView
        coursesLinearLayout.setOrientation(LinearLayout.VERTICAL);

        // Изначально сворачиваем список
        coursesLinearLayout.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = coursesLinearLayout.getLayoutParams();
        params.height = 0;
        coursesLinearLayout.setLayoutParams(params);
        expandIcon.setRotation(0);

        headerContainer.setOnClickListener(v -> toggleExpansion());
        loadCoursesProgress();

        return view;
    }

    private void expandList() {
        coursesLinearLayout.setVisibility(View.VISIBLE);
        coursesLinearLayout.post(() -> {
            if (!isExpanded) return;

            // Измеряем целевую высоту
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    coursesLinearLayout.getWidth(),
                    View.MeasureSpec.EXACTLY
            );
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    0,
                    View.MeasureSpec.UNSPECIFIED
            );
            coursesLinearLayout.measure(widthSpec, heightSpec);
            int targetHeight = coursesLinearLayout.getMeasuredHeight();

            ValueAnimator animator = ValueAnimator.ofInt(0, targetHeight);
            animator.addUpdateListener(animation -> {
                int value = (int) animation.getAnimatedValue();
                ViewGroup.LayoutParams params = coursesLinearLayout.getLayoutParams();
                params.height = value;
                coursesLinearLayout.setLayoutParams(params);
            });
            animator.setDuration(300);
            animator.start();
        });
    }

    private void collapseList() {
        int startHeight = coursesLinearLayout.getHeight();
        ValueAnimator animator = ValueAnimator.ofInt(startHeight, 0);
        animator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            ViewGroup.LayoutParams params = coursesLinearLayout.getLayoutParams();
            params.height = value;
            coursesLinearLayout.setLayoutParams(params);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                coursesLinearLayout.setVisibility(View.GONE);
            }
        });
        animator.setDuration(300);
        animator.start();
    }

    private void toggleExpansion() {
        isExpanded = !isExpanded;
        expandIcon.setRotation(isExpanded ? 180 : 0);

        if (isExpanded) {
            expandList();
        } else {
            collapseList();
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
                // Проверяем прерывание после первого запроса
                if (Thread.interrupted()) throw new InterruptedException();

                if (userInfo == null || isDestroyed || !isAdded()) return;

                int userId = userInfo.getInt("userid");
                List<Course> courses = getCourses(token, userId);

                // Проверяем уничтожение перед сохранением в кэш
                if (isDestroyed || !isAdded()) return;
                saveToCache(courses);

                // Проверяем уничтожение перед обновлением UI
                if (getActivity() == null || isDestroyed || !isAdded()) return;

                requireActivity().runOnUiThread(() -> {
                    if (isDestroyed || !isAdded()) return;
                    loadingProgressBar.setVisibility(View.GONE);
                    updateUI(courses);
                });
            } catch (InterruptedException | InterruptedIOException e) {
                Log.d(TAG, "Loading cancelled");
            } catch (Exception e) {
                Log.e(TAG, "Error loading courses", e);
                if (getActivity() == null || isDestroyed || !isAdded()) return;

                // Показываем кэш при ошибке
                if (cachedCourses != null && !cachedCourses.isEmpty()) {
                    requireActivity().runOnUiThread(() -> {
                        if (isDestroyed || !isAdded()) return;
                        loadingProgressBar.setVisibility(View.GONE);
                        updateUI(cachedCourses);
                    });
                } else {
                    requireActivity().runOnUiThread(() -> {
                        if (isDestroyed || !isAdded()) return;
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
            // Проверка прерывания перед запросом
            if (Thread.interrupted()) throw new InterruptedException();

            HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "core_webservice_get_site_info")
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request request = new Request.Builder().url(url).build();

            // Выполняем запрос с явной проверкой прерывания
            Response response = null;
            try {
                response = client.newCall(request).execute();

                // Проверяем прерывание после получения ответа
                if (Thread.interrupted()) throw new InterruptedException();

                String responseBody = response.body().string();

                // Дополнительная проверка после чтения тела ответа
                if (Thread.interrupted()) throw new InterruptedException();

                return new JSONObject(responseBody);
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close(); // Закрываем тело ответа явно
                }
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Site info request interrupted", e);
            throw new InterruptedIOException();
        }
    }

    private List<Course> getCourses(String token, int userId) throws IOException, JSONException {
        try {
            // Проверка прерывания перед созданием запроса
            if (Thread.interrupted()) throw new InterruptedException();

            HttpUrl coursesUrl = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "core_enrol_get_users_courses")
                    .addQueryParameter("userid", String.valueOf(userId))
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request coursesRequest = new Request.Builder().url(coursesUrl).build();

            // Выполняем запрос с явной проверкой прерывания
            Response response = null;
            try {
                response = client.newCall(coursesRequest).execute();

                // Проверяем прерывание после получения ответа
                if (Thread.interrupted()) throw new InterruptedException();

                String responseBody = response.body().string();

                // Дополнительная проверка после чтения тела ответа
                if (Thread.interrupted()) throw new InterruptedException();

                // Проверка на ошибку API
                if (responseBody.trim().startsWith("{")) {
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("exception")) {
                        throw new IOException("Moodle error: " + json.optString("message", "Unknown error"));
                    }
                }

                return parseCourses(new JSONArray(responseBody));
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close(); // Закрываем тело ответа явно
                }
            }
        } catch (InterruptedException e) {
            Log.d(TAG, "Course request interrupted", e);
            throw new InterruptedIOException();
        }
    }

    private List<Course> parseCourses(JSONArray coursesJson) throws JSONException {
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < coursesJson.length(); i++) {
            JSONObject course = coursesJson.getJSONObject(i);
            // Используем optString с fallback на "name"
            String name = course.optString("fullname", course.optString("name", "Без названия"));
            int id = course.getInt("id");
            int progress = 0;
            if (course.has("progress") && !course.isNull("progress")) {
                progress = course.optInt("progress", 0);
            }
            courses.add(new Course(name, "", id, progress));
        }
        return courses;
    }

    private void updateUI(List<Course> courses) {
        if (isDestroyed) return;

        // Очищаем контейнер перед обновлением
        coursesLinearLayout.removeAllViews();

        if (courses == null || courses.isEmpty()) {
            coursesCountText.setText("Нет активных курсов");
            overallProgressBar.setProgress(0);
            overallProgressText.setText("Общий прогресс: 0%");
            coursesLinearLayout.setVisibility(View.GONE);
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

        // Динамически создаем элементы курсов
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Course course : courses) {
            View courseView = inflater.inflate(R.layout.item_course_progress, coursesLinearLayout, false);

            TextView courseName = courseView.findViewById(R.id.courseName);
            ProgressBar progressBar = courseView.findViewById(R.id.progressBar);
            TextView progressText = courseView.findViewById(R.id.progressText);

            courseName.setText(course.getName() != null ? course.getName() : "Без названия");

            int progress = Math.max(0, Math.min(course.getProgress(), 100));
            progressBar.setProgress(progress);
            progressText.setText(String.format(Locale.getDefault(), "%d%%", progress));

            courseView.setOnClickListener(v -> {
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

            coursesLinearLayout.addView(courseView);
        }
    }
}