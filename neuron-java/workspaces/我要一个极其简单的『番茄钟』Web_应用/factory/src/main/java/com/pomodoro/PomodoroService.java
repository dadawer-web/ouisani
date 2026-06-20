package com.pomodoro;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 番茄钟服务
 * 管理计时器的生命周期和状态
 */
@Service
public class PomodoroService {

    // 存储用户的计时器实例
    private final Map<String, PomodoroTimer> timers = new ConcurrentHashMap<>();
    
    // 定时任务执行器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    /**
     * 创建新的计时器
     */
    public PomodoroTimer createTimer(int durationMinutes) {
        PomodoroTimer timer = new PomodoroTimer();
        timer.setTotalSeconds(durationMinutes * 60);
        timer.setRemainingSeconds(durationMinutes * 60);
        return timer;
    }

    /**
     * 开始计时
     */
    public PomodoroTimer startTimer(PomodoroTimer timer) {
        if (timer.getStatus() == PomodoroTimer.Status.RUNNING) {
            return timer;
        }

        timer.setStatus(PomodoroTimer.Status.RUNNING);

        // 启动倒计时任务
        String timerId = UUID.randomUUID().toString();
        timers.put(timerId, timer);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (timer.getStatus() == PomodoroTimer.Status.RUNNING) {
                    int remaining = timer.getRemainingSeconds();
                    if (remaining > 0) {
                        timer.setRemainingSeconds(remaining - 1);
                        System.out.println("剩余时间: " + timer.getFormattedTime());
                    } else {
                        timer.setStatus(PomodoroTimer.Status.COMPLETED);
                        timer.setCompletedPomodoros(timer.getCompletedPomodoros() + 1);
                        System.out.println("番茄完成! 已完成: " + timer.getCompletedPomodoros());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1, 1, TimeUnit.SECONDS);

        return timer;
    }

    /**
     * 暂停计时
     */
    public PomodoroTimer pauseTimer(PomodoroTimer timer) {
        timer.setStatus(PomodoroTimer.Status.PAUSED);
        return timer;
    }

    /**
     * 继续计时
     */
    public PomodoroTimer resumeTimer(PomodoroTimer timer) {
        timer.setStatus(PomodoroTimer.Status.RUNNING);
        return timer;
    }

    /**
     * 重置计时器
     */
    public PomodoroTimer resetTimer(PomodoroTimer timer) {
        timer.setStatus(PomodoroTimer.Status.IDLE);
        timer.setRemainingSeconds(timer.getTotalSeconds());
        return timer;
    }
}
