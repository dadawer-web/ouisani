// 番茄钟计时器应用
let timeLeft = 25 * 60; // 25分钟，以秒为单位
let timerInterval = null;
let isRunning = false;

// DOM元素
const minutesDisplay = document.getElementById('minutes');
const secondsDisplay = document.getElementById('seconds');
const statusDisplay = document.getElementById('status');
const startBtn = document.getElementById('startBtn');
const pauseBtn = document.getElementById('pauseBtn');
const resetBtn = document.getElementById('resetBtn');

// 更新计时器显示
function updateDisplay() {
    const minutes = Math.floor(timeLeft / 60);
    const seconds = timeLeft % 60;
    
    minutesDisplay.textContent = minutes.toString().padStart(2, '0');
    secondsDisplay.textContent = seconds.toString().padStart(2, '0');
}

// 开始计时器
function startTimer() {
    if (!isRunning) {
        isRunning = true;
        statusDisplay.textContent = '专注中...';
        startBtn.textContent = '继续';
        
        timerInterval = setInterval(function() {
            timeLeft--;
            updateDisplay();
            
            if (timeLeft <= 0) {
                clearInterval(timerInterval);
                isRunning = false;
                statusDisplay.textContent = '🎉 番茄时间结束！休息一下吧！';
                startBtn.textContent = '开始';
                
                // 播放提示音（可选）
                playNotification();
            }
        }, 1000);
    }
}

// 暂停计时器
function pauseTimer() {
    if (isRunning) {
        clearInterval(timerInterval);
        isRunning = false;
        statusDisplay.textContent = '已暂停';
        startBtn.textContent = '继续';
    }
}

// 重置计时器
function resetTimer() {
    clearInterval(timerInterval);
    isRunning = false;
    timeLeft = 25 * 60;
    updateDisplay();
    statusDisplay.textContent = '准备开始';
    startBtn.textContent = '开始';
}

// 播放提示音
function playNotification() {
    // 使用Web Audio API创建简单的提示音
    try {
        const audioContext = new (window.AudioContext || window.webkitAudioContext)();
        const oscillator = audioContext.createOscillator();
        const gainNode = audioContext.createGain();
        
        oscillator.connect(gainNode);
        gainNode.connect(audioContext.destination);
        
        oscillator.frequency.value = 800;
        oscillator.type = 'sine';
        
        gainNode.gain.setValueAtTime(0.3, audioContext.currentTime);
        gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 1);
        
        oscillator.start(audioContext.currentTime);
        oscillator.stop(audioContext.currentTime + 1);
    } catch (e) {
        console.log('提示音播放失败:', e);
    }
}

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', function() {
    updateDisplay();
    console.log('番茄钟应用已初始化');
});

// 键盘快捷键支持
document.addEventListener('keydown', function(event) {
    switch(event.code) {
        case 'Space':
            event.preventDefault();
            if (isRunning) {
                pauseTimer();
            } else {
                startTimer();
            }
            break;
        case 'KeyR':
            if (event.ctrlKey || event.metaKey) {
                resetTimer();
            }
            break;
    }
});
