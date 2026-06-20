#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate Spring Boot Pomodoro Timer REST API Backend
节点: generate_spring_boot_backend
职责: 创建Spring Boot番茄钟REST API后端项目结构
"""

import os
import json
import time
import shutil
from datetime import datetime
from typing import Dict, List, Any

# AIOS BaseAgent 继承要求
class BaseAgent:
    """Base Agent class for AIOS framework"""
    def __init__(self, name: str, description: str = ""):
        self.name = name
        self.description = description
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """Process input data and return results - must be overridden"""
        raise NotImplementedError("Subclasses must implement process_data method")

class SpringBootGenerator(BaseAgent):
    """
    Spring Boot 番茄钟后端代码生成器
    生成完整的 Spring Boot REST API 项目结构
    """
    
    def __init__(self):
        super().__init__(
            name="spring-boot-pomodoro-generator",
            description="生成Spring Boot番茄钟REST API后端项目"
        )
        self.output_base_dir = "/factory/outputs"
        self.project_name = "pomodoro-api"
        self.project_version = "1.0.0"
        
        # Spring Boot 项目目录结构
        self.project_structure = {
            "src/main/java/com/pomodoro/api": [
                "PomodoroApplication.java",
                "controller/PomodoroController.java",
                "service/PomodoroService.java",
                "model/PomodoroSession.java",
                "model/TimerStatus.java",
                "dto/PomodoroRequest.java",
                "dto/PomodoroResponse.java",
                "config/WebConfig.java"
            ],
            "src/main/resources": [
                "application.properties",
                "static/index.html",
                "templates/error.html"
            ],
            "": [
                "pom.xml",
                "README.md",
                ".gitignore"
            ]
        }
        
        # Spring Boot 版本
        self.spring_boot_version = "3.1.5"
        self.java_version = "17"
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理输入数据并生成Spring Boot后端项目
        
        Args:
            data: 包含生成配置的字典，可包含:
                - project_name: 项目名称（可选）
                - output_dir: 输出目录（可选）
                - features: 功能特性列表（可选）
        
        Returns:
            包含生成结果和项目路径的字典
        """
        start_time = time.time()
        print(f"[generate_spring_boot_backend] 🚀 开始生成Spring Boot番茄钟后端...", flush=True)
        
        # 解析输入参数
        if "project_name" in data:
            self.project_name = data["project_name"]
        if "output_dir" in data:
            self.output_base_dir = data["output_dir"]
        
        # 创建输出目录
        project_dir = os.path.join(self.output_base_dir, self.project_name)
        os.makedirs(project_dir, exist_ok=True)
        
        try:
            # 1. 创建项目目录结构
            self._create_project_structure(project_dir)
            
            # 2. 生成所有代码文件
            generated_files = self._generate_all_files(project_dir)
            
            # 3. 生成构建配置
            self._generate_build_files(project_dir)
            
            # 4. 创建运行脚本
            self._create_run_script(project_dir)
            
            # 5. 验证生成结果
            validation_result = self._validate_generation(project_dir)
            
            execution_time = time.time() - start_time
            
            result = {
                "status": "success",
                "project_name": self.project_name,
                "project_directory": project_dir,
                "generated_files": generated_files,
                "file_count": len(generated_files),
                "validation": validation_result,
                "execution_time_seconds": round(execution_time, 2),
                "timestamp": datetime.now().isoformat(),
                "message": "Spring Boot番茄钟后端项目生成完成"
            }
            
            # 保存结果到输出目录
            result_path = os.path.join(self.output_base_dir, "generate_spring_boot_backend_result.json")
            with open(result_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2, ensure_ascii=False)
            
            print(f"[generate_spring_boot_backend] ✅ 项目生成成功！共生成 {len(generated_files)} 个文件", flush=True)
            print(f"[generate_spring_boot_backend] 📁 项目目录: {project_dir}", flush=True)
            print(f"[generate_spring_boot_backend] ⏱️  耗时: {execution_time:.2f} 秒", flush=True)
            
            return result
            
        except Exception as e:
            error_msg = f"生成Spring Boot项目失败: {str(e)}"
            print(f"[generate_spring_boot_backend] ❌ {error_msg}", flush=True)
            
            error_result = {
                "status": "error",
                "error": error_msg,
                "project_directory": project_dir if 'project_dir' in locals() else None,
                "timestamp": datetime.now().isoformat()
            }
            
            # 保存错误结果
            error_path = os.path.join(self.output_base_dir, "generate_spring_boot_backend_error.json")
            os.makedirs(self.output_base_dir, exist_ok=True)
            with open(error_path, 'w', encoding='utf-8') as f:
                json.dump(error_result, f, indent=2, ensure_ascii=False)
            
            raise
    
    def _create_project_structure(self, base_dir: str):
        """创建项目目录结构"""
        print(f"[generate_spring_boot_backend] 📂 创建项目目录结构...", flush=True)
        
        for directory in self.project_structure.keys():
            if directory:  # 跳过空字符串（根目录）
                dir_path = os.path.join(base_dir, directory)
                os.makedirs(dir_path, exist_ok=True)
                print(f"[generate_spring_boot_backend]   📁 创建目录: {directory}", flush=True)
    
    def _generate_all_files(self, base_dir: str) -> List[str]:
        """生成所有代码文件"""
        print(f"[generate_spring_boot_backend] 🔧 生成代码文件...", flush=True)
        
        generated_files = []
        
        # 1. 生成主应用类
        app_file = self._generate_main_application(base_dir)
        generated_files.append(app_file)
        
        # 2. 生成控制器
        controller_file = self._generate_controller(base_dir)
        generated_files.append(controller_file)
        
        # 3. 生成服务层
        service_file = self._generate_service(base_dir)
        generated_files.append(service_file)
        
        # 4. 生成模型类
        model_files = self._generate_models(base_dir)
        generated_files.extend(model_files)
        
        # 5. 生成DTO类
        dto_files = self._generate_dtos(base_dir)
        generated_files.extend(dto_files)
        
        # 6. 生成配置类
        config_file = self._generate_config(base_dir)
        generated_files.append(config_file)
        
        # 7. 生成配置文件
        config_files = self._generate_config_files(base_dir)
        generated_files.extend(config_files)
        
        # 8. 生成前端示例页面
        frontend_files = self._generate_frontend(base_dir)
        generated_files.extend(frontend_files)
        
        return generated_files
    
    def _generate_main_application(self, base_dir: str) -> str:
        """生成Spring Boot主应用类"""
        file_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/PomodoroApplication.java")
        
        content = '''package com.pomodoro.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 番茄钟REST API主应用类
 * 
 * @author AIOS Generator
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class PomodoroApplication {

    public static void main(String[] args) {
        SpringApplication.run(PomodoroApplication.class, args);
        System.out.println("🍅 番茄钟API服务已启动！");
        System.out.println("📝 API文档: http://localhost:8080/swagger-ui.html");
        System.out.println("✅ 健康检查: http://localhost:8080/api/health");
    }
}'''
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"[generate_spring_boot_backend]   📄 生成: PomodoroApplication.java", flush=True)
        return file_path
    
    def _generate_controller(self, base_dir: str) -> str:
        """生成REST控制器"""
        file_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/controller/PomodoroController.java")
        
        content = '''package com.pomodoro.api.controller;

import com.pomodoro.api.dto.PomodoroRequest;
import com.pomodoro.api.dto.PomodoroResponse;
import com.pomodoro.api.model.TimerStatus;
import com.pomodoro.api.service.PomodoroService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 番茄钟REST API控制器
 * 提供番茄钟计时器的RESTful接口
 */
@RestController
@RequestMapping("/api/pomodoro")
@CrossOrigin(origins = "*")
public class PomodoroController {

    @Autowired
    private PomodoroService pomodoroService;

    /**
     * 开始新的番茄钟会话
     */
    @PostMapping("/start")
    public ResponseEntity<PomodoroResponse> startPomodoro(@RequestBody PomodoroRequest request) {
        PomodoroResponse response = pomodoroService.startPomodoro(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 暂停当前番茄钟
     */
    @PostMapping("/pause")
    public ResponseEntity<PomodoroResponse> pausePomodoro() {
        PomodoroResponse response = pomodoroService.pausePomodoro();
        return ResponseEntity.ok(response);
    }

    /**
     * 恢复暂停的番茄钟
     */
    @PostMapping("/resume")
    public ResponseEntity<PomodoroResponse> resumePomodoro() {
        PomodoroResponse response = pomodoroService.resumePomodoro();
        return ResponseEntity.ok(response);
    }

    /**
     * 停止当前番茄钟
     */
    @PostMapping("/stop")
    public ResponseEntity<PomodoroResponse> stopPomodoro() {
        PomodoroResponse response = pomodoroService.stopPomodoro();
        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前番茄钟状态
     */
    @GetMapping("/status")
    public ResponseEntity<PomodoroResponse> getStatus() {
        PomodoroResponse response = pomodoroService.getCurrentStatus();
        return ResponseEntity.ok(response);
    }

    /**
     * 获取番茄钟历史记录
     */
    @GetMapping("/history")
    public ResponseEntity<List<PomodoroResponse>> getHistory() {
        List<PomodoroResponse> history = pomodoroService.getHistory();
        return ResponseEntity.ok(history);
    }

    /**
     * 重置番茄钟计数
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetPomodoro() {
        Map<String, Object> result = pomodoroService.resetPomodoro();
        return ResponseEntity.ok(result);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Pomodoro API",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 获取番茄钟配置信息
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = pomodoroService.getConfig();
        return ResponseEntity.ok(config);
    }
}'''
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"[generate_spring_boot_backend]   📄 生成: PomodoroController.java", flush=True)
        return file_path
    
    def _generate_service(self, base_dir: str) -> str:
        """生成服务层"""
        file_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/service/PomodoroService.java")
        
        content = '''package com.pomodoro.api.service;

import com.pomodoro.api.dto.PomodoroRequest;
import com.pomodoro.api.dto.PomodoroResponse;
import com.pomodoro.api.model.PomodoroSession;
import com.pomodoro.api.model.TimerStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 番茄钟服务层
 * 实现番茄钟计时器的核心业务逻辑
 */
@Service
public class PomodoroService {

    // 默认番茄钟时长（分钟）
    private static final int DEFAULT_POMODORO_MINUTES = 25;
    // 默认短休息时长（分钟）
    private static final int DEFAULT_SHORT_BREAK_MINUTES = 5;
    // 默认长休息时长（分钟）
    private static final int DEFAULT_LONG_BREAK_MINUTES = 15;
    // 长休息间隔（几个番茄钟后）
    private static final int LONG_BREAK_INTERVAL = 4;

    // 当前活动会话
    private PomodoroSession currentSession;
    // 历史记录
    private final List<PomodoroSession> history = Collections.synchronizedList(new ArrayList<>());
    // 计时器状态
    private TimerStatus timerStatus = TimerStatus.IDLE;
    // 当前会话ID
    private String currentSessionId;

    /**
     * 开始新的番茄钟会话
     */
    public PomodoroResponse startPomodoro(PomodoroRequest request) {
        // 检查是否有正在进行的会话
        if (currentSession != null && timerStatus == TimerStatus.RUNNING) {
            throw new RuntimeException("已有番茄钟正在运行，请先停止当前会话");
        }

        // 创建新会话
        String sessionId = UUID.randomUUID().toString();
        int durationMinutes = request.getDurationMinutes() != null ? 
            request.getDurationMinutes() : DEFAULT_POMODORO_MINUTES;
        
        PomodoroSession session = new PomodoroSession(
            sessionId,
            request.getTaskName() != null ? request.getTaskName() : "番茄钟",
            durationMinutes,
            LocalDateTime.now(),
            request.getNotes()
        );

        this.currentSession = session;
        this.currentSessionId = sessionId;
        this.timerStatus = TimerStatus.RUNNING;

        System.out.println("🍅 番茄钟开始: " + session.getTaskName() + " (" + durationMinutes + "分钟)");

        return PomodoroResponse.fromSession(session, timerStatus);
    }

    /**
     * 暂停番茄钟
     */
    public PomodoroResponse pausePomodoro() {
        if (currentSession == null || timerStatus != TimerStatus.RUNNING) {
            throw new RuntimeException("没有正在运行的番茄钟");
        }

        currentSession.pause();
        timerStatus = TimerStatus.PAUSED;

        System.out.println("⏸️  番茄钟已暂停");

        return PomodoroResponse.fromSession(currentSession, timerStatus);
    }

    /**
     * 恢复番茄钟
     */
    public PomodoroResponse resumePomodoro() {
        if (currentSession == null || timerStatus != TimerStatus.PAUSED) {
            throw new RuntimeException("没有暂停的番茄钟");
        }

        currentSession.resume();
        timerStatus = TimerStatus.RUNNING;

        System.out.println("▶️  番茄钟已恢复");

        return PomodoroResponse.fromSession(currentSession, timerStatus);
    }

    /**
     * 停止番茄钟
     */
    public PomodoroResponse stopPomodoro() {
        if (currentSession == null) {
            throw new RuntimeException("没有活动的番茄钟");
        }

        currentSession.complete();
        timerStatus = TimerStatus.COMPLETED;

        // 添加到历史记录
        history.add(0, currentSession);
        if (history.size() > 100) { // 保留最近100条记录
            history.remove(history.size() - 1);
        }

        PomodoroSession completedSession = currentSession;
        currentSession = null;
        currentSessionId = null;

        System.out.println("✅ 番茄钟完成: " + completedSession.getTaskName());

        return PomodoroResponse.fromSession(completedSession, timerStatus);
    }

    /**
     * 获取当前状态
     */
    public PomodoroResponse getCurrentStatus() {
        if (currentSession == null) {
            return PomodoroResponse.idle();
        }
        return PomodoroResponse.fromSession(currentSession, timerStatus);
    }

    /**
     * 获取历史记录
     */
    public List<PomodoroResponse> getHistory() {
        List<PomodoroResponse> responses = new ArrayList<>();
        for (PomodoroSession session : history) {
            responses.add(PomodoroResponse.fromSession(session, TimerStatus.COMPLETED));
        }
        return responses;
    }

    /**
     * 重置所有状态
     */
    public Map<String, Object> resetPomodoro() {
        currentSession = null;
        currentSessionId = null;
        timerStatus = TimerStatus.IDLE;
        history.clear();

        System.out.println("🔄 番茄钟已重置");

        return Map.of(
            "status", "reset",
            "message", "所有番茄钟数据已清除",
            "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * 获取配置信息
     */
    public Map<String, Object> getConfig() {
        return Map.of(
            "defaultPomodoroMinutes", DEFAULT_POMODORO_MINUTES,
            "defaultShortBreakMinutes", DEFAULT_SHORT_BREAK_MINUTES,
            "defaultLongBreakMinutes", DEFAULT_LONG_BREAK_MINUTES,
            "longBreakInterval", LONG_BREAK_INTERVAL,
            "currentStatus", timerStatus,
            "historyCount", history.size()
        );
    }

    /**
     * 定时任务：检查番茄钟是否完成
     */
    @Scheduled(fixedRate = 1000) // 每秒检查一次
    public void checkPomodoroCompletion() {
        if (currentSession != null && timerStatus == TimerStatus.RUNNING) {
            Duration elapsed = Duration.between(currentSession.getStartTime(), LocalDateTime.now());
            Duration remaining = Duration.ofMinutes(currentSession.getDurationMinutes()).minus(elapsed);

            if (remaining.isNegative() || remaining.isZero()) {
                // 番茄钟时间到
                stopPomodoro();
                System.out.println("⏰ 番茄钟时间到！请休息一下。");
            }
        }
    }
}'''
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"[generate_spring_boot_backend]   📄 生成: PomodoroService.java", flush=True)
        return file_path
    
    def _generate_models(self, base_dir: str) -> List[str]:
        """生成模型类"""
        model_files = []
        
        # 1. PomodoroSession.java
        session_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/model/PomodoroSession.java")
        session_content = '''package com.pomodoro.api.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 番茄钟会话模型
 */
public class PomodoroSession {
    private String id;
    private String taskName;
    private int durationMinutes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime pauseTime;
    private long pausedDurationSeconds;
    private String notes;
    private boolean completed;

    public PomodoroSession() {}

    public PomodoroSession(String id, String taskName, int durationMinutes, 
                          LocalDateTime startTime, String notes) {
        this.id = id;
        this.taskName = taskName;
        this.durationMinutes = durationMinutes;
        this.startTime = startTime;
        this.notes = notes;
        this.completed = false;
        this.pausedDurationSeconds = 0;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public void pause() {
        this.pauseTime = LocalDateTime.now();
    }

    public void resume() {
        if (pauseTime != null) {
            this.pausedDurationSeconds += Duration.between(pauseTime, LocalDateTime.now()).getSeconds();
            this.pauseTime = null;
        }
    }

    public void complete() {
        this.endTime = LocalDateTime.now();
        this.completed = true;
    }

    public Duration getRemainingTime() {
        if (completed) return Duration.ZERO;
        
        LocalDateTime now = LocalDateTime.now();
        Duration elapsed = Duration.between(startTime, now);
        Duration totalDuration = Duration.ofMinutes(durationMinutes);
        Duration remaining = totalDuration.minus(elapsed).plusSeconds(pausedDurationSeconds);
        
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public long getRemainingSeconds() {
        return getRemainingTime().getSeconds();
    }

    public int getProgressPercentage() {
        Duration total = Duration.ofMinutes(durationMinutes);
        Duration remaining = getRemainingTime();
        long elapsedSeconds = total.minus(remaining).getSeconds();
        return (int) ((elapsedSeconds * 100) / total.getSeconds());
    }
}'''
        
        with open(session_path, 'w', encoding='utf-8') as f:
            f.write(session_content)
        model_files.append(session_path)
        
        # 2. TimerStatus.java
        status_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/model/TimerStatus.java")
        status_content = '''package com.pomodoro.api.model;

/**
 * 计时器状态枚举
 */
public enum TimerStatus {
    IDLE,       // 空闲状态
    RUNNING,    // 运行中
    PAUSED,     // 已暂停
    COMPLETED   // 已完成
}'''
        
        with open(status_path, 'w', encoding='utf-8') as f:
            f.write(status_content)
        model_files.append(status_path)
        
        print(f"[generate_spring_boot_backend]   📄 生成: 模型类 (2个文件)", flush=True)
        return model_files
    
    def _generate_dtos(self, base_dir: str) -> List[str]:
        """生成DTO类"""
        dto_files = []
        
        # 1. PomodoroRequest.java
        request_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/dto/PomodoroRequest.java")
        request_content = '''package com.pomodoro.api.dto;

/**
 * 番茄钟请求DTO
 */
public class PomodoroRequest {
    private String taskName;
    private Integer durationMinutes;
    private String notes;

    // Getters and Setters
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}'''
        
        with open(request_path, 'w', encoding='utf-8') as f:
            f.write(request_content)
        dto_files.append(request_path)
        
        # 2. PomodoroResponse.java
        response_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/dto/PomodoroResponse.java")
        response_content = '''package com.pomodoro.api.dto;

import com.pomodoro.api.model.PomodoroSession;
import com.pomodoro.api.model.TimerStatus;

import java.time.LocalDateTime;

/**
 * 番茄钟响应DTO
 */
public class PomodoroResponse {
    private String sessionId;
    private String taskName;
    private TimerStatus status;
    private int durationMinutes;
    private long remainingSeconds;
    private int progressPercentage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String notes;
    private String message;

    // Getters and Setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    
    public TimerStatus getStatus() { return status; }
    public void setStatus(TimerStatus status) { this.status = status; }
    
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    
    public long getRemainingSeconds() { return remainingSeconds; }
    public void setRemainingSeconds(long remainingSeconds) { this.remainingSeconds = remainingSeconds; }
    
    public int getProgressPercentage() { return progressPercentage; }
    public void setProgressPercentage(int progressPercentage) { this.progressPercentage = progressPercentage; }
    
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    /**
     * 从会话对象创建响应
     */
    public static PomodoroResponse fromSession(PomodoroSession session, TimerStatus status) {
        PomodoroResponse response = new PomodoroResponse();
        response.setSessionId(session.getId());
        response.setTaskName(session.getTaskName());
        response.setStatus(status);
        response.setDurationMinutes(session.getDurationMinutes());
        response.setRemainingSeconds(session.getRemainingSeconds());
        response.setProgressPercentage(session.getProgressPercentage());
        response.setStartTime(session.getStartTime());
        response.setEndTime(session.getEndTime());
        response.setNotes(session.getNotes());
        
        // 设置状态消息
        switch (status) {
            case RUNNING:
                response.setMessage("番茄钟运行中");
                break;
            case PAUSED:
                response.setMessage("番茄钟已暂停");
                break;
            case COMPLETED:
                response.setMessage("番茄钟已完成！");
                break;
            default:
                response.setMessage("番茄钟就绪");
        }
        
        return response;
    }

    /**
     * 创建空闲状态响应
     */
    public static PomodoroResponse idle() {
        PomodoroResponse response = new PomodoroResponse();
        response.setStatus(TimerStatus.IDLE);
        response.setMessage("当前没有活动的番茄钟");
        response.setRemainingSeconds(0);
        response.setProgressPercentage(0);
        return response;
    }
}'''
        
        with open(response_path, 'w', encoding='utf-8') as f:
            f.write(response_content)
        dto_files.append(response_path)
        
        print(f"[generate_spring_boot_backend]   📄 生成: DTO类 (2个文件)", flush=True)
        return dto_files
    
    def _generate_config(self, base_dir: str) -> str:
        """生成配置类"""
        file_path = os.path.join(base_dir, "src/main/java/com/pomodoro/api/config/WebConfig.java")
        
        content = '''package com.pomodoro.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置类
 * 配置CORS和其他Web相关设置
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}'''
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"[generate_spring_boot_backend]   📄 生成: WebConfig.java", flush=True)
        return file_path
    
    def _generate_config_files(self, base_dir: str) -> List[str]:
        """生成配置文件"""
        config_files = []
        
        # 1. application.properties
        app_props_path = os.path.join(base_dir, "src/main/resources/application.properties")
        app_props_content = '''# 番茄钟API应用配置
spring.application.name=pomodoro-api
server.port=8080

# 日志配置
logging.level.com.pomodoro.api=INFO
logging.level.org.springframework=WARN

# Jackson配置
spring.jackson.serialization.write-dates-as-timestamps=false
spring.jackson.date-format=yyyy-MM-dd HH:mm:ss
spring.jackson.time-zone=Asia/Shanghai

# 跨域配置（已在WebConfig中配置）
# spring.web.cors.allowed-origins=*

# 健康检查
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always'''
        
        with open(app_props_path, 'w', encoding='utf-8') as f:
            f.write(app_props_content)
        config_files.append(app_props_path)
        
        # 2. pom.xml
        pom_path = os.path.join(base_dir, "pom.xml")
        pom_content = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>{self.spring_boot_version}</version>
        <relativePath/>
    </parent>
    
    <groupId>com.pomodoro</groupId>
    <artifactId>{self.project_name}</artifactId>
    <version>{self.project_version}</version>
    <name>{self.project_name}</name>
    <description>番茄钟REST API后端</description>
    
    <properties>
        <java.version>{self.java_version}</java.version>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>'''
        
        with open(pom_path, 'w', encoding='utf-8') as f:
            f.write(pom_content)
        config_files.append(pom_path)
        
        print(f"[generate_spring_boot_backend]   📄 生成: 配置文件 (2个文件)", flush=True)
        return config_files
    
    def _generate_frontend(self, base_dir: str) -> List[str]:
        """生成前端示例页面"""
        frontend_files = []
        
        # 1. index.html (简单的测试页面)
        index_path = os.path.join(base_dir, "src/main/resources/static/index.html")
        index_content = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🍅 番茄钟API测试</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        .container {
            background: white;
            padding: 30px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        h1 {
            color: #e74c3c;
            text-align: center;
        }
        .api-section {
            margin: 20px 0;
            padding: 15px;
            background: #f8f9fa;
            border-radius: 5px;
        }
        button {
            background: #e74c3c;
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 5px;
            cursor: pointer;
            margin: 5px;
        }
        button:hover {
            background: #c0392b;
        }
        #result {
            margin-top: 20px;
            padding: 15px;
            background: #ecf0f1;
            border-radius: 5px;
            white-space: pre-wrap;
            font-family: monospace;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🍅 番茄钟API测试页面</h1>
        
        <div class="api-section">
            <h3>基础操作</h3>
            <button onclick="startPomodoro()">开始番茄钟</button>
            <button onclick="pausePomodoro()">暂停</button>
            <button onclick="resumePomodoro()">恢复</button>
            <button onclick="stopPomodoro()">停止</button>
            <button onclick="getStatus()">获取状态</button>
        </div>
        
        <div class="api-section">
            <h3>API测试</h3>
            <button onclick="testHealth()">健康检查</button>
            <button onclick="testConfig()">获取配置</button>
            <button onclick="getHistory()">历史记录</button>
            <button onclick="resetAll()">重置所有</button>
        </div>
        
        <div id="result">点击按钮测试API...</div>
    </div>

    <script>
        const API_BASE = '/api/pomodoro';
        
        async function callApi(endpoint, method = 'GET', body = null) {
            const options = {
                method,
                headers: { 'Content-Type': 'application/json' }
            };
            if (body) options.body = JSON.stringify(body);
            
            try {
                const response = await fetch(API_BASE + endpoint, options);
                const data = await response.json();
                showResult(data);
                return data;
            } catch (error) {
                showResult({ error: error.message });
            }
        }
        
        function showResult(data) {
            document.getElementById('result').textContent = 
                JSON.stringify(data, null, 2);
        }
        
        function startPomodoro() {
            callApi('/start', 'POST', {
                taskName: '编码工作',
                durationMinutes: 25,
                notes: '专注编码时间'
            });
        }
        
        function pausePomodoro() { callApi('/pause', 'POST'); }
        function resumePomodoro() { callApi('/resume', 'POST'); }
        function stopPomodoro() { callApi('/stop', 'POST'); }
        function getStatus() { callApi('/status'); }
        function testHealth() { callApi('/health'); }
        function testConfig() { callApi('/config'); }
        function getHistory() { callApi('/history'); }
        function resetAll() { callApi('/reset', 'POST'); }
    </script>
</body>
</html>'''
        
        with open(index_path, 'w', encoding='utf-8') as f:
            f.write(index_content)
        frontend_files.append(index_path)
        
        print(f"[generate_spring_boot_backend]   📄 生成: 前端测试页面", flush=True)
        return frontend_files
    
    def _generate_build_files(self, base_dir: str):
        """生成构建和运行文件"""
        # 1. .gitignore
        gitignore_path = os.path.join(base_dir, ".gitignore")
        gitignore_content = '''# Maven
target/
pom.xml.tag
pom.xml.releaseBackup
pom.xml.versionsBackup
pom.xml.next
release.properties
dependency-reduced-pom.xml
buildNumber.properties
.mvn/timing.properties
.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
*.iws
*.ipr
.vscode/
.settings/
.classpath
.project
.factorypath
*.swp
*~

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/
log/

# Spring Boot
spring-shell.log

# Package Files
*.jar
*.war
*.nar
*.ear
*.tar.gz
*.rar
*.zip

# Virtual machine crash logs
hs_err_pid*
replay_pid*

# Maven wrapper
.mvn/wrapper/maven-wrapper.jar

# Gradle
.gradle/
build/

# Node.js
node_modules/
npm-debug.log*
yarn-debug.log*
yarn-error.log*'''
        
        with open(gitignore_path, 'w', encoding='utf-8') as f:
            f.write(gitignore_content)
        
        # 2. README.md
        readme_path = os.path.join(base_dir, "README.md")
        readme_content = f'''# {self.project_name}

🍅 番茄钟REST API后端

## 项目概述

这是一个基于Spring Boot {self.spring_boot_version}的番茄钟计时器REST API后端，提供完整的番茄钟管理功能。

## 功能特性

- ⏱️ 番茄钟计时器管理（开始、暂停、恢复、停止）
- 📊 进度跟踪和状态监控
- 📝 任务名称和备注支持
- 📈 历史记录查询
- 🏥 健康检查接口
- ⚙️ 配置信息查询
- 🔄 重置功能

## 技术栈

- **后端框架**: Spring Boot {self.spring_boot_version}
- **Java版本**: Java {self.java_version}
- **构建工具**: Maven
- **API风格**: RESTful

## 快速开始

### 前提条件

1. 安装Java {self.java_version}或更高版本
2. 安装Maven 3.6+（或使用项目中的mvnw）

### 运行项目

```bash
# 使用Maven运行
./mvnw spring-boot:run

# 或者使用打包后的JAR
mvn clean package
java -jar target/{self.project_name}-{self.project_version}.jar
```

### 访问应用

- 应用主页: http://localhost:8080
- API测试页面: http://localhost:8080/index.html
- 健康检查: http://localhost:8080/api/pomodoro/health
- 配置信息: http://localhost:8080/api/pomodoro/config

## API端点

| 方法 | 端点 | 描述 |
|------|------|------|
| POST | `/api/pomodoro/start` | 开始新的番茄钟 |
| POST | `/api/pomodoro/pause` | 暂停当前番茄钟 |
| POST | `/api/pomodoro/resume` | 恢复暂停的番茄钟 |
| POST | `/api/pomodoro/stop` | 停止当前番茄钟 |
| GET | `/api/pomodoro/status` | 获取当前状态 |
| GET | `/api/pomodoro/history` | 获取历史记录 |
| POST | `/api/pomodoro/reset` | 重置所有数据 |
| GET | `/api/pomodoro/health` | 健康检查 |
| GET | `/api/pomodoro/config` | 获取配置信息 |

## 开发说明

### 项目结构

```
src/main/java/com/pomodoro/api/
├── PomodoroApplication.java      # 主应用类
├── controller/                   # REST控制器
├── service/                      # 业务逻辑层
├── model/                        # 数据模型
├── dto/                          # 数据传输对象
└── config/                       # 配置类
```

### 添加新功能

1. 在`model`包中添加数据模型
2. 在`dto`包中添加请求/响应DTO
3. 在`service`包中实现业务逻辑
4. 在`controller`包中添加REST端点

## 许可证

MIT License'''
        
        with open(readme_path, 'w', encoding='utf-8') as f:
            f.write(readme_content)
        
        print(f"[generate_spring_boot_backend]   📄 生成: 构建文件 (2个文件)", flush=True)
    
    def _create_run_script(self, base_dir: str):
        """创建运行脚本"""
        script_path = os.path.join(base_dir, "run.sh")
        script_content = '''#!/bin/bash
# 番茄钟API启动脚本

echo "🍅 启动番茄钟REST API服务..."
echo "📁 项目目录: $(pwd)"
echo ""

# 检查Java版本
if command -v java &> /dev/null; then
    java -version
    echo ""
else
    echo "❌ 错误: 未找到Java运行时"
    echo "请安装Java 17或更高版本"
    exit 1
fi

# 使用Maven运行
if [ -f "mvnw" ]; then
    echo "🚀 使用Maven Wrapper启动..."
    chmod +x mvnw
    ./mvnw spring-boot:run
elif command -v mvn &> /dev/null; then
    echo "🚀 使用Maven启动..."
    mvn spring-boot:run
else
    echo "⚠️  未找到Maven，尝试直接运行JAR..."
    if [ -f "target/{self.project_name}-{self.project_version}.jar" ]; then
        java -jar target/{self.project_name}-{self.project_version}.jar
    else
        echo "❌ 错误: 未找到可执行文件"
        echo "请先构建项目: mvn clean package"
        exit 1
    fi
fi'''
        
        with open(script_path, 'w', encoding='utf-8') as f:
            f.write(script_content)
        
        # 使脚本可执行
        os.chmod(script_path, 0o755)
        
        print(f"[generate_spring_boot_backend]   📄 生成: 运行脚本 (run.sh)", flush=True)
    
    def _validate_generation(self, base_dir: str) -> Dict[str, Any]:
        """验证生成结果"""
        validation = {
            "valid": True,
            "files_generated": 0,
            "directories_created": 0,
            "errors": [],
            "warnings": []
        }
        
        # 检查关键文件是否存在
        required_files = [
            "src/main/java/com/pomodoro/api/PomodoroApplication.java",
            "src/main/java/com/pomodoro/api/controller/PomodoroController.java",
            "pom.xml",
            "README.md"
        ]
        
        for file_path in required_files:
            full_path = os.path.join(base_dir, file_path)
            if os.path.exists(full_path):
                validation["files_generated"] += 1
            else:
                validation["errors"].append(f"缺失必要文件: {file_path}")
                validation["valid"] = False
        
        # 统计生成的文件总数
        for directory, files in self.project_structure.items():
            for file in files:
                full_path = os.path.join(base_dir, directory, file) if directory else os.path.join(base_dir, file)
                if os.path.exists(full_path):
                    validation["files_generated"] += 1
        
        # 统计创建的目录数
        for root, dirs, files in os.walk(base_dir):
            validation["directories_created"] += len(dirs)
        
        # 检查pom.xml语法（简单验证）
        pom_path = os.path.join(base_dir, "pom.xml")
        if os.path.exists(pom_path):
            try:
                with open(pom_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                    if "spring-boot-starter-parent" not in content:
                        validation["warnings"].append("pom.xml可能缺少Spring Boot父依赖")
            except Exception as e:
                validation["warnings"].append(f"读取pom.xml失败: {str(e)}")
        
        print(f"[generate_spring_boot_backend]   ✅ 验证结果: {validation['files_generated']} 个文件, {validation['directories_created']} 个目录", flush=True)
        
        return validation


# 测试函数
def test_spring_boot_generator():
    """测试Spring Boot生成器功能"""
    print("=" * 60)
    print("🧪 测试Spring Boot番茄钟后端生成器")
    print("=" * 60)
    
    # 创建生成器实例
    generator = SpringBootGenerator()
    
    # 测试输入数据
    test_data = {
        "project_name": "pomodoro-api-test",
        "output_dir": "/factory/outputs/test"
    }
    
    try:
        # 执行生成
        result = generator.process_data(test_data)
        
        print("\n" + "=" * 60)
        print("📊 测试结果:")
        print("=" * 60)
        print(f"状态: {result['status']}")
        print(f"项目名称: {result['project_name']}")
        print(f"生成文件数: {result['file_count']}")
        print(f"执行时间: {result['execution_time_seconds']} 秒")
        print(f"验证状态: {'✅ 通过' if result['validation']['valid'] else '❌ 失败'}")
        
        if result['validation']['errors']:
            print(f"错误: {result['validation']['errors']}")
        
        print("=" * 60)
        
        return result['status'] == 'success'
        
    except Exception as e:
        print(f"\n❌ 测试失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


if __name__ == "__main__":
    # 独立测试入口
    success = test_spring_boot_generator()
    
    if success:
        print("\n🎉 NODE_VERIFIED_AND_READY")
        print("Spring Boot番茄钟后端生成器测试通过！")
    else:
        print("\n💥 测试失败，请检查错误信息")
        exit(1)