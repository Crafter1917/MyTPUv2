package com.example.mytpu.moodle;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.text.Html;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.text.HtmlCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AssignmentDetailActivity extends AppCompatActivity {

    private static final String TAG = "AssignmentDetail";
    private static final int PICK_FILE_REQUEST = 1001;

    private TextView moduleTitle;
    private TextView assignmentDueDate;
    private TextView assignmentStatus;
    private TextView assignmentGrade;
    private TextView assignmentSubmissionDate;
    private TextView htmlContentTextView;
    private ProgressBar progressBar;
    private LinearLayout filesContainer;
    private LinearLayout commentsContainer;
    private MaterialButton btnAddFile;
    private MaterialButton btnSubmit;

    private OkHttpClient client;
    private String token;
    private int assignmentId;
    private int courseId;
    private int cmid;
    private JSONObject currentAssignment;
    private List<Uri> selectedFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assignment_detail);

        // Получение данных из Intent
        assignmentId = getIntent().getIntExtra("assignmentId", 0);
        courseId = getIntent().getIntExtra("courseId", 0);
        cmid = getIntent().getIntExtra("cmid", 0);
        String assignmentJson = getIntent().getStringExtra("assignmentJson");

        // Инициализация представлений
        initViews();
        initHttpClient();
        loadToken();

        try {
            // Используем полученные данные вместо повторной загрузки
            currentAssignment = new JSONObject(assignmentJson);
            displayAssignmentInfo(currentAssignment);
        } catch (JSONException e) {
            Log.e(TAG, "Ошибка парсинга данных задания", e);
            Toast.makeText(this, "Ошибка загрузки задания", Toast.LENGTH_SHORT).show();
            finish();
        }

        // Обработчики кнопок
        btnAddFile.setOnClickListener(v -> addFileToAssignment());
        btnSubmit.setOnClickListener(v -> submitAssignment());
    }

    private void initViews() {
        moduleTitle = findViewById(R.id.moduleTitle);
        assignmentDueDate = findViewById(R.id.assignmentDueDate);
        assignmentStatus = findViewById(R.id.assignmentStatus);
        assignmentGrade = findViewById(R.id.assignmentGrade);
        assignmentSubmissionDate = findViewById(R.id.assignmentSubmissionDate);
        htmlContentTextView = findViewById(R.id.htmlContentTextView);
        progressBar = findViewById(R.id.progressBar);
        filesContainer = findViewById(R.id.filesContainer);
        commentsContainer = findViewById(R.id.commentsContainer);
        btnAddFile = findViewById(R.id.btnAddFile);
        btnSubmit = findViewById(R.id.btnSubmit);

        progressBar.setVisibility(View.VISIBLE);
    }

    private void initHttpClient() {
        client = new OkHttpClient();
    }

    private void loadToken() {
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
            Log.d(TAG, "Token loaded: " + token);
        } catch (Exception e) {
            Log.e(TAG, "Error loading token", e);
            Toast.makeText(this, "Ошибка авторизации", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void displayAssignmentInfo(JSONObject assignment) {
        try {
            // Основная информация
            moduleTitle.setText(assignment.getString("name"));

            // Срок сдачи
            long dueDateSeconds = assignment.getLong("duedate");
            String formattedDate;
            JSONArray introFiles = assignment.optJSONArray("introfiles");
            JSONArray introAttachments = assignment.optJSONArray("introattachments");

            boolean hasFiles = (introFiles != null && introFiles.length() > 0) ||
                    (introAttachments != null && introAttachments.length() > 0);

            if (!hasFiles) {
                findViewById(R.id.filesCardView).setVisibility(View.GONE);
            }

            // Отображение файлов описания
            if (introFiles != null) {
                for (int i = 0; i < introFiles.length(); i++) {
                    JSONObject file = introFiles.getJSONObject(i);
                    addFileView(file.getString("filename"), file.getString("fileurl"), "Файл задания");
                }
            }
            if (introAttachments != null) {
                for (int i = 0; i < introAttachments.length(); i++) {
                    JSONObject file = introAttachments.getJSONObject(i);
                    addFileView(file.getString("filename"), file.getString("fileurl"), "Дополнительный материал");
                }
            }
            if (dueDateSeconds > 0) {
                long dueDateMillis = dueDateSeconds * 1000;
                formattedDate = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(new Date(dueDateMillis));
            } else {
                formattedDate = "Срок не указан";
            }

            if (dueDateSeconds > 0) {
                assignmentDueDate.setText("Срок сдачи: " + formattedDate);
            } else {
                assignmentDueDate.setText("Срок сдачи не установлен");
                assignmentDueDate.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            }

            // Описание
            String introHtml = assignment.getString("intro");
            htmlContentTextView.setText(HtmlCompat.fromHtml(
                    introHtml,
                    HtmlCompat.FROM_HTML_MODE_COMPACT
            ));

            // Загрузка статуса отправки
            loadSubmissionStatus();
            progressBar.setVisibility(View.GONE);
        } catch (JSONException e) {
            Log.e(TAG, "Error displaying assignment", e);
            Toast.makeText(this, "Ошибка отображения задания", Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(View.GONE); // Добавляем скрытие прогресс-бара при ошибке
        }
    }

    private void loadSubmissionStatus() {
        HttpUrl url = HttpUrl.parse("https://stud.lms.tpu.ru/webservice/rest/server.php").newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_assign_get_submission_status")
                .addQueryParameter("assignid", String.valueOf(assignmentId))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Submission status error: " + e.getMessage());
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("feedbackcomments")) {
                        displayComments(json.getJSONArray("feedbackcomments"));
                    }
                    runOnUiThread(() -> {
                        try {
                            // Обновление статуса
                            if (json.has("lastattempt")) {
                                JSONObject lastAttempt = json.getJSONObject("lastattempt");

                                // Статус задания
                                if (lastAttempt.has("submission")) {
                                    JSONObject submission = lastAttempt.getJSONObject("submission");
                                    String status = submission.optString("status", "new");
                                    assignmentStatus.setText(getStatusText(status));

                                    // Дата отправки
                                    if (submission.has("timemodified") && !submission.isNull("timemodified")) {
                                        long time = submission.getLong("timemodified") * 1000;
                                        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                                .format(new Date(time));
                                        assignmentSubmissionDate.setText(date);
                                    }
                                }

                                // Оценка и отзыв
                                if (lastAttempt.has("feedback")) {
                                    JSONObject feedback = lastAttempt.getJSONObject("feedback");

                                    // Оценка
                                    if (feedback.has("grade")) {
                                        JSONObject grade = feedback.getJSONObject("grade");
                                        String gradeText = grade.optString("gradefordisplay", "-");
                                        assignmentGrade.setText(gradeText);
                                    }

                                    // Отзыв преподавателя
                                    if (feedback.has("plugins")) {
                                        JSONArray plugins = feedback.getJSONArray("plugins");
                                        displayFeedback(plugins);
                                    }
                                }
                            }

                            // Отображение отправленных файлов
                            if (json.has("lastattempt") &&
                                    json.getJSONObject("lastattempt").has("submission") &&
                                    json.getJSONObject("lastattempt").getJSONObject("submission").has("plugins")) {

                                JSONArray plugins = json.getJSONObject("lastattempt")
                                        .getJSONObject("submission")
                                        .getJSONArray("plugins");

                                for (int i = 0; i < plugins.length(); i++) {
                                    JSONObject plugin = plugins.getJSONObject(i);
                                    if ("file".equals(plugin.getString("type"))) {
                                        JSONArray files = plugin.getJSONArray("fileareas")
                                                .getJSONObject(0)
                                                .getJSONArray("files");

                                        for (int j = 0; j < files.length(); j++) {
                                            JSONObject file = files.getJSONObject(j);
                                            addFileView(file.getString("filename"), file.getString("fileurl"),
                                                    "Ваши отправленные файлы");
                                        }
                                    }
                                }
                            }

                        } catch (JSONException e) {
                            Log.e(TAG, "Error parsing submission status", e);
                        } finally {
                            progressBar.setVisibility(View.GONE);
                        }
                    });

                } catch (JSONException e) {
                    Log.e(TAG, "JSON error in submission status", e);
                    runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                }
            }
        });
    }

    private void displayComments(JSONArray comments) {
        runOnUiThread(() -> {
            for (int i = 0; i < comments.length(); i++) {
                JSONObject comment = null;
                try {
                    comment = comments.getJSONObject(i);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                try {
                    addCommentView(
                            comment.getString("author"),
                            comment.getString("date"),
                            comment.getString("content")
                    );
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void addCommentView(String author, String date, String content) {
        View commentView = getLayoutInflater().inflate(R.layout.item_comment, null);

        ((TextView) commentView.findViewById(R.id.commentAuthor)).setText(author);
        ((TextView) commentView.findViewById(R.id.commentDate)).setText(date);
        ((TextView) commentView.findViewById(R.id.commentContent)).setText(content);

        commentsContainer.addView(commentView);
    }

    private void displayFeedback(JSONArray plugins) {
        try {
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject plugin = plugins.getJSONObject(i);
                if ("comments".equals(plugin.getString("type"))) {
                    JSONArray editorFields = plugin.getJSONArray("editorfields");

                    for (int j = 0; j < editorFields.length(); j++) {
                        JSONObject field = editorFields.getJSONObject(j);
                        String text = field.optString("text", "");
                        if (!text.isEmpty()) {
                            addFeedbackView("Отзыв преподавателя", text);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing feedback", e);
        }
    }

    private void addFeedbackView(String title, String content) {
        runOnUiThread(() -> {
            // Создаем карточку
            MaterialCardView feedbackCard = new MaterialCardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, dpToPx(16));
            feedbackCard.setLayoutParams(cardParams);

            // Устанавливаем параметры карточки
            feedbackCard.setRadius(dpToPx(12));
            feedbackCard.setCardElevation(dpToPx(2));

            // Контейнер для контента
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

            // Заголовок отзыва
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));

            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            titleParams.setMargins(0, 0, 0, dpToPx(8));
            titleView.setLayoutParams(titleParams);

            // Содержимое отзыва
            TextView contentView = new TextView(this);
            contentView.setText(HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT));
            contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            contentView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));

            // Добавляем элементы
            layout.addView(titleView);
            layout.addView(contentView);
            feedbackCard.addView(layout);

            // Добавляем перед комментариями
            LinearLayout rootLayout = findViewById(R.id.rootLayout);
            View commentsCard = findViewById(R.id.commentsCardView);

            if (commentsCard != null) {
                int commentsIndex = rootLayout.indexOfChild(commentsCard);
                if (commentsIndex >= 0) {
                    rootLayout.addView(feedbackCard, commentsIndex);
                } else {
                    rootLayout.addView(feedbackCard);
                }
            } else {
                rootLayout.addView(feedbackCard);
            }
        });
    }

    private String getStatusText(String status) {
        if (status == null) return "Неизвестно";

        switch (status.toLowerCase()) {
            case "draft": return "Черновик";
            case "submitted": return "Отправлено на проверку";
            case "graded": return "Оценено";
            case "reopened": return "Требует доработки";
            case "new":
            default: return "Не начато";
        }
    }

    private void addFileView(String fileName, String fileUrl, String category) {
        runOnUiThread(() -> {
            // Создаем контейнер для категории
            LinearLayout categoryContainer = new LinearLayout(this);
            categoryContainer.setOrientation(LinearLayout.VERTICAL);
            categoryContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            // Добавляем заголовок категории
            TextView categoryTitle = new TextView(this);
            categoryTitle.setText(category);
            categoryTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            categoryTitle.setTypeface(null, Typeface.BOLD);
            categoryTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            categoryTitle.setPadding(0, dpToPx(8), 0, dpToPx(4));

            categoryContainer.addView(categoryTitle);

            // Создаем кнопку файла
            MaterialButton fileButton = new MaterialButton(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dpToPx(8));
            fileButton.setLayoutParams(params);

            fileButton.setText(fileName);
            fileButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_file));
            fileButton.setIconGravity(MaterialButton.ICON_GRAVITY_START);
            fileButton.setIconSize(dpToPx(24));
            fileButton.setIconPadding(dpToPx(8));
            fileButton.setOnClickListener(v -> openFile(fileUrl));

            categoryContainer.addView(fileButton);
            filesContainer.addView(categoryContainer);
        });
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void openFile(String url) {
        new Thread(() -> {
            try {
                URL fileUrl = new URL(url + "?token=" + token);
                HttpURLConnection connection = (HttpURLConnection) fileUrl.openConnection();
                connection.connect();

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    // Получаем оригинальное имя файла из URL
                    String fileName = new File(fileUrl.getPath()).getName();

                    // Удаляем параметры из имени файла (если есть)
                    int queryIndex = fileName.indexOf('?');
                    if (queryIndex > -1) {
                        fileName = fileName.substring(0, queryIndex);
                    }

                    // Создаем файл с оригинальным именем
                    File outputDir = new File(getCacheDir(), "downloaded_files");
                    if (!outputDir.exists()) outputDir.mkdirs();

                    File outputFile = new File(outputDir, fileName);

                    InputStream input = connection.getInputStream();
                    try (FileOutputStream output = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }

                    // Открываем файл
                    Uri contentUri = FileProvider.getUriForFile(
                            AssignmentDetailActivity.this,
                            "com.example.mytpu.provider",
                            outputFile);

                    Intent openIntent = new Intent(Intent.ACTION_VIEW);
                    openIntent.setDataAndType(contentUri, getMimeType(fileName));
                    openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    startActivity(openIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error opening file", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка открытия файла", Toast.LENGTH_SHORT).show());
            }
        }).start();
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

    private void addFileToAssignment() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(
                    Intent.createChooser(intent, "Выберите файл(ы)"),
                    PICK_FILE_REQUEST
            );
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Установите файловый менеджер", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                if (data.getClipData() != null) {
                    // Множественный выбор
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri fileUri = data.getClipData().getItemAt(i).getUri();
                        selectedFiles.add(fileUri);
                        addSelectedFileView(fileUri);
                    }
                } else if (data.getData() != null) {
                    // Один файл
                    Uri fileUri = data.getData();
                    selectedFiles.add(fileUri);
                    addSelectedFileView(fileUri);
                }
            }
        }
    }

    private void addSelectedFileView(Uri fileUri) {
        runOnUiThread(() -> {
            String fileName = getFileName(fileUri);

            // Создаем контейнер для файла и кнопки удаления
            LinearLayout fileContainer = new LinearLayout(this);
            fileContainer.setOrientation(LinearLayout.HORIZONTAL);
            fileContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            fileContainer.setPadding(0, 0, 0, dpToPx(16));

            // Кнопка файла
            MaterialButton fileButton = new MaterialButton(this);
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1
            );
            fileButton.setLayoutParams(buttonParams);

            fileButton.setText(fileName);
            fileButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_right_arrow_down));
            fileButton.setIconGravity(MaterialButton.ICON_GRAVITY_START);
            fileButton.setIconSize(dpToPx(24));
            fileButton.setIconPadding(dpToPx(8));
            fileButton.setOnClickListener(v -> openLocalFile(fileUri));

            // Кнопка удаления
            ImageButton removeButton = new ImageButton(this);
            removeButton.setImageResource(R.drawable.ic_portal);
            removeButton.setBackgroundColor(Color.TRANSPARENT);
            removeButton.setOnClickListener(v -> {
                filesContainer.removeView(fileContainer);
                selectedFiles.remove(fileUri);
            });

            // Добавляем элементы в контейнер
            fileContainer.addView(fileButton);
            fileContainer.addView(removeButton);

            filesContainer.addView(fileContainer);
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void openLocalFile(Uri fileUri) {
        try {
            String fileName = getFileName(fileUri);
            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(fileUri, getMimeType(fileName)); // Используем корректный MIME-тип
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(openIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitAssignment() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Добавьте файлы для отправки", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnSubmit.setEnabled(false);

        // Создаем временную директорию для файлов
        File outputDir = new File(getCacheDir(), "submission");
        if (!outputDir.exists()) outputDir.mkdirs();

        List<File> filesToUpload = new ArrayList<>(); // Объявляем здесь
        try {
            for (Uri fileUri : selectedFiles) {
                InputStream in = getContentResolver().openInputStream(fileUri);
                File outFile = new File(outputDir, getFileName(fileUri));
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                filesToUpload.add(outFile);
            }

            // Реальный запрос на отправку
            HttpUrl url = HttpUrl.parse("https://stud.lms.tpu.ru/webservice/rest/server.php").newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "mod_assign_save_submission")
                    .addQueryParameter("assignmentid", String.valueOf(assignmentId))
                    .addQueryParameter("plugindata[files_filemanager]", new JSONObject()
                            .put("files", createFileArray(filesToUpload)).toString())
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", MediaType.parse("text/plain")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnSubmit.setEnabled(true);
                        Toast.makeText(AssignmentDetailActivity.this, "Ошибка отправки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnSubmit.setEnabled(true);

                        if (response.isSuccessful()) {
                            new AlertDialog.Builder(AssignmentDetailActivity.this)
                                    .setTitle("Отправка задания")
                                    .setMessage("Файлы успешно отправлены!")
                                    .setPositiveButton("OK", null)
                                    .show();
                        } else {
                            Toast.makeText(AssignmentDetailActivity.this, "Ошибка сервера: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "File copy or request error", e);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                btnSubmit.setEnabled(true);
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    private JSONArray createFileArray(List<File> files) throws JSONException {
        JSONArray fileArray = new JSONArray();
        for (File file : files) {
            JSONObject fileJson = new JSONObject();
            fileJson.put("filename", file.getName());
            fileJson.put("filepath", "/");
            fileJson.put("mimetype", getMimeType(file.getName()));
            fileArray.put(fileJson);
        }
        return fileArray;
    }

}