package com.example.mytpu.mailTPU;

import static com.example.mytpu.mailTPU.MailActivity.getCsrfToken;
import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;
import android.webkit.MimeTypeMap;
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.FileUtils;
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.IOUtils;
import org.chromium.base.ContentUriUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.stream.Collectors;
import okhttp3.Cookie;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.HttpUrl;
import okio.Buffer;

public class RoundcubeAPI {
    private static final String TAG = "RoundcubeAPI";
    private static String emailId;
    public static Request request;
    public static String BASE_URL = "https://letter.tpu.ru/mail/";
    private static String fetchPreviewContent(OkHttpClient client, String csrfToken, String emailId, boolean allowRemote) throws IOException {

        HttpUrl.Builder url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder();

        if(allowRemote) {
            url.addQueryParameter("_task", "mail")
                    .addQueryParameter("_action", "show")
                    .addQueryParameter("_uid", emailId)
                    .addQueryParameter("_mbox", "INBOX")
                    .addQueryParameter("_safe", "1")
                    .addQueryParameter("_token", csrfToken);

        } else {
            url.addQueryParameter("_task", "mail")
                    .addQueryParameter("_action", "show")
                    .addQueryParameter("_uid", emailId)
                    .addQueryParameter("_mbox", "INBOX")
                    .addQueryParameter("_framed", "1")
                    .addQueryParameter("_token", csrfToken);

        }



        request = new Request.Builder()
                .url(url.build())
                .addHeader("accept", "text/html")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Preview error: " + response.code());
            String responseBody = response.body().string();

            // Проверка на ошибку сессии
            if (responseBody.contains("session_error") ||
                    responseBody.contains("Your session is invalid")) {
                try {
                    throw new MailActivity.SessionExpiredException("Session expired");
                } catch (MailActivity.SessionExpiredException e) {
                    throw new RuntimeException(e);
                }
            }
            return responseBody;
        }
    }

    private static String fetchWithRetry(OkHttpClient client, Request request) throws IOException {
        int attempts = 0;
        while (attempts < 3) {
            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) return response.body().string();
            } catch (IOException e) {
                if (e.getMessage().contains("Canceled") && attempts < 2) {
                    attempts++;
                    continue;
                }
                throw e;
            }
        }
        throw new IOException("Failed after 3 attempts");
    }

    // RoundcubeAPI.java

    public static int getFolderMessageCount(JSONArray messages) {
        try {
            // Пытаемся получить общее количество из первого сообщения
            if (messages.length() > 0) {
                JSONObject first = messages.getJSONObject(0);
                if (first.has("_messagecount")) {
                    return first.getInt("_messagecount");
                }
            }

            // Fallback: возвращаем количество на текущей странице
            return messages.length();
        } catch (JSONException e) {
            return messages.length();
        }
    }

    public static JSONArray fetchMessages(Context context, int currentPage, String folder)
            throws IOException, JSONException, MailActivity.SessionExpiredException, InterruptedException {

        synchronized (SessionLock.LOCK) {
        String newToken = MailActivity.refreshCsrfToken(context);
        if (newToken.isEmpty()) {
            throw new MailActivity.SessionExpiredException("Failed to refresh CSRF token");
        }


        Log.i(TAG, "Fetching messages list for page: " + currentPage);
        // Во всех компонентах используйте:
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        if (Thread.interrupted()) {
            throw new InterruptedException("Task canceled");
        }
            HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                    .addQueryParameter("_task", "mail")
                    .addQueryParameter("_action", "list")
                    .addQueryParameter("_layout", "widescreen")
                    .addQueryParameter("_mbox", folder)
                    .addQueryParameter("_page", String.valueOf(currentPage))
                    .addQueryParameter("_remote", "1")
                    .addQueryParameter("_unlock", "loading" + System.currentTimeMillis())
                    .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                    .addQueryParameter("_token", MailActivity.getCsrfToken(context))
                    .build();
        Log.d("API", "Fetching messages from: " + url);
        Request request = new Request.Builder()
                .url(url)
                .build();


        try (Response response = client.newCall(request).execute()) {
            String responseBody = fetchWithRetry(client, request);
            Log.d(TAG, "Server response:"
                    + "\nCode: " + response.code()
                    + "\nBody length: " + responseBody.length() + " chars");
            Log.d("API", "Response code: " + response.code());
            //Log.d("API", "Response headers: " + response.headers());
            Log.d("API", "Full response: " + responseBody);
            handleResponseErrors(response, responseBody);
            JSONObject jsonResponse = new JSONObject(responseBody);

            if (jsonResponse.has("exec")) {
                String exec = jsonResponse.getString("exec");
                if (exec.contains("session_error")) {
                    throw new MailActivity.SessionExpiredException("Session expired (server response)");
                }
            }
            if (response.code() == 401) {
                throw new MailActivity.SessionExpiredException("Unauthorized (401)");
            }
            return parseJsResponse(responseBody);
        } catch (TooManyAttemptsException e) {
            throw new RuntimeException(e);
        }
    }
    }









    private static JSONArray parseJsResponse(String jsCode) throws JSONException {
        Log.d(TAG, "Parsing JavaScript response: " + jsCode);
        JSONArray result = new JSONArray();
        Pattern pattern = Pattern.compile(
                "this\\.add_message_row\\((\\d+),\\s*(\\{.*?\\})\\s*,\\s*\\{.*?\\}\\s*,\\s*(true|false)\\);",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(jsCode);
        while (matcher.find()) {
            String uid = matcher.group(1);
            String rawJson = matcher.group(2);

            try {
                // Упрощенная подготовка JSON-строки
                String fixedJson = rawJson
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\'", "'");

                // Используем JsonReader в lenient-режиме
                JsonReader reader = new JsonReader(new StringReader(fixedJson));
                reader.setLenient(true);

                // Ручной парсинг JSON
                JSONObject emailJson = parseJsonObject(reader);
                emailJson.put("uid", uid);
                result.put(emailJson);
            } catch (Exception e) {
                Log.e("RoundcubeAPI", "JSON parse error: " + e.getMessage() + "\nJSON: " + rawJson);
            }
        }

        Log.i(TAG, "Total messages parsed: " + result.length());
        return result;
    }

    // Ручной парсинг JSON-объекта с использованием JsonReader
    private static JSONObject parseJsonObject(JsonReader reader) throws IOException, JSONException {
        JSONObject result = new JSONObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            switch (reader.peek()) {
                case STRING:
                    result.put(key, reader.nextString());
                    break;
                case NUMBER:
                    try {
                        result.put(key, reader.nextLong());
                    } catch (NumberFormatException e) {
                        result.put(key, reader.nextDouble());
                    }
                    break;
                case BOOLEAN:
                    result.put(key, reader.nextBoolean());
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();
        return result;
    }








    public static class TooManyAttemptsException extends Exception {
        public TooManyAttemptsException(String message) {
            super(message);
        }
    }

    private static void handleResponseErrors(Response response, String responseBody)
            throws IOException, MailActivity.SessionExpiredException, TooManyAttemptsException {
        if (responseBody.contains("session_error")
                || responseBody.contains("Your session is invalid")) {
            throw new MailActivity.SessionExpiredException("Session expired (server response)");
        }
        if (responseBody.contains("Too many login attempts")) {
            throw new TooManyAttemptsException("Слишком много попыток. Попробуйте позже");
        }
        if (responseBody.contains("id=\"rcmloginform\"")) {
            throw new MailActivity.SessionExpiredException("Требуется повторная авторизация");
        }
        // Проверка кодов состояния
        if (response.code() == 401 && responseBody.contains("Too many attempts")) {
            throw new TooManyAttemptsException("Too many login attempts");
        }


        if (!response.isSuccessful()) {
            throw new IOException("HTTP error: " + response.code() + " - " + responseBody);
        }
    }


    public static HttpUrl buildUrl(String baseUrl, String task, String action, String uid,
                                   String mbox, boolean framed, String token) {
        HttpUrl parsedUrl = HttpUrl.parse(baseUrl);
        if(parsedUrl == null) throw new IllegalArgumentException("Invalid base URL");

        HttpUrl.Builder builder = parsedUrl.newBuilder()
                .addQueryParameter("_task", task)
                .addQueryParameter("_action", action)
                .addQueryParameter("_uid", uid)
                .addQueryParameter("_mbox", mbox);

        if(framed) builder.addQueryParameter("_framed", "1");
        if(token != null) builder.addQueryParameter("_token", token);

        return builder.build();
    }

    public static String fetchEmailContent(Context context, String emailId, boolean allowRemote,
                                           AttachmentsCallback callback) throws IOException {
        // Во всех компонентах используйте:
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        String csrfToken = MailActivity.refreshCsrfToken(context);
        String mainContent = fetchPreviewContent(client, csrfToken, emailId, allowRemote);
        return processHtmlContent(context, mainContent, emailId, csrfToken, callback);
    }


    private static String processHtmlContent(Context context, String html, String emailId, String token,
                                             AttachmentsCallback callback) {
        try {
            Document doc = Jsoup.parse(html);
            fixAllResourceUrls(doc, emailId, token);
            doc.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
            doc.outputSettings().charset("UTF-8");

            // Дополнительные исправления HTML
            doc.select("meta[http-equiv=Content-Security-Policy]").remove();

            // Извлекаем основной контейнер с телом письма
            Element messageBody = doc.selectFirst("#messagebody");
            // Во всех компонентах используйте:
            OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
            // RoundcubeAPI.java
            List<Attachment> attachments = parseAttachments(doc, emailId, token, client, callback, context);
            boolean hasBlocked = html.contains("blocked.gif");
            Log.d(TAG, "hasBlocked: "+hasBlocked);
            if (callback != null) {
                ((EmailDetailActivity)context).setHasBlockedResources(hasBlocked);
            }
            // RoundcubeAPI.java
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (context instanceof EmailDetailActivity) {
                        ((EmailDetailActivity) context).setupAttachmentsRecycler(attachments);
                    } else {
                        Log.e(TAG, "Invalid context type: " + context.getClass().getSimpleName());
                    }
                });
            }


            if (messageBody != null) {
                // Удаляем ВСЕ скрипты, стили и ненужные элементы
                messageBody.select("script, style, .image-attachment, .attachmentslist, .popupmenu, .header, .header-links").remove();

                // Очищаем HTML с сохранением базовой структуры
                String cleanContent = messageBody.html();

                // Восстанавливаем важные inline-стили для изображений
                Document cleanDoc = Jsoup.parseBodyFragment(cleanContent);
                cleanDoc.select("img").forEach(img -> {
                    img.attr("style", "max-width: 100%; height: auto;"); // Фиксируем стиль изображений
                    img.removeAttr("onclick"); // Удаляем обработчики событий
                });

                return cleanDoc.body().html();
            } else {
                Log.w(TAG, "Message body not found, using fallback");
                return html;
            }
        } catch (Exception e) {
            Log.e(TAG, "HTML processing error", e);
            return html;
        }
    }

    private static void fixAllResourceUrls(Document doc, String emailId, String token) {
        Element messageBody = doc.selectFirst(".message-part, #messagebody");
        if (messageBody == null) return;

        // Обработка CID изображений
        messageBody.select("img[src^=cid]").forEach(img -> {
            String cid = img.attr("src").replace("cid:", "");
            String url = buildUrl(
                    "https://letter.tpu.ru/mail/",
                    "mail",
                    "get",
                    emailId,
                    "INBOX",
                    true,
                    token
            ).newBuilder()
                    .addQueryParameter("_part", cid)
                    .addQueryParameter("_embed", "1")
                    .build()
                    .toString();
            img.attr("src", url);
            Log.d(TAG, "Replaced CID image: " + url);
        });
        messageBody.select("*[style*='background-image']").forEach(el -> {
            String style = el.attr("style");
            Matcher m = Pattern.compile("url\\((.*?)\\)").matcher(style);
            if (m.find()) {
                String url = m.group(1).replaceAll("['\"]", "");
                String fixedUrl = fixAttachmentUrl(url, emailId, token);
                el.attr("style", style.replace(url, fixedUrl));
            }
        });
        // Обработка других изображений
        messageBody.select("img[src]").forEach(img -> {
            String src = img.attr("src");
            if (!src.startsWith("http")) {
                String fixed = fixAttachmentUrl(src, emailId, token);
                img.attr("src", fixed);
                Log.d(TAG, "Fixed image URL: " + fixed);
            }
        });
    }

    public interface AttachmentsCallback {
        void onAttachmentsLoaded(List<Attachment> attachments);
    }

    public static List<Attachment> parseAttachments(Document doc, String emailId, String token,
                                                    OkHttpClient client, AttachmentsCallback callback, Context context) {
        List<Attachment> attachments = new ArrayList<>();
        Set<String> processedUrls = new HashSet<>();

        // Основные вложения из списка
        Elements mainAttachments = doc.select("ul.attachmentslist > li");
        // Изображения в теле письма
        Elements imageAttachments = doc.select("p.image-attachment");

        Log.d(TAG, "🔍 Found " + (mainAttachments.size() + imageAttachments.size()) + " attachment containers");

        // Обработка основных вложений
        for (Element container : mainAttachments) {
            try {
                Element link = container.selectFirst("a.filename");
                if (link == null) continue;

                String href = link.attr("href");
                String fileName = container.selectFirst("span.attachment-name").text();
                if (processedUrls.contains(fileName)) continue;
                processedUrls.add(fileName);
                String fileSize = container.selectFirst("span.attachment-size").text();

                // Проверяем расширение файла + класс "image"
                boolean isImage = container.classNames().contains("image")
                        || isImageFile(fileName); // Новая проверка!

                processAttachment(href, fileName, fileSize, isImage, emailId, token, client, attachments, processedUrls, context);
            } catch (Exception e) {
                Log.e(TAG, "💥 Error processing main attachment: " + e.getMessage());
            }
        }

        // Обработка изображений в теле письма
        for (Element container : imageAttachments) {
            try {
                Element link = container.selectFirst("a.download");
                if (link == null) continue;

                String href = link.attr("href");
                String fileName = container.selectFirst("span.image-filename").text();
                if (processedUrls.contains(fileName)) continue;
                processedUrls.add(fileName);
                String fileSize = container.selectFirst("span.image-filesize").text();

                // Всегда проверяем расширение для безопасности
                boolean isImage = isImageFile(fileName);

                processAttachment(href, fileName, fileSize, isImage, emailId, token, client, attachments, processedUrls, context);
            } catch (Exception e) {
                Log.e(TAG, "💥 Error processing image attachment: " + e.getMessage());
            }
        }

        return attachments;
    }

    private static boolean isImageFile(String fileName) {
        String[] imageExtensions = {"jpg", "jpeg", "png", "gif", "bmp", "webp"};
        String ext = getFileExtension(fileName).toLowerCase();
        return Arrays.asList(imageExtensions).contains(ext);
    }

    private static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot == -1) ? "" : fileName.substring(lastDot + 1).replaceAll("[^a-zA-Z0-9]", "");
    }

    private static void processAttachment(String href, String fileName, String fileSize, boolean isImage,
                                          String emailId, String token, OkHttpClient client,
                                          List<Attachment> attachments, Set<String> processedUrls, Context context) {
        try {
            String fixedUrl = fixAttachmentUrl(href, emailId, token);

            if (processedUrls.contains(fixedUrl)) return;
            processedUrls.add(fixedUrl);
            File tempFile = downloadAttachment(client, fixedUrl, fileName, context);

            if (tempFile == null || tempFile.length() == 0) {
                Log.e(TAG, "❌ Skipping invalid attachment: " + fileName);
                if (tempFile != null) tempFile.delete();
                return;
            }

            attachments.add(new Attachment(
                    fileName,
                    tempFile,
                    getFileType(fileName),
                    fileSize,
                    isImage
            ));
            // В методе processAttachment
            Log.d(TAG, "Processing: " + fileName
                    + " | Type: " + getFileType(fileName)
                    + " | isImage: " + isImage);
        } catch (Exception e) {
            Log.e(TAG, "💥 Error processing attachment: " + e.getMessage());
        }
    }

    private static String fixAttachmentUrl(String url, String emailId, String token) {
        try {
            HttpUrl parsedUrl = HttpUrl.parse(url);
            if (parsedUrl == null) {
                parsedUrl = HttpUrl.parse("https://letter.tpu.ru/mail/").resolve(url);
            }

            HttpUrl.Builder builder = parsedUrl.newBuilder()
                    .addQueryParameter("_token", token)
                    .addQueryParameter("_uid", emailId);

            // Добавляем параметр для скачивания
            if (!parsedUrl.queryParameterNames().contains("_download")) {
                builder.addQueryParameter("_download", "1");
            }

            return builder.build().toString();
        } catch (Exception e) {
            Log.e(TAG, "URL fix error: " + e.getMessage());
            return url;
        }
    }

    public static String getFileType(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) return "file";

        String extension = fileName.substring(lastDotIndex + 1)
                .replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase();
        return extension.isEmpty() ? "file" : extension;
    }

    private static File downloadAttachment(OkHttpClient client, String url, String fileName, Context context) {
        try {
            Log.d(TAG, "⏬ Starting download: " + fileName + " from: " + url);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Cookie", getCookiesString(client))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "🚫 Download failed. Code: " + response.code());
                    return null;
                }

                File tempFile = File.createTempFile(
                        "att_" + System.currentTimeMillis(),
                        "." + getFileExtension(fileName),
                        context.getCacheDir()
                );
                tempFile.deleteOnExit();
                Log.d(TAG, "📁 Temp file created: " + tempFile.getAbsolutePath());

                try (InputStream input = response.body().byteStream();
                     FileOutputStream output = new FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;

                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }

                    Log.d(TAG, "✅ Download complete. Size: " + totalRead + " bytes");

                    if (totalRead == 0) {
                        Log.e(TAG, "⚠️ Empty file received");
                        tempFile.delete();
                        return null;
                    }
                }
                catch (IOException e) {
                    tempFile.delete();
                }
                return tempFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "🔥 Download error: " + e.getMessage());
            return null;
        }
    }

    // В RoundcubeAPI.java
    private static String getCookiesString(OkHttpClient client) {
        List<Cookie> cookies = client.cookieJar()
                .loadForRequest(HttpUrl.parse("https://letter.tpu.ru/"));
        // Фильтрация актуальных кук
        return cookies.stream()
                .map(c -> c.name() + "=" + c.value())
                .collect(Collectors.joining("; "));
    }

    public static void cleanupOldFiles(Context context) {
        File cacheDir = context.getCacheDir();
        long now = System.currentTimeMillis();
        long TTL = 24 * 60 * 60 * 1000; // 24 часа

        for (File file : cacheDir.listFiles()) {
            if (file.getName().startsWith("att_")
                    && (now - file.lastModified()) > TTL) {
                file.delete();
            }
        }
    }

    public static class Attachment {
        private final String fileName;
        private final File tempFile;
        private final String fileType;
        private final String fileSize;
        private final boolean isImage;

        public Attachment(String fileName, File tempFile, String fileType,
                          String fileSize, boolean isImage) {
            this.fileName = fileName;
            this.tempFile = tempFile;
            this.fileType = fileType;
            this.fileSize = fileSize;
            this.isImage = isImage;
        }

        // Добавляем геттер для типа
        public boolean isImage() {
            return isImage || RoundcubeAPI.isImageFile(fileName);
        }
        public String getFileName() { return fileName; }
        public File getTempFile() { return tempFile; }
        public String getFileType() { return fileType; }
        public String getFileSize() { return fileSize; }
    }

    public static void clearCache(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.isDirectory()) {
                for (File file : cacheDir.listFiles()) {
                    if (file.getName().startsWith("att_")) {
                        boolean deleted = file.delete();
                        Log.d(TAG, "Deleted temp file: " + file.getName()
                                + " | " + (deleted ? "Success" : "Failed"));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Cache cleanup error: " + e.getMessage());
        }
    }

    public static boolean sendComposedEmail(Context context, String to, String subject, String body,
                                            List<Uri> attachments, String csrfToken) throws IOException {
        try {
            // Во всех компонентах используйте:
            OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
            JSONObject composeData = createDraft(context, csrfToken);
            String composeId = composeData.getString("id");
            String from = composeData.getString("from");

            // 2. Создаем multipart тело запроса с правильными параметрами
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("_task", "mail")
                    .addFormDataPart("_action", "send")
                    .addFormDataPart("_token", csrfToken)
                    .addFormDataPart("_id", composeId)
                    .addFormDataPart("_from", from)
                    .addFormDataPart("_to", to)
                    .addFormDataPart("_subject", subject)
                    .addFormDataPart("_message", body)
                    .addFormDataPart("_is_html", "1")
                    .addFormDataPart("_priority", "0")
                    .addFormDataPart("_store_target", "sent")
                    .addFormDataPart("_sigid", "") // Добавляем обязательные пустые параметры
                    .addFormDataPart("_draftid", "")
                    .addFormDataPart("_cursor", "")
                    .addFormDataPart("_spellang", "ru")
                    .addFormDataPart("_request", "message-form");

            // 3. Загружаем и добавляем вложения с правильным Content-Type
            for (Uri uri : attachments) {
                String fileName = getFileNameFromUri(context, uri);
                String mimeType = getMimeType(context, uri);

                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    byte[] fileData = IOUtils.toByteArray(is);

                    bodyBuilder.addFormDataPart(
                            "_attachments[]", // Roundcube требует именно такое имя параметра
                            fileName,
                            RequestBody.create(
                                    fileData,
                                    MediaType.parse(mimeType + "; charset=binary") // Добавляем charset
                            )
                    );
                }
            }

            // 4. Формируем правильный Referer
            HttpUrl composeUrl = HttpUrl.parse(BASE_URL).newBuilder()
                    .addQueryParameter("_task", "mail")
                    .addQueryParameter("_action", "compose")
                    .addQueryParameter("_id", composeId)
                    .build();

            // 5. Отправляем запрос
            Request request = new Request.Builder()
                    .url(BASE_URL)
                    .header("Referer", composeUrl.toString()) // Важно для Roundcube
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(bodyBuilder.build())
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                Log.d(TAG, "Full server response: " + responseBody);

                // Проверяем наличие ID сообщения в ответе
                return responseBody.contains("\"message\":\"Сообщение отправлено\"")
                        && responseBody.contains("\"sent\":true");
            }
        } catch (JSONException e) {
            throw new IOException("Ошибка разбора данных", e);
        }
    }


    private static JSONObject createDraft(Context context, String csrfToken) throws IOException, JSONException {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "compose")
                .addQueryParameter("_token", csrfToken)
                .addQueryParameter("_framed", "1")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .build();
        // Во всех компонентах используйте:
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        try (Response response = client.newCall(request).execute()) {
            String html = response.body().string();
            Document doc = Jsoup.parse(html);

            JSONObject result = new JSONObject();
            result.put("id", doc.selectFirst("input[name='_id']").attr("value"));
            result.put("from", doc.selectFirst("select[name='_from'] option[selected]").attr("value"));

            return result;
        }
    }
    public static String getMimeType(Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        if (mimeType == null) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    getFileExtension(getFileNameFromUri(context, uri))
            );
        }
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    @SuppressLint("Range")
    private static String getFileNameFromUri(Context context, Uri uri) {
        String fileName = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting filename from ContentResolver", e);
            }
        }

        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }

        // Удаляем временные метки из имени файла
        return fileName.replaceAll("%20", " ")
                .replaceAll("[?].*", "");
    }

    private static byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }


    public static boolean forwardEmail(Context context,String emailId,String to,String subject,String body,List<Uri> attachments, String csrfToken
    ) throws IOException {
        if (csrfToken == null || csrfToken.isEmpty()) {
            Log.e(TAG, "Invalid CSRF token for forwarding");
            return false;
        }

        // 1. Загружаем страницу композиции для пересылки
        HttpUrl composeUrl = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "compose")
                .addQueryParameter("_forward_uid", emailId)
                .addQueryParameter("_token", csrfToken)
                .build();
        // Во всех компонентах используйте:
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        String composeHtml;
        try (Response response = client.newCall(new Request.Builder().url(composeUrl).build()).execute()) {
            composeHtml = response.body().string();
            if (!response.isSuccessful()) {
                Log.e(TAG, "Ошибка загрузки страницы композиции: " + response.code());
                return false;
            }
        }

        // 2. Извлекаем параметры композиции
        String composeId = extractFromHTML(composeHtml, "input[name='_id']");
        String from = extractFromHTML(composeHtml, "select[name='_from'] option[selected]");

        if (TextUtils.isEmpty(composeId) || TextUtils.isEmpty(from)) {
            Log.e(TAG, "Не удалось извлечь параметры композиции");
            return false;
        }

        // 3. Строим multipart запрос
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("_task", "mail")
                .addFormDataPart("_action", "send")
                .addFormDataPart("_token", csrfToken)
                .addFormDataPart("_id", composeId)
                .addFormDataPart("_from", from)
                .addFormDataPart("_to", to)
                .addFormDataPart("_subject", subject)
                .addFormDataPart("_message", body)
                .addFormDataPart("_is_html", "1")
                .addFormDataPart("_mode", "forward")
                .addFormDataPart("_forward_uid", emailId)
                .addFormDataPart("_store_target", "sent");

        // 4. Добавляем вложения
        for (Uri uri : attachments) {
            String fileName = getFileNameFromUri(context, uri);
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                // Исправленный MIME-тип
                String mimeType = ContentUriUtils.getMimeType(fileName);
                if (TextUtils.isEmpty(mimeType)) {
                    mimeType = "application/octet-stream";
                }

                byte[] fileData = IOUtils.toByteArray(is);
                bodyBuilder.addFormDataPart(
                        "_attachments[]",
                        fileName,
                        RequestBody.create(fileData, MediaType.parse(mimeType))
                );
            } catch (Exception e) {
                Log.e(TAG, "Ошибка вложения: " + fileName, e);
            }
        }

        // 5. Отправляем запрос
        Request request = new Request.Builder()
                .url("https://letter.tpu.ru/mail/")
                .post(bodyBuilder.build())
                .addHeader("Referer", composeUrl.toString())
                .build();
        Log.d(TAG, "отправкa:\n" + bodyBuilder);

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            boolean success = response.isSuccessful() && responseBody.contains("Сообщение отправлено");

            if (!success) {
                Log.e(TAG, "Ошибка отправки. Ответ сервера:\n" + responseBody);
            }
            return success;
        }
    }

    public static boolean sendEmail(Context context,String emailId,String userMessage,String csrfToken,List<Uri> attachments) throws IOException {
        Log.d(TAG, "Starting sendEmail process");

        // 1. Загружаем форму ответа
        HttpUrl composeUrl = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "compose")
                .addQueryParameter("_reply_uid", emailId)
                .addQueryParameter("_mbox", "INBOX")
                .addQueryParameter("_token", csrfToken)
                .build();
        // Во всех компонентах используйте:
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        try (Response response = client.newCall(new Request.Builder().url(composeUrl).build()).execute()) {
            String html = response.body().string();
            Log.d(TAG, "Compose page HTML length: " + html.length());

            // 2. Сохраняем HTML для отладки
            saveDebugHtml(context, html);

            // 3. Извлекаем основные параметры
            String from = extractFromHTML(html, "select[name='_from'] option[selected]");
            String to = extractFromHTML(html, "textarea[name='_to']");
            String subject = extractFromHTML(html, "input[name='_subject']");
            String content = extractFromHTML(html, "textarea[name='_message']");
            String composeId = extractFromHTML(html, "input[name='_id']");

            // 4. Извлекаем дополнительные скрытые поля
            String sigId = extractFromHTML(html, "input[name='_sigid']");
            String spellLang = extractFromHTML(html, "input[name='_spellang']");
            String spellKey = extractFromHTML(html, "input[name='_spellkey']");
            String cursor = extractFromHTML(html, "input[name='_cursor']");
            String draftId = extractFromHTML(html, "input[name='_draftid']");
            String replyAll = extractFromHTML(html, "input[name='_replyall']");
            String priority = extractFromHTML(html, "input[name='_priority']");
            String isSent = extractFromHTML(html, "input[name='_is_sent']");

            // 6. Формируем multipart запрос
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("_task", "mail")
                    .addFormDataPart("_action", "send")
                    .addFormDataPart("_token", csrfToken)
                    .addFormDataPart("_id", composeId)
                    .addFormDataPart("_from", from)
                    .addFormDataPart("_to", to)
                    .addFormDataPart("_subject", formatSubject(subject))
                    .addFormDataPart("_message", content + "\n\n" + userMessage)
                    .addFormDataPart("_mode", "reply")
                    .addFormDataPart("_reply_uid", emailId)
                    .addFormDataPart("_sigid", sigId)
                    .addFormDataPart("_spellang", spellLang)
                    .addFormDataPart("_spellkey", spellKey)
                    .addFormDataPart("_cursor", cursor)
                    .addFormDataPart("_draftid", draftId)
                    .addFormDataPart("_replyall", replyAll)
                    .addFormDataPart("_priority", !priority.isEmpty() ? priority : "0")
                    .addFormDataPart("_is_sent", !isSent.isEmpty() ? isSent : "1")
                    .addFormDataPart("_store_target", "sent")
                    .addFormDataPart("_request", "message-form");

            // 7. Добавляем вложения
            for(Uri uri : attachments) {
                try (InputStream inputStream = context.getContentResolver().openInputStream(Uri.parse(String.valueOf(uri)))) {
                    String mimeType = context.getContentResolver().getType(Uri.parse(String.valueOf(uri)));
                    String fileName = getFileNameFromUri(context, Uri.parse(String.valueOf(uri)));

                    // Читаем содержимое файла
                    byte[] fileData = readBytes(inputStream);
                    if (fileData.length == 0) {
                        Log.e(TAG, "Empty file: " + fileName);
                        continue;
                    }

                    RequestBody fileBody = RequestBody.create(
                            fileData,
                            MediaType.parse(mimeType != null ? mimeType : "application/octet-stream")
                    );

                    bodyBuilder.addFormDataPart(
                            "_attachments[]",
                            fileName,
                            fileBody
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Error processing attachment: " + e.getMessage());
                }
            }

            Request request = new Request.Builder()
                    .url("https://letter.tpu.ru/mail/")
                    .post(bodyBuilder.build())
                    .addHeader("Referer", composeUrl.toString())
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build();

            // 8. Логируем тело запроса
            logRequestBody(bodyBuilder.build());

            // 9. Отправляем запрос
            try (Response sendResponse = client.newCall(request).execute()) {
                String responseBody = sendResponse.body().string();
                Log.d(TAG, "Server response code: " + sendResponse.code());
                Log.d(TAG, "Response body snippet: " + responseBody.substring(0, Math.min(200, responseBody.length())));

                boolean success = responseBody.contains("Сообщение отправлено");

                if (!success) {
                    Log.e(TAG, "Error details:\n" + responseBody);
                    if (responseBody.contains("invalid token")) {
                        MailActivity.handleCsrfError(context);
                    }
                }

                return success;
            }
        }
    }

    private static String formatSubject(String originalSubject) {
        if (originalSubject.startsWith("Re: ")) {
            return originalSubject; // Не добавляем повторно
        }
        return "Re: " + originalSubject;
    }
    // Вспомогательные методы
    private static void saveDebugHtml(Context context, String html) {
        try {
            File file = new File(context.getExternalFilesDir(null), "last_compose.html");
            FileUtils.writeStringToFile(file, html, StandardCharsets.UTF_8);
            Log.d(TAG, "Full HTML saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving HTML", e);
        }
    }

    // Замените блок логирования тела запроса:
    private static void logRequestBody(RequestBody body) {
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            Log.d(TAG, "Request body:\n" + buffer.readUtf8());
        } catch (IOException e) {
            Log.e(TAG, "Error logging request body", e);
        }
    }

    private static String extractFromHTML(String html, String selector) {
        try {
            Document doc = Jsoup.parse(html);
            Element element = doc.selectFirst(selector);

            if (element != null) {
                String value = element.hasAttr("value")
                        ? element.attr("value")
                        : element.text().trim();
                Log.d(TAG, "Extracted value for " + selector + ": " + value);
                return value;
            }

            // Добавляем поиск по ID если не найден по имени
            if (selector.equals("input[name='_id']")) {
                element = doc.selectFirst("input#_id");
                if (element != null) {
                    return element.attr("value");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "HTML parsing error: " + e.getMessage());
        }
        Log.w(TAG, "Element not found with selector: " + selector);
        return "";
    }
}