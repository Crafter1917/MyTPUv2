package com.example.mytpu.moodle;

import static com.example.mytpu.mailTPU.MailActivity.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MoodleApiHelper {
    private static final String WEB_SERVICE_URL = "https://stud.lms.tpu.ru/webservice/rest/server.php";
    public static void markNotificationRead(String token, int messageId) throws IOException {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_message_mark_message_read")
                .addQueryParameter("messageid", String.valueOf(messageId))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).execute().close(); // Отправляем запрос
    }
    public static JSONObject getSiteInfo(String token) throws IOException, JSONException {
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_webservice_get_site_info")
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            return new JSONObject(response.body().string());
        }
    }

    public static List<Notification> getNotifications(String token, int userId) throws IOException, JSONException {
        List<Notification> notifications = new ArrayList<>();
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_message_get_messages")
                .addQueryParameter("useridto", String.valueOf(userId))
                .addQueryParameter("limit", "50")
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            JSONArray messages = new JSONArray(response.body().string());
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                notifications.add(new Notification(
                        message.getInt("id"),
                        message.optString("subject", ""),
                        message.optString("text", ""),
                        message.getLong("timecreated"),
                        message.getBoolean("read"),
                        message.getInt("useridfrom"),
                        message.optString("userfromfullname", ""),
                        message.optString("smallmessage", "")
                ));
            }
        }
        return notifications;
    }
}