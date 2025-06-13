package com.example.mytpu.moodle;

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
    public int getId() { return id; }
    public String getSubject() { return subject; }
    public String getText() { return text; }
    public String getSmallmessage() { return smallmessage; }
}