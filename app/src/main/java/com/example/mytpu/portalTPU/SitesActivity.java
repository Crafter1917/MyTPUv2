package com.example.mytpu.portalTPU;


import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import com.example.mytpu.R;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.IOException;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SitesActivity extends AppCompatActivity {
    private static final String TAG = "SitesActivityPortal" ;
    private WebView webView;
    private ProgressBar progressBar;
    private PersistentCookieStore cookieStore;
    private OkHttpClient client;
    private String username;
    private String password;
    private boolean isFirstLoad = true;
    private boolean isProcessingRedirect = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sites);
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        cookieStore = PersistentCookieStore.getInstance(this);
        client = new OkHttpClient.Builder()
                .cookieJar(cookieStore)
                .followRedirects(false)
                .build();
        loadCredentials();
        webView.clearCache(true);
        webView.clearHistory();
        setupWebView();
        syncCookies();
        loadPortal();
    }

    private void loadCredentials() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedSharedPreferences prefs = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            username = prefs.getString("username", "");
            password = prefs.getString("password", "");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки учетных данных", e);
            Toast.makeText(this, "Ошибка безопасности", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isFirstLoad = true;
        isProcessingRedirect = false;
    }
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("https://oauth.tpu.ru")) { // HTTPS
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page finished: " + url);
                progressBar.setVisibility(View.GONE);

                // Обрабатываем только первый запуск и OAuth редиректы
                if (isFirstLoad || isProcessingRedirect) {
                    isFirstLoad = false;
                    isProcessingRedirect = false;
                    syncCookies();
                    checkAuthStatusOnce();
                }
            }
        });
    }

    private void checkAuthStatusOnce() {
        Log.d(TAG, "Checking authentication status once");
        webView.evaluateJavascript(
                "(function() { return document.body.innerHTML.includes('login-form'); })();",
                result -> {
                    if ("true".equals(result)) {
                        Log.d(TAG, "Login form detected - attempting auto-login");
                        new Thread(() -> {
                            try {
                                if (performLogin()) {
                                    Log.d(TAG, "Auto-login successful - reloading page");
                                    runOnUiThread(() -> webView.reload());
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Auto-login error", e);
                            }
                        }).start();
                    } else {
                        Log.d(TAG, "User is authenticated");
                    }
                }
        );
    }

    private void syncCookies() {
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptThirdPartyCookies(webView, true);
        manager.removeAllCookies(null);

        List<Cookie> cookies = PersistentCookieStore.getInstance(this).getCookies();
        Log.d(TAG, "Syncing " + cookies.size() + " cookies to WebView");

        for (Cookie cookie : cookies) {
            String domain = cookie.domain();
            // Форматируем куку
            String cookieStr = cookie.name() + "=" + cookie.value()
                    + "; Domain=" + domain
                    + "; Path=" + cookie.path()
                    + (cookie.secure() ? "; Secure" : "")
                    + (cookie.httpOnly() ? "; HttpOnly" : "");

            // Для Secure-кук используем ТОЛЬКО HTTPS
            if (cookie.secure()) {
                manager.setCookie("https://" + domain, cookieStr);
            }
            // Для обычных кук устанавливаем для HTTP/HTTPS
            else {
                manager.setCookie("http://" + domain, cookieStr);
                manager.setCookie("https://" + domain, cookieStr);
            }
        }
        manager.flush();
    }

    private boolean performLogin() throws IOException {
        // Логирование начала процесса авторизации
        Log.d(TAG, "Starting authorization process");
        Log.d(TAG, "Username: " + username);

        // 1. Запрос CSRF токена
        Request csrfRequest = new Request.Builder()
                .url("https://oauth.tpu.ru/auth/login.html") // HTTPS
                .build();


        Log.d(TAG, "Requesting CSRF token from: http://oauth.tpu.ru/auth/login.html");

        String token = null;
        try (Response csrfResponse = client.newCall(csrfRequest).execute()) {
            // Логирование ответа
            Log.d(TAG, "CSRF response code: " + csrfResponse.code());
            Log.d(TAG, "CSRF response headers: " + csrfResponse.headers());

            if (!csrfResponse.isSuccessful()) {
                Log.e(TAG, "CSRF request failed: " + csrfResponse.body().string());
                return false;
            }

            String html = csrfResponse.body().string();
            Document doc = Jsoup.parse(html);
            token = doc.selectFirst("input[name=_csrf]").attr("value");

            if (token == null || token.isEmpty()) {
                Log.e(TAG, "CSRF token not found in HTML");
                Log.e(TAG, "HTML content: " + html);
                return false;
            }

            Log.d(TAG, "CSRF token received: " + token);
        } catch (Exception e) {
            Log.e(TAG, "Error during CSRF request", e);
            return false;
        }

        // 2. Формирование запроса авторизации
        RequestBody body = new FormBody.Builder()
                .add("_csrf", token)
                .add("LoginForm[username]", username)
                .add("LoginForm[password]", password)
                .add("LoginForm[rememberMe]", "1")
                .add("login-button", "")
                .build();

        Request request = new Request.Builder()
                .url("https://oauth.tpu.ru/auth/login.html") // HTTPS
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://oauth.tpu.ru") // HTTPS
                .header("Referer", "https://oauth.tpu.ru/auth/login.html") // HTTPS
                .post(body)
                .build();

        Log.d(TAG, "Sending login request");

        // 3. Отправка запроса авторизации
        try (Response response = client.newCall(request).execute()) {
            // Логирование ответа
            Log.d(TAG, "Login response code: " + response.code());
            Log.d(TAG, "Login response headers: " + response.headers());

            if (response.code() == 302) {
                String location = response.header("Location");
                Log.d(TAG, "Redirect location: " + location);

                if (location != null) {
                    // Следуем за редиректом
                    Request redirectRequest = new Request.Builder()
                            .url(location)
                            .build();

                    try (Response redirectResponse = client.newCall(redirectRequest).execute()) {
                        Log.d(TAG, "Redirect response code: " + redirectResponse.code());
                        Log.d(TAG, "Redirect response headers: " + redirectResponse.headers());

                        if (redirectResponse.isSuccessful()) {
                            return true;
                        }
                    }
                }
            }

            // Если не 302, логируем тело ответа
            String responseBody = response.body().string();
            Log.e(TAG, "Unexpected response code: " + response.code());
            Log.e(TAG, "Response body: " + responseBody);

            // Попробуем найти ошибку в HTML
            Document errorDoc = Jsoup.parse(responseBody);
            String errorMsg = errorDoc.select(".error-message").text();
            if (!errorMsg.isEmpty()) {
                Log.e(TAG, "Error message from server: " + errorMsg);
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error during login request", e);
            return false;
        }
    }

    private void loadPortal() {
        webView.clearHistory();
        webView.clearCache(true);
        webView.loadUrl("https://lk.tpu.ru/");
    }
}