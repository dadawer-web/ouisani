#!/usr/bin/env python3
# AIOS Node: create_frontend_files
# 职责: 创建前端项目目录及基础HTML/JS文件结构
# 角色: Python Coder (防御性编程、强类型传参、I/O冲刷铁律)

import os
import sys
from pathlib import Path

# requires: 无 (使用标准库)

class BaseAgent:
    """基础代理类，AIOS节点必须继承此类"""
    
    def process_data(self, data):
        """处理数据的核心方法，子类必须重写"""
        raise NotImplementedError("子类必须实现process_data方法")
    
    def run(self):
        """运行代理的入口方法"""
        try:
            print(f"AGENT_START: {self.__class__.__name__}", flush=True)
            result = self.process_data({})
            print(f"AGENT_SUCCESS: {self.__class__.__name__}", flush=True)
            return result
        except Exception as e:
            print(f"AGENT_FAILED: {self.__class__.__name__} - {str(e)}", flush=True)
            sys.exit(1)

class CreateFrontendFiles(BaseAgent):
    """创建前端文件的代理"""
    
    def process_data(self, data):
        """创建前端项目目录及基础HTML/JS文件结构"""
        
        print("FRONTEND_START: 开始创建前端项目结构", flush=True)
        
        # 定义路径
        frontend_dir = "/factory/frontend"
        output_path = "/factory/outputs/create_frontend_files_output.json"
        
        try:
            # 创建前端目录
            print(f"FRONTEND_STEP1: 创建目录 {frontend_dir}", flush=True)
            Path(frontend_dir).mkdir(parents=True, exist_ok=True)
            print(f"FRONTEND_STEP1_DONE: 目录创建成功", flush=True)
            
            # 创建HTML文件
            print("FRONTEND_STEP2: 创建 index.html", flush=True)
            html_content = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>番茄钟</title>
    <link rel="stylesheet" href="styles.css">
</head>
<body>
    <div class="container">
        <h1>🍅 番茄钟</h1>
        <div class="timer-display">
            <div class="time" id="time-display">25:00</div>
            <div class="status" id="status">准备开始</div>
        </div>
        <div class="controls">
            <button id="start-btn" class="btn start">开始</button>
            <button id="pause-btn" class="btn pause" disabled>暂停</button>
            <button id="reset-btn" class="btn reset">重置</button>
        </div>
        <div class="cycle-info">
            <p>当前周期: <span id="cycle-count">1</span>/4</p>
            <p>工作时间: <span id="work-time">25</span>分钟</p>
            <p>休息时间: <span id="break-time">5</span>分钟</p>
        </div>
        <div class="log">
            <h3>活动日志</h3>
            <div id="log-content"></div>
        </div>
    </div>
    <script src="script.js"></script>
</body>
</html>"""
            
            # 创建CSS文件
            print("FRONTEND_STEP3: 创建 styles.css", flush=True)
            css_content = """* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

body {
    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    min-height: 100vh;
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 20px;
}

.container {
    background: white;
    border-radius: 20px;
    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
    padding: 40px;
    text-align: center;
    max-width: 500px;
    width: 100%;
}

h1 {
    color: #333;
    margin-bottom: 30px;
    font-size: 2.5em;
}

.timer-display {
    margin-bottom: 40px;
}

.time {
    font-size: 4em;
    font-weight: bold;
    color: #e74c3c;
    text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
    margin-bottom: 10px;
}

.status {
    font-size: 1.2em;
    color: #666;
    text-transform: uppercase;
    letter-spacing: 2px;
}

.controls {
    display: flex;
    justify-content: center;
    gap: 15px;
    margin-bottom: 30px;
}

.btn {
    padding: 12px 30px;
    border: none;
    border-radius: 50px;
    font-size: 1.1em;
    font-weight: bold;
    cursor: pointer;
    transition: all 0.3s ease;
    min-width: 100px;
}

.btn:hover {
    transform: translateY(-3px);
    box-shadow: 0 10px 20px rgba(0, 0, 0, 0.2);
}

.btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    transform: none;
    box-shadow: none;
}

.start {
    background: linear-gradient(135deg, #00b894, #00cec9);
    color: white;
}

.pause {
    background: linear-gradient(135deg, #fdcb6e, #e17055);
    color: white;
}

.reset {
    background: linear-gradient(135deg, #a29bfe, #6c5ce7);
    color: white;
}

.cycle-info {
    background: #f8f9fa;
    border-radius: 10px;
    padding: 20px;
    margin-bottom: 30px;
    border-left: 5px solid #667eea;
}

.cycle-info p {
    margin: 8px 0;
    color: #555;
    font-size: 1em;
}

.cycle-info span {
    font-weight: bold;
    color: #333;
}

.log {
    background: #f8f9fa;
    border-radius: 10px;
    padding: 20px;
    text-align: left;
    max-height: 200px;
    overflow-y: auto;
}

.log h3 {
    color: #333;
    margin-bottom: 15px;
    border-bottom: 2px solid #667eea;
    padding-bottom: 10px;
}

#log-content {
    font-family: monospace;
    font-size: 0.9em;
    color: #555;
    line-height: 1.6;
}

.log-entry {
    padding: 5px 0;
    border-bottom: 1px solid #eee;
}

.log-entry:last-child {
    border-bottom: none;
}"""
            
            # 创建JavaScript文件
            print("FRONTEND_STEP4: 创建 script.js", flush=True)
            js_content = """// 番茄钟应用
class TomatoClock {
    constructor() {
        // 时间设置（分钟）
        this.workMinutes = 25;
        this.breakMinutes = 5;
        this.cycles = 4;
        
        // 状态变量
        this.isRunning = false;
        this.isPaused = false;
        this.currentCycle = 1;
        this.isBreakTime = false;
        this.timeRemaining = this.workMinutes * 60; // 转换为秒
        this.timerInterval = null;
        
        // DOM元素
        this.timeDisplay = document.getElementById('time-display');
        this.statusDisplay = document.getElementById('status');
        this.startBtn = document.getElementById('start-btn');
        this.pauseBtn = document.getElementById('pause-btn');
        this.resetBtn = document.getElementById('reset-btn');
        this.cycleCount = document.getElementById('cycle-count');
        this.workTimeDisplay = document.getElementById('work-time');
        this.breakTimeDisplay = document.getElementById('break-time');
        this.logContent = document.getElementById('log-content');
        
        // 初始化显示
        this.updateDisplay();
        this.workTimeDisplay.textContent = this.workMinutes;
        this.breakTimeDisplay.textContent = this.breakMinutes;
        
        // 绑定事件
        this.bindEvents();
        
        // 添加日志
        this.addLog('番茄钟应用已启动');
    }
    
    bindEvents() {
        this.startBtn.addEventListener('click', () => this.start());
        this.pauseBtn.addEventListener('click', () => this.pause());
        this.resetBtn.addEventListener('click', () => this.reset());
    }
    
    start() {
        if (this.isRunning && !this.isPaused) return;
        
        if (this.isPaused) {
            // 继续
            this.isPaused = false;
            this.addLog('继续计时');
        } else {
            // 开始
            this.isRunning = true;
            this.addLog(`开始第 ${this.currentCycle} 个番茄钟`);
        }
        
        this.updateButtons();
        this.statusDisplay.textContent = this.isBreakTime ? '休息中...' : '工作中...';
        
        this.timerInterval = setInterval(() => {
            this.timeRemaining--;
            
            if (this.timeRemaining <= 0) {
                this.timerComplete();
            }
            
            this.updateDisplay();
        }, 1000);
    }
    
    pause() {
        if (!this.isRunning || this.isPaused) return;
        
        this.isPaused = true;
        clearInterval(this.timerInterval);
        
        this.statusDisplay.textContent = '已暂停';
        this.addLog('计时已暂停');
        
        this.updateButtons();
    }
    
    reset() {
        clearInterval(this.timerInterval);
        
        this.isRunning = false;
        this.isPaused = false;
        this.currentCycle = 1;
        this.isBreakTime = false;
        this.timeRemaining = this.workMinutes * 60;
        
        this.updateDisplay();
        this.statusDisplay.textContent = '准备开始';
        this.addLog('番茄钟已重置');
        
        this.updateButtons();
    }
    
    timerComplete() {
        clearInterval(this.timerInterval);
        
        if (this.isBreakTime) {
            // 休息结束，开始工作
            this.isBreakTime = false;
            this.timeRemaining = this.workMinutes * 60;
            this.addLog(`休息结束，开始第 ${this.currentCycle} 个工作周期`);
            this.statusDisplay.textContent = '准备开始工作';
        } else {
            // 工作结束，开始休息
            this.addLog(`第 ${this.currentCycle} 个工作周期完成！`);
            
            if (this.currentCycle >= this.cycles) {
                // 所有周期完成
                this.addLog('🎉 恭喜！所有番茄钟周期完成！');
                this.reset();
                return;
            }
            
            this.isBreakTime = true;
            this.timeRemaining = this.breakMinutes * 60;
            this.currentCycle++;
            this.cycleCount.textContent = this.currentCycle;
            this.addLog(`开始休息时间（第 ${this.currentCycle - 1} 个番茄钟后）`);
            this.statusDisplay.textContent = '休息中...';
        }
        
        this.isRunning = false;
        this.updateButtons();
        
        // 自动开始下一个周期
        setTimeout(() => {
            if (!this.isRunning) {
                this.start();
            }
        }, 1000);
    }
    
    updateDisplay() {
        const minutes = Math.floor(this.timeRemaining / 60);
        const seconds = this.timeRemaining % 60;
        
        this.timeDisplay.textContent = 
            `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
        
        // 更新页面标题
        document.title = `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')} - 番茄钟`;
    }
    
    updateButtons() {
        this.startBtn.disabled = this.isRunning && !this.isPaused;
        this.pauseBtn.disabled = !this.isRunning || this.isPaused;
        
        this.startBtn.textContent = this.isPaused ? '继续' : '开始';
    }
    
    addLog(message) {
        const timestamp = new Date().toLocaleTimeString('zh-CN');
        const logEntry = document.createElement('div');
        logEntry.className = 'log-entry';
        logEntry.innerHTML = `<strong>[${timestamp}]</strong> ${message}`;
        
        this.logContent.insertBefore(logEntry, this.logContent.firstChild);
        
        // 限制日志条目数量
        if (this.logContent.children.length > 50) {
            this.logContent.removeChild(this.logContent.lastChild);
        }
    }
}

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    const app = new TomatoClock();
    console.log('番茄钟应用初始化完成');
});"""
            
            # 写入文件
            print("FRONTEND_STEP5: 写入文件到VFS", flush=True)
            
            # 使用绝对路径写入文件
            with open(f"{frontend_dir}/index.html", "w", encoding="utf-8") as f:
                f.write(html_content)
                
            with open(f"{frontend_dir}/styles.css", "w", encoding="utf-8") as f:
                f.write(css_content)
                
            with open(f"{frontend_dir}/script.js", "w", encoding="utf-8") as f:
                f.write(js_content)
            
            print(f"FRONTEND_FILES_CREATED: 所有前端文件已创建到 {frontend_dir}", flush=True)
            
            # 创建输出目录
            os.makedirs("/factory/outputs", exist_ok=True)
            
            # 保存结果到输出文件
            import json
            result = {
                "status": "success",
                "message": "前端项目结构创建成功",
                "created_files": [
                    f"{frontend_dir}/index.html",
                    f"{frontend_dir}/styles.css",
                    f"{frontend_dir}/script.js"
                ],
                "directory_structure": {
                    "frontend": frontend_dir,
                    "files_created": 3
                },
                "project_info": {
                    "name": "番茄钟Web应用",
                    "description": "一个简单的番茄钟应用，包含25分钟工作、5分钟休息的标准番茄钟周期",
                    "features": ["计时器显示", "开始/暂停/重置", "周期循环", "活动日志"]
                }
            }
            
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            
            print(f"FRONTEND_OUTPUT_SAVED: 结果已保存到 {output_path}", flush=True)
            print("CREATE_FRONTEND_FILES_SUCCESS: 前端文件创建节点执行成功", flush=True)
            
            return result
            
        except Exception as e:
            print(f"CREATE_FRONTEND_FILES_ERROR: {str(e)}", flush=True)
            raise

if __name__ == "__main__":
    # 创建代理实例并运行
    agent = CreateFrontendFiles()
    result = agent.run()
    
    # 输出最终结果
    print(f"FINAL_RESULT: {result}", flush=True)
    print("NODE_VERIFIED_AND_READY", flush=True)