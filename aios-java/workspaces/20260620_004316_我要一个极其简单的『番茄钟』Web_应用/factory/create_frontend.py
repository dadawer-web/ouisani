#!/usr/bin/env python3
"""
create_frontend.py - 生成番茄钟Web前端
节点职责：生成HTML/JS前端文件，调用后端REST API实现番茄钟界面，
         包含开始计时和结束按钮，自动引用后端API配置。
"""

import os
import json
import sys

# Try to import BaseAgent, if not available define a minimal version
try:
    from aios_agent import BaseAgent
except ImportError:
    class BaseAgent:
        """Minimal BaseAgent for standalone execution"""
        def __init__(self):
            pass
        
        def process_data(self, data):
            raise NotImplementedError("Subclasses must implement process_data")
        
        def run(self):
            """Run the agent"""
            try:
                result = self.process_data({})
                print(f"Agent completed successfully: {result}")
                return result
            except Exception as e:
                print(f"Agent failed: {e}", flush=True)
                sys.exit(1)


class CreateFrontendAgent(BaseAgent):
    """
    生成番茄钟Web前端的Agent
    负责创建HTML和JavaScript文件，实现番茄钟UI并调用后端REST API
    """
    
    def __init__(self):
        super().__init__()
        self.output_dir = '/factory/outputs'
        self.frontend_dir = '/factory/frontend'
        
    def ensure_directory(self, path):
        """确保目录存在"""
        os.makedirs(path, exist_ok=True)
        print(f"[INFO] Directory ensured: {path}", flush=True)
    
    def get_html_template(self):
        """生成番茄钟HTML模板"""
        html_content = '''<!DOCTYPE html>
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
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%);
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
        
        .title {
            font-size: 2em;
            color: #e74c3c;
            margin-bottom: 10px;
        }
        
        .subtitle {
            color: #666;
            margin-bottom: 30px;
            font-size: 0.9em;
        }
        
        .timer-display {
            font-size: 5em;
            font-weight: bold;
            color: #2c3e50;
            margin: 20px 0;
            font-family: 'Courier New', monospace;
            text-shadow: 2px 2px 4px rgba(0,0,0,0.1);
        }
        
        .timer-label {
            font-size: 1em;
            color: #7f8c8d;
            margin-bottom: 30px;
            text-transform: uppercase;
            letter-spacing: 2px;
        }
        
        .controls {
            display: flex;
            gap: 15px;
            justify-content: center;
            flex-wrap: wrap;
        }
        
        .btn {
            padding: 15px 30px;
            border: none;
            border-radius: 10px;
            font-size: 1.1em;
            font-weight: bold;
            cursor: pointer;
            transition: all 0.3s ease;
            min-width: 120px;
        }
        
        .btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(0,0,0,0.2);
        }
        
        .btn-start {
            background: #27ae60;
            color: white;
        }
        
        .btn-start:hover {
            background: #229954;
        }
        
        .btn-pause {
            background: #f39c12;
            color: white;
        }
        
        .btn-pause:hover {
            background: #d68910;
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
        
        .status {
            margin-top: 20px;
            padding: 10px;
            border-radius: 8px;
            font-size: 0.9em;
            display: none;
        }
        
        .status.success {
            background: #d5f4e6;
            color: #27ae60;
            display: block;
        }
        
        .status.error {
            background: #fadbd8;
            color: #e74c3c;
            display: block;
        }
        
        .status.info {
            background: #d6eaf8;
            color: #2980b9;
            display: block;
        }
        
        .sessions-info {
            margin-top: 20px;
            display: flex;
            justify-content: center;
            gap: 20px;
        }
        
        .session-item {
            text-align: center;
        }
        
        .session-count {
            font-size: 2em;
            font-weight: bold;
            color: #e74c3c;
        }
        
        .session-label {
            font-size: 0.8em;
            color: #999;
        }
        
        .mode-selector {
            display: flex;
            gap: 10px;
            justify-content: center;
            margin-bottom: 20px;
        }
        
        .mode-btn {
            padding: 8px 16px;
            border: 2px solid #e74c3c;
            background: white;
            color: #e74c3c;
            border-radius: 20px;
            cursor: pointer;
            font-size: 0.9em;
            transition: all 0.3s;
        }
        
        .mode-btn.active {
            background: #e74c3c;
            color: white;
        }
        
        .mode-btn:hover {
            background: #fadbd8;
        }
        
        .mode-btn.active:hover {
            background: #c0392b;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1 class="title">🍅 番茄钟</h1>
        <p class="subtitle">专注工作，高效休息</p>
        
        <div class="mode-selector">
            <button class="mode-btn active" data-minutes="25" onclick="setMode(this, 25)">专注 25分钟</button>
            <button class="mode-btn" data-minutes="5" onclick="setMode(this, 5)">短休 5分钟</button>
            <button class="mode-btn" data-minutes="15" onclick="setMode(this, 15)">长休 15分钟</button>
        </div>
        
        <div class="timer-display" id="timer">25:00</div>
        <div class="timer-label" id="timer-label">准备开始</div>
        
        <div class="controls">
            <button class="btn btn-start" id="btn-start" onclick="startTimer()">开始</button>
            <button class="btn btn-pause" id="btn-pause" onclick="pauseTimer()" style="display:none;">暂停</button>
            <button class="btn btn-stop" id="btn-stop" onclick="stopTimer()" style="display:none;">结束</button>
            <button class="btn btn-reset" id="btn-reset" onclick="resetTimer()">重置</button>
        </div>
        
        <div class="status" id="status"></div>
        
        <div class="sessions-info">
            <div class="session-item">
                <div class="session-count" id="sessions-count">0</div>
                <div class="session-label">完成番茄数</div>
            </div>
            <div class="session-item">
                <div class="session-count" id="total-minutes">0</div>
                <div class="session-label">专注分钟</div>
            </div>
        </div>
    </div>

    <script>
        // API配置 - 后端REST API地址
        const API_BASE_URL = window.location.protocol + '//' + window.location.hostname + ':8080/api';
        
        // 计时器状态
        let timerState = {
            isRunning: false,
            isPaused: false,
            totalSeconds: 25 * 60,
            remainingSeconds: 25 * 60,
            intervalId: null,
            sessionsCompleted: 0,
            totalMinutesFocused: 0,
            currentSessionId: null
        };

        // DOM元素
        const timerDisplay = document.getElementById('timer');
        const timerLabel = document.getElementById('timer-label');
        const btnStart = document.getElementById('btn-start');
        const btnPause = document.getElementById('btn-pause');
        const btnStop = document.getElementById('btn-stop');
        const btnReset = document.getElementById('btn-reset');
        const statusDiv = document.getElementById('status');
        const sessionsCount = document.getElementById('sessions-count');
        const totalMinutes = document.getElementById('total-minutes');

        // 显示状态消息
        function showStatus(message, type = 'info') {
            statusDiv.textContent = message;
            statusDiv.className = 'status ' + type;
            statusDiv.style.display = 'block';
            setTimeout(() => {
                statusDiv.style.display = 'none';
            }, 3000);
        }

        // 格式化时间为 mm:ss
        function formatTime(seconds) {
            const mins = Math.floor(seconds / 60);
            const secs = seconds % 60;
            return String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
        }

        // 更新计时器显示
        function updateDisplay() {
            timerDisplay.textContent = formatTime(timerState.remainingSeconds);
            sessionsCount.textContent = timerState.sessionsCompleted;
            totalMinutes.textContent = timerState.totalMinutesFocused;
        }

        // 设置模式（专注/短休/长休）
        function setMode(button, minutes) {
            if (timerState.isRunning) {
                showStatus('请先停止当前计时器', 'error');
                return;
            }
            
            // 更新按钮状态
            document.querySelectorAll('.mode-btn').forEach(btn => btn.classList.remove('active'));
            button.classList.add('active');
            
            // 更新计时器
            timerState.totalSeconds = minutes * 60;
            timerState.remainingSeconds = minutes * 60;
            timerLabel.textContent = minutes === 25 ? '专注模式' : minutes === 5 ? '短休息' : '长休息';
            updateDisplay();
            showStatus(`已设置为 ${minutes} 分钟`, 'info');
        }

        // 调用后端API创建会话
        async function createSession() {
            try {
                const response = await fetch(`${API_BASE_URL}/sessions`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        type: timerState.totalSeconds === 25 * 60 ? 'FOCUS' : 'BREAK',
                        duration: timerState.totalSeconds / 60
                    })
                });
                
                if (!response.ok) {
                    throw new Error('API请求失败');
                }
                
                const data = await response.json();
                timerState.currentSessionId = data.id;
                showStatus('会话已创建', 'success');
                return data;
            } catch (error) {
                console.error('创建会话失败:', error);
                showStatus('后端API不可用，使用本地模式', 'info');
                timerState.currentSessionId = 'local-' + Date.now();
                return null;
            }
        }

        // 调用后端API完成会话
        async function completeSession() {
            if (!timerState.currentSessionId || timerState.currentSessionId.startsWith('local-')) {
                return;
            }
            
            try {
                const response = await fetch(`${API_BASE_URL}/sessions/${timerState.currentSessionId}/complete`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
                
                if (!response.ok) {
                    throw new Error('完成会话失败');
                }
                
                showStatus('会话已完成', 'success');
            } catch (error) {
                console.error('完成会话失败:', error);
            }
        }

        // 开始计时
        async function startTimer() {
            if (timerState.isRunning && timerState.isPaused) {
                // 恢复计时
                timerState.isPaused = false;
                timerLabel.textContent = '计时中...';
                btnPause.style.display = 'inline-block';
                btnStart.style.display = 'none';
                startInterval();
                showStatus('继续计时', 'info');
                return;
            }
            
            if (timerState.isRunning) return;
            
            // 创建会话
            await createSession();
            
            timerState.isRunning = true;
            timerState.isPaused = false;
            timerLabel.textContent = '计时中...';
            
            btnStart.style.display = 'none';
            btnPause.style.display = 'inline-block';
            btnStop.style.display = 'inline-block';
            
            startInterval();
            showStatus('开始计时！', 'success');
        }

        // 启动间隔计时器
        function startInterval() {
            timerState.intervalId = setInterval(() => {
                if (timerState.remainingSeconds > 0) {
                    timerState.remainingSeconds--;
                    updateDisplay();
                } else {
                    // 计时结束
                    clearInterval(timerState.intervalId);
                    timerState.isRunning = false;
                    
                    if (timerState.totalSeconds === 25 * 60) {
                        // 专注模式完成
                        timerState.sessionsCompleted++;
                        timerState.totalMinutesFocused += 25;
                        showStatus('🍅 太棒了！完成一个番茄！', 'success');
                        playSound();
                    } else {
                        showStatus('休息结束，继续加油！', 'success');
                    }
                    
                    completeSession();
                    resetButtons();
                }
            }, 1000);
        }

        // 暂停计时
        function pauseTimer() {
            if (!timerState.isRunning) return;
            
            clearInterval(timerState.intervalId);
            timerState.isPaused = true;
            timerLabel.textContent = '已暂停';
            
            btnPause.style.display = 'none';
            btnStart.style.display = 'inline-block';
            btnStart.textContent = '继续';
            
            showStatus('计时已暂停', 'info');
        }

        // 停止计时
        function stopTimer() {
            clearInterval(timerState.intervalId);
            timerState.isRunning = false;
            timerState.isPaused = false;
            
            completeSession();
            resetButtons();
            
            timerLabel.textContent = '已停止';
            showStatus('计时已停止', 'info');
        }

        // 重置计时器
        function resetTimer() {
            clearInterval(timerState.intervalId);
            timerState.isRunning = false;
            timerState.isPaused = false;
            timerState.remainingSeconds = timerState.totalSeconds;
            
            resetButtons();
            updateDisplay();
            
            timerLabel.textContent = '准备开始';
            showStatus('计时器已重置', 'info');
        }

        // 重置按钮状态
        function resetButtons() {
            btnStart.style.display = 'inline-block';
            btnStart.textContent = '开始';
            btnPause.style.display = 'none';
            btnStop.style.display = 'none';
        }

        // 播放提示音
        function playSound() {
            try {
                const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                const oscillator = audioCtx.createOscillator();
                const gainNode = audioCtx.createGain();
                
                oscillator.connect(gainNode);
                gainNode.connect(audioCtx.destination);
                
                oscillator.frequency.value = 800;
                oscillator.type = 'sine';
                gainNode.gain.value = 0.3;
                
                oscillator.start();
                
                setTimeout(() => {
                    oscillator.stop();
                    audioCtx.close();
                }, 500);
            } catch (e) {
                console.log('无法播放提示音:', e);
            }
        }

        // 初始化
        updateDisplay();
        console.log('🍅 番茄钟前端已加载');
        console.log('API Base URL:', API_BASE_URL);
    </script>
</body>
</html>'''
        return html_content

    def get_api_config_js(self):
        """生成API配置文件"""
        config_content = '''/**
 * 番茄钟 API 配置文件
 * 此文件配置后端REST API的连接参数
 */

const PomodoroAPI = {
    // API基础地址 - 根据环境自动调整
    BASE_URL: window.location.protocol + '//' + window.location.hostname + ':8080/api',
    
    // API端点
    ENDPOINTS: {
        SESSIONS: '/sessions',
        SESSION_COMPLETE: (id) => `/sessions/${id}/complete`,
        SESSION_CANCEL: (id) => `/sessions/${id}/cancel`,
        STATS: '/stats'
    },
    
    // 请求配置
    REQUEST_CONFIG: {
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        timeout: 10000
    },
    
    // 发起API请求
    async request(method, endpoint, data = null) {
        const url = this.BASE_URL + endpoint;
        const options = {
            method,
            headers: this.REQUEST_CONFIG.headers
        };
        
        if (data && (method === 'POST' || method === 'PUT')) {
            options.body = JSON.stringify(data);
        }
        
        try {
            const response = await fetch(url, options);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            return await response.json();
        } catch (error) {
            console.error(`API请求失败 [${method} ${url}]:`, error);
            throw error;
        }
    },
    
    // 创建番茄钟会话
    async createSession(type = 'FOCUS', duration = 25) {
        return this.request('POST', this.ENDPOINTS.SESSIONS, {
            type,
            duration
        });
    },
    
    // 完成会话
    async completeSession(sessionId) {
        return this.request('POST', this.ENDPOINTS.SESSION_COMPLETE(sessionId));
    },
    
    // 取消会话
    async cancelSession(sessionId) {
        return this.request('POST', this.ENDPOINTS.SESSION_CANCEL(sessionId));
    },
    
    // 获取统计信息
    async getStats() {
        return this.request('GET', this.ENDPOINTS.STATS);
    }
};

// 导出配置
if (typeof module !== 'undefined' && module.exports) {
    module.exports = PomodoroAPI;
}
'''
        return config_content

    def generate_readme(self):
        """生成README说明文档"""
        readme_content = '''# 🍅 番茄钟前端应用

## 简介
这是一个简洁美观的番茄钟Web应用前端，采用纯HTML/CSS/JavaScript实现，可调用后端REST API进行数据持久化。

## 文件结构
```
/frontend/
├── index.html          # 主页面
├── api-config.js       # API配置文件
└── README.md           # 本说明文档
```

## 功能特性
- 🎯 专注模式（25分钟）
- ☕ 短休息（5分钟）
- 🌴 长休息（15分钟）
- ⏸️ 暂停/继续功能
- 📊 完成统计
- 🔔 完成提示音
- 🌐 后端API集成

## 使用方法

### 1. 独立使用（无需后端）
直接在浏览器中打开 `index.html` 即可使用本地模式。

### 2. 配合后端API使用
确保后端API服务运行在 `http://localhost:8080/api`，前端会自动调用后端接口。

### 3. 修改API地址
编辑 `api-config.js` 文件中的 `BASE_URL` 变量：
```javascript
BASE_URL: 'http://your-api-server:port/api'
```

## 后端API接口
- `POST /api/sessions` - 创建新的番茄钟会话
- `POST /api/sessions/:id/complete` - 完成会话
- `POST /api/sessions/:id/cancel` - 取消会话
- `GET /api/stats` - 获取统计数据

## 浏览器兼容性
- Chrome 60+
- Firefox 55+
- Safari 11+
- Edge 79+

## 开发说明
前端采用原生JavaScript，无需构建工具，可直接部署到任何静态文件服务器。
'''
        return readme_content

    def process_data(self, data):
        """
        处理数据：生成番茄钟前端文件
        
        Args:
            data: 输入数据（当前未使用）
            
        Returns:
            dict: 包含生成文件路径的结果
        """
        print("=== 开始生成番茄钟前端 ===", flush=True)
        
        try:
            # 1. 创建输出目录
            self.ensure_directory(self.frontend_dir)
            self.ensure_directory(self.output_dir)
            
            # 2. 生成HTML文件
            print("[STEP 1] 生成 index.html ...", flush=True)
            html_path = os.path.join(self.frontend_dir, 'index.html')
            with open(html_path, 'w', encoding='utf-8') as f:
                f.write(self.get_html_template())
            print(f"[SUCCESS] HTML文件已生成: {html_path}", flush=True)
            
            # 3. 生成API配置文件
            print("[STEP 2] 生成 api-config.js ...", flush=True)
            config_path = os.path.join(self.frontend_dir, 'api-config.js')
            with open(config_path, 'w', encoding='utf-8') as f:
                f.write(self.get_api_config_js())
            print(f"[SUCCESS] API配置文件已生成: {config_path}", flush=True)
            
            # 4. 生成README文档
            print("[STEP 3] 生成 README.md ...", flush=True)
            readme_path = os.path.join(self.frontend_dir, 'README.md')
            with open(readme_path, 'w', encoding='utf-8') as f:
                f.write(self.generate_readme())
            print(f"[SUCCESS] README文档已生成: {readme_path}", flush=True)
            
            # 5. 保存结果到outputs目录
            result = {
                "status": "success",
                "node": "create_frontend",
                "files_generated": [
                    {"path": html_path, "type": "html", "description": "番茄钟主页面"},
                    {"path": config_path, "type": "javascript", "description": "API配置文件"},
                    {"path": readme_path, "type": "markdown", "description": "说明文档"}
                ],
                "frontend_url": "file://" + html_path,
                "api_base_url": "http://localhost:8080/api"
            }
            
            result_path = os.path.join(self.output_dir, 'create_frontend_result.json')
            with open(result_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            print(f"[SUCCESS] 结果已保存: {result_path}", flush=True)
            
            print("\n=== 番茄钟前端生成完成 ===", flush=True)
            print(f"📁 前端目录: {self.frontend_dir}", flush=True)
            print(f"📄 HTML文件: {html_path}", flush=True)
            print(f"⚙️  API配置: {config_path}", flush=True)
            print(f"📋 说明文档: {readme_path}", flush=True)
            print(f"📊 结果文件: {result_path}", flush=True)
            print("\n✅ NODE_VERIFIED_AND_READY", flush=True)
            
            return result
            
        except Exception as e:
            print(f"\n❌ 错误: {str(e)}", flush=True)
            import traceback
            traceback.print_exc()
            raise


if __name__ == "__main__":
    # 独立运行测试
    print("🚀 启动 create_frontend Agent ...", flush=True)
    agent = CreateFrontendAgent()
    try:
        result = agent.process_data({})
        print("\n🎉 Agent 执行成功!", flush=True)
    except Exception as e:
        print(f"\n💥 Agent 执行失败: {e}", flush=True)
        sys.exit(1)