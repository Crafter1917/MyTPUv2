package com.example.mytpu.moodle;

import static com.example.mytpu.moodle.ModuleDetailActivity.WEB_SERVICE_URL;
import okio.Buffer;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.mytpu.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class QuizAttemptActivity extends AppCompatActivity {
    private Map<Integer, String> questionHtmlCache = new HashMap<>();
    private Map<Integer, List<Integer>> pageSlotsMap = new HashMap<>();
    private static final String TAG = "QuizAttempt";
    private OkHttpClient client;
    private String token;
    private int attemptId;
    private long uniqueId = -1;
    private ProgressBar progressBar;
    private LinearLayout questionsLayout;
    private LinearLayout questionsNavLayout;
    private int currentPage = 0;
    private int totalPages;
    private CountDownTimer countDownTimer;
    private Map<String, Request> saveRequestCache = new HashMap<>();
    private int cmid;
    private Button submitButton;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_attempt);

        progressBar = findViewById(R.id.progressBar);
        questionsLayout = findViewById(R.id.questionsLayout);
        questionsNavLayout = findViewById(R.id.questionsNavLayout);
        submitButton = findViewById(R.id.btnSubmit);

        initSecureStorage();

        attemptId = getIntent().getIntExtra("attemptId", -1);
        if (attemptId == -1) {
            finishWithError("Invalid attempt ID");
            return;
        }
        questionHtmlCache = new HashMap<>();
        saveRequestCache = new HashMap<>();
        String layoutStr = getIntent().getStringExtra("layout");
        cmid = getIntent().getIntExtra("cmid", -1);
        if (cmid == -1) {
            finishWithError("Invalid cmid");
            return;
        }
        if (layoutStr == null || layoutStr.isEmpty()) {
            Log.e(TAG, "Layout string is missing or empty!");
            loadLayoutFromApi();
        } else {
            pageSlotsMap = parseLayoutToPages(layoutStr);
            setupNavigation();
            loadPage(currentPage);
        }
    }

    private void loadLayoutFromApi() {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_get_user_attempts")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .build();

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> showError("Ошибка загрузки layout"));
            }

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
                                setupNavigation();
                                loadPage(currentPage);
                            });
                            return;
                        }
                    }
                    runOnUiThread(() -> showError("Layout not found in API response"));
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Error processing layout: " + e.getMessage()));
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
            }

            client = new OkHttpClient();
        } catch (Exception e) {
            showError("Security error: " + e.getMessage());
            finish();
        }
    }

    private String extractSessKey(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            Element sesskeyElement = doc.select("input[name=sesskey]").first();
            if (sesskeyElement != null) {
                return sesskeyElement.attr("value");
            }
            Pattern pattern = Pattern.compile("sesskey=([a-zA-Z0-9]+)");
            Matcher matcher = pattern.matcher(htmlContent);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting sesskey", e);
        }
        return "default_sesskey";
    }

    private void setupNavigation() {
        Button prevButton = findViewById(R.id.btnPrev);
        Button nextButton = findViewById(R.id.btnNext);

        prevButton.setOnClickListener(v -> navigateToPage(currentPage - 1));
        nextButton.setOnClickListener(v -> navigateToPage(currentPage + 1));

        submitButton.setOnClickListener(v -> {
            saveCurrentPageAnswers(() -> new AlertDialog.Builder(QuizAttemptActivity.this)
                    .setTitle("Завершение теста")
                    .setMessage("Вы уверены, что хотите завершить попытку?")
                    .setPositiveButton("Да", (dialog, which) -> submitAttempt())
                    .setNegativeButton("Нет", null)
                    .show());
        });
    }

    private void navigateToPage(int newPage) {
        if (newPage < 0 || newPage >= totalPages) return;

        saveCurrentPageAnswers(() -> {
            currentPage = newPage;
            loadPage(currentPage);
        });
    }

    private void saveCurrentPageAnswers(Runnable onComplete) {
        Log.d(TAG, "Saving answers for page: " + currentPage);
        boolean hasChanges = false;

        for (View view : getAllEditTexts(questionsLayout)) {
            if (view instanceof EditText) {
                EditText editText = (EditText) view;
                Integer slot = (Integer) editText.getTag(R.id.tag_slot);
                String name = (String) editText.getTag(R.id.tag_input_name);
                String type = (String) editText.getTag(R.id.tag_question_type);

                if (slot == null || name == null || type == null) {
                    Log.w(TAG, "Missing tags for EditText: " + editText);
                    continue;
                }

                String text = editText.getText().toString();
                Log.d(TAG, "Saving answer for slot: " + slot + ", name: " + name);
                saveTextAnswer(slot, name, text, "essay".equals(type), currentPage, getNextPage());
                hasChanges = true;
            }
        }

        if (hasChanges) {
            showSavingProgress();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                hideSavingProgress();
                onComplete.run();
            }, 500);
        } else {
            onComplete.run();
        }
    }

    private void showSavingProgress() {
        submitButton.setText("Сохранение...");
        submitButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideSavingProgress() {
        submitButton.setText("Завершить");
        submitButton.setEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private int getNextPage() {
        if (currentPage < totalPages - 1) {
            return currentPage + 1;
        }
        return -1;
    }

    private void loadPage(int pageIndex) {
        progressBar.setVisibility(View.VISIBLE);
        questionsLayout.removeAllViews();

        int apiPage = pageIndex;

        HttpUrl.Builder urlBuilder = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_get_attempt_data")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("page", String.valueOf(apiPage))
                .addQueryParameter("moodlewsrestformat", "json");

        HttpUrl url = urlBuilder.build();
        Log.d(TAG, "Loading page: " + url);

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> showError("Connection error: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Response: " + responseBody);

                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("questions")) {
                        JSONArray questions = json.getJSONArray("questions");

                        if (json.has("attempt")) {
                            JSONObject attempt = json.getJSONObject("attempt");
                            if (uniqueId == -1 && attempt.has("uniqueid")) {
                                uniqueId = attempt.getLong("uniqueid");
                            }
                            if (attempt.has("endtime")) {
                                long endTime = attempt.getLong("endtime");
                                startTimer(endTime);
                            }
                        }

                        runOnUiThread(() -> displayQuestions(questions));
                    } else {
                        runOnUiThread(() -> showError("No questions found in response"));
                    }
                } catch (JSONException e) {
                    runOnUiThread(() -> showError("Data error: " + e.getMessage()));
                }
            }
        });
    }

    private void startTimer(long endTime) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        long currentTime = System.currentTimeMillis() / 1000;
        long timeLeft = endTime - currentTime;

        if (timeLeft <= 0) {
            submitAttempt();
            return;
        }

        countDownTimer = new CountDownTimer(timeLeft * 1000, 1000) {
            @SuppressLint("DefaultLocale")
            @Override
            public void onTick(long millisUntilFinished) {
                if (isFinishing() || isDestroyed()) return;

                TextView timer = findViewById(R.id.timer);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60;
                timer.setText(String.format("%02d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                if (isFinishing() || isDestroyed()) return;
                submitAttempt();
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

        totalPages = map.size();
        return map;
    }

    @SuppressLint("SetTextI18n")
    private void displayQuestions(JSONArray questions) {
        try {
            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.getJSONObject(i);
                String htmlContent = question.getString("html");
                int slot = question.getInt("slot");

                questionHtmlCache.put(slot, htmlContent);
                String questionType = question.getString("type");
                Log.d(TAG, "Question #" + slot + " type: " + questionType);

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

                LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(16, 16, 16, 16);

                Document doc = Jsoup.parse(htmlContent);
                doc.select("script").remove();

                Element qtextElement = doc.select("div.qtext").first();
                String questionTextStr = (qtextElement != null) ? qtextElement.text() : "Текст вопроса отсутствует";

                TextView questionText = new TextView(this);
                questionText.setText("Вопрос " + (i + 1) + ": " + questionTextStr);
                questionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                questionText.setTypeface(null, Typeface.BOLD);
                layout.addView(questionText);

                if ("multichoice".equals(questionType)) {
                    LinearLayout answersLayout = new LinearLayout(this);
                    answersLayout.setOrientation(LinearLayout.VERTICAL);
                    answersLayout.setPadding(0, 16, 0, 0);

                    Elements answerDivs = doc.select("div.answer > div.r0, div.answer > div.r1");
                    if (!answerDivs.isEmpty()) {
                        for (Element answerDiv : answerDivs) {
                            Elements inputs = answerDiv.select("input[type=radio], input[type=checkbox]");
                            Element input = inputs.first();
                            Element label = answerDiv.select("div[data-region=answer-label]").first();

                            if (input != null && label != null) {
                                String inputType = input.attr("type");
                                String inputName = input.attr("name");
                                String inputValue = input.attr("value");
                                String answerText = label.text();

                                CompoundButton button;
                                if ("checkbox".equals(inputType)) {
                                    button = new CheckBox(this);
                                } else if ("radio".equals(inputType)) {
                                    button = new RadioButton(this);
                                } else {
                                    continue;
                                }

                                button.setText(answerText);
                                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                                button.setTag(inputName + "_" + inputValue);

                                if (input.hasAttr("checked")) {
                                    button.setChecked(true);
                                }

                                button.setOnCheckedChangeListener((buttonView, isChecked) -> {
                                    saveAnswer(slot, inputName, inputValue, isChecked);
                                });

                                answersLayout.addView(button);
                            }
                        }
                        layout.addView(answersLayout);
                    }
                } else if ("essay".equals(questionType) || "shortanswer".equals(questionType)) {
                    Element textArea = doc.select("textarea").first();
                    Element inputText = doc.select("input[type=text]").first();
                    String inputName = null;

                    if (textArea != null) {
                        inputName = textArea.attr("name");
                    } else if (inputText != null) {
                        inputName = inputText.attr("name");
                    }

                    if (inputName != null) {
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

                        if ("essay".equals(questionType)) {
                            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                            editText.setMinLines(5);
                            editText.setGravity(Gravity.TOP);
                            editText.setVerticalScrollBarEnabled(true);
                        } else {
                            editText.setInputType(InputType.TYPE_CLASS_TEXT);
                            editText.setSingleLine(true);
                        }

                        editText.setTag(R.id.tag_input_name, inputName);
                        editText.setTag(R.id.tag_question_type, questionType);
                        editText.setTag(R.id.tag_slot, slot);

                        Element existingAnswer = doc.select("textarea, input[type=text]").first();
                        if (existingAnswer != null) {
                            String value = existingAnswer.val();
                            if (value != null && !value.isEmpty()) {
                                editText.setText(value);
                            }
                        }

                        layout.addView(editText);
                    } else {
                        TextView errorText = new TextView(this);
                        errorText.setText("Не удалось найти поле для ответа");
                        errorText.setTextColor(Color.RED);
                        layout.addView(errorText);
                    }
                } else {
                    TextView unsupportedType = new TextView(this);
                    unsupportedType.setText("Тип вопроса не поддерживается: " + questionType);
                    unsupportedType.setTextColor(Color.RED);
                    layout.addView(unsupportedType);
                }

                card.addView(layout);
                questionsLayout.addView(card);
            }

            updateNavigationButtons();
            progressBar.setVisibility(View.GONE);
        } catch (JSONException e) {
            showError("Error loading questions: " + e.getMessage());
        }
    }

    private List<View> getAllEditTexts(ViewGroup group) {
        List<View> editTexts = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if (view instanceof ViewGroup) {
                editTexts.addAll(getAllEditTexts((ViewGroup) view));
            } else if (view instanceof EditText) {
                editTexts.add(view);
            }
        }
        return editTexts;
    }

    private void updateNavigationButtons() {
        Button prevButton = findViewById(R.id.btnPrev);
        Button nextButton = findViewById(R.id.btnNext);

        prevButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage < totalPages - 1);

        TextView pageInfo = findViewById(R.id.pageInfo);
        pageInfo.setText(String.format("%d/%d", currentPage + 1, totalPages));
    }

    private void saveAnswer(int slot, String inputName, String inputValue, boolean isChecked) {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_save_attempt")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("slots[" + slot + "]", inputName)
                .addQueryParameter(inputName, isChecked ? inputValue : "0")
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(QuizAttemptActivity.this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    runOnUiThread(() ->
                            Toast.makeText(QuizAttemptActivity.this, "Ответ сохранен", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void saveTextAnswer(int slot, String inputName, String text, boolean isEssay, int currentPage, int nextPage) {
        Log.d(TAG, "Saving text answer for slot: " + slot);

        if (uniqueId == -1) {
            if (questionHtmlCache.containsKey(slot)) {
                Pattern pattern = Pattern.compile("question-(\\d+)-" + slot);
                Matcher matcher = pattern.matcher(questionHtmlCache.get(slot));
                if (matcher.find()) {
                    uniqueId = Long.parseLong(matcher.group(1));
                }
            }
            if (uniqueId == -1) {
                Log.e(TAG, "Unique ID recovery failed");
                return;
            }
        }

        if (cmid <= 0) {
            cmid = getIntent().getIntExtra("cmid", -1);
            if (cmid <= 0) {
                Log.e(TAG, "cmid recovery failed");
                return;
            }
        }

        if (!questionHtmlCache.containsKey(slot)) {
            Log.e(TAG, "No HTML cached for slot: " + slot);
            return;
        }

        String html = questionHtmlCache.get(slot);
        String sesskey = extractSessKey(html);
        Log.d(TAG, "Extracted sesskey: " + sesskey);

        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder().build();

        String sequenceCheck = "1";
        try {
            Document doc = Jsoup.parse(html);
            Element seqInput = doc.select("input[name$=sequencecheck]").first();
            if (seqInput != null) {
                sequenceCheck = seqInput.attr("value");
                Log.d(TAG, "Extracted sequencecheck: " + sequenceCheck);
            } else {
                Log.w(TAG, "Sequencecheck input not found for slot: " + slot);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting sequencecheck", e);
        }

        FormBody.Builder formBuilder = new FormBody.Builder()
                .add("wstoken", token)
                .add("wsfunction", "mod_quiz_save_attempt")
                .add("cmid", String.valueOf(cmid))
                .add("attempt", String.valueOf(attemptId))
                .add("sesskey", sesskey)
                .add("slots[]", String.valueOf(slot))
                .add("thispage", String.valueOf(currentPage))
                .add("nextpage", String.valueOf(nextPage))
                .add("timeup", "0")
                .add("scrollpos", "")
                .add("next", "Следующая страница");

        String prefix = "q" + uniqueId + ":" + slot;
        formBuilder.add(prefix + "_:flagged", "0");
        formBuilder.add(prefix + "_:sequencecheck", sequenceCheck);
        formBuilder.add(prefix + "_answer", text);

        if (isEssay) {
            formBuilder.add(prefix + "_answerformat", "1");
        }

        RequestBody body = formBuilder.build();
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        String requestBodyStr = bodyToString(body);
        Log.d(TAG, "Saving text answer request: " + requestBodyStr);

        String requestHash = slot + "_" + text.hashCode();
        if (saveRequestCache.containsKey(requestHash)) {
            Log.w(TAG, "Duplicate request skipped: " + requestHash);
            return;
        }
        saveRequestCache.put(requestHash, request);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Save failed for slot: " + slot, e);
                saveRequestCache.remove(requestHash);
                runOnUiThread(() ->
                        Toast.makeText(QuizAttemptActivity.this, "Ошибка сохранения", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "null";
                    Log.d(TAG, "Save response for slot " + slot + ": " + response.code() + " - " + responseBody);
                    saveRequestCache.remove(requestHash);

                    if (response.isSuccessful()) {
                        Log.d(TAG, "Save successful for slot: " + slot);
                        runOnUiThread(() ->
                                Toast.makeText(QuizAttemptActivity.this, "Ответ сохранён", Toast.LENGTH_SHORT).show());
                    } else {
                        Log.w(TAG, "Save failed with code: " + response.code());
                        if (responseBody.contains("invalidparameter")) {
                            Log.e(TAG, "Invalid parameter detected");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing save response", e);
                }
            }
        });
    }

    private String bodyToString(RequestBody body) {
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        } catch (IOException e) {
            return "Exception while converting body: " + e.getMessage();
        }
    }

    private void submitAttempt() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "mod_quiz_process_attempt")
                .addQueryParameter("attemptid", String.valueOf(attemptId))
                .addQueryParameter("finishattempt", "1")
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        progressBar.setVisibility(View.VISIBLE);
        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> showError("Ошибка завершения: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Submit response: " + responseBody);

                    if (response.isSuccessful()) {
                        JSONObject json = new JSONObject(responseBody);
                        if (json.has("status") && json.getString("status").equals("ok")) {
                            runOnUiThread(() -> {
                                Toast.makeText(QuizAttemptActivity.this,
                                        "Тест завершен", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                            return;
                        }
                    }
                    runOnUiThread(() -> showError("Ошибка завершения попытки"));
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Ошибка обработки: " + e.getMessage()));
                }
            }
        });
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
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}