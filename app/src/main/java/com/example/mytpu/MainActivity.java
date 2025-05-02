package com.example.mytpu;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.moodle.DashboardActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String LOGIN_API_URL = "https://stud.lms.tpu.ru/login/token.php";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";

    private OkHttpClient client;
    private ExecutorService executor;
    private SharedPreferences sharedPreferences;
    private TextView logTextView;
    private EditText usernameField;
    private EditText passwordField;
    private Button loginButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        client = ((MyApplication) getApplication()).getClient();

        logTextView = findViewById(R.id.logTextView);
        usernameField = findViewById(R.id.username);
        passwordField = findViewById(R.id.password);
        loginButton = findViewById(R.id.loginButton);

        // Initialize EncryptedSharedPreferences
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e("MainActivity", "Error initializing secure preferences", e);
            // Handle error - clear corrupted data and retry
            try {
                SharedPreferences preferences = getSharedPreferences("user_credentials", MODE_PRIVATE);
                preferences.edit().clear().apply();
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = EncryptedSharedPreferences.create(
                        "user_credentials",
                        masterKeyAlias,
                        this,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception ex) {
                Log.e("MainActivity", "Critical error initializing preferences", ex);
                finish();
            }
        }

        executor = Executors.newSingleThreadExecutor();

        if (sharedPreferences != null &&
                sharedPreferences.contains("username") &&
                sharedPreferences.contains("password")) {
            executor.execute(this::autoLogin);
        }

        loginButton.setOnClickListener(v -> {
            String username = usernameField.getText().toString().trim();
            String password = passwordField.getText().toString().trim();

            if (!username.isEmpty() && !password.isEmpty()) {
                executor.execute(() -> login(username, password));
            } else {
                showToast("Введите логин и пароль");
            }
        });
    }

    private void login(String username, String password) {
        try {
            addLog("Попытка входа для: " + username);

            FormBody formBody = new FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .add("service", "moodle_mobile_app")
                    .build();

            Request request = new Request.Builder()
                    .url(LOGIN_API_URL)
                    .post(formBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();

                if (!response.isSuccessful()) {
                    handleLoginError(responseBody);
                    return;
                }

                JSONObject json = new JSONObject(responseBody);
                if (json.has("token")) {
                    String token = json.getString("token");
                    saveCredentials(username, password, token);
                    fetchUserInfo(token);
                    navigateToDashboard();
                } else {
                    handleLoginError(responseBody);
                }
            }
        } catch (IOException | JSONException e) {
            addLog("Ошибка: " + e.getMessage());
        }
    }

    private void handleLoginError(String responseBody) {
        try {
            JSONObject errorJson = new JSONObject(responseBody);
            String error = errorJson.optString("error", "Неизвестная ошибка");
            String errorCode = errorJson.optString("errorcode", "");

            runOnUiThread(() -> {
                if (errorCode.equals("invalidlogin")) {
                    passwordField.setError("Неверный логин или пароль");
                } else {
                    Toast.makeText(this, "Ошибка: " + error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (JSONException e) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Ошибка формата ответа", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void fetchUserInfo(String token) {
        try {
            HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                    .addQueryParameter("wstoken", token)
                    .addQueryParameter("wsfunction", "core_webservice_get_site_info")
                    .addQueryParameter("moodlewsrestformat", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    JSONObject json = new JSONObject(response.body().string());
                    if (json.has("userid")) {
                        int userId = json.getInt("userid");
                        sharedPreferences.edit().putInt("userid", userId).apply();
                        addLog("ID пользователя: " + userId);
                    }
                }
            }
        } catch (IOException | JSONException e) {
            addLog("Ошибка получения информации: " + e.getMessage());
        }
    }

    private void saveCredentials(String username, String password, String token) {
        if (sharedPreferences != null) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("username", username);
            editor.putString("password", password);
            editor.putString("token", token);
            editor.apply();
        }
        ((MyApplication) getApplication()).setAuthToken(token);
    }

    private void autoLogin() {
        if (sharedPreferences == null) return;

        String username = sharedPreferences.getString("username", null);
        String password = sharedPreferences.getString("password", null);

        if (username != null && password != null) {
            addLog("Автовход для: " + username);
            login(username, password);
        }
    }

    private void navigateToDashboard() {
        runOnUiThread(() -> {
            startActivity(new Intent(MainActivity.this, DashboardActivity.class));
            finish();
        });
    }

    private void addLog(String message) {
        runOnUiThread(() -> logTextView.append(message + "\n"));
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

}