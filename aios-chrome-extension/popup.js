/**
 * AIOS Bridge — Popup UI Controller
 */

const DEFAULT_WS_URL = 'ws://localhost:19999/ws/browser';

document.addEventListener('DOMContentLoaded', async () => {
  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const wsUrlEl = document.getElementById('wsUrl');
  const connStatusEl = document.getElementById('connStatus');
  const tabTitleEl = document.getElementById('tabTitle');
  const tabUrlEl = document.getElementById('tabUrl');
  const btnReconnect = document.getElementById('btnReconnect');
  const btnOptions = document.getElementById('btnOptions');

  // 加载 WebSocket URL
  const result = await chrome.storage.local.get(['wsUrl']);
  const wsUrl = result.wsUrl || DEFAULT_WS_URL;
  wsUrlEl.textContent = wsUrl;

  // 获取当前标签页信息
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) {
      tabTitleEl.textContent = tab.title || '-';
      tabUrlEl.textContent = tab.url || '-';
    }
  } catch (e) {
    // 忽略
  }

  // 从 background 获取连接状态
  try {
    const response = await chrome.runtime.sendMessage({ type: 'popup_status' });
    if (response) {
      updateStatus(response.connected, response.wsUrl || wsUrl);
    }
  } catch (e) {
    updateStatus(false, wsUrl);
  }

  // 重连按钮
  btnReconnect.addEventListener('click', async () => {
    btnReconnect.disabled = true;
    btnReconnect.textContent = 'Reconnecting...';
    statusDot.className = 'status-dot connecting';
    statusText.textContent = 'Reconnecting...';

    try {
      await chrome.runtime.sendMessage({ type: 'popup_reconnect' });
    } catch (e) {
      // Service Worker 可能未运行
    }

    // 等待 2 秒后刷新状态
    setTimeout(() => {
      btnReconnect.disabled = false;
      btnReconnect.textContent = 'Reconnect';
      chrome.runtime.sendMessage({ type: 'popup_status' })
        .then(r => {
          if (r) updateStatus(r.connected, r.wsUrl || wsUrl);
        })
        .catch(() => updateStatus(false, wsUrl));
    }, 2000);
  });

  // 配置按钮
  btnOptions.addEventListener('click', () => {
    chrome.runtime.openOptionsPage();
  });

  function updateStatus(connected, url) {
    if (connected) {
      statusDot.className = 'status-dot connected';
      statusText.textContent = 'Connected';
      connStatusEl.textContent = 'Connected';
    } else {
      statusDot.className = 'status-dot';
      statusText.textContent = 'Disconnected';
      connStatusEl.textContent = 'Disconnected';
    }
    wsUrlEl.textContent = url || DEFAULT_WS_URL;
  }
});

// 监听来自 background 的状态更新
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'connection_status') {
    const statusDot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');
    const connStatusEl = document.getElementById('connStatus');

    if (msg.connected) {
      statusDot.className = 'status-dot connected';
      statusText.textContent = 'Connected';
      connStatusEl.textContent = 'Connected';
    } else {
      statusDot.className = 'status-dot';
      statusText.textContent = 'Disconnected';
      connStatusEl.textContent = 'Disconnected';
    }
  }
});
