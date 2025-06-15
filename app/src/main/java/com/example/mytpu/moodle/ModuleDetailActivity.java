package com.example.mytpu.moodle;

import static android.text.Html.fromHtml;

import androidx.cardview.widget.CardView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.WorkInfo;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.text.HtmlCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ModuleDetailActivity extends AppCompatActivity {
    public static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    private static final String TAG = "ModuleDetail";
    private static final String SHARED_PREFS_NAME = "secret_shared_prefs";
    private LiveData<WorkInfo> workInfoLiveData = new MutableLiveData<>();
    private TextView moduleTitle;
    private TextView contentView;
    private TextView contentScrollView;
    private ProgressBar progressBar;
    private String token;
    private OkHttpClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private JSONObject currentQuiz;
    private JSONArray quizAttempts;
    private int coursemodule;
    private LinearLayout contentLayout;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_module_detail);

        initViews();
        initSecureStorage();
        initHttpClient();

        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            token = sharedPreferences.getString("token", null);
            Log.d("Token retrieved", "Token retrieved: " + token);

        } catch (Exception e) {
            Log.e(TAG, "Error accessing secure storage", e);
            finish();
        }
        try {
            loadModuleData();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        workInfoLiveData = new MutableLiveData<>();
        workInfoLiveData.observe(this, workInfo -> {
            if (workInfo != null) {
                if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                    String filePath = workInfo.getOutputData().getString("file_path");
                    if (filePath != null) {
                        openPdfFile(new File(filePath));
                    }
                } else if (workInfo.getState() == WorkInfo.State.FAILED) {
                    showError("Ошибка загрузки файла");
                }
            }
        });
    }

    private void initViews() {
        moduleTitle = findViewById(R.id.moduleTitle);
        contentView = findViewById(R.id.plainTextContentView);
        contentScrollView = findViewById(R.id.htmlContentTextView);
        progressBar = findViewById(R.id.progressBar);
        contentLayout = findViewById(R.id.contentLayout);
    }

    private void initSecureStorage() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    SHARED_PREFS_NAME,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );




        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Security init error: " + e.getMessage());
            handleSecurityException(e);
        }
    }

    private void initHttpClient() {
        client = new OkHttpClient();
    }

    private void loadModuleData() throws Exception {
        int cmid = getIntent().getIntExtra("cmid", 0);
        int instanceId = getIntent().getIntExtra("instanceId", 0);
        String type = getIntent().getStringExtra("type");

        int courseId = getIntent().getIntExtra("courseid", 0); // Получаем courseId
        String moduleName = getIntent().getStringExtra("name"); // Получаем название модуля
        Log.d("cmid", "cmid="+cmid);
        Log.d("instanceId", "instanceId="+instanceId);
        Log.d("type", "type="+type);
        Log.d("courseId", "courseId="+courseId);
        Log.d("moduleName", "moduleName="+moduleName);
        executor.execute(() -> {
            try {
                if ("page".equals(type)) {
                    loadPageContent(cmid);
                }else if ("quiz".equals(type)) {
                    int quizId = getIntent().getIntExtra("instanceId", 0); // Берем instanceId для quiz
                    loadQuizContent(quizId, cmid);
                } else {
                    processModuleType(type, cmid, "", instanceId); // Передаем courseId в обработчик
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e("ModuleDetail", "Ошибка загрузки модуля", e);
                    finish();
                });
            }
        });
    }

    private void processModuleType(String type, int moduleId, String description, int instanceId) throws Exception {
        switch (type) {
            case "page":
                loadPageContent(moduleId);
                break;
            case "resource":
                loadResourceContent(moduleId);
                break;
            case "assign":
                loadAssignmentContent(moduleId);
                break;
            case "forum":
                loadForumContent(instanceId);
                break;
            case "url":
                loadUrlContent(moduleId);
                break;
            case "folder":
                loadFolderContent(moduleId);
                break;
            case "quiz":
                loadQuizContent(moduleId,moduleId);
                break;
            case "scorm":
                loadScormContent(moduleId);
                break;
            case "feedback":
            case "label":
                runOnUiThread(() -> {
                    displayNoContent("Текстовый блок");
                    progressBar.setVisibility(View.GONE);
                });
                break;
            case "book": // Книги
                loadBookContent(instanceId);
                break;
            case "glossary": // Глоссарий
                loadGlossaryContent(instanceId);
                break;
            case "lesson": // Уроки
                loadLessonContent(instanceId);
                break;
            case "wiki": // Wiki
                loadStandardContent(type, instanceId);
                break;
            case "choice": // Голосования
                loadStandardContent(type, instanceId);
                break;

            case "lanebs":
                loadLanebsContent(moduleId);
                break;
            default:
                loadGenericContent(type, instanceId);
        }
    }

    // Загрузка контента книги
    private void loadBookContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_book_get_books_by_courses")
                .addQueryParameter("bookids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            JSONArray books = json.getJSONArray("books");
            JSONObject book = books.getJSONObject(0);
            displayHtmlContent(book.getString("intro"), book.getString("name"));
        });
    }

    // Загрузка глоссария
    private void loadGlossaryContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_glossary_get_glossaries_by_courses")
                .addQueryParameter("glossaryids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            JSONArray glossaries = json.getJSONArray("glossaries");
            JSONObject glossary = glossaries.getJSONObject(0);
            displayHtmlContent(glossary.getString("intro"), glossary.getString("name"));
        });
    }

    // Загрузка уроков
    private void loadLessonContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_lesson_get_lessons_by_courses")
                .addQueryParameter("lessonids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            JSONArray lessons = json.getJSONArray("lessons");
            JSONObject lesson = lessons.getJSONObject(0);
            displayHtmlContent(lesson.getString("intro"), lesson.getString("name"));
        });
    }

    private void loadStandardContent(String type, int instanceId) {
        try {
            String function = "mod_" + type + "_get_" + type + "s_by_courses";
            String paramName = type + "ids[0]";

            HttpUrl url = buildApiUrl(function)
                    .addQueryParameter(paramName, String.valueOf(instanceId))
                    .build();

            executeRequest(url, json -> {
                try {
                    JSONArray items = json.getJSONArray(type + "s");
                    if (items.length() > 0) {
                        JSONObject item = items.getJSONObject(0);
                        displayHtmlContent(item.optString("intro", ""), item.getString("name"));
                    } else {
                        displayNoContent("Контент недоступен");
                    }
                } catch (JSONException e) {
                    handleContentError(e);
                }
            });
        } catch (Exception e) {
            showError("Ошибка загрузки: " + e.getMessage());
        }
    }

    private void displayHtmlContent(String html, String title) {
        runOnUiThread(() -> {
            moduleTitle.setText(title);
            contentScrollView.setText(HtmlCompat.fromHtml(
                    html,
                    HtmlCompat.FROM_HTML_MODE_LEGACY
            ));
            progressBar.setVisibility(View.GONE);
        });
    }
    // Универсальная загрузка для неподдерживаемых типов
    private void loadGenericContent(String type, int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_" + type + "_get_" + type + "s_by_courses")
                .addQueryParameter(type + "ids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            JSONArray items = json.getJSONArray(type + "s");
            if (items.length() > 0) {
                JSONObject item = items.getJSONObject(0);
                displayHtmlContent(item.optString("intro", ""), item.getString("name"));
            } else {
                displayNoContent("Контент недоступен");
            }
        });
    }

    private void loadScormContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_scorm_get_scorms_by_courses")
                .addQueryParameter("scormids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            JSONObject scorm = json.getJSONArray("scorms").getJSONObject(0);
            openUrl(scorm.getString("launch"));
        });
    }

    private void loadLanebsContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_lanebs_get_reader_token")
                .addEncodedQueryParameter("instanceid", String.valueOf(instanceId)) // Передаем instanceId=979
                .build();

        executeRequest(url, json -> {
            String token = json.getString("token");
            openUrl("https://books.lanbook.com/auth?token=" + token);
        });
    }


    private void loadPageContent(int cmid) throws JSONException, IOException {
        HttpUrl url = buildApiUrl("mod_page_get_pages_by_courses")
                .build();

        executeRequest(url, json -> {
            try {
                JSONArray pages = json.getJSONArray("pages");
                JSONObject targetPage = null;

                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.getJSONObject(i);
                    if (page.getInt("coursemodule") == cmid) {
                        Log.d(TAG,"coursemodule: "+cmid);
                        targetPage = page;
                        break;
                    }
                }

                if (targetPage == null) {
                    displayNoContent("Страница не найдена");
                    return;
                }

                // Assign to a final variable for use in the lambda
                final JSONObject finalTargetPage = targetPage;

                String htmlContent = targetPage.getString("content");
                targetPage.getJSONArray("contentfiles");

                Html.ImageGetter imageGetter = source -> {
                    URLDrawable urlDrawable = new URLDrawable();
                    new LoadImageTask(urlDrawable).execute(source); // Запуск асинхронной загрузки
                    return urlDrawable;
                };

                runOnUiThread(() -> {
                    contentScrollView.setText(HtmlCompat.fromHtml(
                            htmlContent,
                            HtmlCompat.FROM_HTML_MODE_LEGACY,
                            imageGetter,
                            null
                    ));

                    try {
                        // Use the final variable here
                        moduleTitle.setText(finalTargetPage.getString("name"));
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                    progressBar.setVisibility(View.GONE);
                });

            } catch (JSONException e) {
                handleContentError(e);
            }
        });
    }

    private void loadResourceContent(int cmid) throws IOException, JSONException {
        int courseId = getIntent().getIntExtra("courseid", -1);
        if (courseId == -1) {
            showError("Ошибка: курс не выбран");
            return;
        }

        HttpUrl url = buildApiUrl("mod_resource_get_resources_by_courses")
                .addQueryParameter("courseids[0]", String.valueOf(courseId))
                .build();

        executeRequest(url, json -> {
            JSONArray resources = json.getJSONArray("resources");
            JSONObject targetResource = null;

            for (int i = 0; i < resources.length(); i++) {
                JSONObject resource = resources.getJSONObject(i);
                if (resource.getInt("coursemodule") == cmid) {
                    Log.d(TAG,"coursemodule: "+cmid);
                    targetResource = resource;
                    break;
                }
            }

            if (targetResource == null) {
                displayNoContent("Ресурс не найден");
                return;
            }

            // Исправлено: contentfiles вместо contents
            JSONArray contentFiles = targetResource.getJSONArray("contentfiles");
            if (contentFiles.length() > 0) {
                String fileUrl = contentFiles.getJSONObject(0).getString("fileurl");
                downloadFile(fileUrl);  // Start downloading the file and open it when done
            } else {
                displayNoContent("Файл не найден");
            }


        });
    }

    private void loadAssignmentContent(int cmid) throws IOException, JSONException {
        // Проверка валидности courseId
        int courseId = getIntent().getIntExtra("courseid", -1);
        if (courseId <= 0) {
            showErrorAndFinish("Неверный ID курса");
            return;
        }

        // 2. Формирование правильного запроса с courseids вместо assignmentids
        // Используйте cmid вместо courseId для запроса заданий
        HttpUrl url = buildApiUrl("mod_assign_get_assignments")
                .addQueryParameter("assignmentids[0]", String.valueOf(courseId)) // должно быть instanceId
                .build();

        executeRequest(url, json -> {
            try {
                JSONArray courses = json.getJSONArray("courses");
                JSONObject targetCourse = findCourseById(courses, courseId);

                if (targetCourse == null) {
                    showError("Курс не найден");
                    return;
                }

                JSONArray assignments = targetCourse.getJSONArray("assignments");
                JSONObject targetAssignment = findAssignmentByCmid(assignments, cmid);

                if (targetAssignment != null) {
                    displayAssignmentInfo(targetAssignment);
                } else {
                    showError("Задание не найдено в курсе");
                }
            } catch (JSONException e) {
                handleAssignmentError(json, cmid, courseId);
            }
        });
    }

    private JSONObject findCourseById(JSONArray courses, int targetId) throws JSONException {
        for (int i = 0; i < courses.length(); i++) {
            JSONObject course = courses.getJSONObject(i);
            if (course.getInt("id") == targetId) {
                return course;
            }
        }
        return null;
    }

    private JSONObject findAssignmentByCmid(JSONArray assignments, int cmid) throws JSONException {
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject assignment = assignments.getJSONObject(i);
            if (assignment.getInt("cmid") == cmid) {
                return assignment;
            }
        }
        return null;
    }

    // 4. Улучшенная обработка ошибок
    private void handleAssignmentError(JSONObject response, int cmid, int courseId) {
        try {
            String errorCode = response.optString("errorcode", "unknown");
            String message = response.optString("message", "Неизвестная ошибка");

            String debugInfo = String.format(
                    "Ошибка при запросе задания:\nCMID: %d\nCourse ID: %d\nКод ошибки: %s\nСообщение: %s",
                    cmid, courseId, errorCode, message
            );

            Log.e(TAG, debugInfo);
            showError("Ошибка загрузки задания: " + message);

        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки ошибки задания", e);
        }
    }

    private void loadForumContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_forum_get_forum_discussions")
                .addQueryParameter("forumid", String.valueOf(instanceId))
                .addQueryParameter("perpage", "100")
                .build();

        executeRequest(url, json -> {
            if (json.has("discussions")) {
                JSONArray discussions = json.getJSONArray("discussions");
                if (discussions.length() == 0) {
                    displayNoContent("Нет доступных обсуждений");
                } else {
                    displayForumDiscussions(discussions);
                }
            }
        });
    }

    private void displayNoContent(String message) {
        runOnUiThread(() -> {
            contentLayout.removeAllViews();
            TextView textView = new TextView(this);
            textView.setText(message);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            textView.setTextColor(Color.RED);
            textView.setGravity(Gravity.CENTER);

            contentLayout.addView(textView);
            progressBar.setVisibility(View.GONE);
        });
    }

    private void loadFolderContent(int folderId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_folder_get_folders_by_courses")
                .addQueryParameter("folderids[0]", String.valueOf(folderId))
                .build();

        executeRequest(url, json -> {
            JSONArray folders = json.getJSONArray("folders");
            displayFolderFiles(folders.getJSONObject(0).getJSONArray("files"));
        });
    }

    private void loadUrlContent(int urlId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_url_get_urls_by_courses")
                .addQueryParameter("urlids[0]", String.valueOf(urlId))
                .build();

        executeRequest(url, json -> {
            JSONArray urls = json.getJSONArray("urls");
            openUrl(urls.getJSONObject(0).getString("externalurl"));
        });
    }

    private void executeRequest(HttpUrl url, ResponseHandler handler) throws IOException, JSONException {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    if (json.has("exception")) {
                        String error = json.optString("message", "Unknown error");
                        runOnUiThread(() -> showError("API Error: " + error));
                        return;
                    }
                    handler.handleResponse(json);
                }
            } catch (Exception e) {
                runOnUiThread(() -> showError("Ошибка: " + e.getMessage()));
            }
        });
    }

    private void displayAssignmentInfo(JSONObject assignment) throws JSONException {
        // Парсинг основных данных
        String name = assignment.getString("name");
        String introHtml = assignment.getString("intro");
        int grade = assignment.getInt("grade");

        // Парсинг файлов инструкций
        JSONArray introFiles = assignment.getJSONArray("introfiles");
        List<String> fileLinks = new ArrayList<>();
        for (int i = 0; i < introFiles.length(); i++) {
            JSONObject file = introFiles.getJSONObject(i);
            fileLinks.add(file.getString("fileurl"));
        }

        // Форматирование текста
        String formattedText = String.format(
                "📌 %s\n\nОценка: %d баллов\n\n%s\n\nПрикрепленные файлы:\n%s",
                name,
                grade,
                fromHtml(introHtml),
                TextUtils.join("\n", fileLinks)
        );

        runOnUiThread(() -> {
            contentView.setText(formattedText);
            progressBar.setVisibility(View.GONE);
        });
    }

    @SuppressLint("DefaultLocale")
    private void displayForumDiscussions(JSONArray discussions) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < discussions.length(); i++) {
            JSONObject discussion = discussions.getJSONObject(i);
            sb.append(String.format("💬 %s\nАвтор: %s\nСообщений: %d\n\n",
                    discussion.getString("name"),
                    discussion.getString("authorfullname"),
                    discussion.getInt("numreplies")));
        }
        runOnUiThread(() -> {
            contentView.setText(sb.toString());
            progressBar.setVisibility(View.GONE);
        });
    }

    @SuppressLint("DefaultLocale")
    private void displayFolderFiles(JSONArray files) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            sb.append(String.format("📄 %s\nРазмер: %.2f MB\n\n",
                    file.getString("filename"),
                    file.getLong("filesize") / (1024.0 * 1024.0)));
        }
        runOnUiThread(() -> {
            contentView.setText(sb.toString());
            progressBar.setVisibility(View.GONE);
        });
    }

    private void downloadFile(String fileUrl) {
        try {
            URL url = new URL(fileUrl+"?token="+token);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setDoOutput(true);
            Log.d(TAG, "downloadFile: "+url);

            InputStream inputStream = connection.getInputStream();
            File file = new File(getExternalFilesDir(null), "downloaded_file.pdf");

            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    "com.example.mytpu.provider",  // Убедитесь, что authorities здесь совпадает с тем, что в manifest
                    file
            );

            Log.d(TAG, "Generated URI: " + contentUri.toString());
            FileOutputStream outputStream = new FileOutputStream(file);

            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }

            outputStream.flush();
            outputStream.close();
            inputStream.close();

            openPdfFile(file);
            finish();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка скачивания файла: " + e.getMessage());
            showError("Ошибка скачивания файла");
        }
    }

    private void openPdfFile(File file) {
        try {
            if (!file.exists()) {
                showError("Файл не найден: " + file.getAbsolutePath());
                return;
            }

            // Генерация URI
            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    "com.example.mytpu.provider",  // Убедитесь, что это совпадает с authorities в AndroidManifest
                    file
            );

            Log.d(TAG, "Generated URI: " + contentUri.toString());

            // Создание Intent для открытия PDF
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Log.d(TAG, "Intent data: " + contentUri.toString());

            // Открытие файла через Intent
            startActivity(Intent.createChooser(intent, "Открыть PDF"));

        } catch (Exception e) {
            Log.e(TAG, "Ошибка открытия файла: " + e.getMessage());
            showError("Ошибка доступа к файлу");
        }
    }
    private void loadQuizAttempts(int quizId, int coursemodule) throws JSONException, IOException {
        HttpUrl url = buildApiUrl("mod_quiz_get_user_attempts")
                .addQueryParameter("quizid", String.valueOf(quizId))
                .addQueryParameter("status", "all")
                .build();

        executeRequest(url, json -> {
            try {
                quizAttempts = json.getJSONArray("attempts");
                Log.d(TAG, "Attempts received: " + quizAttempts.length());
            } catch (JSONException e) {
                showError("Ошибка данных попыток");
            }
        });
    }
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            showError("Нет приложения для открытия ссылки");
        }
        hideProgress();
    }

    private void hideProgress() {
        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
    }

    private HttpUrl.Builder buildApiUrl(String wsFunction) {
        return HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", wsFunction)
                .addQueryParameter("moodlewsrestformat", "json");
    }

    private void handleSecurityException(Exception e) {
        Log.e(TAG, "Security error: ", e);
        showErrorAndFinish("Ошибка безопасности приложения");
    }

    private void handleContentError(Exception e) {
        runOnUiThread(() -> {
            String error = (e instanceof JSONException)
                    ? "Ошибка формата данных"
                    : "Ошибка: " + e.getMessage();

            contentView.setText(error);
            progressBar.setVisibility(View.GONE);
            Log.e(TAG, "Ошибка контента", e);
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            contentView.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    private void showErrorAndFinish(String message) {
        showError(message);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
    }

    private void loadQuizContent(int quizId, int cmid) throws IOException, JSONException {
        Log.d(TAG, "Loading quiz with id: " + quizId);
        int courseId = getIntent().getIntExtra("courseid", -1);

        if (courseId <= 0) {
            showError("Неверный ID курса");
            return;
        }
        coursemodule = courseId;
        HttpUrl url = buildApiUrl("mod_quiz_get_quizzes_by_courses")
                .addQueryParameter("courseids[0]", String.valueOf(courseId))
                .build();

        executeRequest(url, json -> {
            try {
                JSONArray quizzes = json.getJSONArray("quizzes");
                for (int i = 0; i < quizzes.length(); i++) {
                    JSONObject quiz = quizzes.getJSONObject(i);
                    if (quiz.getInt("id") == quizId) {
                        currentQuiz = quiz; // Сохраняем текущий тест
                        loadQuizAttempts(quizId, quiz.getInt("coursemodule"));
                        checkQuizAccess(quizId, quiz, cmid);
                        break;
                    }
                }
            } catch (JSONException e) {
                handleContentError(e);
            }
        });
    }

    private void checkQuizAccess(int quizId, JSONObject quiz,int cmid) throws JSONException, IOException {
        HttpUrl accessUrl = buildApiUrl("mod_quiz_get_quiz_access_information")
                .addQueryParameter("quizid", String.valueOf(quizId))
                .build();

        executeRequest(accessUrl, accessJson -> {
            try {
                // Получаем параметры доступа напрямую из JSON
                boolean canAttempt = accessJson.getBoolean("canattempt");
                boolean canPreview = accessJson.getBoolean("canpreview");
                boolean canReview = accessJson.getBoolean("canreviewmyattempts");

                // Обновляем метод отображения
                displayQuizPreview(quiz, canAttempt, canPreview, canReview);

                if (canAttempt) {
                    setupAttemptButton(quizId, cmid);
                }

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка парсинга access info: " + e.getMessage());
                handleContentError(e);
            }
        });
    }

    private void displayQuizPreview(JSONObject quiz,
                                    boolean canAttempt,
                                    boolean canPreview,
                                    boolean canReview) throws JSONException {
        runOnUiThread(() -> {
            // Создаем контейнер
            LinearLayout container = findViewById(R.id.contentLayout);
            container.removeAllViews();

            // CardView
            CardView card = new CardView(this);
            card.setCardBackgroundColor(Color.WHITE);
            card.setCardElevation(8f);
            card.setRadius(16f);
            card.setUseCompatPadding(true);

            // Внутренний макет
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(32, 32, 32, 32);

            // Название теста
            TextView title = new TextView(this);
            try {
                title.setText(quiz.getString("name"));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(Color.DKGRAY);
            layout.addView(title);

            // Добавляем информационные блоки
            try {
                addInfoRow(layout, "Макс. попыток", String.valueOf(quiz.getInt("attempts")));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            try {
                addInfoRow(layout, "Лимит времени", quiz.getInt("timelimit") + " мин");
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            try {
                addInfoRow(layout, "Макс. оценка", String.valueOf(quiz.getDouble("grade")));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            // Добавляем статусы доступа
            addStatusRow(layout, "Прохождение", canAttempt);
            addStatusRow(layout, "Предпросмотр", canPreview);
            addStatusRow(layout, "Просмотр результатов", canReview);

            card.addView(layout);
            container.addView(card);
            progressBar.setVisibility(View.GONE);
        });
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(String.format("%s: %s", label, value));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#616161"));
        tv.setPadding(0, 16, 0, 0);
        parent.addView(tv);
    }

    private void addStatusRow(LinearLayout parent, String label, boolean status) {
        TextView tv = new TextView(this);
        tv.setText(String.format("• %s: %s", label, status ? "✅ Доступно" : "❌ Недоступно"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(status ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
        tv.setPadding(0, 8, 0, 0);
        parent.addView(tv);
    }

    private void setupAttemptButton(int quizId, int cmid) {
        runOnUiThread(() -> {
            LinearLayout container = findViewById(R.id.contentLayout);
            JSONObject activeAttempt = getActiveAttempt();
            Button startButton = new Button(this);

            // Удаляем предыдущие кнопки
            for (int i = 0; i < container.getChildCount(); i++) {
                View v = container.getChildAt(i);
                if (v instanceof Button) container.removeView(v);
            }

            if (activeAttempt != null) {
                startButton.setText("Продолжить попытку");
                startButton.setOnClickListener(v -> {
                    try {
                        // ДОБАВЛЯЕМ ПЕРЕДАЧУ LAYOUT
                        String layoutStr = activeAttempt.getString("layout");

                        Intent intent = new Intent(
                                ModuleDetailActivity.this,
                                QuizAttemptActivity.class
                        );
                        intent.putExtra("attemptId", activeAttempt.getInt("id"));
                        intent.putExtra("layout", layoutStr); // ПЕРЕДАЕМ LAYOUT
                        intent.putExtra("cmid", cmid); // Добавьте эту строку
                        Log.d("cmid", "cmid="+cmid);
                        startActivity(intent);
                    } catch (JSONException e) {
                        showError("Ошибка запуска: " + e.getMessage());
                    }
                });
            } else {
                startButton.setText("Начать попытку");
                startButton.setOnClickListener(v -> startNewAttempt(quizId, cmid));
            }

            container.addView(startButton);
        });
    }

    private void startNewAttempt(int quizId, int cmid) {
        new AlertDialog.Builder(this)
                .setTitle("Начать новую попытку?")
                .setMessage("У вас осталось попыток: " + getRemainingAttempts())
                .setPositiveButton("Начать", (dialog, which) -> {
                    try {
                        processNewAttempt(quizId, cmid);
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting attempt", e);
                        showError("Ошибка запуска попытки");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private int getRemainingAttempts() {
        try {
            if (currentQuiz == null) return 0;

            int maxAttempts = currentQuiz.getInt("attempts");
            int usedAttempts = (int) quizAttempts.length();
            return maxAttempts - usedAttempts;
        } catch (JSONException e) {
            Log.e(TAG, "Error getting remaining attempts", e);
            return 0;
        }
    }

    private JSONObject getActiveAttempt() {
        try {
            for (int i = 0; i < quizAttempts.length(); i++) {
                JSONObject attempt = quizAttempts.getJSONObject(i);
                if (attempt.getString("state").equals("inprogress")) {
                    return attempt;
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error finding active attempt", e);
        }
        return null;
    }

    private void processNewAttempt(int quizId, int cmid) throws JSONException, IOException {
        HttpUrl startUrl = buildApiUrl("mod_quiz_start_attempt")
                .addQueryParameter("quizid", String.valueOf(quizId))
                .build();

        executeRequest(startUrl, json -> {
            try {
                JSONObject attempt = json.getJSONObject("attempt");
                String layoutStr = attempt.getString("layout"); // Получаем как строку
                Log.d(TAG,"startUrl QuizAttemptActivity: "+ startUrl);

                Intent intent = new Intent(ModuleDetailActivity.this, QuizAttemptActivity.class);
                intent.putExtra("attemptId", attempt.getInt("id"));
                intent.putExtra("layout", layoutStr); // Передаём как строку
                intent.putExtra("cmid", cmid);
                Log.d("cmid", "cmid="+cmid);
                startActivity(intent);
            } catch (JSONException e) {
                if (json.has("errorcode") && json.getString("errorcode").equals("attemptstillinprogress")) {
                    runOnUiThread(() -> {
                        new AlertDialog.Builder(this)
                                .setTitle("Активная попытка")
                                .setMessage("У вас есть незавершенная попытка. Хотите продолжить?")
                                .setPositiveButton("Да", (d, w) -> {
                                    JSONObject active = getActiveAttempt();
                                    if (active != null) {
                                        try {
                                            loadAttemptQuestions(active.getInt("id"));
                                        } catch (JSONException ex) {
                                            showError("Ошибка загрузки попытки");
                                        } catch (IOException ex) {
                                            throw new RuntimeException(ex);
                                        }
                                    }
                                })
                                .setNegativeButton("Нет", null)
                                .show();
                    });
                } else {
                    showError("Ошибка запуска попытки");
                }
            }
        });
    }

    private void loadAttemptQuestions(int attemptId) throws JSONException, IOException {
        HttpUrl questionsUrl = buildApiUrl("mod_quiz_get_attempt_data")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("page", "0") // Добавляем обязательный параметр
                .build();

        executeRequest(questionsUrl, json -> {
            try {
                if (json.has("questions")) {
                    JSONArray questions = json.getJSONArray("questions");
                    displayQuestions(questions);
                } else {
                    showError("Не удалось загрузить вопросы");
                }
            } catch (JSONException e) {
                handleContentError(e);
            }
        });
    }

    private void displayQuestions(JSONArray questions) {
        runOnUiThread(() -> {
            try {
                StringBuilder questionsText = new StringBuilder();
                questionsText.append("Вопросы теста:\n\n");

                for (int i = 0; i < questions.length(); i++) {
                    JSONObject question = questions.getJSONObject(i);
                    String htmlContent = question.getString("html");

                    // Извлекаем текст вопроса из HTML
                    String questionText = extractQuestionText(htmlContent);

                    questionsText.append(i + 1)
                            .append(". ")
                            .append(questionText)
                            .append("\n");

                    // Извлекаем варианты ответов
                    List<String> options = extractOptions(htmlContent);
                    for (int j = 0; j < options.size(); j++) {
                        questionsText.append("   ")
                                .append((char) ('A' + j))
                                .append(") ")
                                .append(options.get(j))
                                .append("\n");
                    }
                    questionsText.append("\n");
                }

                contentView.setText(questionsText.toString());

            } catch (JSONException e) {
                Log.e(TAG, "Error parsing questions: " + e.getMessage());
                showError("Ошибка загрузки вопросов");
            }
        });
    }

    private String extractQuestionText(String html) {
        try {
            // Используем Jsoup для парсинга HTML
            Document doc = Jsoup.parse(html);
            Elements qtext = doc.select("div.qtext");
            return qtext.text().trim();
        } catch (Exception e) {
            Log.e(TAG, "HTML parse error: " + e.getMessage());
            return "Не удалось извлечь текст вопроса";
        }
    }

    private List<String> extractOptions(String html) {
        List<String> options = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements answerLabels = doc.select("div.answer div.d-flex div.flex-fill");

            for (Element label : answerLabels) {
                options.add(label.text().trim());
            }
        } catch (Exception e) {
            Log.e(TAG, "Options parse error: " + e.getMessage());
        }
        return options;
    }

    @Override
    protected void onDestroy() {
        if (workInfoLiveData != null) {
            workInfoLiveData.removeObservers(this);
        }
        super.onDestroy();
        executor.shutdown();
    }

    interface ResponseHandler {
        void handleResponse(JSONObject json) throws Exception;
    }

    private class URLDrawable extends BitmapDrawable {
        private Drawable drawable;

        @Override
        public int getIntrinsicWidth() {
            return drawable != null ? drawable.getIntrinsicWidth() : super.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return drawable != null ? drawable.getIntrinsicHeight() : super.getIntrinsicHeight();
        }
    }

    private class LoadImageTask extends AsyncTask<String, Void, Drawable> {
        private final URLDrawable urlDrawable;
        private final String baseUrl = "https://stud.lms.tpu.ru";

        public LoadImageTask(URLDrawable urlDrawable) {
            this.urlDrawable = urlDrawable;
        }

        @Override
        protected Drawable doInBackground(String... params) {
            try {
                String imageSource = params[0];

                // 1. Обработка относительных путей
                if (imageSource.startsWith("/")) {
                    imageSource = baseUrl + imageSource;
                }

                // 2. Добавление токена
                Uri.Builder uriBuilder = Uri.parse(imageSource).buildUpon();
                if (token != null) {
                    uriBuilder.appendQueryParameter("token", token);
                }

                String finalUrl = uriBuilder.build().toString();
                Log.d(TAG, "Loading image: " + finalUrl);

                // 3. Получение ширины экрана
                int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
                int screenHeidth = Resources.getSystem().getDisplayMetrics().heightPixels;
                // 4. Загрузка и масштабирование
                URL url = new URL(finalUrl);
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);

                try (InputStream is = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);

                    if (bitmap == null) {
                        throw new IOException("Failed to decode bitmap");
                    }

                    int maxWidth = Resources.getSystem().getDisplayMetrics().widthPixels - 32; // Отступы
                    float scaleRatio = (float) maxWidth / bitmap.getWidth();
                    int targetWidth = (int) (bitmap.getWidth() * scaleRatio);
                    int targetHeight = (int) (bitmap.getHeight() * scaleRatio);

                    return new BitmapDrawable(getResources(),
                            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true));
                }

            } catch (Exception e) {
                Log.e(TAG, "Image load error: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(Drawable result) {
            if (result != null) {
                result.setBounds(0, 0,
                        result.getIntrinsicWidth(),
                        result.getIntrinsicHeight());

                urlDrawable.drawable = result;

                // Исправленный код:
                CharSequence currentText = contentScrollView.getText();
                SpannableStringBuilder spannableBuilder;

                if (currentText instanceof Spannable) {
                    spannableBuilder = new SpannableStringBuilder(currentText);
                } else {
                    spannableBuilder = SpannableStringBuilder.valueOf(currentText);
                }

                // Найти позицию для вставки изображения (пример)
                ImageSpan[] spans = spannableBuilder.getSpans(0, spannableBuilder.length(), ImageSpan.class);
                for (ImageSpan span : spans) {
                    if (span.getDrawable() == urlDrawable) {
                        int start = spannableBuilder.getSpanStart(span);
                        int end = spannableBuilder.getSpanEnd(span);
                        spannableBuilder.removeSpan(span);
                        spannableBuilder.setSpan(new ImageSpan(result), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }

                contentScrollView.setText(spannableBuilder);
            }
        }
    }

}