package com.example.mytpu.schedule;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.example.mytpu.R;

import java.util.List;

public class MarqueeSpinnerAdapter extends ArrayAdapter<String> {
    private Context context;

    public MarqueeSpinnerAdapter(Context context, List<String> items) {
        super(context, R.layout.spinner_item, items);
        this.context = context;
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.spinner_item, parent, false);
        }

        TextView textView = convertView.findViewById(android.R.id.text1);
        textView.setText(getItem(position));
        textView.setSelected(true); // Активируем marquee эффект

        return convertView;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        // Для выпадающего списка используем стандартный вид
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_dropdown_item, parent, false);
        }

        TextView textView = convertView.findViewById(android.R.id.text1);
        textView.setText(getItem(position));
        textView.setSingleLine(false); // Разрешаем многострочный текст в выпадающем списке
        textView.setMaxLines(2); // Ограничиваем до 2 строк

        return convertView;
    }
}