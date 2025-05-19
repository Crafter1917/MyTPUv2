package com.example.mytpu.mailTPU;


import static android.content.ContentValues.TAG;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.mytpu.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MailActivity extends AppCompatActivity {
    private SharedPreferences mainPrefs;
    private RecyclerView emailsRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton fabCompose;
    private ExecutorService executor;
    private static SharedPreferences sharedPreferences;
    public static OkHttpClient client;
    private EmailAdapter adapter;
    private String username;
    private String password;
    private boolean isActivityDestroyed = false;
    private long lastToastTime = 0;
    private static final long TOAST_INTERVAL = 3000; // 3 секунды
    private Handler sessionHandler = new Handler();
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    public static Context context;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mail);
        context = this;
        executor = Executors.newFixedThreadPool(2);
        client = MyMailSingleton.getInstance(MailActivity.this).getClient();
        if (client == null) {
            Log.e(TAG, "Client initialization failed");
            finish();
            return;
        }

        initViews();
        setupSecureStorage();
        setupRefresh();
        checkAuthAndLoadEmails();
    }

    public static String getCsrfToken(Context context) {
        if (sharedPreferences == null) {
            throw new IllegalStateException("SharedPreferences not initialized");
        }
        return sharedPreferences.getString("csrf_token", "");
    }


    private Runnable sessionMaintainer = new Runnable() {
        @Override
        public void run() {
            if (!isActivityDestroyed) {
                refreshSession();
                sessionHandler.postDelayed(this, 300000); // Каждые 5 минут
            }
        }
    };
    // Новый метод для обновления токена
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
            Log.e(TAG, "CSRF refresh failed", e);
        }

        return newToken; // Возвращаем токен
    }

    // В класс MailActivity добавить
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
            Log.e("MailActivity", "Secure storage error", e);
            showToast("Ошибка безопасности");
            finish();
        }
    }

    private void setupRefresh() {
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
                    if (isSessionValid()) break;

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

    private void refreshCsrfTokenSync() throws IOException {
        OkHttpClient client = MyMailSingleton.getInstance(this).getClient();

        Request request = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=mail")
                .header("Cache-Control", "no-cache")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String html = response.body().string();
                String newCsrf = extractCsrfToken(html);
                if (!newCsrf.isEmpty()) {
                    sharedPreferences.edit().putString("csrf_token", newCsrf).apply();
                }
            }
        }
    }

    private void loadEmailsInternal(boolean clear) {
        executor.execute(() -> {
            try {
                JSONArray messages = RoundcubeAPI.fetchMessages(MailActivity.this, currentPage); // Передаем currentPage
                List<Email> newEmails = parseMessages(messages);

                hasMore = messages.length() >= 50; // Предполагаем, что страниц по 50 элементов

                runOnUiThread(() -> {
                    if (clear) {
                        adapter.clearData();
                    }
                    adapter.addData(newEmails);
                    swipeRefreshLayout.setRefreshing(false);
                    isLoading = false;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    handleError("Load error", e);
                    isLoading = false;
                });
            }
        });
    }

    private void handleError(String message, Exception e) {
        Log.e(TAG, message, e);

        if (e instanceof IOException) {
            if (e.getMessage().contains("certificate")) {
                showToast("Ошибка безопасности соединения");
            } else if (e.getMessage().contains("timed out")) {
                showToast("Таймаут соединения");
            } else {
                showToast("Ошибка сети");
            }
        } else if (e instanceof JSONException) {
            showToast("Ошибка формата данных");
        } else {
            showToast("Неизвестная ошибка");
        }

        if (e instanceof SessionExpiredException) {
            reauthenticate();
        }
    }

    private void clearSessionData() {
        // Полная очистка всех данных сессии
        sharedPreferences.edit()
                .clear()
                .apply();

        client.cookieJar().saveFromResponse(
                HttpUrl.get("https://letter.tpu.ru"),
                Collections.emptyList()
        );

        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    private static JSONObject performMailLogin(String username, String password)
            throws IOException, JSONException {

        // Clear existing cookies
        client.cookieJar().saveFromResponse(
                HttpUrl.get("https://letter.tpu.ru"),
                Collections.emptyList()
        );

        // 1. Get initial page and CSRF token
        Request getRequest = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=login")
                .header("User-Agent", "MyTPUApp/1.0")
                .build();

        Response getResponse = client.newCall(getRequest).execute();
        String responseBody = getResponse.body().string();
        Log.d("Auth", "Initial GET response body: " + responseBody); // Log response body

        String csrfToken = extractCsrfToken(responseBody);
        Log.d("Auth", "Extracted CSRF token: " + csrfToken);

        if (csrfToken.isEmpty()) {
            throw new IOException("Failed to extract CSRF token");
        }

        // 2. Send login request
        RequestBody formBody = new FormBody.Builder()
                .add("_token", csrfToken)
                .add("_task", "login")
                .add("_action", "login")
                .add("_user", username)
                .add("_pass", password)
                .add("_timezone", "Europe/Moscow")
                .add("_remember", "1") // Add remember me parameter if required
                .build();

        Request loginRequest = new Request.Builder()
                .url("https://letter.tpu.ru/mail/?_task=login")
                .post(formBody)
                .header("Referer", "https://letter.tpu.ru/mail/?_task=login")
                .build();

        try (Response loginResponse = client.newCall(loginRequest).execute()) {
            // Log response details
            Log.d("Auth", "Login response code: " + loginResponse.code());
            Log.d("Auth", "Login response headers: " + loginResponse.headers());
            Log.d("Auth", "Response cookies: " + client.cookieJar().loadForRequest(HttpUrl.get("https://letter.tpu.ru")));

            // Check for server-side error messages in response body
            String loginResponseBody = loginResponse.body().string();
            if (loginResponseBody.contains("Invalid username or password")) {
                throw new IOException("Invalid credentials");
            }

            // 3. Check redirect
            if (loginResponse.isRedirect()) {
                String location = loginResponse.header("Location");
                if (location != null && location.contains("_task=mail")) {
                    return new JSONObject().put("success", true);
                }
            }

            // 4. Verify session cookie directly from headers
            Headers headers = loginResponse.headers();
            String cookiesHeader = headers.get("Set-Cookie");
            boolean hasSessionCookie = cookiesHeader != null && cookiesHeader.contains("roundcube_sessauth");

            // Also check stored cookies
            List<Cookie> cookies = client.cookieJar()
                    .loadForRequest(HttpUrl.get("https://letter.tpu.ru"));

            hasSessionCookie |= cookies.stream()
                    .anyMatch(c -> c.name().equals("roundcube_sessauth") && !c.value().isEmpty());

            if (hasSessionCookie) {
                return new JSONObject().put("success", true);
            }

            throw new IOException("No session cookie received. Response body: " + loginResponseBody);
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

    private boolean isSessionValid() {
        List<Cookie> cookies = client.cookieJar().loadForRequest(
                HttpUrl.get("https://letter.tpu.ru")
        );

        return cookies.stream().anyMatch(c ->
                c.name().equals("roundcube_sessauth") &&
                        !c.value().isEmpty() &&
                        (c.expiresAt() == 0 || c.expiresAt() > System.currentTimeMillis()));
    }


    private List<Email> parseMessages(JSONArray messages) throws JSONException {
        List<Email> emails = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;

            String uid = msg.optString("uid", "");
            // Добавляем проверку uid
            if (uid.isEmpty()) {
                Log.e("ParseMessages", "Missing UID in message: " + msg);
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
        return emails;
    }

    private void loadEmails() {
        executor.execute(() -> {
            try {
                if (!isSessionValid()) {
                    throw new SessionExpiredException("Session expired");
                }

                // Добавляем проверку CSRF токена
                if (getCsrfToken(context).isEmpty()) {
                    refreshCsrfToken(this);
                }

                JSONArray messages = RoundcubeAPI.fetchMessages(this, currentPage);
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
            new AlertDialog.Builder(this)
                    .setTitle("Сессия истекла")
                    .setMessage("Выполняется повторный вход...")
                    .setCancelable(false)
                    .show();

            executor.execute(() -> {
                try {
                    clearSessionData();
                    boolean authSuccess = performReauthentication();

                    runOnUiThread(() -> {
                        if (authSuccess) {
                            loadEmailsInternal(true);
                            Toast.makeText(this, "Сессия восстановлена", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                }
            });
        });
    }

    private boolean performReauthentication() {
        try {
            Log.d("Auth", "Starting automatic reauthentication");
            JSONObject authResult = performMailLogin(username, password);
            return authResult.getBoolean("success");
        } catch (Exception e) {
            Log.e("Auth", "Reauthentication failed", e);
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

    private String extractFrom(String fromto) {
        if (fromto == null || fromto.isEmpty()) return "Неизвестный отправитель";

        // Пытаемся найти email
        Matcher emailMatcher = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
                .matcher(fromto);
        if (emailMatcher.find()) {
            return emailMatcher.group();
        }

        // Пытаемся извлечь текст из HTML
        String text = Jsoup.parse(fromto).text();
        return text.isEmpty() ? "Неизвестный отправитель" : text;
    }

    private void showToast(String message) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastToastTime > TOAST_INTERVAL) {
            lastToastTime = currentTime;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        }
    }

    private void refreshSession() {
        executor.execute(() -> {
            try {
                HttpUrl refreshUrl = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                        .addQueryParameter("_task", "mail")
                        .addQueryParameter("_action", "refresh")
                        .build();

                Request request = new Request.Builder()
                        .url(refreshUrl)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        Log.w("Session", "Refresh failed: " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.e("Session", "Refresh error", e);
            }
        });
    }
    @Override
    protected void onResume() {
        super.onResume();
        sessionHandler.postDelayed(sessionMaintainer, 300000);
    }
    @Override
    protected void onPause() {
        super.onPause();
        sessionHandler.removeCallbacks(sessionMaintainer);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityDestroyed = true;
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow(); // Используем shutdownNow для немедленного прерывания
        }
    }

    public static class MyMailSingleton {
        private static MyMailSingleton instance;
        private Context context;
        private final OkHttpClient client;
        private final PersistentCookieJar cookieJar;
        public Headers getAuthHeaders() {
            List<Cookie> cookies = cookieJar.loadForRequest(HttpUrl.parse("https://letter.tpu.ru"));
            StringBuilder cookieHeader = new StringBuilder();
            for (Cookie cookie : cookies) {
                cookieHeader.append(cookie.name()).append("=").append(cookie.value()).append("; ");
            }
            return new Headers.Builder()
                    .add("Cookie", cookieHeader.toString())
                    .build();
        }
        private MyMailSingleton(Context context) {
            this.context = context.getApplicationContext();
            this.cookieJar = new PersistentCookieJar(this.context);
            this.client = new OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .addInterceptor(new AuthInterceptor(context))
                    .build();
        }

        public void clearCookies() {
            cookieJar.clear();
        }

        public static synchronized MyMailSingleton getInstance(Context context) {
            if (instance == null) {
                instance = new MyMailSingleton(context);
            }
            return instance;
        }

        public OkHttpClient getClient() {
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

            private String getCookiesString(Chain chain) {
                return chain.request().headers().toMultimap()
                        .getOrDefault("Cookie", Collections.emptyList())
                        .stream()
                        .collect(Collectors.joining("; "));
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