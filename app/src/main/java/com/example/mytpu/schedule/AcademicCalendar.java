package com.example.mytpu.schedule;

import java.util.Calendar;
import java.util.Date;

public class AcademicCalendar {
    public static Calendar getDateForWeekAndDay(int year, int weekNumber, int weekday) {
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.setMinimalDaysInFirstWeek(4);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.WEEK_OF_YEAR, weekNumber);
        cal.set(Calendar.DAY_OF_WEEK, getCalendarDayOfWeek(weekday));
        return cal;
    }
    private static int getCalendarDayOfWeek(int weekday) {
        switch (weekday) {
            case 1: return Calendar.MONDAY;
            case 2: return Calendar.TUESDAY;
            case 3: return Calendar.WEDNESDAY;
            case 4: return Calendar.THURSDAY;
            case 5: return Calendar.FRIDAY;
            case 6: return Calendar.SATURDAY;
            case 7: return Calendar.SUNDAY;
            default: return Calendar.MONDAY;
        }
    }

    public static int getAcademicYear(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);

        // Учебный год начинается с сентября
        if (cal.get(Calendar.MONTH) < Calendar.AUGUST) {
            return year -1;
        } else {
            return year;
        }
    }
}
