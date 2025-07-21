package com.example.mytpu.moodle;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.mytpu.R;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.List;

public class CourseContentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SECTION = 0;
    private static final int TYPE_COURSE_CARD = 1;
    private final List<CourseSection> sections;
    private final String token; // Добавлено поле для токена

    @Override
    public int getItemViewType(int position) {
        if (sections.get(position).getName().equals("Course Card") &&
                !sections.get(position).getModules().isEmpty() &&
                sections.get(position).getModules().get(0).type.equals("course_card")) {
            return TYPE_COURSE_CARD;
        }
        return TYPE_SECTION;
    }

    public static class CourseSection {
        private String name;
        private List<ModuleAdapter.CourseModule> modules;

        public CourseSection(String name, List<ModuleAdapter.CourseModule> modules) {
            this.name = name;
            this.modules = modules;
        }

        public String getName() {
            return name;
        }

        public List<ModuleAdapter.CourseModule> getModules() {
            return modules;
        }
    }

    public CourseContentAdapter(List<CourseSection> sections, String token) {
        this.sections = sections;
        this.token = token; // Сохраняем токен
    }

    public void updateData(List<CourseSection> newSections) {
        this.sections.clear();
        this.sections.addAll(newSections);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_COURSE_CARD) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_course_card, parent, false);
            return new CourseCardViewHolder(view, token); // Передаем токен
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_course_section, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == TYPE_COURSE_CARD) {
            CourseCardViewHolder cardHolder = (CourseCardViewHolder) holder;
            String content = sections.get(position).getModules().get(0).description;
            cardHolder.bind(content);
        } else {
            SectionViewHolder sectionHolder = (SectionViewHolder) holder;
            CourseSection section = sections.get(position);
            sectionHolder.sectionTitle.setText(section.getName());

            ModuleAdapter moduleAdapter = new ModuleAdapter(section.getModules(), sectionHolder.itemView.getContext());
            sectionHolder.modulesRecyclerView.setLayoutManager(new LinearLayoutManager(sectionHolder.itemView.getContext()));
            sectionHolder.modulesRecyclerView.setAdapter(moduleAdapter);
        }
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    static class CourseCardViewHolder extends RecyclerView.ViewHolder {
        private final TextView courseTitle;
        private final TextView courseDescription;
        private final ImageView courseImage;
        private final String token;

        public CourseCardViewHolder(@NonNull View itemView, String token) {
            super(itemView);
            courseTitle = itemView.findViewById(R.id.courseTitle);
            courseDescription = itemView.findViewById(R.id.courseDescription);
            courseImage = itemView.findViewById(R.id.courseImage);
            this.token = token;
        }

        public void bind(String content) {
            // Парсинг HTML контента
            Document doc = Jsoup.parse(content);

            // Извлекаем данные
            String title = doc.select("h2 span").text();
            String description = doc.select(".card-text").text();
            String imageUrl = doc.select("img").attr("src");

            courseTitle.setText(title);
            courseDescription.setText(description);

            // Загрузка изображения с обработкой ошибок
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    // Добавляем токен для аутентификации
                    String authenticatedUrl = imageUrl + "?token=" + token;
                    Log.d("CourseCard", "Title: " + title);
                    Log.d("CourseCard", "Description: " + description);
                    Log.d("CourseCard", "Image URL: " + imageUrl);
                    Log.d("CourseCard", "Authenticated URL: " + authenticatedUrl);

                    Glide.with(itemView.getContext())
                            .load(authenticatedUrl)
                            .error(R.drawable.placeholder) // Запасное изображение
                            .placeholder(R.drawable.placeholder) // Плейсхолдер
                            .into(courseImage);
                } catch (Exception e) {
                    Log.e("Glide", "Error loading image", e);
                    courseImage.setImageResource(R.drawable.placeholder);
                }
            } else {
                courseImage.setImageResource(R.drawable.placeholder);
            }
        }
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        TextView sectionTitle;
        RecyclerView modulesRecyclerView;

        public SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            sectionTitle = itemView.findViewById(R.id.sectionTitle);
            modulesRecyclerView = itemView.findViewById(R.id.modulesRecyclerView);
        }
    }
}