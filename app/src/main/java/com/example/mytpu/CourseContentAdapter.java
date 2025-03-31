package com.example.mytpu;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class CourseContentAdapter extends RecyclerView.Adapter<CourseContentAdapter.SectionViewHolder> {

    private final List<CourseSection> sections;

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

    public CourseContentAdapter(List<CourseSection> sections) {
        this.sections = sections;
    }

    public void updateData(List<CourseSection> newSections) {
        this.sections.clear();
        this.sections.addAll(newSections);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_course_section, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        CourseSection section = sections.get(position);
        holder.sectionTitle.setText(section.getName());

        // Передаем контекст из ViewHolder
        ModuleAdapter moduleAdapter = new ModuleAdapter(section.getModules(), holder.itemView.getContext());
        holder.modulesRecyclerView.setLayoutManager(new LinearLayoutManager(holder.itemView.getContext()));
        holder.modulesRecyclerView.setAdapter(moduleAdapter);
    }

    @Override
    public int getItemCount() {
        return sections.size();
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