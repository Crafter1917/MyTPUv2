package com.example.mytpu.mailTPU;


import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.mytpu.R;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class EmailDetailActivity extends AppCompatActivity {
    private OkHttpClient client;
    private TextView senderTextView, subjectTextView, dateTextView;
    private ProgressBar progressBar;
    private String emailId;
    private String subject;
    private String from;
    private String date;
    private WebView webView;
    private static final String TAG = "EmailDetailActivity";
    static final int STORAGE_PERMISSION_CODE = 101;
    private List<RoundcubeAPI.Attachment> attachments = new ArrayList<>();
    private EmailDetailActivity context;
    private LinearLayout replyContainer;
    private EditText replyEditText;
    private String emailBody;
    private Button allowRemoteButton;
    private boolean allowRemoteResources = false;


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение получено", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Нужно разрешение для скачивания", Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_detail);
        context = this;
        webView = findViewById(R.id.webView);
        initReplyUI();
        // Инициализация UI
        progressBar = findViewById(R.id.progressBar);
        senderTextView = findViewById(R.id.senderTextView);
        subjectTextView = findViewById(R.id.subjectTextView);
        dateTextView = findViewById(R.id.dateTextView);
        client = MailActivity.MyMailSingleton.getInstance(this).getClient();
        allowRemoteButton = findViewById(R.id.allowRemoteButton);
        allowRemoteButton.setOnClickListener(v -> {
            allowRemoteResources = true;
            reloadEmailWithRemoteResources();
        });
        syncCookiesToWebView();
        // Извлекаем информацию из Intent
        emailId = getIntent().getStringExtra("email_id");
        subject = getIntent().getStringExtra("subject");
        from = getIntent().getStringExtra("from");
        date = getIntent().getStringExtra("date");
        if (savedInstanceState == null) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    // Проверка валидности сессии перед загрузкой
                    if (!MailActivity.isSessionValid(this)) {
                        if (!MailActivity.forceReauthenticate(this)) {
                            runOnUiThread(() -> finish());
                            return;
                        }
                    }
                    String content = RoundcubeAPI.fetchEmailContent(
                            EmailDetailActivity.this,
                            emailId,
                            false, // Добавляем параметр allowRemote
                            new RoundcubeAPI.AttachmentsCallback() {
                                @Override
                                public void onAttachmentsLoaded(List<RoundcubeAPI.Attachment> attachments) {
                                    runOnUiThread(() -> {
                                        if (!isDestroyed()) {
                                            setupAttachmentsRecycler(attachments);
                                        }
                                    });
                                }
                            }
                    );
                    emailBody = content; // Преобразуем HTML в текст

                    runOnUiThread(() -> {
                        if (!isDestroyed()) {
                            showHtml(content);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        // Проверяем тип исключения
                        if (e instanceof MailActivity.SessionExpiredException) {
                            allowRemoteButton.setVisibility(View.GONE);
                            webView.loadData("<h3>Ошибка сессии. Пожалуйста, перезайдите в приложение.</h3>",
                                    "text/html", "UTF-8");
                        } else if (e instanceof IOException) {
                            Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Неизвестная ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
            }
        });
    }
        // Установка данных на экран
        if (subject != null) subjectTextView.setText(subject);
        if (from != null) senderTextView.setText(from);
        if (date != null) dateTextView.setText(date);
        PeriodicWorkRequest cleanupRequest =
                new PeriodicWorkRequest.Builder(CacheCleanupWorker.class, 24, TimeUnit.HOURS)
                        .build();
        WorkManager.getInstance(this).enqueue(cleanupRequest);
        Executors.newSingleThreadExecutor().execute(() -> MailActivity.refreshCsrfToken(this));
    }

    // Добавьте эти поля в класс
    private void initReplyUI() {
        replyContainer = findViewById(R.id.reply_container);
        replyEditText = findViewById(R.id.reply_edit_text);
        ImageButton sendButton = findViewById(R.id.send_button);

        ImageButton forwardButton = findViewById(R.id.forward_button);
        forwardButton.setOnClickListener(v -> {
            Log.d("EmailDetailActivity", "═════════ FORWARD INIT ═════════");
            Log.d("EmailDetailActivity", "Original email ID: " + emailId);
            Log.d("EmailDetailActivity", "Attachments count: " + attachments.size());

            Intent intent = new Intent(EmailDetailActivity.this, ComposeActivity.class);
            intent.putExtra("action", "forward");
            intent.putExtra("subject", subject);
            intent.putExtra("emailId", emailId);

            String processedHtml = processHtmlForForward(String.valueOf(EmailDetailActivity.this));
            intent.putExtra("body", processedHtml);


            // Формируем HTML-заголовок
            String forwardedHeader = String.format(
                    "<div style='margin: 15px 0; border-left: 3px solid #ddd; padding: 10px 15px; color: #666;'>" +
                            "<p style='margin: 0 0 8px 0; font-weight: bold;'>─── Пересланное сообщение ───</p>" +
                            "<p style='margin: 0 0 5px 0;'>От: %s</p>" +
                            "<p style='margin: 0;'>Дата: %s</p>" +
                            "</div>",
                    from != null ? from : "Неизвестный отправитель",
                    new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date())
            );

            // Объединяем с оригинальным HTML
            String fullBody = forwardedHeader + emailBody;
            String encodedBody = Base64.encodeToString(fullBody.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
            intent.putExtra("body", encodedBody);

            // Передача вложений
            ArrayList<Uri> attachmentUris = new ArrayList<>();
            for (RoundcubeAPI.Attachment attachment : attachments) {
                try {
                    File file = attachment.getTempFile();
                    if (file == null || !file.exists()) continue;

                    Uri uri = FileProvider.getUriForFile(
                            EmailDetailActivity.this,
                            "com.example.mytpu.fileprovider", // Должно совпадать с манифестом
                            file
                    );
                    // Добавляем флаг доступа
                    context.grantUriPermission(
                            "com.example.mytpu", // Ваш пакет
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                    attachmentUris.add(uri);
                } catch (Exception e) {
                    Log.e(TAG, "Attachment error: " + e.getMessage());
                }
            }
            intent.putParcelableArrayListExtra("attachments", attachmentUris);
            startActivity(intent);
        });
        sendButton.setOnClickListener(v -> sendReply());
    }
    private String processHtmlForForward(String html) {
        Document doc = Jsoup.parse(html);
        Elements imgs = doc.select("img");

        // Генерируем новые CID для изображений
        int counter = 1;
        for (Element img : imgs) {
            String newCid = "img_" + System.currentTimeMillis() + "_" + counter++;
            img.attr("src", "cid:" + newCid);
        }

        return doc.html();
    }
    private void reloadEmailWithRemoteResources() {
        progressBar.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String content = RoundcubeAPI.fetchEmailContent(
                        EmailDetailActivity.this,
                        emailId,
                        true, // Разрешаем внешние ресурсы
                        new RoundcubeAPI.AttachmentsCallback() {
                            @Override
                            public void onAttachmentsLoaded(List<RoundcubeAPI.Attachment> attachments) {
                                runOnUiThread(() -> setupAttachmentsRecycler(attachments));
                            }
                        }
                );
                runOnUiThread(() -> {
                    showHtml(content);
                });
            } catch (IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка перезагрузки", Toast.LENGTH_SHORT).show());
            }
        });
    }
    private void sendReply() {
        String message = replyEditText.getText().toString().trim();
        if (message.isEmpty()) {
            Toast.makeText(this, R.string.enter_message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Collect attachment URIs
        ArrayList<Uri> attachmentUris = new ArrayList<>();
        for (RoundcubeAPI.Attachment attachment : attachments) {
            try {
                Uri uri = FileProvider.getUriForFile(
                        EmailDetailActivity.this,
                        "com.example.mytpu.fileprovider",
                        attachment.getTempFile()
                );
                attachmentUris.add(uri);
            } catch (Exception e) {
                Log.e(TAG, "Error creating URI for attachment", e);
            }
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String csrfToken = MailActivity.refreshCsrfToken(this);
                boolean success = RoundcubeAPI.sendEmail(
                        this,
                        emailId,
                        message,
                        csrfToken,
                        attachmentUris // Pass attachments here
                );

                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, R.string.message_sent, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.send_error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        FileLogger.close();
        super.onDestroy();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
    }

    

    private void configureWebView(String html) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // В EmailDetailActivity добавить:
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            private boolean pageLoaded = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageLoaded) {
                    pageLoaded = true;
                    progressBar.setVisibility(View.GONE);
                    view.evaluateJavascript(
                            "document.querySelectorAll('meta[http-equiv]').forEach(e => e.remove());",
                            null
                    );


                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("https://letter.tpu.ru/")) {
                    view.loadUrl(url);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }


        });
    }
    public void setHasBlockedResources(boolean hasBlocked) {
        runOnUiThread(() ->
                allowRemoteButton.setVisibility(hasBlocked ? View.VISIBLE : View.GONE));
    }
    private void showHtml(String html) {
        configureWebView(html);
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(html)) {
                webView.loadData("<h3>Ошибка загрузки письма</h3>", "text/html", "UTF-8");
                return;
            }
            TextView attachmentsLabel = findViewById(R.id.attachmentsLabel);
            attachmentsLabel.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);

            if (webView.getUrl() != null && webView.getUrl().startsWith("data:text/html")) {
                webView.loadUrl("about:blank");
            }

            String processedHtml = fixRelativeLinks(html);
            Log.d(TAG, "processedHtml load : "+ processedHtml);
            try {
                webView.loadDataWithBaseURL(
                        "https://letter.tpu.ru/",
                        processedHtml,
                        "text/html",
                        "UTF-8",
                        null
                );
            } catch (Exception e) {
                Log.d(TAG, "WebView load error"+ e);
            }
        });
    }

    private String fixRelativeLinks(String html) {
        try {
            Document doc = Jsoup.parse(html);
            String baseUrl = "https://letter.tpu.ru";

            doc.select("base").remove();
            doc.head().appendElement("base").attr("href", baseUrl);

            String[] attributes = {"href", "src", "data-src", "content"};
            for (String attr : attributes) {
                doc.select("[" + attr + "]").forEach(element -> {
                    String value = element.attr(attr);
                    if (!value.startsWith("http") && !value.startsWith("data:") && !value.startsWith("mailto:")) {
                        try {
                            URL url = new URL(new URL(baseUrl), value);
                            element.attr(attr, url.toString());
                        } catch (Exception e) {
                            Log.w(TAG, "Error resolving URL: " + value);
                        }
                    }
                });
            }

            return doc.html();
        } catch (Exception e) {
            Log.d(TAG, "Error fixing links"+ e);
            return html;
        }
    }

    private void syncCookiesToWebView() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptThirdPartyCookies(webView, true);
            cookieManager.removeAllCookies(null);

            HttpUrl mailUrl = HttpUrl.parse("https://letter.tpu.ru/");
            List<Cookie> cookies = client.cookieJar().loadForRequest(mailUrl);

            for (Cookie cookie : cookies) {
                String cookieStr = String.format("%s=%s; domain=%s; path=%s%s",
                        cookie.name(),
                        cookie.value(),
                        cookie.domain(),
                        cookie.path(),
                        cookie.secure() ? "; Secure" : "");

                cookieManager.setCookie(mailUrl.toString(), cookieStr);
            }
            CookieManager.getInstance().flush();
        } catch (Exception e) {
            Log.d(TAG, "Cookie sync error: "+ e);
        }
    }

    public void setupAttachmentsRecycler(List<RoundcubeAPI.Attachment> attachments) {
        runOnUiThread(() -> {
            Log.d(TAG, "Starting attachments setup");

            LinearLayout container = findViewById(R.id.CardLinearLayout); // Было CardLinearLayiut
            if (container == null) {
                Log.d(TAG, "⚠️ CardLinearLayout not found!");
                return;
            }


            // 2. Очищаем предыдущие элементы
            container.removeAllViews();

            // 3. Проверка наличия вложений
            if (attachments == null || attachments.isEmpty()) {
                Log.d(TAG, "No attachments to display");
                container.setVisibility(View.GONE);
                return;
            }

            // 4. Отображаем контейнер
            container.setVisibility(View.VISIBLE);

            // 5. Добавляем карточки
            for (RoundcubeAPI.Attachment attachment : attachments) {
                try {
                    Log.d(TAG, "Processing attachment: " + attachment.getFileName());
                    Log.d(TAG, "File path: " + attachment.getTempFile().getAbsolutePath());
                    // 6. Проверка существования файла
                    if (attachment.getTempFile() == null || !attachment.getTempFile().exists()) {
                        Log.d(TAG, "File missing: " + attachment.getFileName());
                        continue;
                    }

                    // 7. Создаем CardView
                    CardView card = (CardView) createAttachmentCardView(attachment);
                    if (card != null) {
                        // 8. Настраиваем параметры макета
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        params.setMargins(0, 0, 0, dpToPx(16));

                        // 9. Добавляем карточку в контейнер
                        container.addView(card, params);
                        Log.d(TAG, "Card added: " + attachment.getFileName());
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error creating card: " + e.getMessage());
                }
            }

            // 10. Принудительное обновление UI
            container.invalidate();
            container.requestLayout();
        });
    }

    private View createAttachmentCardView(RoundcubeAPI.Attachment attachment) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.attachment_card, null);

        ImageView ivBackground = cardView.findViewById(R.id.ivBackground);
        TextView tvFileName = cardView.findViewById(R.id.tvFileName);
        TextView fileIcon = cardView.findViewById(R.id.fileIcon);
        Button btnOpen = cardView.findViewById(R.id.btnOpen);
        Button btnDownload = cardView.findViewById(R.id.btnDownload);

        tvFileName.setText(attachment.getFileName());

        if (attachment.isImage()) {
            fileIcon.setVisibility(View.GONE);
            ivBackground.setVisibility(View.VISIBLE);

            // Загрузка изображения
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;

            try {
                Bitmap bitmap = BitmapFactory.decodeFile(attachment.getTempFile().getPath(), options);
                ivBackground.setImageBitmap(bitmap);
            } catch (Exception e) {
                Log.d(TAG, "Error loading image: " + e.getMessage());
                ivBackground.setVisibility(View.GONE);
                fileIcon.setVisibility(View.VISIBLE);
                fileIcon.setText("🖼️");
            }
        } else {
            ivBackground.setVisibility(View.GONE);
            fileIcon.setVisibility(View.VISIBLE);
            fileIcon.setText(getFileIcon(attachment.getFileType()));
        }

        // Обработчики кликов
        btnOpen.setOnClickListener(v -> handleOpenFile(attachment));
        btnDownload.setOnClickListener(v -> handleDownload(attachment));

        return cardView;
    }

    // Метод для получения иконки по типу файла
    private String getFileIcon(String fileType) {
        switch (fileType.toLowerCase()) { // Учитываем регистр
            case "pdf":
                return "📄";
            case "doc":
            case "docx":
                return "📝";
            case "xls":
            case "xlsx":
                return "📊";
            case "ppt":
            case "pptx":
                return "📊";
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
                return "📦";
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                return "🖼️";
            case "mp3":
            case "wav":
            case "ogg":
                return "🎵";
            case "mp4":
            case "avi":
            case "mov":
            case "mkv":
                return "🎥";
            case "txt":
            case "log":
                return "📃";
            case "csv":
            case "xml":
            case "json":
                return "📑";
            case "java":
            case "py":
            case "cpp":
            case "html":
            case "css":
            case "js":
                return "📜";
            case "exe":
            case "apk":
                return "⚙️";
            default:
                return "📁";
        }
    }

    private void handleOpenFile(RoundcubeAPI.Attachment attachment) {
        try {
            File file = attachment.getTempFile();
            Uri uri = FileProvider.getUriForFile(this,
                    "com.example.mytpu.provider", // Замените на ваш authority
                    file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getPath()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.d(TAG, "Error opening file: " + e.getMessage());
        }
    }

    private void handleDownload(RoundcubeAPI.Attachment attachment) {
        // Реализация сохранения файла в публичную директорию Downloads
        try {
            File sourceFile = attachment.getTempFile();
            String fileName = attachment.getFileName();

            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            contentValues.put(MediaStore.Downloads.MIME_TYPE, getMimeType(fileName));

            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);

            try (OutputStream os = resolver.openOutputStream(uri)) {
                Files.copy(sourceFile.toPath(), os);
            }

            Toast.makeText(this, "File saved to Downloads", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.d(TAG, "Download failed: " + e.getMessage());
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
        }
    }

    // Вспомогательный метод для определения MIME-типа
    private static String getMimeType(String fileName) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName).toLowerCase();
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

        // Ручная проверка для изображений
        if (type == null) {
            if (extension.equals("jpg") || extension.equals("jpeg")) return "image/jpeg";
            if (extension.equals("png")) return "image/png";
            if (extension.equals("gif")) return "image/gif";
        }
        return type != null ? type : "application/octet-stream";
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // В EmailDetailActivity измените объявление CacheCleanupWorker
    public static class CacheCleanupWorker extends Worker {
        public CacheCleanupWorker(@NonNull Context context,
                                  @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            RoundcubeAPI.cleanupOldFiles(getApplicationContext());
            return Result.success();
        }
    }

}