package com.pomodoro;

/**
 * 番茄钟计时器模型
 */
public class PomodoroTimer {

    // 状态枚举
    public enum Status {
        IDLE,       // 空闲
        RUNNING,    // 运行中
        PAUSED,     // 暂停
        COMPLETED   // 完成
    }

    private Status status;
    private int totalSeconds;      // 总时长（秒）
    private int remainingSeconds;  // 剩余秒数
    private int completedPomodoros; // 已完成的番茄数

    public PomodoroTimer() {
        this.status = Status.IDLE;
        this.totalSeconds = 25 * 60;  // 默认25分钟
        this.remainingSeconds = this.totalSeconds;
        this.completedPomodoros = 0;
    }

    // Getters and Setters
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getTotalSeconds() { return totalSeconds; }
    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
        if (this.remainingSeconds > totalSeconds) {
            this.remainingSeconds = totalSeconds;
        }
    }

    public int getRemainingSeconds() { return remainingSeconds; }
    public void setRemainingSeconds(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }

    public int getCompletedPomodoros() { return completedPomodoros; }
    public void setCompletedPomodoros(int completedPomodoros) {
        this.completedPomodoros = completedPomodoros;
    }

    /**
     * 获取剩余时间的格式化字符串 mm:ss
     */
    public String getFormattedTime() {
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * 获取进度百分比
     */
    public double getProgress() {
        if (totalSeconds == 0) return 0;
        return ((double)(totalSeconds - remainingSeconds) / totalSeconds) * 100;
    }
}
