package com.pomodoro;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import java.util.HashMap;
import java.util.Map;

/**
 * 番茄钟REST控制器
 * 提供API接口供前端调用
 */
@RestController
@RequestMapping("/api/pomodoro")
@CrossOrigin(origins = "*")
public class PomodoroController {

    @Autowired
    private PomodoroService pomodoroService;

    // 存储当前计时器（简化版，实际应使用会话管理）
    private PomodoroTimer currentTimer;

    /**
     * 创建新的番茄钟
     * POST /api/pomodoro/create?duration=25
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createTimer(
            @RequestParam(defaultValue = "25") int duration) {
        currentTimer = pomodoroService.createTimer(duration);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "created");
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 开始计时
     * POST /api/pomodoro/start
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startTimer() {
        if (currentTimer == null) {
            currentTimer = pomodoroService.createTimer(25);
        }
        currentTimer = pomodoroService.startTimer(currentTimer);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 暂停计时
     * POST /api/pomodoro/pause
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pauseTimer() {
        if (currentTimer == null) {
            return ResponseEntity.badRequest().build();
        }
        currentTimer = pomodoroService.pauseTimer(currentTimer);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "paused");
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 继续计时
     * POST /api/pomodoro/resume
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resumeTimer() {
        if (currentTimer == null) {
            return ResponseEntity.badRequest().build();
        }
        currentTimer = pomodoroService.resumeTimer(currentTimer);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "resumed");
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 重置计时器
     * POST /api/pomodoro/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetTimer() {
        if (currentTimer == null) {
            currentTimer = pomodoroService.createTimer(25);
        }
        currentTimer = pomodoroService.resetTimer(currentTimer);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "reset");
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前状态
     * GET /api/pomodoro/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        if (currentTimer == null) {
            currentTimer = pomodoroService.createTimer(25);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("timer", timerToMap(currentTimer));
        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查
     * GET /api/pomodoro/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "番茄钟服务");
        return ResponseEntity.ok(response);
    }

    /**
     * 将计时器转换为Map
     */
    private Map<String, Object> timerToMap(PomodoroTimer timer) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", timer.getStatus().toString());
        map.put("totalSeconds", timer.getTotalSeconds());
        map.put("remainingSeconds", timer.getRemainingSeconds());
        map.put("formattedTime", timer.getFormattedTime());
        map.put("progress", timer.getProgress());
        map.put("completedPomodoros", timer.getCompletedPomodoros());
        return map;
    }
}
