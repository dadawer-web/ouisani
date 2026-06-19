// 番茄钟应用主逻辑
class PomodoroTimer {
    constructor() {
        // 状态
        this.isRunning = false;
        this.isPaused = false;
        this.isWorkTime = true;
        this.timeLeft = 0;
        this.totalTime = 0;
        this.completedPomodoros = 0;
        this.timerInterval = null;
        
        // DOM元素
        this.timeDisplay = document.getElementById('time-display');
        this.statusText = document.getElementById('status-text');
        this.startBtn = document.getElementById('start-btn');
        this.pauseBtn = document.getElementById('pause-btn');
        this.resetBtn = document.getElementById('reset-btn');
        this.workDurationInput = document.getElementById('work-duration');
        this.breakDurationInput = document.getElementById('break-duration');
        this.progressFill = document.getElementById('progress');
        this.completedCount = document.getElementById('completed-count');
        this.notification = document.getElementById('notification');
        
        // 初始化
        this.init();
    }
    
    init() {
        // 设置默认时间
        this.updateDisplay(this.getWorkDuration() * 60);
        this.updateProgress(0);
        
        // 绑定事件
        this.startBtn.addEventListener('click', () => this.start());
        this.pauseBtn.addEventListener('click', () => this.pause());
        this.resetBtn.addEventListener('click', () => this.reset());
        
        // 输入变化时重置
        this.workDurationInput.addEventListener('change', () => {
            if (!this.isRunning) {
                this.reset();
            }
        });
        
        this.breakDurationInput.addEventListener('change', () => {
            if (!this.isRunning) {
                this.reset();
            }
        });
        
        // 加载完成次数
        this.loadCompletedPomodoros();
    }
    
    getWorkDuration() {
        return parseInt(this.workDurationInput.value) || 25;
    }
    
    getBreakDuration() {
        return parseInt(this.breakDurationInput.value) || 5;
    }
    
    start() {
        if (this.isRunning && !this.isPaused) return;
        
        if (!this.isRunning) {
            // 新开始
            this.totalTime = this.isWorkTime ? 
                this.getWorkDuration() * 60 : 
                this.getBreakDuration() * 60;
            this.timeLeft = this.totalTime;
        }
        
        this.isRunning = true;
        this.isPaused = false;
        
        this.updateButtons();
        this.updateStatus();
        
        // 开始计时
        this.timerInterval = setInterval(() => {
            this.timeLeft--;
            this.updateDisplay(this.timeLeft);
            this.updateProgress(this.timeLeft / this.totalTime);
            
            if (this.timeLeft <= 0) {
                this.timerComplete();
            }
        }, 1000);
        
        this.showNotification('计时开始！');
    }
    
    pause() {
        if (!this.isRunning) return;
        
        clearInterval(this.timerInterval);
        this.isPaused = true;
        this.isRunning = false;
        
        this.updateButtons();
        this.updateStatus();
        
        this.showNotification('计时已暂停');
    }
    
    reset() {
        clearInterval(this.timerInterval);
        this.isRunning = false;
        this.isPaused = false;
        this.isWorkTime = true;
        
        this.updateButtons();
        this.updateStatus();
        this.updateDisplay(this.getWorkDuration() * 60);
        this.updateProgress(0);
        
        this.showNotification('已重置');
    }
    
    timerComplete() {
        clearInterval(this.timerInterval);
        this.isRunning = false;
        
        if (this.isWorkTime) {
            // 工作时间结束
            this.completedPomodoros++;
            this.saveCompletedPomodoros();
            this.completedCount.textContent = this.completedPomodoros;
            this.showNotification('🍅 番茄钟完成！休息一下吧！');
            
            // 播放提示音
            this.playSound();
            
            // 自动切换到休息时间
            setTimeout(() => {
                this.isWorkTime = false;
                this.reset();
                this.start();
            }, 2000);
        } else {
            // 休息时间结束
            this.showNotification('休息结束，开始新的番茄钟！');
            this.playSound();
            
            // 自动切换到工作时间
            setTimeout(() => {
                this.isWorkTime = true;
                this.reset();
                this.start();
            }, 2000);
        }
    }
    
    updateDisplay(seconds) {
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        this.timeDisplay.textContent = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }
    
    updateProgress(ratio) {
        const percentage = Math.round((1 - ratio) * 100);
        this.progressFill.style.width = `${percentage}%`;
    }
    
    updateStatus() {
        if (this.isWorkTime) {
            this.statusText.textContent = this.isPaused ? '工作时间 - 已暂停' : '工作时间';
            this.statusText.style.color = '#27ae60';
        } else {
            this.statusText.textContent = this.isPaused ? '休息时间 - 已暂停' : '休息时间';
            this.statusText.style.color = '#f39c12';
        }
    }
    
    updateButtons() {
        this.startBtn.disabled = this.isRunning && !this.isPaused;
        this.pauseBtn.disabled = !this.isRunning;
        
        if (this.isRunning) {
            this.startBtn.textContent = '继续';
        } else {
            this.startBtn.textContent = '开始';
        }
    }
    
    showNotification(message) {
        this.notification.textContent = message;
        this.notification.classList.add('show');
        
        setTimeout(() => {
            this.notification.classList.remove('show');
        }, 3000);
    }
    
    playSound() {
        // 简单的提示音
        try {
            const audioContext = new (window.AudioContext || window.webkitAudioContext)();
            const oscillator = audioContext.createOscillator();
            const gainNode = audioContext.createGain();
            
            oscillator.connect(gainNode);
            gainNode.connect(audioContext.destination);
            
            oscillator.frequency.value = 800;
            oscillator.type = 'sine';
            gainNode.gain.value = 0.3;
            
            oscillator.start(audioContext.currentTime);
            oscillator.stop(audioContext.currentTime + 0.5);
            
            // 第二声
            setTimeout(() => {
                const osc2 = audioContext.createOscillator();
                const gain2 = audioContext.createGain();
                osc2.connect(gain2);
                gain2.connect(audioContext.destination);
                osc2.frequency.value = 1000;
                osc2.type = 'sine';
                gain2.gain.value = 0.3;
                osc2.start(audioContext.currentTime);
                osc2.stop(audioContext.currentTime + 0.5);
            }, 600);
        } catch (e) {
            console.log('无法播放提示音:', e);
        }
    }
    
    saveCompletedPomodoros() {
        localStorage.setItem('pomodoro-completed', this.completedPomodoros.toString());
    }
    
    loadCompletedPomodoros() {
        const saved = localStorage.getItem('pomodoro-completed');
        if (saved) {
            this.completedPomodoros = parseInt(saved);
            this.completedCount.textContent = this.completedPomodoros;
        }
    }
}

// 应用启动
document.addEventListener('DOMContentLoaded', () => {
    window.pomodoroTimer = new PomodoroTimer();
});