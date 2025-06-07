package com.example.mytpu.moodle;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mytpu.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class NotificationsActivity extends AppCompatActivity {

    private static final String TAG = "NotificationsActivity";
    private RecyclerView recyclerView;
    private NotificationsAdapter adapter;
    private List<Notification> notifications = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications);

        recyclerView = findViewById(R.id.notificationsRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotificationsAdapter(notifications);
        recyclerView.setAdapter(adapter);

        loadNotifications();
    }

    private void loadNotifications() {
        new Thread(() -> {
            try {
                String token = SharedPreferencesHelper.getToken(this);
                int userId = SharedPreferencesHelper.getUserId(this);

                if (userId == 0) {
                    JSONObject siteInfo = MoodleApiHelper.getSiteInfo(token);
                    userId = siteInfo.getInt("userid");
                    SharedPreferencesHelper.saveUserId(this, userId);
                }

                List<Notification> result = MoodleApiHelper.getNotifications(token, userId);
                runOnUiThread(() -> {
                    notifications.clear();
                    notifications.addAll(result);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading notifications", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка загрузки уведомлений", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}