package com.example.mytpu;

import android.app.Application;
import android.util.Log;

import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;

import java.util.*;

public class MyApplication extends Application {
    private OkHttpClient client;

    @Override
    public void onCreate() {
        super.onCreate();


        // Настройка OkHttpClient
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        client = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .cookieJar(new CookieJar() {
                    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        if (cookies != null && !cookies.isEmpty()) {
                            cookieStore.put(url.host(), cookies);
                            Log.d("cookie", cookieStore.toString() );
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        return cookieStore.getOrDefault(url.host(), new ArrayList<>());
                    }
                })
                .build();
    }

    public OkHttpClient getClient() {
        return client;
    }
}
