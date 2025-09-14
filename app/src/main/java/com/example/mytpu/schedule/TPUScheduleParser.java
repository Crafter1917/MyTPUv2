// TPUScheduleParser.java
package com.example.mytpu.schedule;

import static android.content.Context.MODE_PRIVATE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TPUScheduleParser {
    private static final String TAG = "TPUScheduleParser";
    private static final String BASE_URL = "https://rasp.tpu.ru/";
    private static final String COOKIE_PREFS = "TPUCookies";
    private Context context;
    private OkHttpClient client;
    private SharedPreferences cookiePrefs;
    DecryptionCallback decryptionCallback;

    public interface DecryptionCallback {
        void onDecryptionComplete(String decryptedHtml);
        void onDecryptionError(Exception e);
    }
    public Schedule getSchedule(String groupId, int year, int weekNumber) throws IOException {
        Log.d(TAG, "Fetching schedule for group: " + groupId + ", year: " + year + ", week: " + weekNumber);

        // Проверяем валидность куки перед запросом
        if (!checkCookiesValidity()) {
            Log.d(TAG, "Cookies invalid, requiring captcha");
            throw new IOException("Требуется обновление авторизации. Решите капчу.");
        }

        String urlStr = String.format("%s%s/%d/%d/view.html", BASE_URL, groupId, year, weekNumber);
        Log.d(TAG, "Request URL: " + urlStr);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(urlStr).newBuilder();
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();
        request = addCookiesToRequest(request);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorMessage;
                int code = response.code();
                if (code == 400) {
                    errorMessage = "Расписание на выбранную неделю недоступно";
                } else if (code == 404) {
                    errorMessage = "Расписание не найдено";
                } else {
                    errorMessage = "Ошибка сервера: " + code;
                }
                Log.d(TAG, "Schedule request failed: " + errorMessage);
                throw new IOException(errorMessage);
            }

            // ДОБАВЛЯЕМ ПРОВЕРКУ НА NULL
            if (response.body() == null) {
                Log.e(TAG, "Response body is null for URL: " + urlStr);
                throw new IOException("Пустой ответ от сервера");
            }

            String responseBody = response.body().string();
            Log.d(TAG, "Response received, length: " + responseBody.length());

            // Сохраняем исходный HTML для отладки
            saveHtmlToFile(responseBody, "original_" + groupId + "_" + year + "_" + weekNumber + ".html");

            // Проверяем наличие зашифрованных данных
            if (responseBody.contains("encrypt")) {
                Log.d(TAG, "Encrypted data detected, attempting decryption");
                int maxAttempts = 3;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        Log.d(TAG, "Decryption attempt " + attempt + " of " + maxAttempts);
                        String decryptedHtml = decryptWithWebView(groupId, year, weekNumber);
                        if (decryptedHtml == null || decryptedHtml.isEmpty()) {
                            throw new IOException("Decryption returned empty result");
                        }
                        Log.d(TAG, "Decrypted HTML length: " + decryptedHtml.length());

                        // Сохраняем расшифрованный HTML для анализа
                        saveHtmlToFile(decryptedHtml, "decrypted_" + groupId + "_" + year + "_" + weekNumber + ".html");

                        Document doc = Jsoup.parse(decryptedHtml);
                        Schedule schedule = parseSchedule(doc, groupId, year, weekNumber);

                        // Очищаем временные файлы после успешного парсинга
                        cleanupTempFiles();

                        return schedule;
                    } catch (Exception e) {
                        if (attempt == maxAttempts) {
                            Log.e(TAG, "Decryption failed after " + maxAttempts + " attempts: " + e.getMessage());
                            throw new IOException("Ошибка дешифрования после " + maxAttempts + " попыток: " + e.getMessage());
                        } else {
                            Log.w(TAG, "Decryption attempt " + attempt + " failed, retrying...", e);
                            try {
                                // Задержка между попытками
                                Thread.sleep(200);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted during decryption retry", ie);
                            }
                        }
                    }
                }
            } else {
                Log.d(TAG, "No encrypted data found, parsing directly");
                Document doc = Jsoup.parse(responseBody);
                Schedule schedule = parseSchedule(doc, groupId, year, weekNumber);

                // Очищаем временные файлы после успешного парсинга
                cleanupTempFiles();

                return schedule;
            }
        }
        return null;
    }
    private String decryptWithWebView(String groupId, int year, int weekNumber) {
        Log.d(TAG, "Starting WebView decryption for group: " + groupId + ", year: " + year + ", week: " + weekNumber);

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        final Exception[] error = new Exception[1];

        // Запускаем на главном потоке
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = this.context;
            if (!(context instanceof Activity)) {
                error[0] = new IOException("Context is not an Activity");
                latch.countDown();
                return;
            }

            Activity activity = (Activity) context;

            // Создаем WebView программно
            WebView webView = new WebView(activity);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);

            String url = String.format("https://rasp.tpu.ru/%s/%d/%d/view.html", groupId, year, weekNumber);
            Log.d(TAG, "WebView loading URL: " + url);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "WebView page finished loading: " + url);

                    // Динамическое время ожидания на основе размера контента
                    int waitTime = calculateDynamicWaitTime(view.getContentHeight() * 100); // Примерная оценка
                    Log.d(TAG, "Dynamic wait time: " + waitTime + "ms");

                    // Даем время на выполнение JavaScript
                    new Handler().postDelayed(() -> {
                        view.evaluateJavascript(
                                "(function() { return document.documentElement.outerHTML; })();",
                                html -> {
                                    try {
                                        if (html == null) {
                                            throw new IOException("WebView returned null HTML");
                                        }

                                        String cleanedHtml = html.replaceAll("^\"|\"$", "")
                                                .replace("\\u003C", "<")
                                                .replace("\\u003E", ">")
                                                .replace("\\\"", "\"")
                                                .replace("\\\\", "\\");

                                        if (cleanedHtml.isEmpty()) {
                                            throw new IOException("WebView returned empty HTML");
                                        }

                                        Log.d(TAG, "WebView decryption successful, HTML length: " + cleanedHtml.length());

                                        // Сохраняем HTML в файл для анализа
                                        String filename = String.format("decrypted_%s_%d_%d.html", groupId, year, weekNumber);
                                        saveHtmlToFile(cleanedHtml, filename);

                                        result[0] = cleanedHtml;
                                        latch.countDown();
                                    } catch (Exception e) {
                                        error[0] = e;
                                        latch.countDown();
                                    }
                                }
                        );
                    }, waitTime);
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    super.onReceivedError(view, errorCode, description, failingUrl);
                    error[0] = new IOException("WebView error: " + description);
                    latch.countDown();
                }
            });

            // Загружаем куки в WebView
            loadCookiesToWebView(webView);

            // Загружаем URL
            webView.loadUrl(url);
        });

        // Ждем завершения
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Decryption timeout or interrupted", e);
        }

        if (error[0] != null) {
            throw new RuntimeException("Failed to decrypt HTML via WebView: " + error[0].getMessage(), error[0]);
        }

        return result[0];
    }
    public void cleanupTempFiles() {
        new Thread(() -> {
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir != null && dir.exists()) {
                    File[] files = dir.listFiles((d, name) ->
                            name.startsWith("original_") || name.startsWith("decrypted_") || name.startsWith("no_timetable_"));

                    if (files != null) {
                        int deletedCount = 0;
                        for (File file : files) {
                            if (file.exists() && file.delete()) {
                                deletedCount++;
                            } else {
                                Log.w(TAG, "Failed to delete temp file: " + file.getName());
                            }
                        }
                        Log.d(TAG, "Cleaned up " + deletedCount + " temporary files");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning up temp files", e);
            }
        }).start();
    }
    private void saveHtmlToFile(String html, String filename) {
        try {
            File file = new File(context.getExternalFilesDir(null), filename);
            FileWriter writer = new FileWriter(file);
            writer.write(html);
            writer.close();
            Log.d(TAG, "HTML saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving HTML to file", e);
        }
    }
    private void loadCookiesToWebView(WebView webView) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
            String cookies = prefs.getString("cookies", "");

            if (!cookies.isEmpty()) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setCookie(BASE_URL, cookies);
                cookieManager.flush();
                Log.d(TAG, "Cookies loaded to WebView: " + cookies);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cookies to WebView: " + e.getMessage());
        }
    }
    public TPUScheduleParser(Context context) {
        this.context = context;
        this.cookiePrefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);

        // Инициализация HTTP-клиента с поддержкой cookies
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .cookieJar(new JavaNetCookieJar(new java.net.CookieManager()))
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request originalRequest = chain.request();
                        Request requestWithCookies = addCookiesToRequest(originalRequest);
                        Response response = chain.proceed(requestWithCookies);

                        // Сохраняем cookies из ответа
                        if (response.headers("Set-Cookie") != null) {
                            saveCookiesFromResponse(response);
                        }

                        return response;
                    }
                })
                .build();
    }
    private void saveCookiesFromResponse(Response response) {
        try {
            List<String> cookies = response.headers("Set-Cookie");
            if (cookies != null && !cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (String cookie : cookies) {
                    cookieHeader.append(cookie).append("; ");
                }

                SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
                prefs.edit().putString("cookies", cookieHeader.toString()).apply();

                Log.d(TAG, "Saved cookies from response: " + cookieHeader.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving cookies from response: " + e.getMessage());
        }
    }
    public boolean checkCookiesValidity() {
        try {
            // Пробуем запросить данные, требующие авторизации
            List<School> schools = getSchools();
            return schools != null && !schools.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Cookies check failed: " + e.getMessage());
            return false;
        }
    }
    private Request addCookiesToRequest(Request request) {
        SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
        String cookies = prefs.getString("cookies", "");

        if (!cookies.isEmpty()) {
            String formattedCookies = cookies;
            if (!cookies.endsWith(";")) {
                formattedCookies = cookies + ";";
            }

            return request.newBuilder()
                    .header("Cookie", formattedCookies)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build();
        }

        return request;
    }
    public void forceSetCookies(String cookies) {
        SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
        prefs.edit().putString("cookies", cookies).apply();
        Log.d(TAG, "Cookies forced: " + cookies);
    }
    public void loadCookiesFromPrefs() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
            String cookies = prefs.getString("cookies", "");

            if (!cookies.isEmpty()) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setCookie(BASE_URL, cookies);
                cookieManager.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cookies: " + e.getMessage());
        }
    }
    public List<School> getSchools() throws IOException {
        List<School> schools = new ArrayList<>();

        Request request = new Request.Builder()
                .url(BASE_URL)
                .build();

        request = addCookiesToRequest(request);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            Document doc = Jsoup.parse(responseBody);

            // Улучшенная проверка капчи
            boolean hasCaptcha = doc.select("input[name=captcha], #captcha, .captcha").size() > 0 ||
                    doc.text().toLowerCase().contains("капча") ||
                    doc.text().toLowerCase().contains("captcha") ||
                    doc.select("form").stream().anyMatch(form ->
                            form.attr("action").toLowerCase().contains("captcha"));

            if (hasCaptcha) {
                saveHtmlToFile(responseBody, "captcha_detected.html");
                throw new IOException("Требуется решение капчи");
            }

            Elements schoolLinks = doc.select("a[href*=/site/department.html?id=]");
            for (Element link : schoolLinks) {
                School school = new School();
                String href = link.attr("href");
                school.id = href.substring(href.indexOf("id=") + 3);
                school.name = link.text().trim();
                schools.add(school);
            }
        }

        return schools;
    }
    public void syncCookiesFromWebView() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie(BASE_URL);

            if (cookies != null && !cookies.isEmpty()) {
                // Нормализуем куки
                String normalizedCookies = cookies.replaceAll("\\s+", "")
                        .replaceAll(";+", ";")
                        .replaceAll("=+", "=");

                SharedPreferences prefs = context.getSharedPreferences(COOKIE_PREFS, MODE_PRIVATE);
                prefs.edit().putString("cookies", normalizedCookies).apply();

                // Также обновляем куки в текущем клиенте
                forceSetCookies(normalizedCookies);
                Log.d(TAG, "Cookies fully synced from WebView: " + normalizedCookies);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing cookies from WebView", e);
        }
    }
    public List<Group> getGroups(String schoolId) throws IOException {
        Log.d(TAG, "Fetching groups for school ID: " + schoolId);
        List<Group> groups = new ArrayList<>();

        Request request = new Request.Builder()
                .url(BASE_URL + "site/department.html?id=" + schoolId + "&cource=1")
                .build();

        request = addCookiesToRequest(request);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            Document doc = Jsoup.parse(responseBody);

            Elements groupLinks = doc.select("a[href*=/gruppa_]");

            for (Element link : groupLinks) {
                String href = link.attr("href");
                if (href.contains("/gruppa_")) {
                    Pattern pattern = Pattern.compile("gruppa_\\d+");
                    Matcher matcher = pattern.matcher(href);
                    if (matcher.find()) {
                        Group group = new Group();
                        group.id = matcher.group();
                        group.name = link.text().trim();
                        groups.add(group);
                        Log.d(TAG, "Found group: " + group.name + " (ID: " + group.id + ")");
                    }
                }
            }
        }
        return groups;
    }
    private Schedule parseSchedule(Document doc, String groupId, int year, int weekNumber) {
        Schedule schedule = new Schedule();
        schedule.groupId = groupId;
        schedule.year = year;
        schedule.weekNumber = weekNumber;
        parseDatesFromTableHeaders(doc, schedule);
        Element table = doc.select("#raspisanie-table table").first();
        if (table != null) {
            parseTableSchedule(table, schedule);
            return schedule;
        }

        Log.d(TAG, "Starting to parse schedule document");

        // Сохраняем HTML для детального анализа
        saveHtmlToFile(doc.html(), "detailed_analysis_" + groupId + "_" + year + "_" + weekNumber + ".html");

        // Анализируем всю структуру документа для поиска расписания
        analyzeDocumentStructure(doc);

        // 1. Поиск расписания через анализ содержимого
        Elements allElements = doc.getAllElements();

        // Ищем элементы, которые могут содержать расписание
        for (Element element : allElements) {
            String text = element.text().trim();
            String html = element.html();

            // Проверяем, содержит ли элемент признаки расписания
            if (isScheduleElement(element, text, html)) {
                Log.d(TAG, "Potential schedule element found: " + element.tagName() +
                        " with classes: " + element.className());

                // Пытаемся извлечь расписание из этого элемента
                if (extractScheduleFromElement(element, schedule)) {
                    Log.d(TAG, "Schedule successfully extracted from element");
                    return schedule;
                }
            }
        }

        // 2. Экстренный поиск по шаблонам времени
        Log.d(TAG, "Trying emergency time-based search");
        extractScheduleFromTimePatterns(doc, schedule);

        // 3. Если ничего не найдено, анализируем почему
        if (schedule.days.stream().allMatch(List::isEmpty)) {
            Log.e(TAG, "Complete schedule parsing failure");
            analyzeParsingFailure(doc, groupId, year, weekNumber);
        }

        return schedule;
    }
    private void parseDatesFromTableHeaders(Document doc, Schedule schedule) {
        try {
            Elements dateHeaders = doc.select("thead tr th.text-center.hidden-xs");

            for (int i = 0; i < dateHeaders.size() && i < 7; i++) {
                Element header = dateHeaders.get(i);
                String text = cleanText(header.text());

                // Извлекаем дату из текста (первая часть до перевода строки)
                String datePart = text.split("\\s+")[0]; // Берем первую часть (дату)

                // Преобразуем формат даты из "dd.MM.yy" в "dd.MM.yyyy"
                SimpleDateFormat inputFormat = new SimpleDateFormat("dd.MM.yy");
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd.MM.yyyy");

                try {
                    Date date = inputFormat.parse(datePart);
                    String formattedDate = outputFormat.format(date);
                    schedule.dates[i] = formattedDate;
                } catch (ParseException e) {
                    schedule.dates[i] = cleanText(datePart); // Оставляем как есть, если не удалось распарсить
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing dates from table headers", e);
        }
    }
    private boolean isScheduleElement(Element element, String text, String html) {
        // Признаки элемента с расписанием
        if (text.contains("08:30") || text.contains("10:25") || text.contains("12:40") ||
                text.contains("14:35") || text.contains("16:30") || text.contains("18:25")) {
            return true;
        }

        if (html.contains("08:30") || html.contains("10:25") || html.contains("12:40") ||
                html.contains("14:35") || html.contains("16:30") || html.contains("18:25")) {
            return true;
        }

        if (element.className().contains("time") || element.className().contains("timetable") ||
                element.className().contains("table") || element.id().contains("rasp")) {
            return true;
        }

        return false;
    }
    private boolean extractScheduleFromElement(Element element, Schedule schedule) {
        try {
            Elements tables = element.select("table");
            if (!tables.isEmpty()) {
                return parseTableSchedule(tables.first(), schedule);
            }
            return parseDataAttributes(element, schedule);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting schedule from element", e);
            return false;
        }
    }
    private boolean parseTableSchedule(Element table, Schedule schedule) {
        Elements rows = table.select("tr");

        for (int i = 0; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cells = row.select("td, th");

            if (cells.size() > 0) {
                // Извлекаем время из атрибута title
                String time = cells.get(0).attr("title");
                if (time.isEmpty()) {
                    time = cells.get(0).text();
                }
                time = cleanText(time.replace("\n", " - "));

                // Пропускаем строки с заголовками
                if (time.equals("Время")) {
                    continue;
                }

                // Обрабатываем ячейки с занятиями
                for (int j = 1; j < cells.size() && j <= 7; j++) {
                    Element cell = cells.get(j);
                    String cellText = cleanText(cell.text());

                    if (!cellText.isEmpty() && !cellText.equals("-")) {
                        Lesson lesson = parseLesson(cell, time);
                        if (lesson != null) {
                            schedule.addLesson(j - 1, lesson);
                        }
                    }
                }
            }
        }

        return !schedule.days.stream().allMatch(List::isEmpty);
    }
    private boolean parseDataAttributes(Element element, Schedule schedule) {
        Log.d(TAG, "Parsing data attributes");
        // Пытаемся найти данные в атрибутах data-*

        Elements dataElements = element.select("[data-time], [data-lesson], [data-subject]");
        for (Element dataElement : dataElements) {
            String time = dataElement.attr("data-time");
            if (time.isEmpty()) continue;

            Lesson lesson = new Lesson();
            lesson.time = time;
            lesson.subject = dataElement.attr("data-subject");
            lesson.teacher = dataElement.attr("data-teacher");
            lesson.location = dataElement.attr("data-location");

            // Определяем день недели
            String dayStr = dataElement.attr("data-day");
            int day = dayStr.isEmpty() ? 0 : Integer.parseInt(dayStr) - 1;

            if (day >= 0 && day < 7) {
                schedule.addLesson(day, lesson);
            }
        }

        return !schedule.days.stream().allMatch(List::isEmpty);
    }
    private void extractScheduleFromTimePatterns(Document doc, Schedule schedule) {
        Log.d(TAG, "Extracting schedule from time patterns");

        // Ищем все элементы, содержащие время занятий
        Pattern timePattern = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
        Elements allElements = doc.body().select("*");

        for (Element element : allElements) {
            String text = element.text();
            Matcher matcher = timePattern.matcher(text);

            if (matcher.find()) {
                String time = matcher.group();
                // Пытаемся найти связанные элементы с информацией о занятии
                findRelatedLessonInfo(element, time, schedule);
            }
        }
    }
    private void findRelatedLessonInfo(Element timeElement, String time, Schedule schedule) {
        // Ищем соседние элементы, которые могут содержать информацию о занятии
        Element parent = timeElement.parent();
        if (parent != null) {
            Elements siblings = parent.children();

            for (int i = 0; i < siblings.size(); i++) {
                if (siblings.get(i).equals(timeElement)) {
                    // Проверяем следующие элементы после элемента с временем
                    for (int j = i + 1; j < siblings.size() && j < i + 8; j++) {
                        Element sibling = siblings.get(j);
                        if (!sibling.text().trim().isEmpty()) {
                            Lesson lesson = parseLesson(sibling, time);
                            if (lesson != null) {
                                schedule.addLesson(j - i - 1, lesson);
                            }
                        }
                    }
                    break;
                }
            }
        }
    }
    private void analyzeDocumentStructure(Document doc) {
        Log.d(TAG, "Analyzing document structure");

        // Анализируем все элементы документа
        Elements allElements = doc.getAllElements();
        Map<String, Integer> tagCount = new HashMap<>();
        Map<String, Integer> classCount = new HashMap<>();

        for (Element element : allElements) {
            // Считаем теги
            String tagName = element.tagName();
            tagCount.put(tagName, tagCount.getOrDefault(tagName, 0) + 1);

            // Считаем классы
            String className = element.className();
            if (!className.isEmpty()) {
                classCount.put(className, classCount.getOrDefault(className, 0) + 1);
            }

            // Логируем элементы, которые могут быть связаны с расписанием
            if (element.text().matches(".*\\d{1,2}:\\d{2}.*") ||
                    element.className().contains("time") ||
                    element.attr("id").contains("rasp")) {
                Log.d(TAG, "Potential schedule element: " + element.tagName() +
                        " class: " + element.className() +
                        " text: " + element.text().substring(0, Math.min(50, element.text().length())));
            }
        }

        // Логируем статистику
        Log.d(TAG, "Tag statistics: " + tagCount);
        Log.d(TAG, "Class statistics: " + classCount);
    }
    private void analyzeParsingFailure(Document doc, String groupId, int year, int weekNumber) {
        Log.e(TAG, "Complete parsing failure analysis");

        // Сохраняем полный HTML для анализа
        saveHtmlToFile(doc.html(), "failure_analysis_" + groupId + "_" + year + "_" + weekNumber + ".html");

        // Ищем любые признаки времени или расписания
        Elements timeElements = doc.select("*:matches(\\d{1,2}:\\d{2})");
        Log.d(TAG, "Time elements found: " + timeElements.size());

        for (Element element : timeElements) {
            Log.d(TAG, "Time element: " + element.tagName() +
                    " class: " + element.className() +
                    " text: " + element.text());
        }

        // Ищем элементы с зашифрованными данными
        Elements encryptElements = doc.select("[data-encrypt]");
        Log.d(TAG, "Encrypted elements: " + encryptElements.size());

        for (Element element : encryptElements) {
            Log.d(TAG, "Encrypted element: " + element.attr("data-encrypt") +
                    " text: " + element.text());
        }
    }
    // Добавляем метод для очистки текста
    private String cleanText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        return text.replaceAll("[\\\\n\\\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // Обновляем метод parseLesson
    private Lesson parseLesson(Element cell, String time) {
        Lesson lesson = new Lesson();
        lesson.time = cleanText(time);

        // Извлекаем данные из структуры ячейки
        Elements divs = cell.select("div");
        if (divs.size() >= 3) {
            // Первый div: предмет и тип
            String firstDivText = divs.get(0).text();
            lesson.subject = cleanText(firstDivText.replaceAll("\\(.*\\)", ""));

            // Второй div: преподаватель
            lesson.teacher = cleanText(divs.get(1).text());

            // Третий div: аудитория
            lesson.location = cleanText(divs.get(2).text());
        } else {
            // Альтернативный парсинг для других форматов
            lesson.subject = cleanText(cell.select(".subject").text());
            lesson.teacher = cleanText(cell.select(".teacher").text());
            lesson.location = cleanText(cell.select(".location").text());
        }

        return lesson;
    }

    private int calculateDynamicWaitTime(int contentLength) {
        int baseTime = 10;
        int additionalTime = contentLength / 1000;
        return Math.min(baseTime + additionalTime, 5000);
    }
    public List<Schedule> getFullYearSchedule(String groupId, int year) throws IOException {
        List<Schedule> yearSchedule = new ArrayList<>();
        for (int week = 1; week <= 26; week++) {
            try {
                Log.d(TAG, "Loading week " + week);
                Schedule weekSchedule = getSchedule(groupId, year, week);
                if (weekSchedule != null) {
                    yearSchedule.add(weekSchedule);
                    Log.d(TAG, "Loaded schedule for week " + week + ", lessons: " +
                            weekSchedule.days.stream().mapToInt(List::size).sum());
                }
            } catch (IOException e) {
                if (e.getMessage().contains("не изменилось") || e.getMessage().contains("Нет содержимого")) {
                    Log.d(TAG, "Skipping week " + week + ": " + e.getMessage());
                } else if (e.getMessage().contains("капч") || e.getMessage().contains("авторизации")) {
                    // Пробрасываем исключение дальше для обработки в активности
                    throw e;
                } else {
                    Log.e(TAG, "Failed to load schedule for week " + week + ": " + e.getMessage());
                }
            }
        }

        if (yearSchedule.isEmpty()) {
            throw new IOException("Не удалось загрузить расписание ни для одной недели");
        }

        return yearSchedule;
    }
    public void loadCookies() {
        try {
            String cookies = cookiePrefs.getString("cookies", "");
            if (!cookies.isEmpty()) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setCookie(BASE_URL, cookies);
                Log.d(TAG, "Cookies loaded: " + cookies);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cookies: " + e.getMessage());
        }
    }
    public static class School {
        public String id;
        public String name;
        public List<Group> groups;
    }
    public static class Group {
        public String id;
        public String name;
    }
    public static class Schedule {
        public String groupId;
        public int year;
        public int weekNumber;
        public String weekType;
        public String datesRange;
        public String[] dates = new String[7]; // Даты для каждого дня недели
        public List<List<Lesson>> days = new ArrayList<>();

        public Schedule() {
            for (int i = 0; i < 7; i++) {
                days.add(new ArrayList<Lesson>());
            }
        }

        public void addLesson(int dayIndex, Lesson lesson) {
            if (dayIndex >= 0 && dayIndex < days.size()) {
                days.get(dayIndex).add(lesson);
            }
        }
    }
    public static class Lesson {
        public String subject;
        public String type;
        public String teacher;
        public String location;
        public String time;
        public String date;
    }
}