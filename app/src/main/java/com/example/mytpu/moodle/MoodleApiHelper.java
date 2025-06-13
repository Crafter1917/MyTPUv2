package com.example.mytpu.moodle;


import static org.chromium.base.ContextUtils.getApplicationContext;

import com.example.mytpu.mailTPU.MailActivity;

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
        OkHttpClient client = MailActivity.MyMailSingleton.getInstance(getApplicationContext()).getClient();
        HttpUrl url = HttpUrl.parse(WEB_SERVICE_URL).newBuilder()
                .addQueryParameter("wstoken", token)
                .addQueryParameter("wsfunction", "core_message_mark_message_read")
                .addQueryParameter("messageid", String.valueOf(messageId))
                .addQueryParameter("moodlewsrestformat", "json")
                .build();

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).execute().close(); // Отправляем запрос
    }

}