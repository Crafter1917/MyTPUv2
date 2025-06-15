package com.example.mytpu;

import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.mailTPU.MailActivity;
import com.example.mytpu.moodle.DashboardActivity;
import com.example.mytpu.portalTPU.PortalAuthHelper;
import com.example.mytpu.schedule.ScheduleActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

public class MainActivity extends BaseActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle toggle;
    private FrameLayout fragmentContainer;
    private SharedPreferences sharedPreferences;
    private PortalAuthHelper portalAuthHelper;
    private boolean isNightMode;
    private ColorManager colorManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Получаем настройки темы ДО setContentView
        SharedPreferences prefs = getSharedPreferences("AppColors", MODE_PRIVATE);
        isNightMode = prefs.getBoolean("isNightMode", false);
        colorManager = new ColorManager(this, isNightMode);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_screen);

        portalAuthHelper = new PortalAuthHelper(this);
        fragmentContainer = findViewById(R.id.content_frame);

        initSharedPreferences();
        setupToolbar();
        setupNavigation();
        checkAuthState();

        // Применяем кастомные цвета
        applyCustomColors();
    }


    @Override
    protected void applyCustomColors() {
        ColorManager colorManager = ColorManager.getInstance(this, isNightMode);

        // Применяем цвета к элементам
        NavigationView navView = findViewById(R.id.nav_view);
        if (navView != null) {
            navView.setBackgroundColor(colorManager.getColor("card_background"));

            View headerView = navView.getHeaderView(0);
            if (headerView != null) {
                TextView emailTextView = headerView.findViewById(R.id.textViewEmail);
                emailTextView.setTextColor(colorManager.getColor("text_primary"));
            }
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(colorManager.getColor("primary_color"));
        }

        MaterialCardView card = findViewById(R.id.progress_card);
        if (card != null) {
            card.setCardBackgroundColor(colorManager.getColor("card_background"));
            card.setStrokeColor(colorManager.getColor("border_color"));

            TextView progressTitle = findViewById(R.id.progress_title);
            if (progressTitle != null) {
                progressTitle.setTextColor(colorManager.getColor("text_primary"));
            }

            TextView progressText = findViewById(R.id.progress_text);
            if (progressText != null) {
                progressText.setTextColor(colorManager.getColor("text_primary"));
            }

            TextView statsText = findViewById(R.id.stats_text);
            if (statsText != null) {
                statsText.setTextColor(colorManager.getColor("text_secondary"));
            }

            LinearProgressIndicator progressBar = findViewById(R.id.progress_bar);
            if (progressBar != null) {
                progressBar.setIndicatorColor(colorManager.getColor("progress_indicator_color"));
                progressBar.setTrackColor(colorManager.getColor("progress_track_color"));
            }
        }
    }

    private void showTodaySchedule() {
        unlockDrawer();
        updateNavHeaderUsername();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, new TodayScheduleFragment())
                .commit();

    }
    private void updateMainContent() {
        unlockDrawer();
        updateNavigationMenu();
        updateNavHeaderUsername();
        fragmentContainer.setVisibility(View.VISIBLE);
        showTodaySchedule();
        // Обновляем фрагмент вместо создания нового
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (currentFragment instanceof TodayScheduleFragment || currentFragment == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new TodayScheduleFragment())
                    .commit();
        }
    }

    private void initSharedPreferences() {
        try {
            sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e("MainActivity", "Error initializing secure preferences", e);
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }
    private void setupNavigation() {
        drawerLayout = findViewById(R.id.drawer_layout);
        toggle = new ActionBarDrawerToggle(
                this,
                drawerLayout,
                findViewById(R.id.toolbar),
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
        );

        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
    }

    private void checkAuthState() {
        fragmentContainer = findViewById(R.id.content_frame);

        Log.d(TAG, "checkAuthState!");
        if (isLoggedIn()) {
            updateMainContent(); // Используем обновленный метод
            Log.d(TAG, "isLoggedIn!");
        } else {
            fragmentContainer.setVisibility(View.VISIBLE);
            showLoginFragment();
            lockDrawer();
            Log.d(TAG, "isNotLoggedIn!");
        }
    }

    private boolean isLoggedIn() {
        return sharedPreferences != null &&
                sharedPreferences.contains("token");
    }

    private void showMainContent() {
        unlockDrawer();
        updateNavigationMenu();
        updateNavHeaderUsername(); // Добавьте этот вызов
        fragmentContainer.setVisibility(View.GONE);
    }

    private void updateNavHeaderUsername() {
        NavigationView navigationView = findViewById(R.id.nav_view);
        if (navigationView != null) {
            View headerView = navigationView.getHeaderView(0);
            if (headerView != null) {
                TextView emailTextView = headerView.findViewById(R.id.textViewEmail);
                String username = sharedPreferences.getString("username", "user@tpu.ru")+"@tpu.ru";
                emailTextView.setText(username);
            }
        }
    }
    private void showLoginFragment() {
        fragmentContainer.setVisibility(View.VISIBLE);
        replaceFragment(new MainScreen(), false); // Уберите добавление в back stack
        lockDrawer();
        updateNavigationMenu();
    }

    private void replaceFragment(Fragment fragment, boolean addToBackStack) {
        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, fragment);

        if (addToBackStack) {
            transaction.addToBackStack(null);
        }
        transaction.commit();
    }

    private void lockDrawer() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        toggle.setDrawerIndicatorEnabled(false);
    }

    private void unlockDrawer() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        toggle.setDrawerIndicatorEnabled(true);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_dashboard) {
            startActivity(new Intent(this, DashboardActivity.class));
        } else if (id == R.id.nav_schedule) {
            startActivity(new Intent(this, ScheduleActivity.class));
        } else if (id == R.id.nav_mail) {
            startActivity(new Intent(this, MailActivity.class));
        } else if (id == R.id.nav_portal) {
        portalAuthHelper.authenticateAndOpenPortal();
        } else if (id == R.id.nav_settings) {
        startActivity(new Intent(this, SettingsActivity.class));
        }
        else if (id == R.id.nav_logout) {
            logout();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }
    public void updateNavigationMenu() {
        NavigationView navigationView = findViewById(R.id.nav_view);
        Menu menu = navigationView.getMenu();
        boolean isLoggedIn = isLoggedIn();

        menu.findItem(R.id.nav_logout).setVisible(isLoggedIn);
    }
    private void logout() {
        if (sharedPreferences != null) {
            sharedPreferences.edit().clear().apply();
        }
        resetNavHeader(); // Добавьте этот вызов
        checkAuthState();
    }

    // Новый метод для сброса email
    private void resetNavHeader() {
        NavigationView navigationView = findViewById(R.id.nav_view);
        if (navigationView != null) {
            View headerView = navigationView.getHeaderView(0);
            if (headerView != null) {
                TextView emailTextView = headerView.findViewById(R.id.textViewEmail);
                emailTextView.setText("user@tpu.ru");
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        refreshContent();
    }

    private void refreshContent() {
        if (isLoggedIn()) {
            updateMainContent();
        }
    }
    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            super.onBackPressed();
        } else {
            finishAffinity();
        }
    }
}