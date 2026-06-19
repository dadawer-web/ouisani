package com.tomatoclock.model;

import java.time.LocalDateTime;

public class TimerSession {
    private String id;
    private String type; // "work" or "break"
    private int durationMinutes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean completed;

    public TimerSession() {}

    public TimerSession(String id, String type, int durationMinutes) {
        this.id = id;
        this.type = type;
        this.durationMinutes = durationMinutes;
        this.completed = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int d) { this.durationMinutes = d; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime t) { this.startTime = t; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime t) { this.endTime = t; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean c) { this.completed = c; }
}
