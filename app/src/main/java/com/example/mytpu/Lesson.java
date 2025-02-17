package com.example.mytpu;

public class Lesson {
    private String day;
    private String time;
    private String subject;

    public Lesson(String day, String time, String subject) {
        this.day = day;
        this.time = time;
        this.subject = subject;
    }

    public String getDay() {
        return day;
    }

    public String getTime() {
        return time;
    }

    public String getSubject() {
        return subject;
    }
}
