package com.example.mytpu.moodle;

// Новый класс в package com.example.mytpu.moodle
public class Notification {
    private int id;
    private String subject;
    private String text;
    private long timecreated;
    private boolean read;
    private int useridfrom;
    private String fullname;
    private String smallmessage;

    public Notification(int id, String subject, String text, long timecreated,
                        boolean read, int useridfrom, String fullname, String smallmessage) {
        this.id = id;
        this.subject = subject;
        this.text = text;
        this.timecreated = timecreated;
        this.read = read;
        this.useridfrom = useridfrom;
        this.fullname = fullname;
        this.smallmessage = smallmessage;
    }

    // Геттеры
    public int getId() { return id; }
    public String getSubject() { return subject; }
    public String getText() { return text; }
    public long getTimecreated() { return timecreated; }
    public boolean isRead() { return read; }
    public int getUseridfrom() { return useridfrom; }
    public String getFullname() { return fullname; }
    public String getSmallmessage() { return smallmessage; }
}