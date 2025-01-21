package com.example.mytpu;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    static final String LOGIN_URL = "https://stud.lms.tpu.ru/login/index.php";

    // Получение общего OkHttpClient из MyApplication
    private OkHttpClient client;
    private ExecutorService executor;
    private SharedPreferences sharedPreferences;
    private TextView logTextView;
    private EditText usernameField;
    private EditText passwordField;
    private Button loginButton;
    private String sesskey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = ((MyApplication) getApplication()).getClient();
        // Инициализация представлений
        logTextView = findViewById(R.id.logTextView);
        usernameField = findViewById(R.id.username);
        passwordField = findViewById(R.id.password);
        loginButton = findViewById(R.id.loginButton);

        // Настройка безопасного хранилища
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Toast.makeText(this, "Ошибка инициализации безопасного хранилища", Toast.LENGTH_LONG).show();
            return;
        }

        executor = Executors.newSingleThreadExecutor();

        // Проверка сохраненных данных для автовхода
        if (sharedPreferences.contains("username") && sharedPreferences.contains("password")) {
            executor.execute(this::autoLogin);
        }

        // Настройка кнопки входа
        loginButton.setOnClickListener(v -> {
            String username = usernameField.getText().toString().trim();
            String password = passwordField.getText().toString().trim();

            if (!username.isEmpty() && !password.isEmpty()) {
                executor.execute(() -> login(username, password));
            } else {
                runOnUiThread(() ->
                        Toast.makeText(this, "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void addLog(String message) {
        runOnUiThread(() -> logTextView.append(message + "\n"));
    }

    private void login(String username, String password) {
        try {
            addLog("Начало логина для пользователя: " + username);

            Request getRequest = new Request.Builder()
                    .url(LOGIN_URL)
                    .build();

            try (Response getResponse = client.newCall(getRequest).execute()) {
                if (!getResponse.isSuccessful()) {
                    throw new IOException("Ошибка подключения: " + getResponse.code());
                }

                String responseBody = getResponse.body() != null ? getResponse.body().string() : "";
                if (responseBody.isEmpty()) {
                    throw new IOException("Получено пустое тело ответа.");
                }

                Document document = Jsoup.parse(responseBody);
                Element sesskeyElement = document.selectFirst("input[name=sesskey]");
                if (sesskeyElement != null) {
                    sesskey = sesskeyElement.attr("value");
                    addLog("Sesskey извлечен: " + sesskey);
                }

                Element tokenElement = document.selectFirst("input[name=logintoken]");
                if (tokenElement == null) {
                    throw new IOException("Не удалось найти logintoken на странице.");
                }

                String logintoken = tokenElement.attr("value");
                addLog("Получен logintoken: " + logintoken);

                FormBody.Builder formBuilder = new FormBody.Builder()
                        .add("anchor", "")
                        .add("logintoken", logintoken)
                        .add("username", username)
                        .add("password", password);

                Request postRequest = new Request.Builder()
                        .url(LOGIN_URL)
                        .post(formBuilder.build())
                        .build();

                try (Response postResponse = client.newCall(postRequest).execute()) {
                    if (!postResponse.isSuccessful()) {
                        throw new IOException("Ошибка входа. Проверьте логин и пароль.");
                    }

                    String postResponseUrl = postResponse.request().url().toString();
                    if (postResponseUrl.equals(LOGIN_URL)) {
                        throw new IOException("Ошибка входа. Проверьте логин и пароль.");
                    }

                    addLog("Успешный вход в систему!");
                    navigateToDashboard();
                    saveCredentials(username, password);
                }
            }
        } catch (IOException e) {
            addLog("Ошибка: " + e.getMessage());
        }
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
        startActivity(intent);
        finish(); // Завершаем MainActivity, чтобы пользователь не мог вернуться назад
    }

    private void saveCredentials(String username, String password) {
        sharedPreferences.edit()
                .putString("username", username)
                .putString("password", password)
                .apply();
        addLog("Данные пользователя сохранены.");
    }

    private void autoLogin() {
        String username = sharedPreferences.getString("username", null);
        String password = sharedPreferences.getString("password", null);

        if (username != null && password != null) {
            addLog("Выполняется автовход для пользователя: " + username);
            login(username, password);
        }
    }
}
