// CookieManager.java
package com.example.mytpu.schedule;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class CookieManager {
    private SharedPreferences prefs;

    public CookieManager(Context context) {
        prefs = context.getSharedPreferences("Cookies", Context.MODE_PRIVATE);
    }

    public void saveCookies(Set<String> cookies) {
        prefs.edit().putStringSet("cookies", cookies).apply();
    }

    public Set<String> loadCookies() {
        return prefs.getStringSet("cookies", new HashSet<>());
    }
}