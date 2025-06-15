package com.example.mytpu.portalTPU;

// PortalAuthHelper.java
import static com.example.mytpu.mailTPU.RoundcubeAPI.request;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.Headers;
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
    private final PersistentCookieStore cookieStore;


    public PortalAuthHelper(Context context) {
        this.context = context;
        this.cookieStore = PersistentCookieStore.getInstance(context); // Используем PersistentCookieStore

        this.client = new OkHttpClient.Builder()
                .cookieJar(cookieStore) // Используем cookieStore как CookieJar
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
                // Проверяем активность сессии
                if (hasSessionCookies()) {
                    Log.d(TAG, "Session exists - opening portal");
                    openSitesActivity();
                    return;
                }

                // Пытаемся выполнить логин
                if (performLogin()) {
                    Log.d(TAG, "Login successful - opening portal");
                    openSitesActivity();
                } else {
                    Log.e(TAG, "Login failed - opening portal anyway");
                    openSitesActivity(); // Все равно открываем
                }
            } catch (Exception e) {
                Log.e(TAG, "Auth error", e);
                openSitesActivity(); // Все равно открываем
            }
        }).start();
    }

    private boolean performLogin() throws IOException {
        // Шаг 1: Получить CSRF токен
        Request csrfRequest = new Request.Builder()
                .url("https://oauth.tpu.ru/auth/login.html") // HTTPS
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .build();
        if (hasSessionCookies()) {
            Log.d(TAG, "Session already active - skipping login");
            return true;
        }
        String token;
        try (Response csrfResponse = client.newCall(csrfRequest).execute()) {
            int statusCode = csrfResponse.code();

            // Обработка редиректа
            if (statusCode == 302) {
                Log.d(TAG, "Redirect detected - checking session");
                return hasSessionCookies(); // Проверить куки после редиректа
            }
            // Обработка CSRF ответа
            Log.d(TAG, "CSRF response code: " + csrfResponse.code());
            Log.d(TAG, "CSRF response headers: " + csrfResponse.headers());

            if (!csrfResponse.isSuccessful()) {
                Log.e(TAG, "CSRF request failed: " + csrfResponse.code());
                if (csrfResponse.body() != null) {
                    Log.e(TAG, "CSRF response body: " + csrfResponse.body().string());
                }
                return false;
            }

            String responseBody = csrfResponse.body().string();
            Document doc = Jsoup.parse(responseBody);
            org.jsoup.nodes.Element csrfElement = doc.selectFirst("input[name=_csrf]");

            if (csrfElement == null) {
                Log.e(TAG, "CSRF element not found in HTML");
                Log.e(TAG, "HTML content: " + responseBody);
                return false;
            }

            token = csrfElement.attr("value");
            if (token.isEmpty()) {
                Log.e(TAG, "CSRF token is empty");
                return false;
            }
            Log.d(TAG, "CSRF token retrieved: " + token);
        }

        RequestBody body = new FormBody.Builder()
                .add("_csrf", token)
                .add("LoginForm[username]", username)
                .add("LoginForm[password]", password)
                .add("LoginForm[rememberMe]", "1")
                .add("login-button", "")
                .build();

        Request loginRequest = new Request.Builder()
                .url("https://oauth.tpu.ru/auth/login.html") // HTTPS
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://oauth.tpu.ru") // HTTPS
                .header("Referer", "https://oauth.tpu.ru/auth/login.html") // HTTPS
                .post(body)
                .build();

        // Шаг 3: Выполнить запрос на вход и обработать редиректы
        try (Response response = client.newCall(loginRequest).execute()) {
            Log.d(TAG, "Login response code: " + response.code());
            Log.d(TAG, "Login response headers: " + response.headers());

            HttpUrl currentUrl = response.request().url();
            Response currentResponse = response;
            int redirectCount = 0;
            final int MAX_REDIRECTS = 5;

            while (currentResponse != null && currentResponse.isRedirect() && redirectCount < MAX_REDIRECTS) {
                redirectCount++;
                String location = currentResponse.header("Location");
                if (location == null) break;

                HttpUrl redirectUrl = currentUrl.resolve(location);
                if (redirectUrl == null) {
                    Log.e(TAG, "Failed to resolve redirect URL: " + location);
                    break;
                }

                Log.d(TAG, "Redirect #" + redirectCount + " to: " + redirectUrl);

                // Проверяем, если это финальный редирект с кодом
                if (location.contains("code=") && location.contains("lk.tpu.ru")) {
                    Log.d(TAG, "OAuth code received: " + location);
                    return handleAuthorizationCode(location);
                }

                Request redirectRequest = new Request.Builder()
                        .url(redirectUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                        .build();

                // Закрываем предыдущий ответ перед новым запросом
                if (currentResponse != response) {
                    currentResponse.close();
                }

                currentResponse = client.newCall(redirectRequest).execute();
                currentUrl = redirectUrl;


                Log.d(TAG, "Redirect response #" + redirectCount + " code: " + currentResponse.code());
            }

            // Проверяем, есть ли ответ после редиректов
            if (currentResponse != null) {

                if (currentResponse != response) {
                    currentResponse.close();
                }
            }

            return hasSessionCookies();
        } catch (Exception e) {
            Log.e(TAG, "Exception during login process", e);
            return false;
        }
    }

    private boolean handleAuthorizationCode(String redirectUrl) {
        try {
            Uri uri = Uri.parse(redirectUrl);
            String code = uri.getQueryParameter("code");
            String state = uri.getQueryParameter("state");

            if (code == null || state == null) {
                Log.e(TAG, "Authorization code or state missing");
                return false;
            }

            Log.d(TAG, "Exchanging code for session: " + code);
            return exchangeCodeForSession(code, state);
        } catch (Exception e) {
            Log.e(TAG, "Error handling authorization code", e);
            return false;
        }
    }

    private boolean exchangeCodeForSession(String code, String state) {
        try {
            // Добавляем client_secret в запрос
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", "http://lk.tpu.ru")
                    .add("client_id", "29")
                    .add("client_secret", "ваш_client_secret") // ДОБАВИТЬ СЕКРЕТ КЛИЕНТА
                    .build();

            Request request = new Request.Builder()
                    .url("http://oauth.tpu.ru/oauth/token")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "OAuth token exchange successful");
                    // Запрос финальной страницы
                    Request finalRequest = new Request.Builder()
                            .url("http://lk.tpu.ru/")
                            .build();
                    try (Response finalResponse = client.newCall(finalRequest).execute()) {
                        return finalResponse.isSuccessful();
                    }
                } else {
                    Log.e(TAG, "Token exchange failed: " + response.body().string());
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "OAuth token exchange error", e);
            return false;
        }
    }


    private boolean hasSessionCookies() {
        List<Cookie> cookies = cookieStore.getCookies(); // Получаем все куки напрямую

        Log.d(TAG, "Total cookies in store: " + cookies.size());
        for (Cookie cookie : cookies) {
            Log.d(TAG, "Cookie in store: " + cookie.name() + "=" + cookie.value() +
                    " | domain: " + cookie.domain() + " | path: " + cookie.path());

            if ("PHPSESSID".equals(cookie.name()) || "_identity".equals(cookie.name())) {
                Log.d(TAG, "Session cookie found: " + cookie.name());
                return true;
            }
        }
        Log.w(TAG, "No session cookies found");
        return false;
    }


    private void openSitesActivity() {
        if (!((Activity) context).isFinishing() && !((Activity) context).isDestroyed()) {
            ((Activity) context).runOnUiThread(() -> {
                Intent intent = new Intent(context, SitesActivity.class);
                context.startActivity(intent);
            });
        }
    }

    private void showToast(String message) {
        ((Activity) context).runOnUiThread(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}