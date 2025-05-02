package com.example.mytpu.schedule;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mytpu.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WeekWheelAdapter extends RecyclerView.Adapter<WeekWheelAdapter.ViewHolder> {

    private final List<WeekItem> weeks = new ArrayList<>();
    private int selectedWeek;
    private int selectedYear;

    public List<WeekItem> getWeeks() {
        return weeks;
    }

    public static class WeekItem {
        public final int weekNumber;
        public final int year;

        public WeekItem(int weekNumber, int year) {
            this.weekNumber = weekNumber;
            this.year = year;
        }
    }

    public WeekWheelAdapter() {
        Calendar cal = Calendar.getInstance();
        selectedWeek = cal.get(Calendar.WEEK_OF_YEAR);
        selectedYear = cal.get(Calendar.YEAR);
    }

    public void setWeeks(List<WeekItem> weeks) {
        this.weeks.clear();
        this.weeks.addAll(weeks);
        notifyDataSetChanged();
    }

    public void updateWeekData(int week, int year) {
        this.selectedWeek = week;
        this.selectedYear = year;
        notifyDataSetChanged();
    }

    public int findCurrentWeekPosition() {
        Calendar today = Calendar.getInstance();
        int currentWeek = today.get(Calendar.WEEK_OF_YEAR);
        int currentYear = today.get(Calendar.YEAR);

        for (int i = 0; i < weeks.size(); i++) {
            WeekItem item = weeks.get(i);
            if (item.weekNumber == currentWeek && item.year == currentYear) {
                return i;
            }
        }
        return 26; // Fallback
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_week_wheel, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WeekItem item = weeks.get(position);

        String weekRange = formatWeekRange(item.weekNumber, item.year);
        holder.weekText.setText(weekRange);

        Calendar cal = Calendar.getInstance();
        int currentWeek = cal.get(Calendar.WEEK_OF_YEAR);
        int currentYear = cal.get(Calendar.YEAR);

        if (item.weekNumber == currentWeek && item.year == currentYear) {
            holder.itemView.setBackgroundResource(R.drawable.border);
        } else {
            holder.itemView.setBackgroundResource(0);
        }
    }

    private String formatWeekRange(int week, int year) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.WEEK_OF_YEAR, week);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);

        Date startDate = cal.getTime();
        cal.add(Calendar.DATE, 6);
        Date endDate = cal.getTime();

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM", new Locale("ru"));
        return sdf.format(startDate) + " - " + sdf.format(endDate);
    }

    @Override
    public int getItemCount() {
        return weeks.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView weekText;

        ViewHolder(View itemView) {
            super(itemView);
            weekText = itemView.findViewById(R.id.weekText);
        }
    }
}
