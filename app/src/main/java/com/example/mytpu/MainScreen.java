package com.example.mytpu;
import static org.chromium.base.ThreadUtils.runOnUiThread;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;


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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

public class MainScreen extends Fragment {
    private static final String LOGIN_API_URL = "https://stud.lms.tpu.ru/login/token.php";
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";

    private OkHttpClient client;
    private ExecutorService executor;
    private SharedPreferences sharedPreferences;
    private TextView logTextView;
    private EditText usernameField;
    private EditText passwordField;
    private Button loginButton;
    private MainActivity activity;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            activity = (MainActivity) context;
        } else {
            throw new RuntimeException("Must be attached to MainActivity");
        }
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_main, container, false);

        // Инициализация элементов через view
        logTextView = view.findViewById(R.id.logTextView);
        usernameField = view.findViewById(R.id.username);
        passwordField = view.findViewById(R.id.password);
        loginButton = view.findViewById(R.id.loginButton);

        // Получение контекста приложения
        client = ((MyApplication) requireActivity().getApplication()).getClient();
        // Initialize EncryptedSharedPreferences
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
            Log.e("MainActivity", "Error initializing secure preferences", e);
            // Handle error - clear corrupted data and retry
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = EncryptedSharedPreferences.create(
                        "user_credentials",
                        masterKeyAlias,
                        requireContext(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception ex) {
                Log.e("MainActivity", "Critical error initializing preferences", ex);
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
        return view;
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
                    Toast.makeText(getActivity(), "Ошибка: " + error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (JSONException e) {
            runOnUiThread(() ->
                    Toast.makeText(getActivity(), "Ошибка формата ответа", Toast.LENGTH_SHORT).show()
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

        // Исправленная строка:
        ((MyApplication) requireActivity().getApplication()).setAuthToken(token);
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
        requireActivity().runOnUiThread(() -> {
            startActivity(new Intent(requireActivity(), MainActivity.class));
            requireActivity().finish();
        });
    }

    private void addLog(String message) {
        runOnUiThread(() -> logTextView.append(message + "\n"));
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(requireActivity(), message, Toast.LENGTH_SHORT).show());
    }

    // Остальные методы с корректировкой контекста (getActivity() вместо getActivity())
}
