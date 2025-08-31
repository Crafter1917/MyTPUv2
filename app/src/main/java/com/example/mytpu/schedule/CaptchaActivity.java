// CaptchaActivity.java
package com.example.mytpu.schedule;

import static com.example.mytpu.mailTPU.RoundcubeAPI.BASE_URL;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.webkit.CookieManager;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mytpu.R;

import org.json.JSONException;
import org.json.JSONObject;

public class CaptchaActivity extends AppCompatActivity {
    private static final String TAG = "CaptchaActivity";
    private WebView webView;
    private TPUScheduleParser tpuParser;
    private String purpose;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_captcha);

        tpuParser = new TPUScheduleParser(this);

        Intent intent = getIntent();
        String groupId = intent.getStringExtra("groupId");
        int year = intent.getIntExtra("year", 2025);
        int weekNumber = intent.getIntExtra("weekNumber", 1);
        purpose = intent.getStringExtra("purpose");

        if (purpose == null) {
            purpose = "captcha";
        }

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        String url;
        if ("decrypt".equals(purpose)) {
            url = String.format("https://rasp.tpu.ru/%s/%d/%d/view.html", groupId, year, weekNumber);
        } else {
            url = "https://rasp.tpu.ru/gruppa_43908/2025/1/view.html";
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if ("decrypt".equals(purpose)) {
                    handleDecryption();
                } else {
                    handleCaptcha();
                }
            }
        });

        webView.loadUrl(url);
    }

    private void handleCaptcha() {
        webView.evaluateJavascript("(function() { " +
                "var captcha = document.querySelector('input[type=submit]'); " +
                "var timetable = document.querySelector('.timetable'); " +
                "return { " +
                "  hasCaptcha: captcha !== null, " +
                "  hasSchedule: timetable !== null " +
                "}; " +
                "})();", value -> {
            try {
                JSONObject result = new JSONObject(value);
                if (!result.getBoolean("hasCaptcha") &&
                        result.getBoolean("hasSchedule")) {
                    saveCookies();
                    setResult(RESULT_OK);
                    finish();
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing page state", e);
            }
        });
    }

    private void handleDecryption() {
        new Handler().postDelayed(() -> {
            webView.evaluateJavascript(
                    "(function() { return document.documentElement.outerHTML; })();",
                    html -> {
                        try {
                            String cleanedHtml = html.replaceAll("^\"|\"$", "");

                            // Возвращаем результат через callback парсера
                            if (tpuParser != null && tpuParser.decryptionCallback != null) {
                                tpuParser.decryptionCallback.onDecryptionComplete(cleanedHtml);
                            }

                            setResult(RESULT_OK);
                            finish();
                        } catch (Exception e) {
                            Log.e(TAG, "Error: " + e.getMessage());
                            if (tpuParser != null && tpuParser.decryptionCallback != null) {
                                tpuParser.decryptionCallback.onDecryptionError(e);
                            }
                            setResult(RESULT_CANCELED);
                            finish();
                        }
                    }
            );
        }, 3000);
    }

    private void saveCookies() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie("https://rasp.tpu.ru");

            if (cookies != null && !cookies.isEmpty()) {
                String decodedCookies = Uri.decode(cookies);
                String normalizedCookies = decodedCookies
                        .replaceAll("\\s+", "")
                        .replaceAll(";+", ";")
                        .replaceAll("=+", "=");

                Log.d(TAG, "Normalized cookies: " + normalizedCookies);

                SharedPreferences prefs = getSharedPreferences("TPUCookies", MODE_PRIVATE);
                prefs.edit().putString("cookies", normalizedCookies).apply();

                SharedPreferences appPrefs = getApplicationContext()
                        .getSharedPreferences("TPUCookies", MODE_PRIVATE);
                appPrefs.edit().putString("cookies", normalizedCookies).apply();
                appPrefs.edit().commit();

                syncCookiesBetweenWebViewAndOkHttp();
                Log.d(TAG, "Cookies saved successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving cookies: " + e.getMessage());
        }
    }

    private void syncCookiesBetweenWebViewAndOkHttp() {
        try {
            android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
            String webViewCookies = cookieManager.getCookie("https://rasp.tpu.ru");

            if (webViewCookies != null && !webViewCookies.isEmpty()) {
                String decodedCookies = Uri.decode(webViewCookies);
                String normalizedCookies = decodedCookies
                        .replaceAll("\\s+", "")
                        .replaceAll(";+", ";")
                        .replaceAll("=+", "=");

                SharedPreferences prefs = getSharedPreferences("TPUCookies", MODE_PRIVATE);
                prefs.edit().putString("cookies", normalizedCookies).apply();

                if (tpuParser != null) {
                    tpuParser.forceSetCookies(normalizedCookies);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing cookies: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if ("decrypt".equals(purpose) && tpuParser != null && tpuParser.decryptionCallback != null) {
            tpuParser.decryptionCallback.onDecryptionError(new Exception("User cancelled decryption"));
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}