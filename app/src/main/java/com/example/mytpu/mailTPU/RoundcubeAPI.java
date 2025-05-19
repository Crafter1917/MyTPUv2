package com.example.mytpu.mailTPU;

import static com.example.mytpu.mailTPU.MailActivity.client;
import static com.example.mytpu.mailTPU.MailActivity.getCsrfToken;

import static java.nio.file.Files.createTempFile;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;

import java.io.IOException;
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
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.HttpUrl;

public class RoundcubeAPI {
    private static final String TAG = "RoundcubeAPI";
    private static final Safelist HTML_WHITELIST = Safelist.relaxed()
            .addTags("a", "img", "span", "br")
            .addAttributes("a", "href", "class", "download")
            .addAttributes("img", "src", "alt", "style", "title")
            .addAttributes("span", "class")
            .preserveRelativeLinks(true);
    private static String emailId;
    public static Request request;

    private static String fetchPreviewContent(OkHttpClient client, String csrfToken, String emailId) throws IOException {
        HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "show")
                .addQueryParameter("_uid", emailId)
                .addQueryParameter("_mbox", "INBOX")
                .addQueryParameter("_framed", "1")
                .addQueryParameter("_token", csrfToken)
                .build();

        request = new Request.Builder()
                .url(url)
                .addHeader("accept", "text/html")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Preview error: " + response.code());
            return response.body().string();
        }
    }


    public static JSONArray fetchMessages(Context context, int currentPage)
            throws IOException, JSONException, MailActivity.SessionExpiredException {
        Log.i(TAG, "Fetching messages list for page: " + currentPage);
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        // Измените метод fetchMessages:
        HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "list")
                .addQueryParameter("_layout", "widescreen") // Добавлено
                .addQueryParameter("_mbox", "INBOX")
                .addQueryParameter("_page", String.valueOf(currentPage))
                .addQueryParameter("_remote", "1")
                .addQueryParameter("_unlock", "loading" + System.currentTimeMillis()) // Добавлено
                .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                .addQueryParameter("_token", getCsrfToken(context))
                .build();

        Log.d("API", "Fetching messages from: " + url);
        request = new Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json") // Важно для формата ответа
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            Log.d(TAG, "Server response:"
                    + "\nCode: " + response.code()
                    + "\nBody length: " + responseBody.length() + " chars");
            Log.d("API", "Response code: " + response.code());
            Log.d("API", "Response headers: " + response.headers());
            Log.d("API", "Full response: " + responseBody);
            handleResponseErrors(response, responseBody);

            return parseJsResponse(responseBody);

        } catch (TooManyAttemptsException e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray parseJsResponse(String jsCode) throws JSONException {
        Log.d(TAG, "Parsing JavaScript response");
        JSONArray result = new JSONArray();
        // Updated regex to accurately capture the email object between { and }, followed by , {
        Pattern pattern = Pattern.compile(
                "this\\.add_message_row\\((\\d+),\\s*(\\{.*?\\})\\s*,\\s*\\{",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(jsCode);
        while (matcher.find()) {
            try {
                String uid = matcher.group(1);
                String rawJson = matcher.group(2)
                        .replaceAll("(?<!\\\\)'", "\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/")
                        .replace("\"", "'");

                // Sanitize the JSON by escaping quotes within string values
                StringBuilder sanitizedJson = new StringBuilder();
                Pattern valuePattern = Pattern.compile(":\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
                Matcher valueMatcher = valuePattern.matcher(rawJson);
                while (valueMatcher.find()) {
                    String valueContent = valueMatcher.group(1);
                    // Escape all unescaped quotes within the string value
                    String escapedContent = valueContent.replace("\"", "\\\"");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        valueMatcher.appendReplacement(sanitizedJson, ":\"" + escapedContent + "\"");
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    valueMatcher.appendTail(sanitizedJson);
                }
                String processedJson = sanitizedJson.toString();

                // Decode HTML entities and remove any remaining HTML tags
                String decodedJson = org.jsoup.parser.Parser.unescapeEntities(processedJson, true);
                decodedJson = decodedJson.replaceAll("<[^>]+>", "");

                JSONObject emailJson = new JSONObject(decodedJson);
                emailJson.put("uid", uid);
                Log.d("RoundcubeAPI", "Parsed email: " + uid);
                result.put(emailJson);
            } catch (JSONException e) {
                Log.e("RoundcubeAPI", "JSON parse error: " + e.getMessage());
            }
        }
        Log.i(TAG, "Total messages parsed: " + result.length());
        return result;
    }
    public static class TooManyAttemptsException extends Exception {
        public TooManyAttemptsException(String message) {
            super(message);
        }
    }
    private static void handleResponseErrors(Response response, String responseBody)
            throws IOException, MailActivity.SessionExpiredException, TooManyAttemptsException {

        // Проверка по содержимому ответа
        if (responseBody.contains("session_error")
                || responseBody.contains("Your session is invalid")) {
            throw new MailActivity.SessionExpiredException("Session expired (server response)");
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

    public static String fetchEmailContent(Context context, String emailId, AttachmentsCallback callback)
            throws IOException {
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(context).getClient();
        String csrfToken = MailActivity.refreshCsrfToken(context);
        String mainContent = fetchPreviewContent(client, csrfToken, emailId);
        return processHtmlContent(context, mainContent, emailId, csrfToken, callback); // Добавляем context
    }


    private static String processHtmlContent(Context context, String html, String emailId, String token,
                                             AttachmentsCallback callback) {
        try {
            Document doc = Jsoup.parse(html);
            fixAllResourceUrls(doc, emailId, token);
            doc.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
            doc.outputSettings().charset("UTF-8");
            // Извлекаем основной контейнер с телом письма
            Element messageBody = doc.selectFirst("#messagebody");
            OkHttpClient client = MailActivity.MyMailSingleton.getInstance(MailActivity.context).getClient();

            // RoundcubeAPI.java
            List<Attachment> attachments = parseAttachments(doc, emailId, token, client, callback);

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
                String cleanContent = Jsoup.clean(messageBody.html(), HTML_WHITELIST);

                // Восстанавливаем важные inline-стили для изображений
                Document cleanDoc = Jsoup.parseBodyFragment(cleanContent);
                cleanDoc.select("img").forEach(img -> {
                    img.attr("style", "max-width: 100%; height: auto;"); // Фиксируем стиль изображений
                    img.removeAttr("onclick"); // Удаляем обработчики событий
                });

                return cleanDoc.body().html();
            } else {
                Log.w(TAG, "Message body not found, using fallback");
                return Jsoup.clean(html, HTML_WHITELIST);
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
                                                    OkHttpClient client, AttachmentsCallback callback) {
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

                processAttachment(href, fileName, fileSize, isImage, emailId, token, client, attachments, processedUrls);
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

                processAttachment(href, fileName, fileSize, isImage, emailId, token, client, attachments, processedUrls);
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
        return (lastDot == -1) ? "" : fileName.substring(lastDot + 1);
    }

    private static void processAttachment(String href, String fileName, String fileSize, boolean isImage,
                                          String emailId, String token, OkHttpClient client,
                                          List<Attachment> attachments, Set<String> processedUrls) {
        try {
            String fixedUrl = fixAttachmentUrl(href, emailId, token);

            if (processedUrls.contains(fixedUrl)) return;
            processedUrls.add(fixedUrl);
            File tempFile = downloadAttachment(client, fixedUrl, fileName);

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

    private static File downloadAttachment(OkHttpClient client, String url, String fileName) {
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
                        "." + getFileExtension(fileName), // Добавьте точку перед расширением
                        MailActivity.context.getCacheDir()
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
                return tempFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "🔥 Download error: " + e.getMessage());
            return null;
        }
    }

    private static String getCookiesString(OkHttpClient client) {
        List<Cookie> cookies = client.cookieJar()
                .loadForRequest(HttpUrl.parse("https://letter.tpu.ru/"));
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






    public static boolean sendEmail(Context context, String emailId, String action,
                                    String subject, String message, String csrfToken) throws IOException {

        HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "compose")
                .addQueryParameter("_id", emailId)
                .addQueryParameter("_token", csrfToken)
                .build();

        FormBody.Builder formBuilder = new FormBody.Builder()
                .add("_task", "mail")
                .add("_action", "send")
                .add("_token", csrfToken)
                .add("_subject", subject)
                .add("_message", message);

        if ("reply".equals(action) || "reply_all".equals(action)) {
            formBuilder.add("_mode", "reply");
        } else if ("forward".equals(action)) {
            formBuilder.add("_mode", "forward");
        }

        Request request = new Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public static boolean deleteEmail(Context context, String emailId) throws IOException {
        String csrfToken = MailActivity.refreshCsrfToken(context);

        HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "delete")
                .addQueryParameter("_uid", emailId)
                .addQueryParameter("_token", csrfToken)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(new FormBody.Builder().build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    public static boolean markAsUnread(Context context, String emailId) throws IOException {
        String csrfToken = MailActivity.refreshCsrfToken(context);

        HttpUrl url = HttpUrl.parse("https://letter.tpu.ru/mail/").newBuilder()
                .addQueryParameter("_task", "mail")
                .addQueryParameter("_action", "setflag")
                .addQueryParameter("_uid", emailId)
                .addQueryParameter("_flag", "unread")
                .addQueryParameter("_token", csrfToken)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(new FormBody.Builder().build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
}
