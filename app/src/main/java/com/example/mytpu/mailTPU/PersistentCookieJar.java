package com.example.mytpu.mailTPU;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

// PersistentCookieJar.java
public class PersistentCookieJar implements CookieJar {
    private final SharedPreferences prefs;

    public PersistentCookieJar(Context context) {
        try {
            String masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            prefs = EncryptedSharedPreferences.create(
                    "mail_cookies",
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            throw new RuntimeException("CookieJar init failed", e);
        }
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        Set<String> cookieStrings = new HashSet<>();
        for (Cookie cookie : cookies) {
            // Save only necessary session cookies
            if (cookie.name().startsWith("roundcube_") && !cookie.persistent()) {
                String cookieStr = cookie.toString();
                cookieStrings.add(cookieStr);
            }
        }
        prefs.edit().putStringSet("cookies", cookieStrings).apply();
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        return prefs.getStringSet("cookies", new HashSet<>()).stream()
                .map(c -> Cookie.parse(url, c))
                .filter(Objects::nonNull)
                .filter(c -> c.expiresAt() == 0 || c.expiresAt() > System.currentTimeMillis())
                .collect(Collectors.toList());
    }

    public void clear() {
        prefs.edit().remove("cookies").apply();
    }
}