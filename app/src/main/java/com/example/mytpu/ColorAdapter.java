package com.example.mytpu;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashMap;
import java.util.Map;

public class ColorAdapter extends RecyclerView.Adapter<ColorAdapter.ColorViewHolder> {
    private Map<String, Integer> colors;
    private final OnColorClickListener listener;

    public interface OnColorClickListener {
        void onColorClick(String colorName);
    }

    public ColorAdapter(Map<String, Integer> colors, OnColorClickListener listener) {
        this.colors = new HashMap<>(colors);
        this.listener = listener;
    }

    public void updateColors(Map<String, Integer> newColors) {
        this.colors = new HashMap<>(newColors);

        // Гарантируем обновление в UI-потоке
        new Handler(Looper.getMainLooper()).post(() -> notifyDataSetChanged());
    }

    @NonNull
    @Override
    public ColorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.color_item, parent, false);
        return new ColorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ColorViewHolder holder, int position) {
        String colorName = (String) colors.keySet().toArray()[position];
        int colorValue = colors.get(colorName);

        holder.colorName.setText(colorName);
        holder.colorPreview.setBackgroundColor(colorValue);

        holder.colorPreview.setOnClickListener(v -> {
            if (listener != null) {
                listener.onColorClick(colorName);
            }
        });
    }

    @Override
    public int getItemCount() {
        return colors.size();
    }

    static class ColorViewHolder extends RecyclerView.ViewHolder {
        TextView colorName;
        View colorPreview;

        ColorViewHolder(View itemView) {
            super(itemView);
            colorName = itemView.findViewById(R.id.colorName);
            colorPreview = itemView.findViewById(R.id.colorPreview);
        }
    }
}