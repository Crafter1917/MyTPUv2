package com.example.mytpu.portalTPU;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

public class PersistentCookieStore implements CookieJar {
    private static final String TAG = "CookieStore";
    private static final String PREFS_NAME = "PersistentCookieStore";
    private static PersistentCookieStore instance;
    private static final String SAMESITE_LAX = "; SameSite=Lax";
    private final SharedPreferences preferences;
    private final List<Cookie> cookies = new ArrayList<>();

    public static PersistentCookieStore getInstance(Context context) {
        if (instance == null) {
            instance = new PersistentCookieStore(context.getApplicationContext());
        }
        return instance;
    }

    public synchronized void add(HttpUrl url, Cookie cookie) {
        // Удаляем старую куку если есть
        cookies.removeIf(c -> matches(c, cookie));

        // Всегда добавляем куку как персистентную
        Cookie persistentCookie = new Cookie.Builder()
                .name(cookie.name())
                .value(cookie.value())
                .domain(cookie.domain())
                .path(cookie.path())
                .expiresAt(cookie.persistent() ?
                        cookie.expiresAt() :
                        System.currentTimeMillis() + 86400000) // 24 часа
                .secure()
                .httpOnly()
                .build();

        cookies.add(persistentCookie);
        Log.d(TAG, "Persisting cookie: " + cookie.name() + " | domain: " + cookie.domain());
        saveCookiesToStorage();
    }

    private PersistentCookieStore(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            preferences = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            loadCookiesFromStorage();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to initialize cookie store", e);
        }
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        Log.d(TAG, "Saving cookies for: " + url.host());
        for (Cookie cookie : cookies) {
            Log.d(TAG, "Saving cookie: " + cookie.name() + "=" + cookie.value());
            add(url, cookie);
        }
        saveCookiesToStorage();
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        Log.d(TAG, "Loading cookies for: " + url.host());
        List<Cookie> matchingCookies = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Cookie cookie : cookies) {
            if (cookie.expiresAt() <= now) {
                Log.d(TAG, "Skipping expired cookie: " + cookie.name());
                continue;
            }

            if (cookie.matches(url)) {
                Log.d(TAG, "Adding cookie: " + cookie.name());
                matchingCookies.add(cookie);
            }
            Log.d(TAG, "Adding cookie: " + cookie.name() + " | domain: " + cookie.domain());
        }
        return matchingCookies;
    }

    public List<Cookie> getCookies() {
        return Collections.unmodifiableList(cookies);
    }

    private void loadCookiesFromStorage() {
        cookies.clear();
        String jsonString = preferences.getString("cookies", "[]");

        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                Cookie cookie = deserializeCookie(jsonArray.getJSONObject(i));
                if (cookie != null) cookies.add(cookie);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse cookies", e);
        }
    }

    private void saveCookiesToStorage() {
        JSONArray jsonArray = new JSONArray();
        for (Cookie cookie : cookies) {
            try {
                jsonArray.put(serializeCookie(cookie));
            } catch (JSONException e) {
                Log.e(TAG, "Failed to serialize cookie", e);
            }
        }

        preferences.edit()
                .putString("cookies", jsonArray.toString())
                .apply();
    }

    private JSONObject serializeCookie(Cookie cookie) throws JSONException {
        return new JSONObject()
                .put("name", cookie.name())
                .put("value", cookie.value())
                .put("expiresAt", cookie.expiresAt())
                .put("domain", cookie.domain())
                .put("path", cookie.path())
                .put("secure", cookie.secure())
                .put("httpOnly", cookie.httpOnly())
                .put("persistent", cookie.persistent())
                .put("hostOnly", cookie.hostOnly());
    }

    private Cookie deserializeCookie(JSONObject json) {
        try {
            Cookie.Builder builder = new Cookie.Builder()
                    .name(json.getString("name"))
                    .value(json.getString("value"))
                    .path(json.getString("path"));

            // Обработка срока действия
            if (json.has("expiresAt") && !json.isNull("expiresAt")) {
                builder.expiresAt(json.getLong("expiresAt"));
            }

            // Обработка домена
            String domain = json.getString("domain");
            if (json.getBoolean("hostOnly")) {
                builder.hostOnlyDomain(domain);
            } else {
                builder.domain(domain);
            }

            // Обработка флагов
            if (json.getBoolean("secure")) builder.secure();
            if (json.getBoolean("httpOnly")) builder.httpOnly();

            return builder.build();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to deserialize cookie", e);
            return null;
        }
    }

    private boolean matches(Cookie c1, Cookie c2) {
        return c1.name().equals(c2.name()) &&
                c1.domain().equals(c2.domain()) &&
                c1.path().equals(c2.path());
    }
}