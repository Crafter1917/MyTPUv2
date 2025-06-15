package com.example.mytpu.mailTPU;


import static android.content.ContentValues.TAG;
import static com.example.mytpu.mailTPU.MailCheckWorker.KEY_LAST_UIDS;
import static com.example.mytpu.mailTPU.MailCheckWorker.PREFS_NAME;
import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.example.mytpu.R;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MailActivity extends AppCompatActivity {
    private SharedPreferences mainPrefs;
    private AlarmManager alarmManager;
    private PendingIntent alarmPendingIntent;
    private RecyclerView emailsRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ExtendedFloatingActionButton fabCompose;
    private ExecutorService executor;
    private static SharedPreferences sharedPreferences;
    private EmailAdapter adapter;
    private static String username;
    private static String password;
    private boolean isActivityDestroyed = false;
    private long lastToastTime = 0;
    private static final long TOAST_INTERVAL = 3000; // 3 секунды
    private Handler sessionHandler = new Handler();
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    public static Context context;
    private NavigationView navigationView;
    private DrawerLayout drawerLayout;
    private String currentFolder = "INBOX";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private ScheduledExecutorService scheduler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mail);
        context = this;
        initSharedPreferences(this);
        executor = Executors.newFixedThreadPool(2);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        startCsrfTokenRefresh();
        initNavigationDrawer();
        initViews();
        setupSecureStorage();checkAuthAndLoadEmails();
        setupRefresh();

        requestNotificationPermission();
        WorkManager.getInstance(this).enqueueUniqueWork(
                "mailCheck",
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                        .setInitialDelay(0, TimeUnit.MINUTES)
                        .build()
        );
        observeWorkManager();
        //findViewById(R.id.test_button).setOnClickListener(v -> {
        //    WorkManager.getInstance(this).enqueue(
        //            new OneTimeWorkRequest.Builder(MailCheckWorker.class).build()
        //    );
        //});

        initAlarmSystem();
    }
    private void initAlarmSystem() {
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, MailReceiver.class);
        alarmPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Запускаем первый вызов сразу
        scheduleNextAlarm(2);
    }
    public void scheduleNextAlarm(long delayMinutes) {
        long triggerAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delayMinutes);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    alarmPendingIntent
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    alarmPendingIntent
            );
        } else {
            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    alarmPendingIntent
            );
        }

        Log.d(TAG, "Next alarm scheduled in " + delayMinutes + " minutes");
    }
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Уведомления отключены", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // MailActivity.java
    public static void initSharedPreferences(Context context) {
        if (sharedPreferences == null) {
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = EncryptedSharedPreferences.create(
                        "mail_credentials",
                        masterKeyAlias,
                        context, // Используем переданный контекст
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                Log.e(TAG, "SharedPreferences init failed", e);
            }
        }
    }
    private void startCsrfTokenRefresh() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (isActivityDestroyed) return;

            try {
                refreshCsrfTokenSync();
            } catch (IOException e) {
                Log.w(TAG, "Periodic CSRF refresh failed");
            }
        }, 0, 4, TimeUnit.MINUTES); // Каждые 5 минут
    }


    private void initNavigationDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        navigationView.setNavigationItemSelectedListener(item -> {
            swipeRefreshLayout.setRefreshing(true);
            int id = item.getItemId();
            String newFolder = "";

            if (id == R.id.nav_inbox) {
                newFolder = "INBOX";
            } else if (id == R.id.nav_drafts) {
                newFolder = "Drafts";
            } else if (id == R.id.nav_sent) {
                newFolder = "Sent";
            } else if (id == R.id.nav_spam) {
                newFolder = "Junk";
            } else if (id == R.id.nav_trash) {
                newFolder = "Trash";
            } else if (id == R.id.nav_massmail) {
                newFolder = "Massmail";
            }

            if (!newFolder.isEmpty() && !newFolder.equals(currentFolder)) {
                currentFolder = newFolder;
                currentPage = 1;
                hasMore = true;
                updateToolbarTitle(); // Обновляем заголовок
                loadEmailsInternal(true);
            }

            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Инициализация кнопки меню
        ImageButton menuButton = findViewById(R.id.menu_button);
        menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
    }

    private void updateToolbarTitle() {
        runOnUiThread(() -> {
            Toolbar toolbar = findViewById(R.id.toolbar);
            toolbar.setTitle(getFolderTitle(currentFolder));
        });
    }

    public static String getCsrfToken(Context context) {
        if (sharedPreferences == null) {
            throw new IllegalStateException("SharedPreferences not initialized");
        }
        return sharedPreferences.getString("csrf_token", "");
    }

    public static String refreshCsrfToken(Context context) {
        OkHttpClient client = MyMailSingleton.getInstance(context).getClient();
        String newToken = "";

        Request request = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=mail")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String html = response.body().string();
            newToken = extractCsrfToken(html); // Извлечение токена из HTML
            sharedPreferences.edit()
                    .putString("csrf_token", newToken)
                    .apply();
        } catch (IOException e) {
            Log.d(TAG, "CSRF refresh failed"+e);
        }

        return newToken; // Возвращаем токен
    }

    public static String getCookiesForWebView() {
        List<Cookie> cookies = MyMailSingleton.getInstance(context)
                .cookieJar.loadForRequest(HttpUrl.get("https://letter.tpu.ru"));

        StringBuilder cookieBuilder = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (cookie.matches(HttpUrl.get("https://letter.tpu.ru"))) {
                cookieBuilder.append(cookie.name())
                        .append("=")
                        .append(cookie.value())
                        .append("; ");
            }
        }
        return cookieBuilder.toString();
    }

    private void initViews() {
        emailsRecyclerView = findViewById(R.id.emailsRecyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        fabCompose = findViewById(R.id.fabCompose);

        // Правильная инициализация адаптера
        adapter = new EmailAdapter(new ArrayList<>(), email -> {
            String emailId = email.getId();
            if (emailId == null || emailId.isEmpty()) {
                showToast("Ошибка загрузки письма");
                return;
            }


            executor.execute(() -> {
                try {
                    Intent intent = new Intent(this, EmailDetailActivity.class);
                    intent.putExtra("email_id", emailId);
                    intent.putExtra("subject", email.getSubject());
                    intent.putExtra("from", email.getFrom());
                    intent.putExtra("date", email.getDate());
                    startActivity(intent);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        showToast("Ошибка загрузки содержимого");
                    });
                }
            });
        });
        emailsRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                int totalItemCount = layoutManager.getItemCount();

                if (!isLoading && hasMore && (lastVisibleItem >= totalItemCount - 5)) {
                    loadNextPage();
                }
                int visibleItemCount = layoutManager.getChildCount();

                int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                if (!isLoading && hasMore) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                            && firstVisibleItemPosition >= 0) {
                        loadNextPage();
                    }
                }
            }
        });
        emailsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        emailsRecyclerView.setAdapter(adapter);

        fabCompose.setOnClickListener(v -> startActivity(new Intent(this, ComposeActivity.class)));
    }

    private void loadNextPage() {
        isLoading = true;
        currentPage++;
        loadEmailsInternal(false);
    }

    private void setupSecureStorage() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

            mainPrefs = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            username = mainPrefs.getString("username", "");
            password = mainPrefs.getString("password", "");

            sharedPreferences = EncryptedSharedPreferences.create(
                    "mail_credentials",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

        } catch (Exception e) {
            Log.d("MailActivity", "Secure storage error"+ e);
            showToast("Ошибка безопасности");
            finish();
        }
    }

    public void setupRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            currentPage = 1;
            hasMore = true;
            Log.d(TAG,"Свайп был для обновления страницы");
            loadEmailsInternal(true);
        });
    }

    private void checkAuthAndLoadEmails() {
        executor.execute(() -> {
            try {
                // Проверка валидности учетных данных
                if (username.isEmpty() || password.isEmpty()) {
                    runOnUiThread(() -> showToast("Введите логин и пароль"));
                    return;
                }

                // Ограничение попыток переаутентификации
                int maxAttempts = 2;
                for (int attempt = 0; attempt < maxAttempts; attempt++) {
                    if (isSessionValid(context)) break;

                    Log.d("Session", "Reauthentication attempt: " + (attempt + 1));
                    boolean authSuccess = performReauthentication();

                    if (!authSuccess && attempt == maxAttempts - 1) {
                        throw new SessionExpiredException("Max auth attempts reached");
                    }

                    // Задержка между попытками
                    if (!authSuccess) Thread.sleep(2000);
                }

                // Принудительное обновление CSRF
                refreshCsrfTokenSync();

                // Загрузка писем
                loadEmailsInternal(true);

            } catch (SessionExpiredException e) {
                handleSessionExpired();
            } catch (Exception e) {
                handleError("Fatal error", e);
            }
        });
    }

    void refreshCsrfTokenSync() throws IOException {
        if (isActivityDestroyed) return;
        OkHttpClient client = MyMailSingleton.getInstance(this).getClient();

        Request request = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=mail")
                .header("Cache-Control", "no-cache")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String html = response.body().string();
                String newCsrf = extractCsrfToken(html);
                Log.d(TAG, "CSRF token refreshed: " + (newCsrf));
                if (!newCsrf.isEmpty()) {
                    sharedPreferences.edit().putString("csrf_token", newCsrf).apply();
                }
            }
        }


    }

    private void checkForSessionError(JSONArray response) throws SessionExpiredException {
        try {
            if (response.length() > 0 && response.getJSONObject(0).has("exec")) {
                String exec = response.getJSONObject(0).getString("exec");
                if (exec.contains("session_error")) {
                    throw new SessionExpiredException("Session expired (server response)");
                }
            }
        } catch (JSONException e) {
            Log.d(TAG, "Error parsing session error"+ e);

        }
    }

    private void loadEmailsInternal(boolean clear) {
        executor.execute(() -> {
            try {
                JSONArray messages = RoundcubeAPI.fetchMessages(MailActivity.this, currentPage, currentFolder); // Передаем currentPage
                List<Email> newEmails = parseMessages(messages);
                if (messages.length() == 0) {
                    checkForSessionError(messages);
                }
                hasMore = messages.length() >= 50; // Предполагаем, что страниц по 50 элементов

                runOnUiThread(() -> {
                    if (clear) {
                        adapter.clearData();
                    }
                    adapter.addData(newEmails);
                    swipeRefreshLayout.setRefreshing(false);
                    isLoading = false;
                });
                if (clear && messages.length() > 0) {
                    Set<String> currentUids = new HashSet<>();
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject msg = messages.getJSONObject(i);
                        currentUids.add(msg.optString("uid", ""));
                    }

                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs.edit()
                            .putStringSet(KEY_LAST_UIDS, currentUids)
                            .apply();
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    handleError("Load error", e);
                    isLoading = false;
                });
            }
        });
    }

    public static void handleCsrfError(Context context) {
        // Ваша логика обработки ошибки CSRF
        Toast.makeText(context, "CSRF Token Expired", Toast.LENGTH_LONG).show();
        Request request = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=mail")
                .header("Cache-Control", "no-cache")
                .build();
        OkHttpClient client = MyMailSingleton.getInstance(context).getClient();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String html = response.body().string();
                String newCsrf = extractCsrfToken(html);
                if (!newCsrf.isEmpty()) {
                    sharedPreferences.edit().putString("csrf_token", newCsrf).apply();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleError(String message, Exception e) {
        Log.d(TAG, message+e);

        if (e instanceof IOException) {
            if (e.getMessage().contains("certificate")) {
                showToast("Ошибка безопасности соединения");
            } else if (e.getMessage().contains("timed out")) {
                showToast("Таймаут соединения");
            } else {
                //    showToast("Ошибка сети");
            }
        } else if (e instanceof JSONException) {
            showToast("Ошибка формата данных");
        } else {
            //  showToast("Неизвестная ошибка");
        }

        if (e instanceof SessionExpiredException) {
            reauthenticate();
        }
    }

    private static void clearSessionData(Context context) {
        // Полная очистка всех данных сессии
        sharedPreferences.edit()
                .clear()
                .apply();
        OkHttpClient client = MyMailSingleton.getInstance(MailActivity.context).getClient();
        client.cookieJar().saveFromResponse(
                HttpUrl.get("https://letter.tpu.ru"),
                Collections.emptyList()
        );

        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    public static JSONObject performMailLogin(String username, String password)
            throws IOException, JSONException {
        OkHttpClient client = MyMailSingleton.getInstance(context).getClient();
        // 1. Полная очистка кук
        client.cookieJar().saveFromResponse(
                HttpUrl.get("https://letter.tpu.ru"),
                Collections.emptyList()
        );

        // 2. Первый запрос для получения CSRF
        Request getRequest = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=login")
                .header("User-Agent", "MyTPUApp/1.0")
                .build();

        Response getResponse = client.newCall(getRequest).execute();
        String responseBody = getResponse.body().string();
        String csrfToken = extractCsrfToken(responseBody);

        Log.d("Auth", "Step1: Got CSRF: " + csrfToken);

        // 3. Формируем запрос на вход
        RequestBody formBody = new FormBody.Builder()
                .add("_token", csrfToken)
                .add("_task", "login")
                .add("_action", "login")
                .add("_user", username)
                .add("_pass", password)
                .add("_timezone", "Europe/Moscow")
                .add("_remember", "1")
                .build();

        Request loginRequest = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=login")
                .post(formBody)
                .header("Referer", "https://letter.tpu.ru/mail/?_task=login")
                .build();

        // 4. Выполняем вход
        try (Response loginResponse = client.newCall(loginRequest).execute()) {
            // 5. Проверяем редирект
            if (loginResponse.isRedirect()) {
                String location = loginResponse.header("Location");
                if (location != null && location.contains("_task=mail")) {
                    Log.d("Auth", "Login redirect success");
                    return new JSONObject().put("success", true);
                }
            }

            // 6. Проверяем куки вручную
            List<Cookie> cookies = client.cookieJar()
                    .loadForRequest(HttpUrl.get("https://letter.tpu.ru"));

            Log.d("Auth", "Received cookies: " + cookies.size());
            for (Cookie cookie : cookies) {
                Log.d("Auth", "Cookie: " + cookie.name() + "=" + cookie.value());
            }

            // 7. Проверяем наличие сессионной куки
            boolean hasSession = cookies.stream().anyMatch(c ->
                    c.name().equals("roundcube_sessauth") && !c.value().isEmpty()
            );

            if (hasSession) {
                Log.d("Auth", "Session cookie found");
                return new JSONObject().put("success", true);
            }

            // 8. Если куки нет, проверяем ответ сервера
            String loginResponseBody = loginResponse.body().string();
            if (loginResponseBody.contains("Invalid username or password")) {
                throw new IOException("Invalid credentials");
            }

            // 9. Проверяем JavaScript-редирект в теле ответа
            if (loginResponseBody.contains("window.location.href")) {
                Pattern redirectPattern = Pattern.compile("window\\.location\\.href\\s*=\\s*['\"]([^'\"]+)");
                Matcher matcher = redirectPattern.matcher(loginResponseBody);
                if (matcher.find()) {
                    String redirectUrl = matcher.group(1);
                    Log.d("Auth", "Found JS redirect to: " + redirectUrl);

                    // Выполняем редирект
                    Request redirectRequest = new Request.Builder()
                            .url(redirectUrl)
                            .build();

                    try (Response redirectResponse = client.newCall(redirectRequest).execute()) {
                        if (redirectResponse.isSuccessful()) {
                            Log.d("Auth", "JS redirect success");
                            return new JSONObject().put("success", true);
                        }
                    }
                }
            }

            throw new IOException("No session cookie received. Response code: " +
                    loginResponse.code() + ", Body: " + loginResponseBody.substring(0, 200));
        }
    }

    // Updated CSRF token extraction with better regex
    private static String extractCsrfToken(String html) {
        // More robust regex pattern
        Pattern pattern = Pattern.compile("<input[^>]+name=\"_token\"[^>]*value=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    public static boolean isSessionValid(Context context) {
        initSharedPreferences(context);
        OkHttpClient client = MyMailSingleton.getInstance(MailActivity.context).getClient();
        List<Cookie> cookies = client.cookieJar().loadForRequest(
                HttpUrl.get("https://letter.tpu.ru")
        );

        boolean hasValidSession = cookies.stream().anyMatch(c ->
                c.name().equals("roundcube_sessauth") &&
                        !c.value().isEmpty() &&
                        (c.expiresAt() == 0 || c.expiresAt() > System.currentTimeMillis()));

        // Дополнительная проверка через API
        if (hasValidSession) {
            try {
                // Проверка через API вместо проверки кук
                Request testRequest = new Request.Builder()
                        .url("https://letter.tpu.ru/mail/?_task=mail&_action=getunread")
                        .build();

                Response testResponse = client.newCall(testRequest).execute();
                return testResponse.code() == 200;
            } catch (IOException e) {
                Log.d(TAG, "Session validation request failed: " + e.getMessage());
                return false;
            }
        }
        Log.d(TAG, "Session valid: " + hasValidSession);
        return hasValidSession;
    }

    private List<Email> parseMessages(JSONArray messages) throws JSONException {
        List<Email> emails = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if (msg == null) continue;

            String uid = msg.optString("uid", "");
            // Добавляем проверку uid
            if (uid.isEmpty()) {
                Log.d("ParseMessages", "Missing UID in message: " + msg);
                continue;
            }

            // Очистка HTML во всех полях
            String subject = Jsoup.parse(msg.optString("subject", "")).text();
            String fromto = Jsoup.parse(msg.optString("fromto", "")).text();
            String snippet = Jsoup.parse(msg.optString("snippet", "")).text();

            // В методе parseMessages():
            emails.add(new Email(
                    uid,
                    extractFrom(fromto),
                    subject,
                    formatDate(msg.optString("date", "")), // Форматированная дата
                    msg.optString("date", ""), // Исходная rawDate для сортировки
                    snippet
            ));

        }
        Collections.sort(emails, (e1, e2) -> {
            try {
                Date d1 = sdf.parse(e1.getRawDate());
                Date d2 = sdf.parse(e2.getRawDate());
                return d2.compareTo(d1);
            } catch (ParseException e) {
                return 0;
            }
        });
        updateMenuCounters(messages.length());
        return emails;
    }

    private void updateMenuCounters(int count) {
        runOnUiThread(() -> {
            Menu menu = navigationView.getMenu();
            MenuItem item = menu.findItem(getCurrentMenuId());
            if (item != null) {
                item.setTitle(String.format(Locale.getDefault(), "%s (%d)",
                        getFolderTitle(currentFolder), count));
            }
        });
    }

    private int getCurrentMenuId() {
        switch (currentFolder) {
            case "INBOX": return R.id.nav_inbox;
            case "Drafts": return R.id.nav_drafts;
            case "Sent": return R.id.nav_sent;
            case "Junk": return R.id.nav_spam;
            case "Trash": return R.id.nav_trash;
            case "Massmail": return R.id.nav_massmail;
            default: return R.id.nav_inbox;
        }
    }

    private String getFolderTitle(String folder) {
        switch (folder) {
            case "INBOX": return "Входящие";
            case "Drafts": return "Черновики";
            case "Sent": return "Отправленные";
            case "Junk": return "СПАМ";
            case "Trash": return "Корзина";
            case "Massmail": return "Massmail";
            default: return "Входящие";
        }
    }

    private void loadEmails() {
        executor.execute(() -> {
            try {
                if (!isSessionValid(context)) {
                    throw new SessionExpiredException("Session expired");
                }

                // Добавляем проверку CSRF токена
                if (getCsrfToken(context).isEmpty()) {
                    refreshCsrfToken(this);
                }

                JSONArray messages = RoundcubeAPI.fetchMessages(MailActivity.this, currentPage, currentFolder);
                List<Email> emailList = parseMessages(messages);

                runOnUiThread(() -> {
                    adapter.updateData(emailList);
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (SessionExpiredException e) {
                handleSessionExpired();
            } catch (Exception e) {
                handleError("Ошибка загрузки", e);
            }
        });
    }

    private void reauthenticate() {
        if (executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(2);
        }

        executor.execute(() -> {
            try {
                boolean success = performReauthentication();
                if (success) {
                    loadEmails();
                }
            } catch (Exception e) {
                handleError("Ошибка аутентификации", e);
            }
        });
    }

    private void handleSessionExpired() {
        Log.d("Session", "Full session reset initiated");

        runOnUiThread(() -> {
            executor.execute(() -> {
                try {
                    clearSessionData(context);
                    boolean authSuccess = performReauthentication();

                    runOnUiThread(() -> {
                        if (authSuccess) {
                            loadEmailsInternal(true);
                        }
                    });
                } catch (Exception e) {
                }
            });
        });
    }

    // В MailActivity.java
    private boolean performReauthentication() {
        try {
            clearSessionData(context);
            // Пауза перед повторной попыткой
            Thread.sleep(1000);
            return MailActivity.forceReauthenticate(context);
        } catch (Exception e) {
            return false;
        }
    }

    public static class SessionExpiredException extends Exception {
        public SessionExpiredException(String message) {
            super(message);
        }
    }

    private String formatDate(String originalDate) {
        SimpleDateFormat currentYearFormat = new SimpleDateFormat("yyyy", Locale.getDefault());
        String currentYear = currentYearFormat.format(new Date());

        SimpleDateFormat[] inputFormats = {
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
                new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss Z", Locale.getDefault()),
                new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()),
                new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        };

        for (SimpleDateFormat inputFormat : inputFormats) {
            try {
                Date date = inputFormat.parse(originalDate);
                SimpleDateFormat outputFormat;

                // Проверяем год
                if (currentYear.equals(new SimpleDateFormat("yyyy", Locale.getDefault()).format(date))) {
                    outputFormat = new SimpleDateFormat("dd MMM HH:mm", new Locale("ru"));
                } else {
                    outputFormat = new SimpleDateFormat("dd MMM yyyy HH:mm", new Locale("ru"));
                }

                return outputFormat.format(date);
            } catch (ParseException ignored) {}
        }

        return originalDate;
    }

    public static String extractFrom(String fromto) {
        if (fromto == null || fromto.isEmpty()) return "Неизвестный отправитель";

        // Удаляем HTML-теги
        String cleanFrom = Jsoup.parse(fromto).text();

        // Извлекаем email
        Matcher emailMatcher = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
                .matcher(cleanFrom);
        if (emailMatcher.find()) {
            return emailMatcher.group();
        }

        // Упрощаем длинные адреса
        if (cleanFrom.length() > 30) {
            return cleanFrom.substring(0, 27) + "...";
        }

        return cleanFrom.isEmpty() ? "Неизвестный отправитель" : cleanFrom;
    }

    private void showToast(String message) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastToastTime > TOAST_INTERVAL) {
            lastToastTime = currentTime;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        WorkManager.getInstance(this).enqueue(
                new OneTimeWorkRequest.Builder(MailCheckWorker.class).build()
        );
        checkSessionValidity();
        setupRefresh();
    }

    private void checkSessionValidity() {
        executor.execute(() -> {
            if (!isSessionValid(context)) {
                Log.d(TAG, "Session expired, reauthenticating");
                performReauthentication();
                loadEmailsInternal(true);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        WorkManager.getInstance(this).enqueue(
                new OneTimeWorkRequest.Builder(MailCheckWorker.class).build()
        );
        sessionHandler.removeCallbacksAndMessages(null);
    }
    private void observeWorkManager() {
        WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData("mailCheck")
                .observe(this, workInfos -> {
                    for (WorkInfo info : workInfos) {
                        Log.d(TAG, "Work state: " + info.getState());
                        if (info.getState() == WorkInfo.State.ENQUEUED) {
                            long nextRun = info.getNextScheduleTimeMillis() - System.currentTimeMillis();
                            Log.d(TAG, "Next run in: " + (nextRun / 60000) + " minutes");
                        }
                    }
                });
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityDestroyed = true;

        // 1. Отмена всех задач Handler
        sessionHandler.removeCallbacksAndMessages(null);

        alarmManager.cancel(alarmPendingIntent);
        // 2. Остановка scheduler
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        // 3. Остановка executor
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }

    public static class MyMailSingleton {
        private static MyMailSingleton instance;
        private OkHttpClient client;
        private Context context;
        private final PersistentCookieJar cookieJar;

        private MyMailSingleton(Context context) {
            this.context = context.getApplicationContext();
            this.cookieJar = new PersistentCookieJar(this.context);
            this.client = new OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .addInterceptor(new AuthInterceptor(context))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
        }

        public static synchronized MyMailSingleton getInstance(Context context) {
            if (instance == null || instance.client == null) {
                instance = new MyMailSingleton(context);
            }
            return instance;
        }

        // В MyMailSingleton.java
        public synchronized OkHttpClient getClient() {
            if (client == null) {
                client = new OkHttpClient.Builder()
                        .cookieJar(cookieJar)
                        .addInterceptor(new AuthInterceptor(context))
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();
            }
            return client;
        }

        private static class AuthInterceptor implements Interceptor {
            private final Context context;

            AuthInterceptor(Context context) {
                this.context = context;
            }

            @Override
            public Response intercept(Chain chain) throws IOException {
                Request original = chain.request();
                Request.Builder builder = original.newBuilder();

                // Добавляем CSRF-токен во все запросы
                String csrfToken = MailActivity.getCsrfToken(context);
                if (!csrfToken.isEmpty()) {
                    builder.header("X-CSRF-Token", csrfToken);
                }

                // Добавляем куки
                String cookies = MailActivity.getCookiesForWebView();
                if (!cookies.isEmpty()) {
                    builder.header("Cookie", cookies);
                }

                return chain.proceed(builder.build());
            }

        }
    }

    public class Email {
        private String id;
        private String from;
        private String subject;
        private String date;
        private String rawDate;
        private String snippet;

        public Email(String id, String from, String subject, String date, String rawDate, String snippet) {
            this.rawDate = rawDate;
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("Email ID cannot be null or empty");
            }
            this.id = id;
            this.from = from;
            this.subject = subject;
            this.date = date;
            this.snippet = snippet;
        }

        public String getId() {
            return id;
        }
        public String getRawDate() {
            return rawDate;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getDate() {
            return date;
        }

        public String getSnippet() {
            return snippet;
        }
    }

    public static boolean forceReauthenticate(Context context) {
        try {
            clearSessionData(MailActivity.context);
            String[] credentials = getCredentials(MailActivity.context);
            JSONObject authResult = performMailLogin(credentials[0], credentials[1]);
            return authResult.getBoolean("success");
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] getCredentials(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    "user_credentials",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return new String[] {
                    prefs.getString("username", ""),
                    prefs.getString("password", "")
            };
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    public static class EmailAdapter extends RecyclerView.Adapter<EmailAdapter.EmailViewHolder> {
        private List<Email> emailList;
        private final OnEmailClickListener listener;

        public void addData(List<Email> newEmails) {
            int start = emailList.size();
            emailList.addAll(newEmails);
            notifyItemRangeInserted(start, newEmails.size());
        }

        public void clearData() {
            emailList.clear();
            notifyDataSetChanged();
        }

        public interface OnEmailClickListener {
            void onEmailClick(Email email);
        }

        public EmailAdapter(List<Email> emailList, OnEmailClickListener listener) {
            this.emailList = emailList;
            this.listener = listener;
        }

        @Override
        public EmailViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_email, parent, false);
            return new EmailViewHolder(view);
        }

        @Override
        public void onBindViewHolder(EmailViewHolder holder, int position) {
            Email email = emailList.get(position);
            holder.fromTextView.setText(email.getFrom());
            holder.subjectTextView.setText(email.getSubject());
            holder.dateTextView.setText(email.getDate());
            holder.bind(email);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEmailClick(email);
                }
            });
        }

        @Override
        public int getItemCount() {
            return emailList.size();
        }

        public void updateData(List<Email> newEmailList) {
            this.emailList.clear();
            this.emailList.addAll(newEmailList);
            notifyDataSetChanged();
        }

        public class EmailViewHolder extends RecyclerView.ViewHolder {
            TextView fromTextView;
            TextView subjectTextView;
            TextView dateTextView;

            public EmailViewHolder(View itemView) {
                super(itemView);
                fromTextView = itemView.findViewById(R.id.email_from);
                subjectTextView = itemView.findViewById(R.id.email_subject);
                dateTextView = itemView.findViewById(R.id.email_date);
            }

            // Добавляем метод bind
            public void bind(Email email) {
                fromTextView.setText(email.getFrom());
                subjectTextView.setText(email.getSubject());
                dateTextView.setText(email.getDate());
            }
        }
    }

}