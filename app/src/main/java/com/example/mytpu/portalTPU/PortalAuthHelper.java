package com.example.mytpu.portalTPU;

// PortalAuthHelper.java
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.mailTPU.PersistentCookieJar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PortalAuthHelper {
    private static final String TAG = "PortalAuthHelper";
    private final OkHttpClient client;
    private final Context context;
    private String username;
    private String password;
    private final PersistentCookieJar cookieJar;

    public PortalAuthHelper(Context context) {
        this.context = context;
        this.cookieJar = new PersistentCookieJar(context);

        this.client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(false)
                .build();

        setupSecureStorage();
    }

    private void setupSecureStorage() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedSharedPreferences mainPrefs = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            username = mainPrefs.getString("username", "");
            password = mainPrefs.getString("password", "");
        } catch (Exception e) {
            Log.e(TAG, "Secure storage error", e);
            showToast("Ошибка безопасности");
        }
    }

    public void authenticateAndOpenPortal() {
        new Thread(() -> {
            try {
                cookieJar.clear();
                if (performLogin()) {
                    openSitesActivity();
                } else {
                    showToast("Ошибка авторизации");
                }
            } catch (Exception e) {
                Log.e(TAG, "Auth error", e);
                showToast("Ошибка подключения");
            }
        }).start();
    }

    private boolean performLogin() throws IOException {
        // Получение CSRF токена
        Request csrfRequest = new Request.Builder()
                .url("https://oauth.tpu.ru/auth/login.html")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build();

        try (Response csrfResponse = client.newCall(csrfRequest).execute()) {
            Document doc = Jsoup.parse(csrfResponse.body().string());
            String token = doc.selectFirst("input[name=_csrf]").attr("value");

            // Добавьте проверку токена
            if (token == null || token.isEmpty()) {
                Log.e(TAG, "CSRF token is missing");
                return false;
            }

            // Формируем тело запроса
            RequestBody body = new FormBody.Builder()
                    .add("_csrf", token)
                    .add("LoginForm[username]", username)
                    .add("LoginForm[password]", password)
                    .add("LoginForm[rememberMe]", "1")
                    .add("login-button", "") // Добавьте недостающий параметр
                    .build();

            // Отправка запроса с нужными заголовками
            Request request = new Request.Builder()
                    .url("https://oauth.tpu.ru/auth/login.html")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://oauth.tpu.ru")
                    .header("Referer", "https://oauth.tpu.ru/auth/login.html")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                // Проверяем редирект
                if (response.code() == 302) {
                    String location = response.header("Location");
                    return location != null && location.contains("authorize");
                }
                return false;
            }
        }
    }



    private void openSitesActivity() {
        ((Activity) context).runOnUiThread(() -> {
            Intent intent = new Intent(context, SitesActivity.class);
            context.startActivity(intent);
        });
    }

    private void showToast(String message) {
        ((Activity) context).runOnUiThread(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}