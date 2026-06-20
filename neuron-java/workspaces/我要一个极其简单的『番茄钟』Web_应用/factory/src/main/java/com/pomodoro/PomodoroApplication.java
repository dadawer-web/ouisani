package com.pomodoro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 番茄钟应用主类
 * 项目名称: pomodoro-clock
 */
@SpringBootApplication
public class PomodoroApplication {

    public static void main(String[] args) {
        SpringApplication.run(PomodoroApplication.class, args);
        System.out.println("=== 番茄钟应用启动成功 ===");
    }
}
