package com.example.mytpu.portalTPU;

import static android.content.ContentValues.TAG;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.mytpu.R;
import com.example.mytpu.mailTPU.PersistentCookieJar;

import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

// SitesActivity.java
public class SitesActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sites);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();
        loadPortal();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                syncCookies();
                checkAuthStatus();
            }
        });
    }

    private void syncCookies() {
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptThirdPartyCookies(webView, true);
        manager.removeAllCookies(null);

        PersistentCookieJar cookieJar = new PersistentCookieJar(this);
        List<Cookie> cookies = cookieJar.loadForRequest(HttpUrl.get("https://oauth.tpu.ru"));

        for (Cookie cookie : cookies) {
            String cookieStr = cookie.toString();
            manager.setCookie(cookie.domain(), cookieStr);
        }
        manager.flush();
    }

    private void checkAuthStatus() {
        webView.evaluateJavascript(
                "document.documentElement.outerHTML",
                html -> {
                    if (html.contains("login-form")) {
                        Toast.makeText(this, "Требуется авторизация", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void loadPortal() {
        webView.loadUrl("https://lk.tpu.ru/");
    }
}