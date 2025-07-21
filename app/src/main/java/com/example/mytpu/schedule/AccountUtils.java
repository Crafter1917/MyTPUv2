// AccountUtils.java
package com.example.mytpu.schedule;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.regex.Pattern;

public class AccountUtils {
    private static final String TAG = "AccountUtils";

    public static boolean hasGoogleAccount(Context context) {
        // Проверяем статус "выхода"
        SharedPreferences prefs = context.getSharedPreferences("AccountPrefs", Context.MODE_PRIVATE);
        if (prefs.getBoolean("is_logged_out", false)) {
            return false;
        }

        try {
            AccountManager am = AccountManager.get(context);
            Pattern googlePattern = Pattern.compile(".*@gmail\\.com|.*@googlemail\\.com", Pattern.CASE_INSENSITIVE);

            if (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }

            Account[] accounts = am.getAccounts();
            for (Account account : accounts) {
                if (googlePattern.matcher(account.name).matches()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Google accounts: " + e.getMessage());
            return false;
        }
    }
}