#!/usr/bin/env python3
"""
Create Backend Agent - 生成Java Spring Boot后端项目
职责：提供番茄钟REST API（包含/start和/stop端点），输出API配置信息
"""

import os
import sys
import json
import shutil
from datetime import datetime
from pathlib import Path

# 检查是否有BaseAgent可导入，如果没有则创建一个简单的基类
try:
    from base_agent import BaseAgent
except ImportError:
    # 简化的BaseAgent基类用于独立测试
    class BaseAgent:
        def __init__(self):
            pass
        
        def process_data(self, data):
            """子类必须重写此方法"""
            raise NotImplementedError("子类必须实现process_data方法")

# 确保输出目录存在
OUTPUT_DIR = Path('/factory/outputs')
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

class CreateBackendAgent(BaseAgent):
    """
    生成Spring Boot后端项目的Agent
    创建项目结构、配置文件和REST API端点
    """
    
    def __init__(self):
        super().__init__()
        self.project_name = "pomodoro-timer-backend"
        self.base_package = "com.pomodoro.timer"
        
    def process_data(self, data):
        """
        处理数据，生成Spring Boot后端项目
        
        Args:
            data: 包含项目配置的字典，可能为空
            
        Returns:
            dict: 包含生成结果的字典
        """
        print(f"🚀 [CreateBackendAgent] 开始生成Spring Boot后端项目...", flush=True)
        
        try:
            # 创建项目目录结构
            project_path = self._create_project_structure()
            
            # 生成pom.xml文件
            pom_path = self._generate_pom_xml(project_path)
            
            # 生成主应用类
            app_path = self._generate_main_application(project_path)
            
            # 生成REST控制器
            controller_path = self._generate_rest_controller(project_path)
            
            # 生成配置文件
            config_path = self._generate_application_properties(project_path)
            
            # 生成API文档
            api_doc_path = self._generate_api_documentation(project_path)
            
            # 输出API配置信息
            api_config = self._create_api_config(project_path)
            
            # 保存结果到输出文件
            output_data = {
                "status": "success",
                "timestamp": datetime.now().isoformat(),
                "project_name": self.project_name,
                "project_path": str(project_path),
                "files_created": [
                    str(pom_path),
                    str(app_path),
                    str(controller_path),
                    str(config_path),
                    str(api_doc_path)
                ],
                "api_config": api_config,
                "endpoints": [
                    {"method": "POST", "path": "/api/pomodoro/start", "description": "开始番茄钟计时"},
                    {"method": "POST", "path": "/api/pomodoro/stop", "description": "停止番茄钟计时"},
                    {"method": "GET", "path": "/api/pomodoro/status", "description": "获取当前番茄钟状态"}
                ]
            }
            
            # 保存输出文件
            output_file = OUTPUT_DIR / "backend_project_info.json"
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(output_data, f, indent=2, ensure_ascii=False)
            
            print(f"✅ [CreateBackendAgent] 项目生成成功！", flush=True)
            print(f"📁 项目路径: {project_path}", flush=True)
            print(f"📄 输出文件: {output_file}", flush=True)
            print(f"🔗 API端点已配置完成", flush=True)
            
            return output_data
            
        except Exception as e:
            error_msg = f"生成项目时发生错误: {str(e)}"
            print(f"❌ [CreateBackendAgent] {error_msg}", flush=True)
            
            # 保存错误信息到输出文件
            error_output = {
                "status": "error",
                "timestamp": datetime.now().isoformat(),
                "error": error_msg
            }
            error_file = OUTPUT_DIR / "backend_project_error.json"
            with open(error_file, 'w', encoding='utf-8') as f:
                json.dump(error_output, f, indent=2, ensure_ascii=False)
            
            raise
    
    def _create_project_structure(self):
        """创建Spring Boot项目目录结构"""
        print("📂 创建项目目录结构...", flush=True)
        
        # 项目根目录
        project_path = Path('/factory/outputs') / self.project_name
        project_path.mkdir(parents=True, exist_ok=True)
        
        # 创建标准的Maven项目结构
        dirs_to_create = [
            'src/main/java',
            'src/main/resources',
            'src/test/java',
            'src/test/resources'
        ]
        
        for dir_path in dirs_to_create:
            (project_path / dir_path).mkdir(parents=True, exist_ok=True)
        
        # 创建包结构
        package_path = project_path / 'src/main/java' / self.base_package.replace('.', '/')
        package_path.mkdir(parents=True, exist_ok=True)
        
        print(f"✅ 项目目录创建完成: {project_path}", flush=True)
        return project_path
    
    def _generate_pom_xml(self, project_path):
        """生成pom.xml文件"""
        print("📝 生成pom.xml文件...", flush=True)
        
        pom_content = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.0</version>
        <relativePath/>
    </parent>
    
    <groupId>com.pomodoro</groupId>
    <artifactId>pomodoro-timer-backend</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <name>Pomodoro Timer Backend</name>
    <description>番茄钟后端服务，提供REST API控制番茄钟计时</description>
    
    <properties>
        <java.version>11</java.version>
        <spring-boot.version>2.7.0</spring-boot.version>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${{spring-boot.version}}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>"""
        
        pom_path = project_path / 'pom.xml'
        with open(pom_path, 'w', encoding='utf-8') as f:
            f.write(pom_content)
        
        print(f"✅ pom.xml文件已生成: {pom_path}", flush=True)
        return pom_path
    
    def _generate_main_application(self, project_path):
        """生成Spring Boot主应用类"""
        print("☕ 生成主应用类...", flush=True)
        
        app_content = f"""package {self.base_package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 番茄钟后端应用主类
 * 提供REST API控制番茄钟计时
 */
@SpringBootApplication
@EnableScheduling
public class PomodoroTimerApplication {{

    public static void main(String[] args) {{
        SpringApplication.run(PomodoroTimerApplication.class, args);
        System.out.println("🍅 番茄钟后端服务已启动！");
        System.out.println("📡 API文档: http://localhost:8080/swagger-ui.html");
        System.out.println("🔗 健康检查: http://localhost:8080/actuator/health");
    }}
}}"""
        
        # 创建主应用类文件
        app_path = project_path / 'src/main/java' / self.base_package.replace('.', '/') / 'PomodoroTimerApplication.java'
        with open(app_path, 'w', encoding='utf-8') as f:
            f.write(app_content)
        
        print(f"✅ 主应用类已生成: {app_path}", flush=True)
        return app_path
    
    def _generate_rest_controller(self, project_path):
        """生成REST控制器"""
        print("🔌 生成REST控制器...", flush=True)
        
        controller_content = f"""package {self.base_package}.controller;

import {self.base_package}.service.PomodoroService;
import {self.base_package}.model.PomodoroStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 番茄钟REST API控制器
 * 提供开始、停止和状态查询接口
 */
@RestController
@RequestMapping("/api/pomodoro")
@CrossOrigin(origins = "*")
public class PomodoroController {{

    private final PomodoroService pomodoroService;

    @Autowired
    public PomodoroController(PomodoroService pomodoroService) {{
        this.pomodoroService = pomodoroService;
    }}

    /**
     * 开始番茄钟计时
     * @return 计时状态信息
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startPomodoro() {{
        try {{
            PomodoroStatus status = pomodoroService.startPomodoro();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "番茄钟已开始计时");
            response.put("status", status);
            response.put("duration", "25分钟");
            return ResponseEntity.ok(response);
        }} catch (Exception e) {{
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "启动番茄钟失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }}
    }}

    /**
     * 停止番茄钟计时
     * @return 计时状态信息
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopPomodoro() {{
        try {{
            PomodoroStatus status = pomodoroService.stopPomodoro();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "番茄钟已停止");
            response.put("status", status);
            return ResponseEntity.ok(response);
        }} catch (Exception e) {{
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "停止番茄钟失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }}
    }}

    /**
     * 获取当前番茄钟状态
     * @return 当前状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {{
        try {{
            PomodoroStatus status = pomodoroService.getCurrentStatus();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", status);
            return ResponseEntity.ok(response);
        }} catch (Exception e) {{
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "获取状态失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }}
    }}

    /**
     * 健康检查接口
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {{
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Pomodoro Timer Backend");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }}
}}"""
        
        # 创建控制器文件
        controller_dir = project_path / 'src/main/java' / self.base_package.replace('.', '/') / 'controller'
        controller_dir.mkdir(parents=True, exist_ok=True)
        controller_path = controller_dir / 'PomodoroController.java'
        
        with open(controller_path, 'w', encoding='utf-8') as f:
            f.write(controller_content)
        
        print(f"✅ REST控制器已生成: {controller_path}", flush=True)
        return controller_path
    
    def _generate_application_properties(self, project_path):
        """生成应用配置文件"""
        print("⚙️ 生成配置文件...", flush=True)
        
        config_content = """# 番茄钟后端应用配置
server.port=8080
server.servlet.context-path=/

# 数据库配置
spring.datasource.url=jdbc:h2:mem:pomodoro_db
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# H2控制台配置
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# 番茄钟配置
pomodoro.duration.minutes=25
pomodoro.break.duration.minutes=5

# 日志配置
logging.level.com.pomodoro.timer=INFO
logging.level.org.springframework.web=INFO"""
        
        # 创建配置文件
        config_path = project_path / 'src/main/resources' / 'application.properties'
        with open(config_path, 'w', encoding='utf-8') as f:
            f.write(config_content)
        
        print(f"✅ 配置文件已生成: {config_path}", flush=True)
        return config_path
    
    def _generate_api_documentation(self, project_path):
        """生成API文档"""
        print("📚 生成API文档...", flush=True)
        
        api_doc = f"""# 番茄钟后端 API 文档

## 服务信息
- **服务名称**: Pomodoro Timer Backend
- **版本**: 1.0.0
- **端口**: 8080
- **基础路径**: /api/pomodoro

## API 端点

### 1. 开始番茄钟
- **URL**: POST /api/pomodoro/start
- **描述**: 开始一个25分钟的番茄钟计时
- **请求体**: 无
- **响应**:
  ```json
  {{
    "success": true,
    "message": "番茄钟已开始计时",
    "status": {{
      "id": 1,
      "startTime": "2024-01-15T10:30:00",
      "endTime": null,
      "status": "RUNNING",
      "durationMinutes": 25
    }},
    "duration": "25分钟"
  }}
  ```

### 2. 停止番茄钟
- **URL**: POST /api/pomodoro/stop
- **描述**: 停止当前番茄钟计时
- **请求体**: 无
- **响应**:
  ```json
  {{
    "success": true,
    "message": "番茄钟已停止",
    "status": {{
      "id": 1,
      "startTime": "2024-01-15T10:30:00",
      "endTime": "2024-01-15T10:45:00",
      "status": "STOPPED",
      "durationMinutes": 15
    }}
  }}
  ```

### 3. 获取状态
- **URL**: GET /api/pomodoro/status
- **描述**: 获取当前番茄钟状态
- **响应**:
  ```json
  {{
    "success": true,
    "status": {{
      "id": 1,
      "startTime": "2024-01-15T10:30:00",
      "endTime": null,
      "status": "RUNNING",
      "durationMinutes": 25,
      "remainingMinutes": 15
    }}
  }}
  ```

### 4. 健康检查
- **URL**: GET /api/pomodoro/health
- **描述**: 检查服务健康状态
- **响应**:
  ```json
  {{
    "status": "UP",
    "service": "Pomodoro Timer Backend",
    "timestamp": "2024-01-15T10:30:00"
  }}
  ```

## 启动说明

### 开发环境
1. 确保已安装 Java 11+ 和 Maven 3.6+
2. 在项目根目录执行: `mvn spring-boot:run`
3. 访问 http://localhost:8080/api/pomodoro/health 验证服务

### 生产环境
1. 构建JAR包: `mvn clean package`
2. 运行: `java -jar target/pomodoro-timer-backend-1.0.0.jar`

## 测试示例

```bash
# 开始番茄钟
curl -X POST http://localhost:8080/api/pomodoro/start

# 停止番茄钟
curl -X POST http://localhost:8080/api/pomodoro/stop

# 获取状态
curl http://localhost:8080/api/pomodoro/status

# 健康检查
curl http://localhost:8080/api/pomodoro/health
```
"""
        
        # 创建API文档
        doc_path = project_path / 'API_DOCUMENTATION.md'
        with open(doc_path, 'w', encoding='utf-8') as f:
            f.write(api_doc)
        
        print(f"✅ API文档已生成: {doc_path}", flush=True)
        return doc_path
    
    def _create_api_config(self, project_path):
        """创建API配置信息"""
        print("🔗 创建API配置信息...", flush=True)
        
        api_config = {
            "service_name": "Pomodoro Timer Backend",
            "version": "1.0.0",
            "base_url": "http://localhost:8080",
            "api_prefix": "/api/pomodoro",
            "endpoints": [
                {
                    "name": "开始番茄钟",
                    "method": "POST",
                    "path": "/start",
                    "full_url": "http://localhost:8080/api/pomodoro/start",
                    "description": "开始一个25分钟的番茄钟计时",
                    "request_body": None,
                    "response_format": "JSON"
                },
                {
                    "name": "停止番茄钟",
                    "method": "POST",
                    "path": "/stop",
                    "full_url": "http://localhost:8080/api/pomodoro/stop",
                    "description": "停止当前番茄钟计时",
                    "request_body": None,
                    "response_format": "JSON"
                },
                {
                    "name": "获取状态",
                    "method": "GET",
                    "path": "/status",
                    "full_url": "http://localhost:8080/api/pomodoro/status",
                    "description": "获取当前番茄钟状态",
                    "request_body": None,
                    "response_format": "JSON"
                },
                {
                    "name": "健康检查",
                    "method": "GET",
                    "path": "/health",
                    "full_url": "http://localhost:8080/api/pomodoro/health",
                    "description": "检查服务健康状态",
                    "request_body": None,
                    "response_format": "JSON"
                }
            ],
            "configuration": {
                "port": 8080,
                "context_path": "/",
                "database": "H2 (内存数据库)",
                "cors_enabled": True,
                "logging_level": "INFO"
            },
            "deployment": {
                "java_version": "11+",
                "maven_version": "3.6+",
                "build_command": "mvn clean package",
                "run_command": "java -jar target/pomodoro-timer-backend-1.0.0.jar"
            }
        }
        
        print("✅ API配置信息创建完成", flush=True)
        return api_config


if __name__ == "__main__":
    # 独立测试模式
    print("🧪 开始独立测试CreateBackendAgent...", flush=True)
    
    try:
        agent = CreateBackendAgent()
        
        # 测试数据
        test_data = {
            "project_name": "pomodoro-timer-backend",
            "api_version": "1.0.0",
            "features": ["start", "stop", "status", "health"]
        }
        
        # 执行测试
        result = agent.process_data(test_data)
        
        print(f"🎉 测试完成！结果状态: {result['status']}", flush=True)
        print(f"📊 项目信息已保存到: /factory/outputs/backend_project_info.json", flush=True)
        
        # 显示部分结果
        if 'api_config' in result:
            print(f"🔗 API配置信息:", flush=True)
            for endpoint in result['api_config']['endpoints']:
                print(f"   {endpoint['method']} {endpoint['full_url']}", flush=True)
        
        sys.exit(0)
        
    except Exception as e:
        print(f"❌ 测试失败: {str(e)}", flush=True)
        sys.exit(1)