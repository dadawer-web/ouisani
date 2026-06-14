/**
 * AIOS Bridge — Background Service Worker
 *
 * 核心职责：
 * 1. 维护与 AIOS 后端的 WebSocket 长连接
 * 2. 接收后端下发的浏览器控制命令
 * 3. 通过 Chrome API / Content Script 执行命令
 * 4. 将执行结果回传给后端
 */

// ════════════════════════════════════════════════════════════════
//  配置
// ════════════════════════════════════════════════════════════════

const DEFAULT_WS_URL = 'ws://localhost:19999/ws/browser';
const RECONNECT_INTERVAL_MS = 3000;
const HEARTBEAT_INTERVAL_MS = 30000;

let ws = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let commandId = 0;

// ════════════════════════════════════════════════════════════════
//  WebSocket 连接管理
// ════════════════════════════════════════════════════════════════

async function getWsUrl() {
  const result = await chrome.storage.local.get(['wsUrl']);
  return result.wsUrl || DEFAULT_WS_URL;
}

async function connect() {
  if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
    return;
  }

  const url = await getWsUrl();
  console.log(`[AIOS Bridge] Connecting to ${url}...`);

  try {
    ws = new WebSocket(url);
  } catch (e) {
    console.error('[AIOS Bridge] WebSocket creation failed:', e);
    scheduleReconnect();
    return;
  }

  ws.onopen = () => {
    console.log('[AIOS Bridge] Connected to AIOS backend');
    updateBadge('connected');
    sendStateUpdate();
    startHeartbeat();
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  };

  ws.onmessage = (event) => {
    handleIncomingCommand(event.data);
  };

  ws.onclose = (event) => {
    console.log(`[AIOS Bridge] Disconnected (code=${event.code})`);
    updateBadge('disconnected');
    stopHeartbeat();
    scheduleReconnect();
  };

  ws.onerror = (event) => {
    console.error('[AIOS Bridge] WebSocket error');
    updateBadge('error');
  };
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(async () => {
    reconnectTimer = null;
    await connect();
  }, RECONNECT_INTERVAL_MS);
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      sendStateUpdate();
    }
  }, HEARTBEAT_INTERVAL_MS);
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

// ════════════════════════════════════════════════════════════════
//  发送消息到后端
// ════════════════════════════════════════════════════════════════

function sendToBackend(data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

/** 向后端发送当前标签页状态 */
async function sendStateUpdate() {
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs.length > 0) {
      const tab = tabs[0];
      sendToBackend({
        type: 'state_update',
        title: tab.title || '',
        url: tab.url || '',
        favIconUrl: tab.favIconUrl || ''
      });
    }
  } catch (e) {
    // 忽略权限错误
  }
}

/** 向后端发送命令执行结果 */
function sendCommandResult(cmdId, action, success, result, error) {
  sendToBackend({
    type: 'command_result',
    command_id: cmdId,
    action: action,
    success: success,
    result: result || null,
    error: error || null
  });
}

// ════════════════════════════════════════════════════════════════
//  命令分发 — 接收后端命令并执行
// ════════════════════════════════════════════════════════════════

async function handleIncomingCommand(rawData) {
  let cmd;
  try {
    cmd = JSON.parse(rawData);
  } catch (e) {
    console.warn('[AIOS Bridge] Invalid JSON command:', rawData);
    return;
  }

  const cmdId = cmd.command_id || (++commandId);
  const action = cmd.action;

  console.log(`[AIOS Bridge] Command: ${action} (id=${cmdId})`);

  try {
    const result = await dispatchCommand(cmd);
    sendCommandResult(cmdId, action, true, result, null);
  } catch (e) {
    console.error(`[AIOS Bridge] Command failed: ${action}`, e);
    sendCommandResult(cmdId, action, false, null, e.message || String(e));
  }
}

async function dispatchCommand(cmd) {
  const action = cmd.action;

  switch (action) {
    case 'navigate':
      return await cmdNavigate(cmd.url);

    case 'click_element':
      return await cmdDomAction(cmd.selector, 'click');

    case 'type_text':
      return await cmdTypeText(cmd.selector, cmd.text);

    case 'extract_text':
      return await cmdExtractText(cmd.selector);

    case 'execute_script':
      return await cmdExecuteScript(cmd.script);

    case 'get_page_source':
      return await cmdGetPageSource();

    case 'wait_for_element':
      return await cmdWaitForElement(cmd.selector, cmd.timeout_ms || 5000);

    case 'screenshot':
      return await cmdScreenshot();

    case 'get_active_tab':
      return await cmdGetActiveTab();

    case 'list_tabs':
      return await cmdListTabs();

    default:
      throw new Error(`Unknown action: ${action}`);
  }
}

// ════════════════════════════════════════════════════════════════
//  命令实现
// ════════════════════════════════════════════════════════════════

/** 导航到指定 URL */
async function cmdNavigate(url) {
  if (!url) throw new Error('URL is required');
  const tab = await getActiveTab();
  await chrome.tabs.update(tab.id, { url: url });
  // 等待页面加载完成
  return new Promise((resolve) => {
    const listener = (updatedTabId, changeInfo) => {
      if (updatedTabId === tab.id && changeInfo.status === 'complete') {
        chrome.tabs.onUpdated.removeListener(listener);
        resolve({ navigated: true, url: url, title: updatedTabId.title || '' });
      }
    };
    chrome.tabs.onUpdated.addListener(listener);
    // 15 秒超时
    setTimeout(() => {
      chrome.tabs.onUpdated.removeListener(listener);
      resolve({ navigated: true, url: url, timeout: true });
    }, 15000);
  });
}

/** 点击元素 */
async function cmdDomAction(selector, action) {
  if (!selector) throw new Error('Selector is required');
  const tab = await getActiveTab();
  const results = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: domActionInPage,
    args: [selector, action]
  });
  const result = results[0]?.result;
  if (result && result.error) throw new Error(result.error);
  return result;
}

/** 在页面上下文中执行 DOM 操作 */
function domActionInPage(selector, action) {
  const el = document.querySelector(selector);
  if (!el) return { error: `Element not found: ${selector}` };
  switch (action) {
    case 'click':
      el.click();
      return { clicked: true, selector: selector, tag: el.tagName };
    default:
      return { error: `Unknown DOM action: ${action}` };
  }
}

/** 输入文本 */
async function cmdTypeText(selector, text) {
  if (!selector) throw new Error('Selector is required');
  const tab = await getActiveTab();
  const results = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: typeTextInPage,
    args: [selector, text || '']
  });
  const result = results[0]?.result;
  if (result && result.error) throw new Error(result.error);
  return result;
}

/** 在页面上下文中输入文本（模拟真实输入事件） */
function typeTextInPage(selector, text) {
  const el = document.querySelector(selector);
  if (!el) return { error: `Element not found: ${selector}` };

  // 聚焦元素
  el.focus();

  // 清空现有值
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    el.value = '';
  } else if (el.isContentEditable) {
    el.textContent = '';
  }

  // 使用 InputEvent 逐字输入（模拟真实键盘输入）
  for (const char of text) {
    const inputEvent = new InputEvent('input', {
      bubbles: true,
      cancelable: true,
      inputType: 'insertText',
      data: char
    });

    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      el.value += char;
    } else if (el.isContentEditable) {
      el.textContent += char;
    }

    el.dispatchEvent(inputEvent);
  }

  // 触发 change 事件
  el.dispatchEvent(new Event('change', { bubbles: true }));

  return { typed: true, selector: selector, length: text.length };
}

/** 提取页面文本 */
async function cmdExtractText(selector) {
  const tab = await getActiveTab();
  const results = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: extractTextInPage,
    args: [selector || null]
  });
  return results[0]?.result;
}

function extractTextInPage(selector) {
  if (selector) {
    const el = document.querySelector(selector);
    if (!el) return { error: `Element not found: ${selector}` };
    return { text: el.innerText || el.textContent, selector: selector };
  }
  return { text: document.body.innerText, selector: 'body' };
}

/** 执行 JavaScript */
async function cmdExecuteScript(script) {
  if (!script) throw new Error('Script is required');
  const tab = await getActiveTab();
  const results = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: new Function('return (' + script + ')'),
    world: 'MAIN'
  });
  return { result: results[0]?.result };
}

/** 获取页面源码 */
async function cmdGetPageSource() {
  const tab = await getActiveTab();
  const results = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: () => document.documentElement.outerHTML
  });
  return { source: results[0]?.result, url: tab.url };
}

/** 等待元素出现 */
async function cmdWaitForElement(selector, timeoutMs) {
  if (!selector) throw new Error('Selector is required');
  const tab = await getActiveTab();
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: (sel) => {
        const el = document.querySelector(sel);
        return el ? { found: true, tag: el.tagName, text: (el.innerText || '').substring(0, 200) } : null;
      },
      args: [selector]
    });

    if (results[0]?.result) {
      return { found: true, selector: selector, waited_ms: Date.now() - startTime, ...results[0].result };
    }

    // 等待 200ms 后重试
    await new Promise(r => setTimeout(r, 200));
  }

  return { found: false, selector: selector, timeout_ms: timeoutMs };
}

/** 浏览器级截图 */
async function cmdScreenshot() {
  const tab = await getActiveTab();
  const dataUrl = await chrome.tabs.captureVisibleTab(tab.windowId, {
    format: 'jpeg',
    quality: 80
  });
  // 返回 base64 数据（去掉 data:image/jpeg;base64, 前缀）
  return { screenshot: dataUrl, format: 'jpeg', tabId: tab.id };
}

/** 获取当前活动标签页 */
async function cmdGetActiveTab() {
  const tab = await getActiveTab();
  return {
    id: tab.id,
    title: tab.title,
    url: tab.url,
    favIconUrl: tab.favIconUrl || '',
    active: true
  };
}

/** 列出所有标签页 */
async function cmdListTabs() {
  const tabs = await chrome.tabs.query({});
  return {
    tabs: tabs.map(t => ({
      id: t.id,
      title: t.title,
      url: t.url,
      active: t.active,
      windowId: t.windowId
    })),
    count: tabs.length
  };
}

// ════════════════════════════════════════════════════════════════
//  辅助函数
// ════════════════════════════════════════════════════════════════

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tabs.length === 0) throw new Error('No active tab found');
  return tabs[0];
}

function updateBadge(status) {
  const colors = {
    connected: '#00C853',
    disconnected: '#FF1744',
    error: '#FF6D00'
  };
  const texts = {
    connected: 'ON',
    disconnected: 'OFF',
    error: 'ERR'
  };
  chrome.action.setBadgeBackgroundColor({ color: colors[status] || '#999' });
  chrome.action.setBadgeText({ text: texts[status] || '??' });
}

// ════════════════════════════════════════════════════════════════
//  事件监听 — 标签页变化时自动推送状态
// ════════════════════════════════════════════════════════════════

chrome.tabs.onActivated.addListener(() => {
  sendStateUpdate();
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.title || changeInfo.url || changeInfo.status === 'complete') {
    sendStateUpdate();
  }
});

// ════════════════════════════════════════════════════════════════
//  Popup 消息处理
// ════════════════════════════════════════════════════════════════

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'popup_status') {
    sendResponse({
      connected: ws && ws.readyState === WebSocket.OPEN,
      wsUrl: ws ? ws.url : ''
    });
    return true;
  }

  if (msg.type === 'popup_reconnect') {
    if (ws) {
      ws.close();
    }
    connect();
    sendResponse({ reconnecting: true });
    return true;
  }
});

// ════════════════════════════════════════════════════════════════
//  启动
// ════════════════════════════════════════════════════════════════

// 扩展安装/更新时自动连接
chrome.runtime.onInstalled.addListener(() => {
  console.log('[AIOS Bridge] Extension installed/updated');
  connect();
});

// Service Worker 唤醒时重连
chrome.runtime.onStartup.addListener(() => {
  connect();
});

// 立即连接
connect();
