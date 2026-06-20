package com.tomatoclock.controller;

import com.tomatoclock.service.TimerService;
import com.tomatoclock.model.TimerSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/timer")
@CrossOrigin(origins = "*")
public class TimerController {

    @Autowired
    private TimerService timerService;

    /**
     * POST /api/timer/start - 开始计时
     * Body: {"type": "work"} 或 {"type": "break"}
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startTimer(@RequestBody Map<String, String> body) {
        String type = body.getOrDefault("type", "work");
        if (!"work".equals(type) && !"break".equals(type)) {
            type = "work";
        }

        TimerSession session = timerService.startTimer(type);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "started");
        response.put("sessionId", session.getId());
        response.put("type", session.getType());
        response.put("durationMinutes", session.getDurationMinutes());
        response.put("startTime", session.getStartTime().toString());
        response.put("message", type.equals("work")
            ? "\ud83c\udf45 开始25分钟工作计时！"
            : "\u2615 开始5分钟休息！");

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/timer/stop - 停止计时
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopTimer() {
        return ResponseEntity.ok(timerService.stopTimer());
    }

    /**
     * POST /api/timer/complete - 完成计时
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> completeTimer() {
        return ResponseEntity.ok(timerService.completeTimer());
    }

    /**
     * POST /api/timer/reset - 重置计时
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetTimer() {
        return ResponseEntity.ok(timerService.resetTimer());
    }

    /**
     * GET /api/timer/status - 获取当前状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(timerService.getStatus());
    }

    /**
     * GET /api/timer/health - 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "Tomato Clock API");
        result.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(result);
    }
}
