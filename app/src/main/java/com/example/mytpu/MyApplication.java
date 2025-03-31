package com.example.mytpu;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MyApplication extends Application {
    private OkHttpClient client;
    private static final String TAG = "MyApplication";
    private static final String PREFS_NAME = "secure_prefs";
    private static final String COOKIE_PREFS = "secure_cookies";
    private SharedPreferences encryptedPrefs;
    private String authToken;
    static PersistentCookieJar cookieJar = null;
    @Override
    public void onCreate() {
        super.onCreate();
        setupSecurePreferences();
        setupHttpClient();
    }
    public void setAuthToken(String token) {
        this.authToken = token;
    }
    private void setupSecurePreferences() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            encryptedPrefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to initialize secure preferences", e);
        }
    }

    private void setupHttpClient() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(message ->
                Log.d("HTTP", message));

        // Альтернатива без BuildConfig - всегда логируем в debug режиме
        boolean isDebug = true; // Замените на вашу логику определения debug режима
        loggingInterceptor.setLevel(isDebug ?
                HttpLoggingInterceptor.Level.BASIC :
                HttpLoggingInterceptor.Level.NONE);

        client = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .cookieJar(new PersistentCookieJar(this))
                .build();
    }

    public OkHttpClient getClient() {
        return client;
    }

    private static class PersistentCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        private final Application application;

        PersistentCookieJar(Application app) {
            this.application = app;
            loadPersistentCookies();
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            String host = url.host();
            cookieStore.put(host, cookies);
            savePersistentCookies();

        }

        public PersistentCookieJar getCookieJar() {
            return cookieJar;
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }

        private void savePersistentCookies() {
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                SharedPreferences prefs = EncryptedSharedPreferences.create(
                        COOKIE_PREFS,
                        masterKeyAlias,
                        application,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );

                // Здесь должна быть реализация сериализации куков
                // Например, используя Gson или другую библиотеку
                prefs.edit().putString("cookies", "serialized_cookies_data").apply();
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "Failed to save cookies", e);
            }
        }

        private void loadPersistentCookies() {
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                SharedPreferences prefs = EncryptedSharedPreferences.create(
                        COOKIE_PREFS,
                        masterKeyAlias,
                        application,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );

                String cookiesData = prefs.getString("cookies", null);
                if (cookiesData != null) {
                    // Здесь должна быть реализация десериализации куков
                }
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "Failed to load cookies", e);
            }
        }
    }
}