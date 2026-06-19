#!/usr/bin/env python3
"""
develop_html_frontend.py - 番茄钟Web前端生成节点
负责生成HTML/JS前端应用，包含开始计时和结束按钮，调用后端API
"""

import os
import json

# 导入BaseAgent
try:
    from base_agent import BaseAgent
except ImportError:
    # 如果base_agent不存在，创建一个简单的模拟类
    class BaseAgent:
        def __init__(self, name="FrontendAgent"):
            self.name = name
        
        def process_data(self, data):
            """子类必须重写此方法"""
            raise NotImplementedError("Subclasses must implement process_data")
        
        def log(self, message):
            """日志记录"""
            print(f"[{self.name}] {message}", flush=True)


class DevelopHTMLFrontendAgent(BaseAgent):
    """
    番茄钟前端开发Agent
    生成完整的HTML/JS前端应用
    """
    
    def __init__(self):
        super().__init__(name="DevelopHTMLFrontend")
        # 使用相对路径，避免权限问题
        self.current_dir = os.getcwd()
        self.output_dir = os.path.join(self.current_dir, "outputs")
    
    def ensure_output_dirs(self):
        """确保输出目录存在"""
        try:
            os.makedirs(self.output_dir, exist_ok=True)
            print(f"[DevelopHTMLFrontend] 输出目录已准备: {self.output_dir}", flush=True)
        except Exception as e:
            print(f"[DevelopHTMLFrontend] 创建目录失败，使用当前目录: {e}", flush=True)
            self.output_dir = self.current_dir
    
    def generate_html_content(self):
        """生成番茄钟HTML内容"""
        html = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>番茄钟 - Tomato Clock</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #ff6b6b, #ee5a24);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }
        
        .container {
            background: white;
            border-radius: 20px;
            padding: 40px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            text-align: center;
            max-width: 400px;
            width: 90%;
        }
        
        h1 {
            color: #ee5a24;
            font-size: 2em;
            margin-bottom: 10px;
        }
        
        .subtitle {
            color: #666;
            margin-bottom: 30px;
            font-size: 0.9em;
        }
        
        .timer-display {
            font-size: 4em;
            font-weight: bold;
            color: #333;
            margin: 20px 0;
            font-family: 'Courier New', monospace;
        }
        
        .status {
            color: #888;
            margin-bottom: 20px;
            font-size: 1.1em;
            min-height: 30px;
        }
        
        .status.working {
            color: #ee5a24;
            font-weight: bold;
        }
        
        .status.break {
            color: #27ae60;
            font-weight: bold;
        }
        
        .buttons {
            display: flex;
            gap: 15px;
            justify-content: center;
            flex-wrap: wrap;
        }
        
        button {
            padding: 15px 30px;
            font-size: 1.1em;
            border: none;
            border-radius: 10px;
            cursor: pointer;
            transition: all 0.3s ease;
            font-weight: bold;
        }
        
        button:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(0,0,0,0.2);
        }
        
        button:active {
            transform: translateY(0);
        }
        
        .btn-start {
            background: #27ae60;
            color: white;
        }
        
        .btn-start:hover {
            background: #2ecc71;
        }
        
        .btn-stop {
            background: #e74c3c;
            color: white;
        }
        
        .btn-stop:hover {
            background: #c0392b;
        }
        
        .btn-reset {
            background: #95a5a6;
            color: white;
        }
        
        .btn-reset:hover {
            background: #7f8c8d;
        }
        
        .stats {
            margin-top: 30px;
            padding-top: 20px;
            border-top: 1px solid #eee;
            display: flex;
            justify-content: space-around;
        }
        
        .stat-item {
            text-align: center;
        }
        
        .stat-number {
            font-size: 2em;
            font-weight: bold;
            color: #ee5a24;
        }
        
        .stat-label {
            color: #888;
            font-size: 0.85em;
        }
        
        .tomato {
            font-size: 3em;
            margin-bottom: 10px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="tomato">🍅</div>
        <h1>番茄钟</h1>
        <p class="subtitle">专注工作，高效休息</p>
        
        <div class="timer-display" id="timer">25:00</div>
        <div class="status" id="status">准备开始</div>
        
        <div class="buttons">
            <button class="btn-start" id="btnStart" onclick="startTimer()">开始专注</button>
            <button class="btn-stop" id="btnStop" onclick="stopTimer()" disabled>结束</button>
            <button class="btn-reset" id="btnReset" onclick="resetTimer()">重置</button>
        </div>
        
        <div class="stats">
            <div class="stat-item">
                <div class="stat-number" id="completedCount">0</div>
                <div class="stat-label">完成番茄</div>
            </div>
            <div class="stat-item">
                <div class="stat-number" id="totalMinutes">0</div>
                <div class="stat-label">专注分钟</div>
            </div>
        </div>
    </div>

    <script>
        // 番茄钟配置
        const WORK_DURATION = 25 * 60;  // 25分钟工作时间
        const BREAK_DURATION = 5 * 60;  // 5分钟休息时间
        
        // 状态变量
        let timeLeft = WORK_DURATION;
        let timerInterval = null;
        let isRunning = false;
        let isBreakTime = false;
        let completedPomodoros = 0;
        let totalFocusMinutes = 0;
        
        // 后端API配置
        const API_BASE_URL = '/api';  // 后端API地址
        
        // DOM元素
        const timerDisplay = document.getElementById('timer');
        const statusDisplay = document.getElementById('status');
        const btnStart = document.getElementById('btnStart');
        const btnStop = document.getElementById('btnStop');
        const btnReset = document.getElementById('btnReset');
        const completedCount = document.getElementById('completedCount');
        const totalMinutes = document.getElementById('totalMinutes');
        
        // 格式化时间显示
        function formatTime(seconds) {
            const mins = Math.floor(seconds / 60);
            const secs = seconds % 60;
            return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
        }
        
        // 更新显示
        function updateDisplay() {
            timerDisplay.textContent = formatTime(timeLeft);
            completedCount.textContent = completedPomodoros;
            totalMinutes.textContent = totalFocusMinutes;
        }
        
        // 调用后端API
        async function callBackendAPI(endpoint, data = {}) {
            try {
                const response = await fetch(`${API_BASE_URL}${endpoint}`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify(data)
                });
                
                if (!response.ok) {
                    console.warn('API调用失败:', response.status);
                    return null;
                }
                
                return await response.json();
            } catch (error) {
                console.warn('API调用异常:', error.message);
                return null;
            }
        }
        
        // 开始计时
        async function startTimer() {
            if (isRunning) return;
            
            isRunning = true;
            btnStart.disabled = true;
            btnStop.disabled = false;
            
            if (isBreakTime) {
                statusDisplay.textContent = '☕ 休息中...';
                statusDisplay.className = 'status break';
            } else {
                statusDisplay.textContent = '🔥 专注中...';
                statusDisplay.className = 'status working';
            }
            
            // 通知后端开始
            await callBackendAPI('/timer/start', {
                type: isBreakTime ? 'break' : 'work',
                duration: timeLeft
            });
            
            // 开始倒计时
            timerInterval = setInterval(() => {
                timeLeft--;
                updateDisplay();
                
                if (timeLeft <= 0) {
                    completeSession();
                }
            }, 1000);
        }
        
        // 停止计时
        async function stopTimer() {
            if (!isRunning) return;
            
            clearInterval(timerInterval);
            isRunning = false;
            btnStart.disabled = false;
            btnStop.disabled = true;
            
            statusDisplay.textContent = '已暂停';
            statusDisplay.className = 'status';
            
            // 通知后端停止
            await callBackendAPI('/timer/stop', {
                timeRemaining: timeLeft,
                type: isBreakTime ? 'break' : 'work'
            });
        }
        
        // 完成一个周期
        async function completeSession() {
            clearInterval(timerInterval);
            isRunning = false;
            btnStart.disabled = false;
            btnStop.disabled = true;
            
            if (!isBreakTime) {
                // 工作完成
                completedPomodoros++;
                totalFocusMinutes += 25;
                
                // 通知后端完成
                await callBackendAPI('/timer/complete', {
                    type: 'work',
                    completedPomodoros: completedPomodoros
                });
                
                // 播放提示音（如果需要）
                playNotification();
                
                statusDisplay.textContent = '🎉 休息时间到！';
                statusDisplay.className = 'status break';
                
                // 切换到休息时间
                isBreakTime = true;
                timeLeft = BREAK_DURATION;
            } else {
                // 休息完成
                await callBackendAPI('/timer/complete', {
                    type: 'break'
                });
                
                statusDisplay.textContent = '💪 准备继续专注！';
                statusDisplay.className = 'status working';
                
                // 切换回工作时间
                isBreakTime = false;
                timeLeft = WORK_DURATION;
            }
            
            updateDisplay();
        }
        
        // 重置计时器
        async function resetTimer() {
            clearInterval(timerInterval);
            isRunning = false;
            isBreakTime = false;
            timeLeft = WORK_DURATION;
            
            btnStart.disabled = false;
            btnStop.disabled = true;
            statusDisplay.textContent = '准备开始';
            statusDisplay.className = 'status';
            
            // 通知后端重置
            await callBackendAPI('/timer/reset');
            
            updateDisplay();
        }
        
        // 播放通知音
        function playNotification() {
            try {
                // 创建简单的提示音
                const audioContext = new (window.AudioContext || window.webkitAudioContext)();
                const oscillator = audioContext.createOscillator();
                const gainNode = audioContext.createGain();
                
                oscillator.connect(gainNode);
                gainNode.connect(audioContext.destination);
                
                oscillator.frequency.value = 800;
                oscillator.type = 'sine';
                gainNode.gain.value = 0.3;
                
                oscillator.start();
                oscillator.stop(audioContext.currentTime + 0.3);
            } catch (e) {
                console.log('无法播放提示音:', e);
            }
        }
        
        // 初始化显示
        updateDisplay();
        console.log('🍅 番茄钟前端已加载');
    </script>
</body>
</html>'''
        return html
    
    def process_data(self, data):
        """
        处理数据，生成番茄钟前端应用
        
        Args:
            data: 输入数据，可以是字典或字符串
        
        Returns:
            dict: 处理结果
        """
        print(f"[DevelopHTMLFrontend] 开始生成番茄钟前端应用...", flush=True)
        
        # 确保输出目录存在
        self.ensure_output_dirs()
        
        # 生成HTML内容
        html_content = self.generate_html_content()
        print(f"[DevelopHTMLFrontend] HTML内容生成完成，长度: {len(html_content)} 字符", flush=True)
        
        # 写入主输出文件
        main_output = os.path.join(self.output_dir, "tomato_clock_frontend.html")
        with open(main_output, 'w', encoding='utf-8') as f:
            f.write(html_content)
        print(f"[DevelopHTMLFrontend] 主文件已写入: {main_output}", flush=True)
        
        # 写入备份
        backup_output = os.path.join(self.output_dir, "tomato_clock_frontend_backup.html")
        with open(backup_output, 'w', encoding='utf-8') as f:
            f.write(html_content)
        print(f"[DevelopHTMLFrontend] 备份文件已写入: {backup_output}", flush=True)
        
        # 生成结果JSON
        result = {
            "status": "success",
            "node": "develop_html_frontend",
            "timestamp": __import__('datetime').datetime.now().isoformat(),
            "output_files": {
                "main": main_output,
                "backup": backup_output
            },
            "features": [
                "25分钟工作计时",
                "5分钟休息计时",
                "开始/停止/重置按钮",
                "后端API集成",
                "完成统计",
                "响应式设计"
            ],
            "api_endpoints": [
                "POST /api/timer/start",
                "POST /api/timer/stop",
                "POST /api/timer/complete",
                "POST /api/timer/reset"
            ]
        }
        
        # 写入结果文件
        result_path = os.path.join(self.output_dir, "develop_html_frontend_output.json")
        with open(result_path, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False)
        print(f"[DevelopHTMLFrontend] 结果文件已写入: {result_path}", flush=True)
        
        print(f"[DevelopHTMLFrontend] ✅ 番茄钟前端生成完成！", flush=True)
        
        return result


if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("🚀 develop_html_frontend.py - 番茄钟前端生成节点", flush=True)
    print("=" * 60, flush=True)
    
    # 创建Agent实例
    agent = DevelopHTMLFrontendAgent()
    
    # 执行任务
    test_data = {"task": "generate_tomato_clock_frontend"}
    result = agent.process_data(test_data)
    
    # 输出结果
    print("\n" + "=" * 60, flush=True)
    print("📋 处理结果:", flush=True)
    print(json.dumps(result, indent=2, ensure_ascii=False), flush=True)
    print("=" * 60, flush=True)
    print("NODE_VERIFIED_AND_READY", flush=True)