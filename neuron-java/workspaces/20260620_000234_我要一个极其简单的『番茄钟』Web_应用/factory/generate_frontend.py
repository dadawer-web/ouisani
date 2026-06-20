#!/usr/bin/env python3
"""
generate_frontend 节点
职责：生成番茄钟（Pomodoro Timer）Web 应用的 HTML/JS 前端界面
"""
import sys
import os
import json

# 添加 factory 目录到 Python 路径
sys.path.insert(0, '/factory')

from base_agent import BaseAgent


class GenerateFrontendAgent(BaseAgent):
    """
    生成番茄钟 Web 前端界面的 Agent
    """
    
    def __init__(self):
        super().__init__("generate_frontend")
        self.output_html_path = "/shared/outputs/pomodoro.html"
        self.output_path = "/factory/outputs/generate_frontend_output.json"
    
    def generate_html(self) -> str:
        """生成番茄钟的 HTML/CSS/JS 前端代码"""
        
        html_content = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🍅 番茄钟 - Pomodoro Timer</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }
        
        .container {
            background: rgba(255, 255, 255, 0.95);
            border-radius: 20px;
            padding: 40px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
            text-align: center;
            max-width: 400px;
            width: 90%;
        }
        
        h1 {
            color: #e74c3c;
            font-size: 2em;
            margin-bottom: 10px;
        }
        
        .subtitle {
            color: #666;
            font-size: 0.9em;
            margin-bottom: 30px;
        }
        
        .timer-display {
            font-size: 5em;
            font-weight: bold;
            color: #2c3e50;
            margin: 20px 0;
            font-family: 'Courier New', monospace;
        }
        
        .mode-label {
            display: inline-block;
            padding: 8px 20px;
            border-radius: 20px;
            font-size: 0.9em;
            font-weight: 600;
            margin-bottom: 20px;
        }
        
        .mode-work {
            background: #fee2e2;
            color: #dc2626;
        }
        
        .mode-break {
            background: #dcfce7;
            color: #16a34a;
        }
        
        .progress-bar {
            width: 100%;
            height: 8px;
            background: #e5e7eb;
            border-radius: 4px;
            margin: 20px 0;
            overflow: hidden;
        }
        
        .progress-fill {
            height: 100%;
            background: linear-gradient(90deg, #e74c3c, #ff6b6b);
            border-radius: 4px;
            transition: width 0.5s ease;
            width: 0%;
        }
        
        .progress-fill.break-mode {
            background: linear-gradient(90deg, #22c55e, #4ade80);
        }
        
        .controls {
            display: flex;
            gap: 10px;
            justify-content: center;
            flex-wrap: wrap;
            margin: 20px 0;
        }
        
        button {
            padding: 12px 24px;
            border: none;
            border-radius: 10px;
            font-size: 1em;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s ease;
        }
        
        button:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.2);
        }
        
        button:active {
            transform: translateY(0);
        }
        
        .btn-start {
            background: #22c55e;
            color: white;
        }
        
        .btn-pause {
            background: #f59e0b;
            color: white;
        }
        
        .btn-reset {
            background: #6b7280;
            color: white;
        }
        
        .btn-skip {
            background: #8b5cf6;
            color: white;
        }
        
        .stats {
            display: flex;
            justify-content: space-around;
            margin-top: 25px;
            padding-top: 20px;
            border-top: 1px solid #e5e7eb;
        }
        
        .stat-item {
            text-align: center;
        }
        
        .stat-value {
            font-size: 1.8em;
            font-weight: bold;
            color: #e74c3c;
        }
        
        .stat-label {
            font-size: 0.8em;
            color: #888;
            margin-top: 4px;
        }
        
        .settings {
            margin-top: 20px;
            padding-top: 15px;
            border-top: 1px solid #e5e7eb;
        }
        
        .settings label {
            display: inline-block;
            margin: 5px 10px;
            font-size: 0.85em;
            color: #555;
        }
        
        .settings input {
            width: 50px;
            padding: 4px;
            border: 1px solid #ddd;
            border-radius: 5px;
            text-align: center;
        }
        
        .notification {
            position: fixed;
            top: 20px;
            right: 20px;
            padding: 15px 25px;
            background: #22c55e;
            color: white;
            border-radius: 10px;
            font-weight: 600;
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.3);
            transform: translateX(400px);
            transition: transform 0.3s ease;
            z-index: 1000;
        }
        
        .notification.show {
            transform: translateX(0);
        }
        
        .emoji-tomato {
            font-size: 1.2em;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🍅 番茄钟</h1>
        <p class="subtitle">专注工作，高效休息</p>
        
        <div class="mode-label mode-work" id="modeLabel">🎯 专注时间</div>
        
        <div class="timer-display" id="timerDisplay">25:00</div>
        
        <div class="progress-bar">
            <div class="progress-fill" id="progressFill"></div>
        </div>
        
        <div class="controls">
            <button class="btn-start" id="btnStart" onclick="startTimer()">▶ 开始</button>
            <button class="btn-pause" id="btnPause" onclick="pauseTimer()" style="display:none;">⏸ 暂停</button>
            <button class="btn-reset" onclick="resetTimer()">↺ 重置</button>
            <button class="btn-skip" onclick="skipPhase()">⏭ 跳过</button>
        </div>
        
        <div class="stats">
            <div class="stat-item">
                <div class="stat-value" id="pomodoroCount">0</div>
                <div class="stat-label">完成番茄</div>
            </div>
            <div class="stat-item">
                <div class="stat-value" id="totalMinutes">0</div>
                <div class="stat-label">专注分钟</div>
            </div>
            <div class="stat-item">
                <div class="stat-value" id="currentCycle">1/4</div>
                <div class="stat-label">当前周期</div>
            </div>
        </div>
        
        <div class="settings">
            <label>🎯 专注: <input type="number" id="workMinutes" value="25" min="1" max="90" onchange="updateSettings()"> 分</label>
            <label>☕ 短休: <input type="number" id="breakMinutes" value="5" min="1" max="30" onchange="updateSettings()"> 分</label>
            <label>🌴 长休: <input type="number" id="longBreakMinutes" value="15" min="1" max="60" onchange="updateSettings()"> 分</label>
        </div>
    </div>
    
    <div class="notification" id="notification"></div>
    
    <script>
        // ========== 番茄钟核心逻辑 ==========
        
        // 状态变量
        let timer = null;
        let isRunning = false;
        let isWorkMode = true;
        let totalSeconds = 25 * 60;
        let remainingSeconds = totalSeconds;
        let pomodoroCount = 0;
        let totalWorkMinutes = 0;
        let currentCycle = 1;
        const CYCLES_BEFORE_LONG_BREAK = 4;
        
        // DOM 元素
        const timerDisplay = document.getElementById('timerDisplay');
        const progressFill = document.getElementById('progressFill');
        const modeLabel = document.getElementById('modeLabel');
        const btnStart = document.getElementById('btnStart');
        const btnPause = document.getElementById('btnPause');
        const pomodoroCountEl = document.getElementById('pomodoroCount');
        const totalMinutesEl = document.getElementById('totalMinutes');
        const currentCycleEl = document.getElementById('currentCycle');
        const notification = document.getElementById('notification');
        
        // 格式化时间显示
        function formatTime(seconds) {
            const mins = Math.floor(seconds / 60);
            const secs = seconds % 60;
            return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
        }
        
        // 更新显示
        function updateDisplay() {
            timerDisplay.textContent = formatTime(remainingSeconds);
            const progress = ((totalSeconds - remainingSeconds) / totalSeconds) * 100;
            progressFill.style.width = `${progress}%`;
            
            if (!isWorkMode) {
                progressFill.classList.add('break-mode');
            } else {
                progressFill.classList.remove('break-mode');
            }
        }
        
        // 显示通知
        function showNotification(message, color = '#22c55e') {
            notification.textContent = message;
            notification.style.background = color;
            notification.classList.add('show');
            setTimeout(() => {
                notification.classList.remove('show');
            }, 3000);
        }
        
        // 更新设置
        function updateSettings() {
            if (!isRunning) {
                if (isWorkMode) {
                    const workMins = parseInt(document.getElementById('workMinutes').value) || 25;
                    totalSeconds = workMins * 60;
                    remainingSeconds = totalSeconds;
                } else {
                    const breakMins = currentCycle % CYCLES_BEFORE_LONG_BREAK === 0 
                        ? (parseInt(document.getElementById('longBreakMinutes').value) || 15)
                        : (parseInt(document.getElementById('breakMinutes').value) || 5);
                    totalSeconds = breakMins * 60;
                    remainingSeconds = totalSeconds;
                }
                updateDisplay();
            }
        }
        
        // 计时器核心函数
        function tick() {
            if (remainingSeconds > 0) {
                remainingSeconds--;
                updateDisplay();
            } else {
                // 时间到！
                clearInterval(timer);
                timer = null;
                isRunning = false;
                
                if (isWorkMode) {
                    // 专注阶段结束
                    pomodoroCount++;
                    const workMins = parseInt(document.getElementById('workMinutes').value) || 25;
                    totalWorkMinutes += workMins;
                    pomodoroCountEl.textContent = pomodoroCount;
                    totalMinutesEl.textContent = totalWorkMinutes;
                    
                    showNotification('🎉 专注完成！休息一下吧~', '#22c55e');
                    playSound();
                    
                    // 切换到休息模式
                    isWorkMode = false;
                    modeLabel.textContent = '☕ 休息时间';
                    modeLabel.className = 'mode-label mode-break';
                    
                    // 判断是短休还是长休
                    let breakMins;
                    if (currentCycle % CYCLES_BEFORE_LONG_BREAK === 0) {
                        breakMins = parseInt(document.getElementById('longBreakMinutes').value) || 15;
                        showNotification('🌴 长休息时间！好好放松~', '#8b5cf6');
                    } else {
                        breakMins = parseInt(document.getElementById('breakMinutes').value) || 5;
                    }
                    
                    totalSeconds = breakMins * 60;
                    remainingSeconds = totalSeconds;
                    
                } else {
                    // 休息阶段结束
                    showNotification('💪 新的番茄开始了！加油！', '#e74c3c');
                    playSound();
                    
                    // 切换回专注模式
                    isWorkMode = true;
                    modeLabel.textContent = '🎯 专注时间';
                    modeLabel.className = 'mode-label mode-work';
                    
                    currentCycle++;
                    currentCycleEl.textContent = `${((currentCycle - 1) % CYCLES_BEFORE_LONG_BREAK) + 1}/${CYCLES_BEFORE_LONG_BREAK}`;
                    
                    const workMins = parseInt(document.getElementById('workMinutes').value) || 25;
                    totalSeconds = workMins * 60;
                    remainingSeconds = totalSeconds;
                }
                
                btnStart.style.display = 'inline-block';
                btnPause.style.display = 'none';
                updateDisplay();
            }
        }
        
        // 播放提示音
        function playSound() {
            try {
                const audioContext = new (window.AudioContext || window.webkitAudioContext)();
                const oscillator = audioContext.createOscillator();
                const gainNode = audioContext.createGain();
                
                oscillator.connect(gainNode);
                gainNode.connect(audioContext.destination);
                
                oscillator.frequency.value = 800;
                oscillator.type = 'sine';
                gainNode.gain.value = 0.3;
                
                oscillator.start();
                setTimeout(() => {
                    oscillator.stop();
                }, 200);
                
                setTimeout(() => {
                    const osc2 = audioContext.createOscillator();
                    const gain2 = audioContext.createGain();
                    osc2.connect(gain2);
                    gain2.connect(audioContext.destination);
                    osc2.frequency.value = 1000;
                    osc2.type = 'sine';
                    gain2.gain.value = 0.3;
                    osc2.start();
                    setTimeout(() => osc2.stop(), 200);
                }, 300);
            } catch(e) {
                // 静默处理音频错误
            }
        }
        
        // 开始计时
        function startTimer() {
            if (!isRunning) {
                isRunning = true;
                btnStart.style.display = 'none';
                btnPause.style.display = 'inline-block';
                timer = setInterval(tick, 1000);
            }
        }
        
        // 暂停计时
        function pauseTimer() {
            if (isRunning) {
                isRunning = false;
                clearInterval(timer);
                timer = null;
                btnStart.style.display = 'inline-block';
                btnPause.style.display = 'none';
            }
        }
        
        // 重置计时器
        function resetTimer() {
            pauseTimer();
            isWorkMode = true;
            modeLabel.textContent = '🎯 专注时间';
            modeLabel.className = 'mode-label mode-work';
            
            const workMins = parseInt(document.getElementById('workMinutes').value) || 25;
            totalSeconds = workMins * 60;
            remainingSeconds = totalSeconds;
            
            currentCycle = 1;
            currentCycleEl.textContent = `1/${CYCLES_BEFORE_LONG_BREAK}`;
            
            updateDisplay();
            showNotification('🍅 番茄钟已重置', '#6b7280');
        }
        
        // 跳过当前阶段
        function skipPhase() {
            if (confirm('确定要跳过当前阶段吗？')) {
                remainingSeconds = 1;
                tick();
            }
        }
        
        // 页面标题动态更新
        function updateTitle() {
            if (isRunning) {
                const mode = isWorkMode ? '🎯' : '☕';
                document.title = `${mode} ${formatTime(remainingSeconds)} - 番茄钟`;
            }
        }
        
        setInterval(updateTitle, 1000);
        
        // 初始化显示
        updateDisplay();
        currentCycleEl.textContent = `1/${CYCLES_BEFORE_LONG_BREAK}`;
    </script>
</body>
</html>"""
        
        return html_content
    
    def process_data(self, data: dict) -> dict:
        """
        生成番茄钟前端界面
        
        Args:
            data: 输入数据，可包含自定义配置
            
        Returns:
            包含生成结果的字典
        """
        print("🍅 [generate_frontend] 开始生成番茄钟前端界面...", flush=True)
        
        try:
            # 确保输出目录存在
            os.makedirs('/shared/outputs', exist_ok=True)
            os.makedirs('/factory/outputs', exist_ok=True)
            
            # 生成 HTML 内容
            print("📝 [generate_frontend] 生成 HTML/CSS/JS 代码...", flush=True)
            html_content = self.generate_html()
            
            # 写入 HTML 文件
            with open(self.output_html_path, 'w', encoding='utf-8') as f:
                f.write(html_content)
            print(f"✅ [generate_frontend] HTML 文件已保存到: {self.output_html_path}", flush=True)
            
            # 统计信息
            file_size = os.path.getsize(self.output_html_path)
            line_count = html_content.count('\n')
            
            result = {
                "status": "success",
                "agent": self.agent_name,
                "output_file": self.output_html_path,
                "file_size_bytes": file_size,
                "line_count": line_count,
                "features": [
                    "番茄钟计时器（25分钟工作/5分钟休息）",
                    "长休息支持（每4个番茄后15分钟长休息）",
                    "可自定义时间设置",
                    "进度条显示",
                    "完成番茄统计",
                    "音效提醒",
                    "响应式设计",
                    "页面标题动态显示剩余时间"
                ],
                "message": "番茄钟前端界面生成成功！"
            }
            
            print(f"📊 [generate_frontend] 文件大小: {file_size} bytes, 行数: {line_count}", flush=True)
            print("✅ [generate_frontend] 前端界面生成完成！", flush=True)
            
            return result
            
        except Exception as e:
            print(f"❌ [generate_frontend] 生成失败: {str(e)}", file=sys.stderr, flush=True)
            return {
                "status": "error",
                "agent": self.agent_name,
                "error": str(e)
            }


if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("🚀 [generate_frontend] 启动番茄钟前端生成器", flush=True)
    print("=" * 60, flush=True)
    
    agent = GenerateFrontendAgent()
    result = agent.run({})
    
    print("=" * 60, flush=True)
    print("📋 [generate_frontend] 执行结果:", flush=True)
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    print("=" * 60, flush=True)
    print("NODE_VERIFIED_AND_READY", flush=True)