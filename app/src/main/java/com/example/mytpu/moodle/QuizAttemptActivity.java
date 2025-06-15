package com.example.mytpu.moodle;

import static com.example.mytpu.moodle.ModuleDetailActivity.WEB_SERVICE_URL;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.BaseActivity;
import com.example.mytpu.ColorManager;
import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;


public class QuizAttemptActivity extends BaseActivity {
    private static final String TAG = "QuizAttempt";
    private ColorManager colorManager;
    private static final String MOODLE_BASE_URL = "https://stud.lms.tpu.ru";
    private final Map<Integer, String> questionHtmlCache = new HashMap<>();
    private Map<Integer, List<Integer>> pageSlotsMap = new HashMap<>();
    private OkHttpClient client;
    private String token;
    private int attemptId;
    private long uniqueId = -1;
    private ProgressBar progressBar;
    private LinearLayout questionsLayout;
    private int currentPage = 0;
    private int totalPages;
    private CountDownTimer countDownTimer;
    private int cmid;
    private final Map<Integer, List<NameValue>> hiddenFieldsCache = new HashMap<>();
    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

    private static class NameValue {
        String name;
        String value;

        NameValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
    @Override
    protected void applyCustomColors() {
        // Обновление цветов динамически созданных элементов
        updateQuestionColors();
    }

    private void updateQuestionColors() {
        for (int i = 0; i < questionsLayout.getChildCount(); i++) {
            View child = questionsLayout.getChildAt(i);
            if (child instanceof CardView) {
                LinearLayout layout = (LinearLayout) ((CardView) child).getChildAt(0);
                for (int j = 0; j < layout.getChildCount(); j++) {
                    View innerChild = layout.getChildAt(j);
                    if (innerChild instanceof TextView) {
                        TextView tv = (TextView) innerChild;
                        if ("error".equals(tv.getTag())) {
                            tv.setTextColor(colorManager.getColor("error_color"));
                        }
                    }
                }
            }
        }
    }
    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_attempt);

        progressBar = findViewById(R.id.progressBar);
        questionsLayout = findViewById(R.id.questionsLayout);

        initSecureStorage();

        attemptId = getIntent().getIntExtra("attemptId", -1);
        if (attemptId == -1) {
            finishWithError("Invalid attempt ID");
            return;
        }

        cmid = getIntent().getIntExtra("cmid", -1);
        if (cmid == -1) {
            finishWithError("Invalid cmid");
            return;
        }
        Log.d(TAG, "cmid="+cmid);
        String layoutStr = getIntent().getStringExtra("layout");
        if (layoutStr == null || layoutStr.isEmpty()) {
            Log.e(TAG, "Layout string is missing or empty!");
            loadLayoutFromApi();
        } else {
            pageSlotsMap = parseLayoutToPages(layoutStr);
            totalPages = pageSlotsMap.size();
            setupNavigation();
            loadPage(currentPage);
        }
    }

    private void saveAnswersViaApi(Runnable onComplete) {
        if (uniqueId == -1) {
            runOnUiThread(() -> {
                showError("Unique ID not available. Cannot save.");
                hideSavingProgress();
            });
            return;
        }

        new Thread(() -> {
            try {
                // Собираем ответы
                List<NameValue> answers = collectAnswersForCurrentPage();

                // Формируем URL для API запроса
                HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                        .addQueryParameter("wstoken", token)
                        .addQueryParameter("wsfunction", "mod_quiz_save_attempt")
                        .addQueryParameter("attemptid", String.valueOf(attemptId))
                        .addQueryParameter("moodlewsrestformat", "json")
                        .build();

                // Строим тело запроса
                FormBody.Builder formBuilder = new FormBody.Builder();
                for (int i = 0; i < answers.size(); i++) {
                    NameValue pair = answers.get(i);
                    formBuilder.add("data[" + i + "][name]", pair.name);
                    formBuilder.add("data[" + i + "][value]", pair.value);
                }

                Request request = new Request.Builder()
                        .url(url)
                        .post(formBuilder.build())
                        .build();

                String fullUrl = url.toString();
                Log.d("API_DEBUG", "Full URL: " + fullUrl);

                StringBuilder paramsLog = new StringBuilder("Request params:\n");
                for (NameValue pair : answers) {
                    paramsLog.append(pair.name).append(" = ").append(pair.value).append("\n");
                }
                Log.d("API_DEBUG", paramsLog.toString());

                // Отправляем запрос
                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();
                Log.d("API_SAVE", "Response: " + responseBody);

                JSONObject json = new JSONObject(responseBody);
                if (json.optBoolean("status", false)) {
                    runOnUiThread(() -> {
                        hideSavingProgress();
                        onComplete.run();
                    });
                } else {
                    runOnUiThread(() -> {
                        hideSavingProgress();
                        showError("Ошибка сохранения: " + json.toString());
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideSavingProgress();
                    showError("Ошибка сохранения: " + e.getMessage());
                });
            }
        }).start();
    }

    private List<NameValue> extractHiddenFields(String html) {
        List<NameValue> hiddenFields = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements inputs = doc.select("input[type=hidden]");
            for (Element input : inputs) {
                String name = input.attr("name");
                String value = input.attr("value");
                hiddenFields.add(new NameValue(name, value));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting hidden fields", e);
        }
        return hiddenFields;
    }

    private List<NameValue> collectAnswersForCurrentPage() {
        List<NameValue> answers = new ArrayList<>();
        List<Integer> slots = pageSlotsMap.get(currentPage);
        if (slots == null) return answers;

        // Добавляем скрытые поля
        for (int slot : slots) {
            List<NameValue> hiddenFields = hiddenFieldsCache.get(slot);
            if (hiddenFields != null) {
                answers.addAll(hiddenFields);
            }
        }

        // Добавляем обязательные системные поля
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        answers.add(new NameValue("thispage", String.valueOf(currentPage)));
        answers.add(new NameValue("timecheck", String.valueOf(currentTimeSeconds)));
        answers.add(new NameValue("nextpage", String.valueOf(currentPage)));
        answers.add(new NameValue("scrollpos", "0"));

        for (int slot : slots) {
            String questionHtml = questionHtmlCache.get(slot);
            if (questionHtml == null) continue;
            String type = getQuestionType(questionHtml);

            if ("multichoice".equals(type)) {
                addMultichoiceAnswers(answers, slot);
            } else if ("essay".equals(type) || "shortanswer".equals(type)) {
                addTextAnswer(answers, slot, type);
            } else if ("truefalse".equals(type)) {
                addTrueFalseAnswer(answers, slot);
            } else if ("matching".equals(type)) {
                addMatchingAnswer(answers, slot);
            } else if ("numerical".equals(type)) {
                addNumericalAnswer(answers, slot);
            }
        }
        return answers;
    }

    private void addMultichoiceAnswers(List<NameValue> answers, int slot) {
        for (View view : getAllViews(questionsLayout, CompoundButton.class)) {
            if ((int) view.getTag(R.id.tag_slot) == slot) {
                CompoundButton button = (CompoundButton) view;
                String inputType = (String) button.getTag(R.id.tag_input_type);
                String inputName = (String) button.getTag(R.id.tag_input_name);

                if ("checkbox".equals(inputType)) {
                    // Для чекбоксов используем специальное имя
                    String fixedName = inputName.replace("_answer", "_choice");
                    answers.add(new NameValue(fixedName, button.isChecked() ? "1" : "0"));
                } else {
                    // Для радио-кнопок
                    if (button.isChecked()) {
                        answers.add(new NameValue(inputName, (String) button.getTag(R.id.tag_input_value)));
                    }
                }
            }
        }
    }

    private void addTrueFalseAnswer(List<NameValue> answers, int slot) {
        // Формируем имя поля в правильном формате
        String fieldName = "q" + uniqueId + ":" + slot + "_answer";
        Log.d(TAG, "Processing true/false for slot: " + slot + ", field: " + fieldName);
        for (View view : getAllViews(questionsLayout, CompoundButton.class)) {
            if ((int) view.getTag(R.id.tag_slot) == slot && view instanceof RadioButton) {
                RadioButton radioButton = (RadioButton) view;
                if (radioButton.isChecked()) {
                    // Используем сформированное имя поля вместо имени из HTML
                    answers.add(new NameValue(fieldName, (String) radioButton.getTag(R.id.tag_input_value)));
                    return; // Найден выбранный вариант, выходим
                }
            }
        }

        // Если ни один вариант не выбран, добавляем пустое значение
        answers.add(new NameValue(fieldName, ""));
    }

    private void addMatchingAnswer(List<NameValue> answers, int slot) {
        for (View view : getAllViews(questionsLayout, Spinner.class)) {
            if ((int) view.getTag(R.id.tag_slot) == slot) {
                Spinner spinner = (Spinner) view;
                String inputName = (String) spinner.getTag(R.id.tag_input_name);
                String selectedValue = (String) spinner.getSelectedItem();
                answers.add(new NameValue(inputName, selectedValue));
            }
        }
    }

    private void addNumericalAnswer(List<NameValue> answers, int slot) {
        for (View view : getAllViews(questionsLayout, EditText.class)) {
            if ((int) view.getTag(R.id.tag_slot) == slot) {
                EditText editText = (EditText) view;
                String answer = editText.getText().toString();
                String fieldName = "q" + uniqueId + ":" + slot + "_answer";
                answers.add(new NameValue(fieldName, answer));
            }
        }
    }

    private void addTextAnswer(List<NameValue> answers, int slot, String type) {
        for (View view : getAllViews(questionsLayout, EditText.class)) {
            if ((int) view.getTag(R.id.tag_slot) == slot) {
                EditText editText = (EditText) view;
                String answer = editText.getText().toString();

                // Сохраняем переносы строк как HTML только для эссе
                if ("essay".equals(type) && !answer.isEmpty()) {
                    // Сохраняем все переносы строк
                    answer = answer.replace("\n", "<br>");
                }

                String fieldName = "q" + uniqueId + ":" + slot + "_answer";
                answers.add(new NameValue(fieldName, answer));
            }
        }
    }

    // Изменяем навигационные методы для использования API
    private void navigateToPage(int newPage) {
        if (newPage < 0 || newPage >= totalPages) return;

        showSavingProgress();
        saveAnswersViaApi(() -> {
            currentPage = newPage;
            loadPage(currentPage);
        });
    }

    private void submitAttempt() {
        if (countDownTimer != null) countDownTimer.cancel();
        showSavingProgress();
        saveAnswersViaApi(() -> finishAttempt(false));
    }

    private void finishAttempt(boolean isTimeUp) {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_process_attempt")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("finishattempt", "1") // Флаг завершения
                .addQueryParameter("timeup", isTimeUp ? "1" : "0") // Завершение по времени
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(new FormBody.Builder().build()) // POST-запрос
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("Ошибка завершения",e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    finish();
                } catch (Exception e) {
                    Log.e("Ошибка обработки ответа" , e.getMessage());
                }
            }
        });
    }

    private void initSecureStorage() {
        try {
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            token = sharedPreferences.getString("token", null);

            if (token == null || token.isEmpty()) {
                showError("Токен не найден. Пожалуйста, войдите снова.");
                finish();
                return;
            }

            // Initialize cookie store
            CookieJar cookieJar = new CookieJar() {
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    // Сохраняем куки в памяти
                    cookieStore.put(url.host(), cookies);

                    // Дополнительно сохраняем важные куки в SharedPreferences
                    saveImportantCookies(cookies);
                }

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> cookies = cookieStore.get(url.host());
                    if (cookies == null) {
                        cookies = new ArrayList<>();
                    }

                    // Добавляем обязательные куки, если их нет
                    boolean hasAuthCookie = false;
                    boolean hasMoodleSession = false;

                    for (Cookie cookie : cookies) {
                        if ("auth_ldaposso_authprovider".equals(cookie.name())) {
                            hasAuthCookie = true;
                        } else if ("MoodleSession".equals(cookie.name())) {
                            hasMoodleSession = true;
                        }
                    }

                    try {
                        SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                                "user_credentials",
                                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                                QuizAttemptActivity.this,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        );

                        if (!hasAuthCookie) {
                            String authCookieValue = sharedPreferences.getString("auth_ldaposso_authprovider", null);
                            if (authCookieValue != null) {
                                cookies.add(new Cookie.Builder()
                                        .name("auth_ldaposso_authprovider")
                                        .value(authCookieValue)
                                        .domain(url.host())
                                        .build());
                            }
                        }

                        if (!hasMoodleSession) {
                            String moodleSession = sharedPreferences.getString("MoodleSession", null);
                            if (moodleSession != null) {
                                cookies.add(new Cookie.Builder()
                                        .name("MoodleSession")
                                        .value(moodleSession)
                                        .domain(url.host())
                                        .build());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error loading cookies for request", e);
                    }

                    return cookies;
                }
            };

            client = new OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .build();

            // Load saved cookies if available
            loadSavedCookies();
            addMissingCookie();
        } catch (Exception e) {
            showError("Security error: " + e.getMessage());
            finish();
        }
    }

    private void addMissingCookie() {
        try {
            URI uri = new URI(MOODLE_BASE_URL);
            List<Cookie> cookies = cookieStore.get(uri.getHost());
            if (cookies == null) {
                cookies = new ArrayList<>();
            }

            // Проверяем, есть ли уже эта кука
            boolean hasAuthCookie = false;
            for (Cookie cookie : cookies) {
                if ("auth_ldaposso_authprovider".equals(cookie.name())) {
                    hasAuthCookie = true;
                    break;
                }
            }

            // Если нет - добавляем
            if (!hasAuthCookie) {
                cookies.add(new Cookie.Builder()
                        .name("auth_ldaposso_authprovider")
                        .value("NOOSSO") // Значение из Python-скрипта
                        .domain(uri.getHost())
                        .build());

                cookieStore.put(uri.getHost(), cookies);
                Log.d(TAG, "Added missing auth_ldaposso_authprovider cookie");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding missing cookie", e);
        }
    }

    private void saveImportantCookies(List<Cookie> cookies) {
        try {
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            SharedPreferences.Editor editor = sharedPreferences.edit();

            for (Cookie cookie : cookies) {
                if ("auth_ldaposso_authprovider".equals(cookie.name())) {
                    editor.putString("auth_ldaposso_authprovider", cookie.value());
                } else if ("MoodleSession".equals(cookie.name())) {
                    editor.putString("MoodleSession", cookie.value());
                }
            }

            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving cookies", e);
        }
    }

    private void loadSavedCookies() {
        try {
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "user_credentials",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            String authCookie = "NOOSSO";
            String moodleSession = sharedPreferences.getString("MoodleSession", null);

            if (authCookie != null && moodleSession != null) {
                URI uri = new URI(MOODLE_BASE_URL);

                List<Cookie> cookies = new ArrayList<>();
                cookies.add(new Cookie.Builder()
                        .name("auth_ldaposso_authprovider")
                        .value(authCookie)
                        .domain(uri.getHost())
                        .build());

                cookies.add(new Cookie.Builder()
                        .name("MoodleSession")
                        .value(moodleSession)
                        .domain(uri.getHost())
                        .build());

                cookieStore.put(uri.getHost(), cookies);
                Log.d(TAG, "Loaded cookies from storage");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cookies", e);
        }
    }

    private void loadLayoutFromApi() {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_get_user_attempts")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> showError("Ошибка загрузки layout"));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    JSONObject json = new JSONObject(response.body().string());
                    JSONArray attempts = json.getJSONArray("attempts");

                    for (int i = 0; i < attempts.length(); i++) {
                        JSONObject attempt = attempts.getJSONObject(i);
                        if (attempt.getInt("id") == attemptId) {
                            String layout = attempt.getString("layout");
                            uniqueId = attempt.getLong("uniqueid");

                            runOnUiThread(() -> {
                                pageSlotsMap = parseLayoutToPages(layout);
                                totalPages = pageSlotsMap.size();
                                setupNavigation();
                                loadPage(currentPage);
                            });
                            return;
                        }
                    }
                    runOnUiThread(() -> showError("Layout not found"));
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Error: " + e.getMessage()));
                }
            }
        });
    }

    private void setupNavigation() {
        Button prevButton = findViewById(R.id.btnPrev);
        Button nextButton = findViewById(R.id.btnNext);
        Button submitButton = findViewById(R.id.btnSubmit);

        prevButton.setOnClickListener(v -> navigateToPage(currentPage - 1));
        nextButton.setOnClickListener(v -> navigateToPage(currentPage + 1));
        submitButton.setOnClickListener(v -> showSubmitConfirmation());
    }

    private void showSubmitConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Завершение теста")
                .setMessage("Вы уверены, что хотите завершить попытку?")
                .setPositiveButton("Да", (dialog, which) -> submitAttempt())
                .setNegativeButton("Нет", null)
                .show();
    }

    private void showSavingProgress() {
        Button submitButton = findViewById(R.id.btnSubmit);
        submitButton.setText("Сохранение...");
        submitButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideSavingProgress() {
        Button submitButton = findViewById(R.id.btnSubmit);
        submitButton.setText("Завершить");
        submitButton.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private String getQuestionType(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // Пытаемся определить тип по классам
            Element root = doc.select("div.que").first();
            if (root != null) {
                String className = root.className();
                if (className.contains("multichoice")) return "multichoice";
                if (className.contains("essay")) return "essay";
                if (className.contains("shortanswer")) return "shortanswer";
                if (className.contains("truefalse")) return "truefalse";
                if (className.contains("match")) return "match";
                if (className.contains("ddmatch") || className.contains("ddwtos")) return "ddmatch";

                if (className.contains("numerical")) return "numerical";
            }

            // Дополнительные проверки
            if (!doc.select("textarea").isEmpty()) return "essay";
            if (!doc.select("input[type=text][name$=answer]").isEmpty()) return "shortanswer";
            if (!doc.select("input[type=radio], input[type=checkbox]").isEmpty()) return "multichoice";
            if (!doc.select("select[name$=_:sub]").isEmpty()) return "match";
            if (!doc.select("div.ddwtos-container").isEmpty()) return "ddmatch";
            if (!doc.select("input[type=text][name$=answer]").isEmpty() && html.contains("numerical")) return "numerical";
            if (!doc.select("input[type=radio][name$=answer]").isEmpty() && html.contains("truefalse")) return "truefalse";

        } catch (Exception e) {
            Log.e(TAG, "Error parsing question type", e);
        }
        return "unknown";
    }

    private void loadPage(int pageIndex) {
        progressBar.setVisibility(View.VISIBLE);
        questionsLayout.removeAllViews();

        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_get_attempt_data")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("page", String.valueOf(pageIndex))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> showError("Ошибка загрузки: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {

                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("questions")) {
                        JSONArray questions = json.getJSONArray("questions");
                        JSONObject attempt = json.optJSONObject("attempt");

                        if (attempt != null) {
                            updateAttemptData(attempt);
                        }

                        runOnUiThread(() -> displayQuestions(questions));
                    } else {
                        runOnUiThread(() -> showError("Нет данных вопросов"));
                    }
                } catch (JSONException e) {
                    runOnUiThread(() -> showError("Ошибка данных: " + e.getMessage()));
                }
            }
        });
    }

    private void updateAttemptData(JSONObject attempt) throws JSONException {
        if (uniqueId == -1 && attempt.has("uniqueid")) {
            uniqueId = attempt.getLong("uniqueid");
            Log.d(TAG, "Unique ID set to: " + uniqueId);
        }
        if (attempt.has("endtime")) {
            startTimer(attempt.getLong("endtime"));
        }
    }

    private void startTimer(long endTime) {
        if (countDownTimer != null) countDownTimer.cancel();

        long currentTime = System.currentTimeMillis() / 1000;
        long timeLeft = Math.max(0, endTime - currentTime);

        if (timeLeft == 0) {
            runOnUiThread(this::submitAttempt); // Запуск в UI-потоке
            return;
        }

        countDownTimer = new CountDownTimer(timeLeft * 1000, 1000) {
            @SuppressLint("DefaultLocale")
            @Override
            public void onTick(long millisUntilFinished) {
                TextView timer = findViewById(R.id.timer);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60;
                timer.setText(String.format("%02d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                finishAttempt(true);
            }
        }.start();
    }

    private Map<Integer, List<Integer>> parseLayoutToPages(String layoutStr) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        String[] parts = layoutStr.split(",");
        int pageIndex = 0;
        map.put(pageIndex, new ArrayList<>());

        for (String part : parts) {
            if (part.equals("0")) {
                pageIndex++;
                map.put(pageIndex, new ArrayList<>());
            } else {
                try {
                    int slot = Integer.parseInt(part);
                    map.get(pageIndex).add(slot);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing layout slot: " + part, e);
                }
            }
        }

        if (map.get(pageIndex).isEmpty()) {
            map.remove(pageIndex);
        }

        return map;
    }

    private void displayQuestions(JSONArray questions) {
        try {
            hiddenFieldsCache.clear(); // Очищаем предыдущий кэш

            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.getJSONObject(i);
                String html = question.getString("html");
                int slot = question.getInt("slot");

                // Извлекаем и сохраняем скрытые поля
                List<NameValue> hiddenFields = extractHiddenFields(html);
                hiddenFieldsCache.put(slot, hiddenFields);
                String type = question.getString("type");

                questionHtmlCache.put(slot, html);
                addQuestionCard(slot, type, html, i + 1);
            }

            updateNavigationButtons();
            progressBar.setVisibility(View.GONE);
        } catch (JSONException e) {
            showError("Ошибка отображения: " + e.getMessage());
        }
    }

    private void addQuestionCard(int slot, String type, String html, int questionNumber) {
        CardView card = new CardView(this);
        card.setCardElevation(4f);
        card.setRadius(8f);
        card.setContentPadding(16, 16, 16, 16);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);

        card.setTag(R.id.tag_question_html, html);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        addQuestionText(layout, html, questionNumber);
        Log.d(TAG, "Adding question card. Slot: " + slot + ", Type: " + type);
        // Добавление элементов ответа
        if ("multichoice".equals(type)) {
            addMultichoiceAnswers(layout, html, slot);
        } else if ("essay".equals(type) || "shortanswer".equals(type)) {
            addTextAnswerField(layout, html, slot, type);
        } else if ("truefalse".equals(type)) {
            addTrueFalseField(layout, html, slot);
        } else if ("matching".equals(type) || "ddmatch".equals(type)) {
            addMatchingField(layout, html, slot, type); // Обновлено для поддержки ddmatch
        } else if ("numerical".equals(type)) {
            addNumericalField(layout, html, slot);
        } else {
            addUnsupportedType(layout, type);
        }
        if ("match".equals(type) || "ddmatch".equals(type)) {
            Log.d(TAG, "Matching question HTML:\n" + html);
        }
        card.addView(layout);
        questionsLayout.addView(card);
    }

    private void addTrueFalseField(LinearLayout layout, String html, int slot) {
        try {
            Document doc = Jsoup.parse(html);
            Elements options = doc.select("input[type=radio]");

            RadioGroup radioGroup = new RadioGroup(this);
            radioGroup.setOrientation(LinearLayout.VERTICAL);
            radioGroup.setPadding(0, 16, 0, 0);

            String selectedValue = null;
            List<RadioButton> buttons = new ArrayList<>();

            for (Element option : options) {
                RadioButton radioButton = new RadioButton(this);

                // Получаем текст ответа
                Element label = option.nextElementSibling();
                if (label != null) {
                    radioButton.setText(label.text());
                } else {
                    // Альтернативный метод для Moodle 4.x
                    Element parent = option.parent();
                    if (parent != null) {
                        radioButton.setText(parent.text());
                    }
                }

                String value = option.attr("value");
                radioButton.setTag(R.id.tag_slot, slot);
                radioButton.setTag(R.id.tag_input_value, value); // Сохраняем значение

                // Проверяем состояние
                if (option.hasAttr("checked") || "checked".equalsIgnoreCase(option.attr("checked"))) {
                    selectedValue = value;
                }

                buttons.add(radioButton);
                radioGroup.addView(radioButton);
            }

            // Устанавливаем выбранную кнопку ПОСЛЕ добавления всех элементов
            if (selectedValue != null) {
                for (RadioButton btn : buttons) {
                    if (selectedValue.equals(btn.getTag(R.id.tag_input_value))) {
                        radioGroup.check(btn.getId());
                        break;
                    }
                }
            }

            layout.addView(radioGroup);
        } catch (Exception e) {
            Log.e(TAG, "Error adding true/false field", e);
            addErrorText(layout, "Ошибка создания поля: " + e.getMessage());
        }
    }

    private void addMatchingField(LinearLayout layout, String html, int slot, String type) {
        try {
            Document doc = Jsoup.parse(html);
            Elements rows = doc.select("table.answer tr"); // Находим все строки таблицы

            if (rows.isEmpty()) {
                Log.w(TAG, "No table rows found for matching question");
                TextView error = new TextView(this);
                error.setText("Не удалось загрузить варианты ответа");
                error.setTextColor(colorManager.getColor("color_1"));
                layout.addView(error);
                return;
            }

            for (Element row : rows) {
                Element textCell = row.selectFirst("td.text");
                if (textCell == null) continue;

                String questionText = textCell.text();
                if (questionText.isEmpty()) continue;

                TextView labelView = new TextView(this);
                labelView.setText(questionText);
                labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                layout.addView(labelView);

                // Ищем элемент <select> в строке
                Element select = row.selectFirst("select");
                if (select == null) {
                    Log.d(TAG, "No select element found in row");
                    continue;
                }

                // Создаем Spinner с вариантами ответов
                Spinner spinner = new Spinner(this);
                List<String> options = new ArrayList<>();
                options.add("-- Выберите --");

                // Добавляем все варианты ответов
                for (Element option : select.select("option")) {
                    if (!option.attr("value").isEmpty()) {
                        options.add(option.text());
                    }
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, options
                );
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);

                // Устанавливаем сохраненное значение
                String selectedValue = null;
                Elements selectedOptions = select.select("option[selected]");
                if (!selectedOptions.isEmpty()) {
                    selectedValue = selectedOptions.first().text();
                }

                if (selectedValue != null && !selectedValue.isEmpty()) {
                    int position = options.indexOf(selectedValue);
                    if (position >= 0) {
                        spinner.setSelection(position);
                    }
                }

                spinner.setTag(R.id.tag_slot, slot);
                spinner.setTag(R.id.tag_input_name, select.attr("name"));
                layout.addView(spinner);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding matching field", e);
            addErrorText(layout, "Ошибка создания поля: " + e.getMessage());
        }
    }

    private void addNumericalField(LinearLayout layout, String html, int slot) {
        try {
            Document doc = Jsoup.parse(html);
            Element input = doc.select("input[type=text]").first();

            if (input != null) {
                TextView label = new TextView(this);
                label.setText("Введите число:");
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                layout.addView(label);

                EditText editText = new EditText(this);
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setTag(R.id.tag_slot, slot);
                editText.setTag(R.id.tag_input_name, input.attr("name"));

                if (input.hasAttr("value")) {
                    editText.setText(input.attr("value"));
                }

                layout.addView(editText);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding numerical field", e);
        }
    }

    private void addQuestionText(LinearLayout layout, String html, int questionNumber) {
        try {
            Document doc = Jsoup.parse(html);
            Element qtextElement = doc.select("div.qtext").first();
            String questionText = (qtextElement != null) ? qtextElement.text() : "Текст вопроса отсутствует";

            TextView questionView = new TextView(this);
            questionView.setText("Вопрос " + questionNumber + ": " + questionText);
            questionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            questionView.setTypeface(null, Typeface.BOLD);
            layout.addView(questionView);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing question text", e);
        }
    }

    private void addMultichoiceAnswers(LinearLayout layout, String html, int slot) {
        try {
            Document doc = Jsoup.parse(html);
            Elements answerDivs = doc.select("div.answer > div.r0, div.answer > div.r1");

            // Определяем тип вопроса (radio или checkbox)
            boolean isRadio = false;
            Element firstInput = answerDivs.select("input").first();
            if (firstInput != null) {
                isRadio = "radio".equals(firstInput.attr("type"));
            }

            ViewGroup answersContainer;
            if (isRadio) {
                // Для одиночного выбора используем RadioGroup
                RadioGroup radioGroup = new RadioGroup(this);
                radioGroup.setOrientation(LinearLayout.VERTICAL);
                answersContainer = radioGroup;
            } else {
                // Для множественного выбора используем LinearLayout
                LinearLayout linearLayout = new LinearLayout(this);
                linearLayout.setOrientation(LinearLayout.VERTICAL);
                answersContainer = linearLayout;
            }

            answersContainer.setPadding(0, 16, 0, 0);

            for (Element answerDiv : answerDivs) {
                Element input = answerDiv.select("input[type=radio], input[type=checkbox]").first();
                Element label = answerDiv.select("div[data-region=answer-label]").first();

                if (input != null && label != null) {
                    CompoundButton button = createChoiceButton(input, label, slot, isRadio);
                    answersContainer.addView(button);

                    // Устанавливаем состояние
                    boolean isChecked = input.hasAttr("checked")
                            || "checked".equalsIgnoreCase(input.attr("checked"));
                    button.setChecked(isChecked);
                }
            }

            layout.addView(answersContainer);
        } catch (Exception e) {
            Log.e(TAG, "Error adding multichoice answers", e);
        }
    }

    private CompoundButton createChoiceButton(Element input, Element label, int slot, boolean isRadio) {
        String inputType = input.attr("type");
        String inputName = input.attr("name");
        String inputValue = input.attr("value");
        String answerText = label.text();

        CompoundButton button = isRadio ? new RadioButton(this) : new CheckBox(this);
        button.setText(answerText);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTag(R.id.tag_slot, slot);

        // Для чекбоксов используем специальное имя с _choice
        if ("checkbox".equals(inputType)) {
            // Извлекаем индекс из ID или создаем уникальный
            String id = input.attr("id");
            String choiceIndex = "";
            if (id != null && id.contains("choice")) {
                choiceIndex = id.substring(id.indexOf("choice") + "choice".length());
            }
            String fixedName = "q" + uniqueId + ":" + slot + "_choice" + choiceIndex;
            button.setTag(R.id.tag_input_name, fixedName);
        } else {
            button.setTag(R.id.tag_input_name, inputName);
        }

        button.setTag(R.id.tag_input_value, inputValue);
        button.setTag(R.id.tag_input_type, inputType); // Сохраняем тип для использования при сохранении

        return button;
    }

    private void addTextAnswerField(LinearLayout layout, String html, int slot, String type) {
        try {
            Document doc = Jsoup.parse(html);
            Element inputElement = doc.select("textarea, input[type=text]").first();
            if (inputElement == null) return;

            String inputName = inputElement.attr("name");

            TextView answerLabel = new TextView(this);
            answerLabel.setText("Ваш ответ:");
            answerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            answerLabel.setPadding(0, 16, 0, 8);
            layout.addView(answerLabel);

            EditText editText = new EditText(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 16);
            editText.setLayoutParams(params);

            if ("essay".equals(type)) {
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                editText.setMinLines(5);
                editText.setGravity(Gravity.TOP);
                editText.setHint("Введите текст (переносы строк сохранятся)");
            } else {
                editText.setInputType(InputType.TYPE_CLASS_TEXT);
                editText.setSingleLine(true);
            }

            editText.setTag(R.id.tag_slot, slot);
            editText.setTag(R.id.tag_input_name, inputName);

            String value = "";
            if (inputElement.hasAttr("value")) {
                value = inputElement.attr("value");
            } else {
                value = inputElement.text();
            }

            if (value != null && !value.isEmpty()) {
                // Для эссе преобразуем HTML в текст с сохранением переносов
                if ("essay".equals(type)) {
                    value = value.replaceAll("<p>", "")
                            .replaceAll("</p>", "\n")
                            .replaceAll("<br\\s*/?>", "\n");
                }
                editText.setText(value);
            }

            layout.addView(editText);
        } catch (Exception e) {
            Log.e(TAG, "Error adding text answer field", e);
            addErrorText(layout, "Ошибка создания поля: " + e.getMessage());
        }
    }

    private void addUnsupportedType(LinearLayout layout, String type) {
        TextView unsupported = new TextView(this);
        unsupported.setText("Тип вопроса не поддерживается: " + type);
        unsupported.setTextColor(colorManager.getColor("color_1"));
        layout.addView(unsupported);
    }

    private void addErrorText(LinearLayout layout, String message) {
        TextView error = new TextView(this);
        error.setText(message);
        error.setTextColor(colorManager.getColor("color_1"));
        layout.addView(error);
    }

    private <T extends View> List<View> getAllViews(ViewGroup group, Class<T> viewType) {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                views.addAll(getAllViews((ViewGroup) view, viewType));
            } else if (viewType.isInstance(view)) {
                views.add(view);
            }
        }
        return views;
    }

    private void updateNavigationButtons() {
        Button prevButton = findViewById(R.id.btnPrev);
        Button nextButton = findViewById(R.id.btnNext);

        prevButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage < totalPages - 1);

        TextView pageInfo = findViewById(R.id.pageInfo);
        pageInfo.setText(String.format("%d/%d", currentPage + 1, totalPages));
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
        });
    }

    private void finishWithError(String message) {
        showError(message);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
    }
    @Override
    public void onBackPressed() {
        saveAnswersViaApi(
                () -> {
                    super.onBackPressed(); // Завершаем активность после сохранения
                }
        );
    }
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Activity resumed");
    }

    @Override
    protected void onPause() {

        Log.d(TAG, "Activity paused");
        saveAnswersViaApi(
                () -> {
                    super.onBackPressed(); // Завершаем активность после сохранения
                }
        );

        super.onPause();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}