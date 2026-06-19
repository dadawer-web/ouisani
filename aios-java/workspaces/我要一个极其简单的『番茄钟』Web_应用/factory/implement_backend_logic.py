#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
implement_backend_logic.py
实现Spring Boot控制器、服务和计时逻辑，编写全部Java后端代码的Python代理
"""
import os
import sys
import json
import tempfile

# 尝试导入BaseAgent，如果不可用则创建模拟类
try:
    from base_agent import BaseAgent
except ImportError:
    class BaseAgent:
        def __init__(self):
            pass
        def process_data(self, data):
            raise NotImplementedError


class ImplementBackendLogic(BaseAgent):
    """
    实现Spring Boot后端逻辑的代理
    职责：创建Spring Boot控制器、服务和计时逻辑的Java代码
    """

    def __init__(self):
        super().__init__()
        self.project_name = "pomodoro-clock"
        self.output_dir = os.path.join(tempfile.gettempdir(), "pomodoro_output")
        self.factory_dir = os.getcwd()
        self.java_base_path = os.path.join(self.output_dir, "src", "main", "java", "com", "pomodoro")

    def process_data(self, data):
        """
        处理输入数据，生成Spring Boot后端代码
        """
        print("IMPLEMENT_BACKEND_LOGIC: Starting backend implementation...", flush=True)

        # 解析输入
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                return {"status": "error", "message": "Invalid JSON input"}

        project_name = data.get("project_name", self.project_name)
        duration = data.get("duration", 25)

        # 创建输出目录结构
        os.makedirs(self.java_base_path, exist_ok=True)
        os.makedirs(os.path.join(self.output_dir, "src", "main", "resources"), exist_ok=True)
        os.makedirs(os.path.join(self.output_dir, "src", "test", "java", "com", "pomodoro"), exist_ok=True)

        generated_files = []

        # 1. 创建主应用类
        app_content = self._generate_application_class(project_name)
        app_file = os.path.join(self.java_base_path, "PomodoroApplication.java")
        self._write_file(app_file, app_content)
        generated_files.append(app_file)

        # 2. 创建计时器模型
        model_content = self._generate_timer_model(duration)
        model_file = os.path.join(self.java_base_path, "PomodoroTimer.java")
        self._write_file(model_file, model_content)
        generated_files.append(model_file)

        # 3. 创建服务层
        service_content = self._generate_timer_service(duration)
        service_file = os.path.join(self.java_base_path, "PomodoroService.java")
        self._write_file(service_file, service_content)
        generated_files.append(service_file)

        # 4. 创建REST控制器
        controller_content = self._generate_controller()
        controller_file = os.path.join(self.java_base_path, "PomodoroController.java")
        self._write_file(controller_file, controller_content)
        generated_files.append(controller_file)

        # 5. 创建配置文件
        config_content = self._generate_application_properties(project_name)
        config_file = os.path.join(self.output_dir, "src", "main", "resources", "application.properties")
        self._write_file(config_file, config_content)
        generated_files.append(config_file)

        # 6. 创建CORS配置
        cors_content = self._generate_cors_config()
        cors_file = os.path.join(self.java_base_path, "CorsConfig.java")
        self._write_file(cors_file, cors_content)
        generated_files.append(cors_file)

        print(f"IMPLEMENT_BACKEND_LOGIC: Generated {len(generated_files)} files", flush=True)
        print(f"IMPLEMENT_BACKEND_LOGIC: Output directory: {self.output_dir}", flush=True)

        return {
            "status": "success",
            "output_dir": self.output_dir,
            "generated_files": generated_files,
            "project_name": project_name
        }

    def _write_file(self, filepath, content):
        """写入文件并打印日志"""
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"  Created: {filepath}", flush=True)

    def _generate_application_class(self, project_name):
        """生成Spring Boot主应用类"""
        return f'''package com.pomodoro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 番茄钟应用主类
 * 项目名称: {project_name}
 */
@SpringBootApplication
public class PomodoroApplication {{

    public static void main(String[] args) {{
        SpringApplication.run(PomodoroApplication.class, args);
        System.out.println("=== 番茄钟应用启动成功 ===");
    }}
}}
'''

    def _generate_timer_model(self, duration):
        """生成计时器模型类"""
        return f'''package com.pomodoro;

/**
 * 番茄钟计时器模型
 */
public class PomodoroTimer {{

    // 状态枚举
    public enum Status {{
        IDLE,       // 空闲
        RUNNING,    // 运行中
        PAUSED,     // 暂停
        COMPLETED   // 完成
    }}

    private Status status;
    private int totalSeconds;      // 总时长（秒）
    private int remainingSeconds;  // 剩余秒数
    private int completedPomodoros; // 已完成的番茄数

    public PomodoroTimer() {{
        this.status = Status.IDLE;
        this.totalSeconds = {duration} * 60;  // 默认{duration}分钟
        this.remainingSeconds = this.totalSeconds;
        this.completedPomodoros = 0;
    }}

    // Getters and Setters
    public Status getStatus() {{ return status; }}
    public void setStatus(Status status) {{ this.status = status; }}

    public int getTotalSeconds() {{ return totalSeconds; }}
    public void setTotalSeconds(int totalSeconds) {{
        this.totalSeconds = totalSeconds;
        if (this.remainingSeconds > totalSeconds) {{
            this.remainingSeconds = totalSeconds;
        }}
    }}

    public int getRemainingSeconds() {{ return remainingSeconds; }}
    public void setRemainingSeconds(int remainingSeconds) {{
        this.remainingSeconds = remainingSeconds;
    }}

    public int getCompletedPomodoros() {{ return completedPomodoros; }}
    public void setCompletedPomodoros(int completedPomodoros) {{
        this.completedPomodoros = completedPomodoros;
    }}

    /**
     * 获取剩余时间的格式化字符串 mm:ss
     */
    public String getFormattedTime() {{
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }}

    /**
     * 获取进度百分比
     */
    public double getProgress() {{
        if (totalSeconds == 0) return 0;
        return ((double)(totalSeconds - remainingSeconds) / totalSeconds) * 100;
    }}
}}
'''

    def _generate_timer_service(self, duration):
        """生成计时器服务类"""
        return '''package com.pomodoro;

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
'''

    def _generate_controller(self):
        """生成REST控制器"""
        return '''package com.pomodoro;

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
'''

    def _generate_application_properties(self, project_name):
        """生成配置文件"""
        return f'''# 番茄钟应用配置
spring.application.name={project_name}
server.port=8080

# 日志配置
logging.level.com.pomodoro=INFO
logging.pattern.console=%d{{HH:mm:ss}} - %msg%n
'''

    def _generate_cors_config(self):
        """生成CORS配置类"""
        return '''package com.pomodoro;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS跨域配置
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
'''


def main():
    """测试函数"""
    print("Testing ImplementBackendLogic...", flush=True)

    try:
        # 创建代理实例
        agent = ImplementBackendLogic()

        # 测试数据
        test_data = {
            "task": "implement_backend",
            "project_name": "pomodoro-clock",
            "duration": 25
        }

        # 执行处理
        result = agent.process_data(test_data)

        print("Test completed successfully!", flush=True)
        print(f"Generated files: {len(result.get('generated_files', []))}", flush=True)
        print(f"Output directory: {result.get('output_dir')}", flush=True)

        # 验证结果
        assert result["status"] == "success"
        assert len(result["generated_files"]) > 0
        print("All assertions passed.", flush=True)

        # 列出生成的文件
        for f in result["generated_files"]:
            print(f"  - {f}", flush=True)

        return 0

    except Exception as e:
        print(f"Test failed: {e}", flush=True)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
