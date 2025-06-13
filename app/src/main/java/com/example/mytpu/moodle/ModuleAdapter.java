package com.example.mytpu.moodle;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import com.example.mytpu.R;
import com.google.gson.annotations.SerializedName;


public class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ModuleViewHolder> {
    private final List<CourseModule> modules;
    private final Context context;

    public ModuleAdapter(List<CourseModule> modules, Context context) {
        this.modules = modules;
        this.context = context;
    }

    public static class CourseModule {
        @SerializedName("id")
        int cmid;
        int courseId;
        int instanceId;
        @SerializedName("name")
        String name;
        @SerializedName("modname")
        String type;
        @SerializedName("url")
        String url;
        @SerializedName("description")
        String description;

        // Исправленный конструктор
        public CourseModule(int cmid, int instanceId, String name, String type,
                            String url, String description, int courseId) {
            this.cmid = cmid;
            this.instanceId = instanceId;
            this.name = name;
            this.type = type;
            this.url = url;
            this.description = description;
            this.courseId = courseId; // Исправлено: было this.courseId = this.courseId
        }

        // Геттеры
        public int getCourseId() {
            return courseId;
        }

        public int getCmid() {
            return cmid;
        }

        public int getInstanceId() {
            return instanceId;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }

    }

    @NonNull
    @Override
    public ModuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_module, parent, false);
        return new ModuleViewHolder(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ModuleViewHolder holder, int position) {
        CourseModule module = modules.get(position);
        holder.moduleName.setText(module.getName());
        holder.moduleType.setText(getModuleType(module.getType()));
        holder.moduleIcon.setImageResource(getIconForType(module.getType()));

        holder.itemView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, ModuleDetailActivity.class);
            intent.putExtra("cmid", module.getCmid());
            intent.putExtra("instanceId", module.getInstanceId());
            intent.putExtra("type", module.getType());
            intent.putExtra("courseid", module.getCourseId());
            intent.putExtra("name", module.getName());
            intent.putExtra("url", module.getUrl());
            context.startActivity(intent);
        });
    }

    private int getIconForType(String type) {
        switch (type) {
            case "resource": return R.drawable.ic_file;
            case "url": return R.drawable.ic_link;
            case "assign": return R.drawable.ic_assignment;
            case "quiz": return R.drawable.ic_quiz;
            case "forum": return R.drawable.ic_forum;
            case "page": return R.drawable.ic_page;
            default: return R.drawable.ic_default;
        }
    }

    static class ModuleViewHolder extends RecyclerView.ViewHolder {
        ImageView moduleIcon;
        TextView moduleName;
        TextView moduleType;

        public ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            moduleIcon = itemView.findViewById(R.id.moduleIcon);
            moduleName = itemView.findViewById(R.id.moduleName);
            moduleType = itemView.findViewById(R.id.moduleType);
        }
    }
    private String getModuleType(String type) {
        switch (type) {
            case "resource": return "Файл";
            case "url": return "Ссылка";
            case "assign": return "Задание";
            case "quiz": return "Тест";
            case "forum": return "Форум";
            case "page": return "Страница";
            case "folder": return "Папка";
            case "lanebs": return "Литература";
            case "label": return "Метка";
            case "book": return "Книга";
            case "scorm": return "SCORM-пакет";
            case "feedback": return "Обратная связь";
            default: return type;
        }
    }

    @Override
    public int getItemCount() {
        return modules.size();
    }

}

