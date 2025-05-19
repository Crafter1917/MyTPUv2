package com.example.mytpu.mailTPU;

import android.net.Uri;
import android.webkit.WebResourceRequest;

import java.util.HashMap;
import java.util.Map;

public class WebResourceRequestAdapter implements WebResourceRequest {
    private final String url;

    WebResourceRequestAdapter(String url) {
        this.url = url;
    }

    @Override
    public Uri getUrl() {
        return Uri.parse(url);
    }

    @Override public boolean isForMainFrame() { return true; }
    @Override public boolean isRedirect() { return false; }
    @Override public boolean hasGesture() { return false; }
    @Override public String getMethod() { return "GET"; }
    @Override public Map<String, String> getRequestHeaders() { return new HashMap<>(); }
}