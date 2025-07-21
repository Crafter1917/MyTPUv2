package com.example.mytpu.moodle;

import static android.text.Html.fromHtml;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
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
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
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
import com.google.android.material.card.MaterialCardView;

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
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
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
        MaterialCardView titleCardView = findViewById(R.id.titleCardView);
        titleCardView.setCardBackgroundColor(getColor(R.color.card_background));

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
    }

    private void initViews() {
        moduleTitle = findViewById(R.id.moduleTitle);
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
                    processModuleType(type, cmid, courseId, instanceId); // Передаем courseId в обработчик
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

    private void processModuleType(String type, int cmid, int courseId, int instanceId) throws Exception {
        switch (type) {
            case "page":
                loadPageContent(cmid);
                break;
            case "resource":
                loadResourceContent(cmid);
                break;
            case "assign":
                loadAssignmentContent( cmid,  courseId);
                break;
            case "forum":
                loadForumContent(instanceId);
                break;
            case "url":
                loadUrlContent(cmid);
                break;
            case "folder":
                loadFolderContent(cmid);
                break;
            case "scorm":
                loadScormContent(cmid);
                break;
            case "feedback":
            case "label":
                loadLabelContent(instanceId);
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
                loadLanebsContent(cmid);
                break;
            default:
                loadGenericContent(type, instanceId);
        }
    }

    private void loadLabelContent(int instanceId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_label_get_labels_by_courses")
                .addQueryParameter("labelids[0]", String.valueOf(instanceId))
                .build();

        executeRequest(url, json -> {
            try {
                JSONArray labels = json.getJSONArray("labels");
                if (labels.length() > 0) {
                    JSONObject label = labels.getJSONObject(0);
                    String intro = label.optString("intro", "");
                    String name = label.optString("name", "Текстовый блок");

                    runOnUiThread(() -> displayLabelContent(name, intro));
                } else {
                    displayNoContent("Контент недоступен");
                }
            } catch (JSONException e) {
                handleContentError(e);
            }
        });
    }

    private void displayLabelContent(String title, String content) {
        runOnUiThread(() -> {
            moduleTitle.setText(title);
            progressBar.setVisibility(View.GONE);

            MaterialCardView contentCard = createContentCard();
            TextView contentView = createStyledTextView();
            contentView.setText(HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY));

            LinearLayout cardLayout = (LinearLayout) contentCard.getChildAt(0);
            cardLayout.addView(contentView);
            contentLayout.addView(contentCard);
        });
    }
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
            progressBar.setVisibility(View.GONE);
            contentLayout.removeAllViews();

            MaterialCardView contentCard = createContentCard();
            LinearLayout cardLayout = (LinearLayout) contentCard.getChildAt(0);

            TextView contentView = createStyledTextView();
            Html.ImageGetter imageGetter = source -> {
                URLDrawable urlDrawable = new URLDrawable();
                new LoadImageTask(urlDrawable, contentView).execute(source);
                return urlDrawable;
            };

            contentView.setText(HtmlCompat.fromHtml(
                    html,
                    HtmlCompat.FROM_HTML_MODE_LEGACY,
                    imageGetter,
                    null
            ));

            cardLayout.addView(contentView);
            contentLayout.addView(contentCard);
        });
    }

    private MaterialCardView createContentCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        card.setCardBackgroundColor(getColor(R.color.card_background));
        card.setCardElevation(4f);
        card.setRadius(12f);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).bottomMargin = 16;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()
        );
        layout.setPadding(padding, padding, padding, padding);
        card.addView(layout);

        return card;
    }

    private TextView createStyledTextView() {
        TextView textView = new TextView(this);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        return textView;
    }

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
        HttpUrl url = buildApiUrl("mod_page_get_pages_by_courses").build();

        executeRequest(url, json -> {
            try {
                JSONArray pages = json.getJSONArray("pages");
                JSONObject targetPage = null;

                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.getJSONObject(i);
                    if (page.getInt("coursemodule") == cmid) {
                        targetPage = page;
                        break;
                    }
                }

                if (targetPage == null) {
                    displayNoContent("Страница не найдена");
                    return;
                }

                final String htmlContent = targetPage.getString("content");
                final String pageName = targetPage.getString("name");

                runOnUiThread(() -> {
                    // Удалите статическое использование contentScrollView
                    displayHtmlContent(htmlContent, pageName);
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

    private void loadAssignmentContent(int cmid, int courseId) throws IOException, JSONException {
        HttpUrl url = buildApiUrl("mod_assign_get_assignments")
                .addQueryParameter("courseids[0]", String.valueOf(courseId))
                .build();

        executeRequest(url, json -> {
            try {
                JSONArray courses = json.getJSONArray("courses");
                if (courses.length() == 0) {
                    showError("Курс не найден");
                    return;
                }

                JSONObject course = courses.getJSONObject(0);
                JSONArray assignments = course.getJSONArray("assignments");

                JSONObject targetAssignment = null;
                for (int i = 0; i < assignments.length(); i++) {
                    JSONObject assignment = assignments.getJSONObject(i);
                    if (assignment.getInt("cmid") == cmid) {
                        targetAssignment = assignment;
                        break;
                    }
                }

                if (targetAssignment != null) {
                    // Запускаем AssignmentDetailActivity с данными задания
                    int assignmentId = targetAssignment.getInt("id");
                    launchAssignmentDetail(cmid, assignmentId, courseId, targetAssignment.toString());
                } else {
                    showError("Задание не найдено в курсе");
                }
            } catch (JSONException e) {
                Log.e(TAG,"Ошибка при обработке задания: "+e);
                showError("Ошибка загрузки данных");
            }
        });
    }

    private void launchAssignmentDetail(int cmid, int assignmentId, int courseId, String assignmentJson) {
        Intent intent = new Intent(this, AssignmentDetailActivity.class);
        intent.putExtra("cmid", cmid);
        intent.putExtra("assignmentId", assignmentId);
        intent.putExtra("courseId", courseId);
        intent.putExtra("assignmentJson", assignmentJson); // Передаем данные задания
        startActivity(intent);
        finish(); // Закрываем текущую активность
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
    private String sanitizeFileName(String fileName) {
        // Убираем недопустимые символы
        String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");

        // Сокращаем длину имени
        int maxLength = 100; // Максимальная длина имени файла
        if (safeName.length() > maxLength) {
            int dotIndex = safeName.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = safeName.substring(dotIndex);
                String name = safeName.substring(0, dotIndex);
                name = name.substring(0, Math.min(name.length(), maxLength - ext.length()));
                safeName = name + ext;
            } else {
                safeName = safeName.substring(0, Math.min(safeName.length(), maxLength));
            }
        }
        return safeName;
    }
    private void downloadFile(String fileUrl) {
        executor.execute(() -> {
            try {
                URL url = new URL(fileUrl + "?token=" + token);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.connect();

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    // Получаем оригинальное имя файла
                    String fileName = getFileNameFromConnection(connection, url);
                    String safeFileName = sanitizeFileName(fileName);

                    // Создаем директорию в кеше
                    File outputDir = new File(getCacheDir(), "downloaded_files");
                    if (!outputDir.exists()) outputDir.mkdirs();

                    File outputFile = new File(outputDir, safeFileName);

                    // Скачиваем файл
                    InputStream input = connection.getInputStream();
                    try (FileOutputStream output = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }

                    // Открываем файл
                    runOnUiThread(() -> openDownloadedFile(outputFile));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error downloading file", e);
                runOnUiThread(() ->
                        showError("Ошибка загрузки файла: " + e.getMessage()));
            }
        });
    }
    private void openDownloadedFile(File file) {
        try {
            if (!file.exists()) {
                showError("Файл не найден: " + file.getAbsolutePath());
                return;
            }

            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    "com.example.mytpu.provider",
                    file
            );

            String mimeType = getMimeType(file.getName());
            Log.d(TAG, "Opening file: " + file.getName() + " with MIME: " + mimeType);

            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(contentUri, mimeType);
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            boolean fileOpened = false;

            try {
                startActivity(openIntent);
                fileOpened = true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No app for specific type, trying generic");
                openIntent.setDataAndType(contentUri, "*/*");
                try {
                    startActivity(openIntent);
                    fileOpened = true;
                } catch (ActivityNotFoundException ex) {
                    showError("Нет приложения для открытия файлов. Установите файловый менеджер.");
                }
            }

            if (fileOpened) {
                // Закрываем активность после небольшой задержки
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    finish();
                    // Опционально: анимация закрытия
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }, 300);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening file", e);
            showError("Ошибка открытия файла: " + e.getMessage());
        }
    }
    private String getFileNameFromConnection(HttpURLConnection connection, URL url) {
        try {
            // Пробуем получить имя из Content-Disposition
            String contentDisposition = connection.getHeaderField("Content-Disposition");
            if (contentDisposition != null) {
                String[] parts = contentDisposition.split(";");
                for (String part : parts) {
                    if (part.trim().startsWith("filename=")) {
                        String fileName = part.substring(part.indexOf('=') + 1).trim();
                        fileName = fileName.replaceAll("^['\"]|['\"]$", "");
                        return URLDecoder.decode(fileName, "UTF-8");
                    }
                }
            }

            // Если не нашли, берем из URL
            String path = url.getPath();
            String fileName = new File(path).getName();
            return URLDecoder.decode(fileName, "UTF-8");

        } catch (UnsupportedEncodingException e) {
            return "downloaded_file_" + System.currentTimeMillis();
        }
    }
    private String getMimeType(String fileName) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);

        // Если не удалось определить через MimeTypeMap
        if (extension == null || extension.isEmpty()) {
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                extension = fileName.substring(lastDot + 1);
            }
        }

        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return type != null ? type : "application/*";
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
            try {
                moduleTitle.setText(quiz.getString("name"));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            progressBar.setVisibility(View.GONE);

            // Создаем карточку для информации о тесте
            MaterialCardView quizCard = createContentCard();
            LinearLayout cardLayout = (LinearLayout) quizCard.getChildAt(0);

            // Добавляем информационные строки
            try {
                addInfoRow(cardLayout, "Макс. попыток", String.valueOf(quiz.getInt("attempts")));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            try {
                addInfoRow(cardLayout, "Лимит времени", quiz.getInt("timelimit") + " мин");
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            try {
                addInfoRow(cardLayout, "Макс. оценка", String.valueOf(quiz.getDouble("grade")));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            // Добавляем статусы доступа
            addStatusRow(cardLayout, "Прохождение", canAttempt);
            addStatusRow(cardLayout, "Предпросмотр", canPreview);
            addStatusRow(cardLayout, "Просмотр результатов", canReview);

            contentLayout.addView(quizCard);
        });
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(String.format("%s: %s", label, value));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tv.setPadding(0, 8, 0, 0);
        parent.addView(tv);
    }


    private void addStatusRow(LinearLayout parent, String label, boolean status) {
        TextView tv = new TextView(this);
        tv.setText(String.format("• %s: %s", label, status ? "✅ Доступно" : "❌ Недоступно"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(status ?
                ContextCompat.getColor(this, R.color.colorStatusCompleted) :
                ContextCompat.getColor(this, R.color.colorStatusLate));
        tv.setPadding(0, 12, 0, 0);
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
        private final WeakReference<TextView> textViewRef; // Слабая ссылка на TextView

        private final String baseUrl = "https://stud.lms.tpu.ru";

        public LoadImageTask(URLDrawable urlDrawable, TextView textView) {
            this.urlDrawable = urlDrawable;
            this.textViewRef = new WeakReference<>(textView);
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
                TextView targetTextView = textViewRef.get();
                if (targetTextView == null || result == null) return;
                urlDrawable.drawable = result;
                CharSequence currentText = targetTextView.getText();
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

                targetTextView.setText(spannableBuilder);
            }
        }
    }

}