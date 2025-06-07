package com.example.mytpu.moodle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.IOException;
import java.security.GeneralSecurityException;

public class SharedPreferencesHelper {
    private static final String PREFS_NAME = "secure_prefs";

    public static String getToken(Context context) throws GeneralSecurityException, IOException {
        SharedPreferences prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
        // В любом Activity
        try {
            int userId = SharedPreferencesHelper.getUserId(context);
            String token = SharedPreferencesHelper.getToken(context);
            Log.d("Test", "UserID: " + userId + ", Token: " + token);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return prefs.getString("token", null);
    }

    public static int getUserId(Context context) throws GeneralSecurityException, IOException {
        SharedPreferences prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
        return prefs.getInt("userid", 0);
    }

    public static void saveUserId(Context context, int userId) throws GeneralSecurityException, IOException {
        SharedPreferences prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
        prefs.edit().putInt("userid", userId).apply();
    }
}