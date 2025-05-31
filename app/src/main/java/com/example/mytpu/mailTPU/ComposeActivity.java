package com.example.mytpu.mailTPU;

import static android.content.ContentValues.TAG;

import static com.example.mytpu.mailTPU.RoundcubeAPI.getMimeType;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mytpu.R;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ComposeActivity extends AppCompatActivity {
    private EditText toEditText, subjectEditText, bodyEditText;
    private Button sendButton;
    private ProgressBar progressBar;
    private List<Uri> attachments = new ArrayList<>();
    private WebView bodyWebView;
    // В ComposeActivity
    private static final int PICK_FILE_REQUEST = 1;
    private String htmlBody;

    private void setupAttachments() {
        Button attachButton = findViewById(R.id.attachButton);
        attachButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, PICK_FILE_REQUEST);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK) {
            if(data != null) {
                if(data.getClipData() != null) {
                    for(int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        attachments.add(uri);
                        addAttachmentView(uri);
                    }
                } else if(data.getData() != null) {
                    Uri uri = data.getData();
                    attachments.add(uri);
                    addAttachmentView(uri);
                }
            }
        }
    }

    private void addAttachmentView(Uri uri) {
        LinearLayout container = findViewById(R.id.attachmentsContainer);

        View view = LayoutInflater.from(this).inflate(R.layout.attachment_item, container, false);

        TextView fileName = view.findViewById(R.id.fileName);
        ImageButton removeBtn = view.findViewById(R.id.removeBtn);

        fileName.setText(getFileName(uri));
        removeBtn.setOnClickListener(v -> {
            attachments.remove(uri);
            container.removeView(view);
        });

        container.addView(view);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);
        bodyWebView = findViewById(R.id.bodyWebView);
        WebSettings webSettings = bodyWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setDomStorageEnabled(true);
        // Инициализация UI элементов
        toEditText = findViewById(R.id.toEditText);
        subjectEditText = findViewById(R.id.subjectEditText);
        bodyEditText = findViewById(R.id.bodyEditText);
        sendButton = findViewById(R.id.sendButton);
        progressBar = findViewById(R.id.progressBar);
        setupAttachments();
        // Обработка входящих параметров
        handleIntent(getIntent());

        sendButton.setOnClickListener(v -> sendEmail());
    }

    private void handleIntent(Intent intent) {
        String action = intent.getStringExtra("action");
        String originalFrom = intent.getStringExtra("original_sender"); // Исправленное имя
        String originalTo = intent.getStringExtra("original_recipient");
        String originalSubject = intent.getStringExtra("subject");
        String originalBody = intent.getStringExtra("body");

        if (action != null) {
            switch (action) {
                case "send":
                    setupNewMessage(
                            intent.getStringExtra("to"),
                            intent.getStringExtra("subject"),
                            intent.getStringExtra("body")
                    );
                    break;
                case "reply":
                    setupReply(originalFrom, originalSubject, originalBody);
                    break;
                case "reply_all":
                    setupReply(originalTo, originalSubject, originalBody);
                    break;
                case "forward":
                    setupForward(
                            originalSubject,
                            intent.getStringExtra("body") // Используем переданное HTML-тело
                    );
                    break;
            }
        }

        if (intent.hasExtra("attachments")) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("attachments");
            if (uris != null) {
                attachments.addAll(uris);
                for (Uri uri : uris) {
                    addAttachmentView(uri);
                }
            }
        }

    }

    private void setupNewMessage(@Nullable String to, @Nullable String subject, @Nullable String body) {
        // Устанавливаем переданные значения или оставляем пустыми
        if (!TextUtils.isEmpty(to)) {
            toEditText.setText(to);
        }

        if (!TextUtils.isEmpty(subject)) {
            subjectEditText.setText(subject);
        }

        if (!TextUtils.isEmpty(body)) {
            bodyEditText.setText(body);
        }

        // Очищаем вложения по умолчанию
        attachments.clear();
        LinearLayout container = findViewById(R.id.attachmentsContainer);
        container.removeAllViews();
    }

    private void setupForward(String subject, String encodedBody) {
        // Декодируем тело
        htmlBody = new String(Base64.decode(encodedBody, Base64.DEFAULT), StandardCharsets.UTF_8);

        // Устанавливаем тему
        String forwardSubject = subject.startsWith("Fwd:") ? subject : "Fwd: " + subject;
        subjectEditText.setText(forwardSubject);

        // Отображаем HTML в WebView
        bodyWebView.loadDataWithBaseURL(
                "https://letter.tpu.ru/",
                htmlBody,
                "text/html",
                "UTF-8",
                null
        );
    }


    private void setupReply(String recipients, String subject, String body) {
        toEditText.setText(recipients);
        subjectEditText.setText(subject.startsWith("Re:") ? subject : "Re: " + subject);
        bodyEditText.setText("\n\n---------- Original Message ----------\n" + body);
    }
    private boolean validateAttachments() {
        for (Uri uri : attachments) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    Toast.makeText(this, "Ошибка доступа к файлу: " + getFileName(uri), Toast.LENGTH_SHORT).show();
                    return false;
                }
                is.close();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка валидации файла", e);
                return false;
            }
        }
        return true;
    }

        private void sendEmail() {
        String to = toEditText.getText().toString().trim();
        String subject = subjectEditText.getText().toString().trim();
        String userMessage = bodyEditText.getText().toString().trim();

        // Формируем полное тело письма
        String fullBody;
        if (htmlBody != null) {
            // Обернем текст пользователя в HTML и добавим оригинал
            fullBody = "<div style='white-space: pre-wrap'>" + userMessage + "</div>" + htmlBody;
        } else {
            fullBody = userMessage;
        }

        // Валидация
        if (TextUtils.isEmpty(fullBody)) {
            Toast.makeText(this, "Введите текст письма", Toast.LENGTH_SHORT).show();
            return;
        }
            if (!validateAttachments()) {
                Toast.makeText(this, "Ошибка в проверке вложений", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d("ComposeActivity", "Attachments count: " + attachments.size());
            for (Uri uri : attachments) {
                Log.d("ComposeActivity", "Attachment URI: " + uri);
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        Log.e("ComposeActivity", "Failed to open URI: " + uri);
                    }
                } catch (IOException e) {
                    Log.e("ComposeActivity", "Error reading file", e);
                }
            }
        // Логирование для отладки
        Log.d("ComposeActivity", "Final HTML body: " + fullBody);
        Log.d("ComposeActivity", "═════════ SENDING EMAIL ═════════");
        Log.d("ComposeActivity", "Action: " + getIntent().getStringExtra("action"));
        Log.d("ComposeActivity", "To: " + to);
        Log.d("ComposeActivity", "Subject: " + subject);
        Log.d("ComposeActivity", "Body length: " + fullBody.length());
        Log.d("ComposeActivity", "Attachments count: " + attachments.size());

        if(TextUtils.isEmpty(to)) {
            Log.e("ComposeActivity", "Validation failed: empty recipients");
            Toast.makeText(this, "Введите получателей", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(subject)) {
            Toast.makeText(this, "Введите тему письма", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(fullBody)) {
            Toast.makeText(this, "Введите текст письма", Toast.LENGTH_SHORT).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        sendButton.setEnabled(false);

            Log.d(TAG, "═════════ EMAIL METADATA ═════════");
            Log.d(TAG, "To: " + to);
            Log.d(TAG, "Subject: " + subject);
            Log.d(TAG, "Body length: " + fullBody.length());
            Log.d(TAG, "Attachments: " + attachments.stream()
                    .map(uri -> getFileName(uri) + " (" + ")")
                    .collect(Collectors.joining(", ")));

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.d("ComposeActivity", "Starting background send process...");
                String csrfToken = MailActivity.refreshCsrfToken(this);
                Log.d("ComposeActivity", "CSRF token obtained: " + (csrfToken != null ? "[exists]" : "null"));

                boolean success;
                Intent intent = getIntent();
                String action = intent.getStringExtra("action");
                Log.d("ComposeActivity", "Processing action: " + action);
                if (csrfToken == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Сессия истекла", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                if ("forward".equals(action)) {
                    String originalEmailId = intent.getStringExtra("emailId");
                    Log.d("ComposeActivity", "Forwarding email ID: " + originalEmailId);

                    success = RoundcubeAPI.forwardEmail(
                            this,
                            originalEmailId,
                            to,
                            subject,
                            fullBody,
                            attachments,
                            csrfToken
                    );
                } else {
                    Log.d("ComposeActivity", "Sending new email");
                    success = RoundcubeAPI.sendComposedEmail(
                            this,
                            to,
                            subject,
                            fullBody,
                            attachments,
                            csrfToken
                    );
                }

                Log.d("ComposeActivity", "Send result: " + success);
                runOnUiThread(() -> {

                    progressBar.setVisibility(View.GONE);
                    sendButton.setEnabled(true);

                    if(success) {
                        Toast.makeText(this, "Письмо отправлено", Toast.LENGTH_SHORT).show();

                        finish();
                    } else {
                            Toast.makeText(this, "Ошибка отправки", Toast.LENGTH_SHORT).show();
                        }
                });
            } catch (Exception e) {
                Log.e("ComposeActivity", "Critical error during sending: ", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    sendButton.setEnabled(true);
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();

                });
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting filename from content URI", e);
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
}