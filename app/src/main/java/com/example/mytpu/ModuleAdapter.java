package com.example.mytpu;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import com.google.gson.annotations.SerializedName;


public class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ModuleViewHolder> {
    private final List<CourseModule> modules;
    private final Context context;

    public ModuleAdapter(List<CourseModule> modules, Context context) {
        this.modules = modules;
        this.context = context;
    }
    // Вынесем класс CourseModule в отдельный статический класс
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

        public String getDescription() {
            return description;
        }
    }

    @NonNull
    @Override
    public ModuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_module, parent, false);
        return new ModuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModuleViewHolder holder, int position) {
        CourseModule module = modules.get(position);
        holder.moduleName.setText(module.getName());
        holder.moduleType.setText(getModuleType(module.getType()));
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, ModuleDetailActivity.class);
            intent.putExtra("cmid", module.getCmid());
            intent.putExtra("instanceId", module.getInstanceId()); // Добавляем instanceId
            intent.putExtra("type", module.getType());
            intent.putExtra("courseid", module.getCourseId()); // Передаем courseId
            intent.putExtra("name", module.getName()); // Передаем название модуля
            intent.putExtra("url", module.getUrl()); // Передаем URL (если нужен)
            context.startActivity(intent);
        });
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

    static class ModuleViewHolder extends RecyclerView.ViewHolder {
        TextView moduleName;
        TextView moduleType;

        public ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            moduleName = itemView.findViewById(R.id.moduleName);
            moduleType = itemView.findViewById(R.id.moduleType);
        }
    }

}

