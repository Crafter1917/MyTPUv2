package com.example.mytpu.mailTPU;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mytpu.R;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private LinearLayout imagesContainer;
    private static final String TAG = "EmailDetailActivity";
    private LruCache<String, Uri> uriCache = new LruCache<>(50);
    static final int STORAGE_PERMISSION_CODE = 101;
    private List<Map<String, String>> attachments = new ArrayList<>();
    private EmailDetailActivity context;
    private String fileType;
    private LinearLayout replyContainer;
    private EditText replyEditText;
    private String currentAction;
    private String replySubject;

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
        configureWebView();
        syncCookiesToWebView();
        // Извлекаем информацию из Intent
        emailId = getIntent().getStringExtra("email_id");
        subject = getIntent().getStringExtra("subject");
        from = getIntent().getStringExtra("from");
        date = getIntent().getStringExtra("date");
        if (savedInstanceState == null) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    String content = RoundcubeAPI.fetchEmailContent(
                            EmailDetailActivity.this,
                            emailId,
                            attachments -> runOnUiThread(() -> {
                                if (!isDestroyed()) { // Проверка на уничтожение активити
                                    setupAttachmentsRecycler(attachments);
                                }
                            })
                    );

                    runOnUiThread(() -> {
                        if (!isDestroyed()) {
                            showHtml(content);
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        if (!isDestroyed()) {
                            Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show();
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
// В методе, где происходит загрузка контента письма

        WorkManager.getInstance(this).enqueue(cleanupRequest);
        // Асинхронное обновление токена
        Executors.newSingleThreadExecutor().execute(() -> MailActivity.refreshCsrfToken(this));
    }

    // Добавьте эти поля в класс


    // В методе onCreate после инициализации других элементов:
    private void initReplyUI() {
        replyContainer = findViewById(R.id.reply_container);
        replyEditText = findViewById(R.id.reply_edit_text);
        ImageButton sendButton = findViewById(R.id.send_button);

        ImageButton replyButton = findViewById(R.id.reply_button);
        ImageButton replyAllButton = findViewById(R.id.reply_all_button);
        ImageButton forwardButton = findViewById(R.id.forward_button);
        ImageButton moreActionsButton = findViewById(R.id.more_actions_button);

        replyButton.setOnClickListener(v -> prepareReply("reply"));
        replyAllButton.setOnClickListener(v -> prepareReply("reply_all"));
        forwardButton.setOnClickListener(v -> prepareReply("forward"));
        sendButton.setOnClickListener(v -> sendReply());
        moreActionsButton.setOnClickListener(this::showMoreActionsPopup);
    }

    private void prepareReply(String action) {
        currentAction = action;
        replyContainer.setVisibility(View.VISIBLE);

        // Формируем тему
        String prefix = "";
        switch (action) {
            case "reply":
            case "reply_all":
                prefix = "Re: ";
                break;
            case "forward":
                prefix = "Fwd: ";
                break;
        }

        String originalSubject = subjectTextView.getText().toString();
        replySubject = originalSubject.startsWith(prefix) ?
                originalSubject : prefix + originalSubject;

        // Формируем текст ответа
        String quote = "\n\n---------- Original Message ----------\n" +
                "From: " + from + "\n" +
                "Date: " + date + "\n" +
                "Subject: " + subject + "\n\n";

        replyEditText.setText(quote);
        replyEditText.requestFocus();
    }

    private void sendReply() {
        String message = replyEditText.getText().toString().trim();
        if (message.isEmpty()) {
            Toast.makeText(this, R.string.enter_message, Toast.LENGTH_SHORT).show();
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String csrfToken = MailActivity.refreshCsrfToken(this);
                boolean success = RoundcubeAPI.sendEmail(
                        this,
                        emailId,
                        currentAction,
                        replySubject,
                        message,
                        csrfToken
                );

                runOnUiThread(() -> {
                    if (success) {
                        replyContainer.setVisibility(View.GONE);
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

    private void showMoreActionsPopup(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.inflate(R.menu.email_actions_menu);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_delete) {
                deleteEmail();
                return true;
            } else if (item.getItemId() == R.id.action_mark_unread) {
                markAsUnread();
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void deleteEmail() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                boolean success = RoundcubeAPI.deleteEmail(this, emailId);
                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, R.string.email_deleted, Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, R.string.delete_error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void markAsUnread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                boolean success = RoundcubeAPI.markAsUnread(this, emailId);
                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, R.string.marked_unread, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.mark_error, Toast.LENGTH_SHORT).show();
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
        RoundcubeAPI.clearCache(this);
        super.onDestroy();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
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
                            "document.querySelectorAll('meta[http-equiv=Content-Security-Policy]').forEach(e => e.remove());",
                            null
                    );
                }
            }
        });
    }

    private void showHtml(String html) {

        runOnUiThread(() -> {
            TextView attachmentsLabel = findViewById(R.id.attachmentsLabel);
            attachmentsLabel.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);

            if (webView.getUrl() != null && webView.getUrl().startsWith("data:text/html")) {
                webView.loadUrl("about:blank");
            }

            String processedHtml = fixRelativeLinks(html);
            try {
                webView.loadDataWithBaseURL(
                        "https://letter.tpu.ru/",
                        processedHtml,
                        "text/html",
                        "UTF-8",
                        null
                );
            } catch (Exception e) {
                Log.e(TAG, "WebView load error", e);
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
            Log.e(TAG, "Error fixing links", e);
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
            Log.e(TAG, "Cookie sync error: ", e);
        }
    }

    public void setupAttachmentsRecycler(List<RoundcubeAPI.Attachment> attachments) {
        runOnUiThread(() -> {
            Log.d(TAG, "Starting attachments setup");

            LinearLayout container = findViewById(R.id.CardLinearLayout); // Было CardLinearLayiut
            if (container == null) {
                Log.e(TAG, "⚠️ CardLinearLayout not found!");
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
                        Log.e(TAG, "File missing: " + attachment.getFileName());
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
                    Log.e(TAG, "Error creating card: " + e.getMessage());
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
                Log.e(TAG, "Error loading image: " + e.getMessage());
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
                    "com.example.mytpu.fileprovider", // Замените на ваш authority
                    file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getPath()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error opening file: " + e.getMessage());
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
            Log.e(TAG, "Download failed: " + e.getMessage());
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
        }
    }

    // Вспомогательный метод для определения MIME-типа
    private String getMimeType(String url) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension == null) {
            // Дополнительная проверка для случаев, когда расширение не извлечено
            extension = url.substring(url.lastIndexOf('.') + 1);
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
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