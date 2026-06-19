#!/usr/bin/env python3
"""
Spring Boot Project Initializer Agent
Creates basic Spring Boot project structure with pom.xml and main class.
"""
import os
import json
import subprocess
from pathlib import Path

# Try to import BaseAgent if available
try:
    from base_agent import BaseAgent
except ImportError:
    # If not available, create a simple base class
    class BaseAgent:
        def __init__(self):
            pass
        
        def process_data(self, data):
            """Process input data and return result"""
            raise NotImplementedError("Subclasses must implement process_data")
        
        def run(self):
            """Run the agent with input data"""
            # In a real system, this would get data from input
            return self.process_data({})


class CreateBackendProject(BaseAgent):
    def __init__(self):
        super().__init__()
        self.project_name = "pomodoro-clock"
        self.base_package = "com.example.pomodoro"
        self.java_version = "17"
        self.spring_boot_version = "3.2.0"
        
    def process_data(self, data):
        """Create Spring Boot project structure"""
        print("SPRING_BOOT_PROJECT_INIT: Starting to create project structure...", flush=True)
        
        # Define paths
        workspace_root = Path("/factory")
        project_root = workspace_root / self.project_name
        
        # Create project directory structure
        directories = [
            project_root,
            project_root / "src" / "main" / "java" / "com" / "example" / "pomodoro",
            project_root / "src" / "main" / "resources",
            project_root / "src" / "test" / "java" / "com" / "example" / "pomodoro",
        ]
        
        for dir_path in directories:
            dir_path.mkdir(parents=True, exist_ok=True)
            print(f"Created directory: {dir_path}", flush=True)
        
        # Create pom.xml
        pom_xml_content = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>{self.spring_boot_version}</version>
        <relativePath/>
    </parent>
    
    <groupId>com.example</groupId>
    <artifactId>{self.project_name}</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>{self.project_name}</name>
    <description>A simple Pomodoro Clock Web Application</description>
    
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
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
'''
        
        pom_xml_path = project_root / "pom.xml"
        with open(pom_xml_path, 'w', encoding='utf-8') as f:
            f.write(pom_xml_content)
        print(f"Created pom.xml: {pom_xml_path}", flush=True)
        
        # Create main application class
        main_class_content = f'''package {self.base_package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class PomodoroClockApplication {{

    public static void main(String[] args) {{
        SpringApplication.run(PomodoroClockApplication.class, args);
        System.out.println("Pomodoro Clock application started successfully!");
    }}
    
    @GetMapping("/")
    public String index() {{
        return "Pomodoro Clock is running!";
    }}
}}
'''
        
        main_class_path = project_root / "src" / "main" / "java" / "com" / "example" / "pomodoro" / "PomodoroClockApplication.java"
        with open(main_class_path, 'w', encoding='utf-8') as f:
            f.write(main_class_content)
        print(f"Created main application class: {main_class_path}", flush=True)
        
        # Create application.properties
        properties_content = f'''# Application Name
spring.application.name={self.project_name}

# Server Configuration
server.port=8080

# Spring Boot Configuration
spring.thymeleaf.cache=false
'''
        
        properties_path = project_root / "src" / "main" / "resources" / "application.properties"
        with open(properties_path, 'w', encoding='utf-8') as f:
            f.write(properties_content)
        print(f"Created application.properties: {properties_path}", flush=True)
        
        # Create a simple test class
        test_class_content = f'''package {self.base_package};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PomodoroClockApplicationTests {{

    @Test
    void contextLoads() {{
        // Test that the application context loads
    }}
}}
'''
        
        test_class_path = project_root / "src" / "test" / "java" / "com" / "example" / "pomodoro" / "PomodoroClockApplicationTests.java"
        with open(test_class_path, 'w', encoding='utf-8') as f:
            f.write(test_class_content)
        print(f"Created test class: {test_class_path}", flush=True)
        
        # Create output directory
        output_dir = workspace_root / "outputs"
        output_dir.mkdir(exist_ok=True)
        
        # Create result file
        result = {
            "status": "success",
            "project_name": self.project_name,
            "project_root": str(project_root),
            "main_class": f"{self.base_package}.PomodoroClockApplication",
            "created_files": [
                str(pom_xml_path),
                str(main_class_path),
                str(properties_path),
                str(test_class_path)
            ],
            "next_steps": [
                "Navigate to project directory",
                "Run: ./mvnw spring-boot:run (or mvn spring-boot:run if Maven is installed)",
                "Access http://localhost:8080"
            ]
        }
        
        result_path = output_dir / "create_backend_project_output.json"
        with open(result_path, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False)
        
        print(f"\\nRESULT_FILE: {result_path}", flush=True)
        print("CREATE_BACKEND_PROJECT_SUCCESS: Spring Boot project structure created successfully!", flush=True)
        print("OUTPUT_FILE: /factory/outputs/create_backend_project_output.json", flush=True)
        
        return result


def main():
    """Main entry point"""
    print("AGENT START: CreateBackendProject", flush=True)
    
    # Check if BaseAgent is available
    if 'BaseAgent' in globals():
        # Use proper BaseAgent inheritance
        agent = CreateBackendProject()
        result = agent.run()
    else:
        # Fallback: run directly
        agent = CreateBackendProject()
        result = agent.process_data({})
    
    print("\\n" + "="*50, flush=True)
    print("SPRING BOOT PROJECT INITIALIZATION COMPLETE", flush=True)
    print("="*50, flush=True)
    print(f"Project created at: /factory/{agent.project_name}", flush=True)
    print(f"Main class: {agent.base_package}.PomodoroClockApplication", flush=True)
    print("\\nTo run the application:", flush=True)
    print(f"  cd /factory/{agent.project_name}", flush=True)
    print("  # If you have Maven installed:", flush=True)
    print("  mvn spring-boot:run", flush=True)
    print("  # Or using Maven Wrapper:", flush=True)
    print("  ./mvnw spring-boot:run", flush=True)
    print("\\nApplication will be available at: http://localhost:8080", flush=True)


if __name__ == "__main__":
    main()