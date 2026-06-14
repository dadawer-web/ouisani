/**
 * AIOS Bridge — Content Script
 *
 * 注入到每个页面，提供 DOM 级操作能力。
 * 大部分 DOM 操作已通过 chrome.scripting.executeScript 在 background.js 中直接执行，
 * 此 content script 作为补充，提供：
 * 1. 页面级事件监听（滚动、点击、输入等）
 * 2. 高亮标记（AIOS 操作时在元素上显示视觉反馈）
 * 3. 页面就绪通知
 */

// 避免重复注入
if (!window.__AIOS_BRIDGE_LOADED) {
  window.__AIOS_BRIDGE_LOADED = true;

  // ════════════════════════════════════════════════════════════════
  //  页面就绪通知
  // ════════════════════════════════════════════════════════════════

  chrome.runtime.sendMessage({
    type: 'page_ready',
    url: window.location.href,
    title: document.title
  }).catch(() => {
    // Service Worker 未就绪时忽略
  });

  // ════════════════════════════════════════════════════════════════
  //  AIOS 操作高亮反馈
  // ════════════════════════════════════════════════════════════════

  const AIOS_HIGHLIGHT_STYLE = `
    position: absolute;
    pointer-events: none;
    z-index: 2147483647;
    border: 2px solid #00E5FF;
    background: rgba(0, 229, 255, 0.1);
    border-radius: 3px;
    transition: opacity 0.3s ease;
  `;

  let highlightEl = null;

  function showHighlight(rect) {
    if (!highlightEl) {
      highlightEl = document.createElement('div');
      highlightEl.setAttribute('style', AIOS_HIGHLIGHT_STYLE);
      document.body.appendChild(highlightEl);
    }

    highlightEl.style.left = (rect.left + window.scrollX) + 'px';
    highlightEl.style.top = (rect.top + window.scrollY) + 'px';
    highlightEl.style.width = rect.width + 'px';
    highlightEl.style.height = rect.height + 'px';
    highlightEl.style.opacity = '1';

    // 2 秒后淡出
    setTimeout(() => {
      if (highlightEl) highlightEl.style.opacity = '0';
    }, 2000);
  }

  function hideHighlight() {
    if (highlightEl) {
      highlightEl.style.opacity = '0';
    }
  }

  // ════════════════════════════════════════════════════════════════
  //  消息处理 — 接收来自 background.js 的指令
  // ════════════════════════════════════════════════════════════════

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'highlight') {
      // 高亮指定元素
      const el = document.querySelector(msg.selector);
      if (el) {
        showHighlight(el.getBoundingClientRect());
        sendResponse({ highlighted: true });
      } else {
        sendResponse({ highlighted: false, error: 'Element not found' });
      }
      return true;
    }

    if (msg.type === 'clear_highlight') {
      hideHighlight();
      sendResponse({ cleared: true });
      return true;
    }

    if (msg.type === 'ping') {
      sendResponse({ pong: true, url: window.location.href });
      return true;
    }
  });

  console.log('[AIOS Bridge] Content script loaded on', window.location.href);
}
