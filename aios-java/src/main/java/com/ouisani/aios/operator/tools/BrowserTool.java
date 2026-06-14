package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import com.ouisani.aios.vfs.ChromeBridgeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 浏览器控制工具 — 对标 OpenClaw 的 Browser Control 能力。
 * <p>
 * 通过 Chrome 扩展桥接实现 DOM 级别的浏览器自动化，
 * 比像素级 ComputerUseTool 更精准、更高效。
 * <p>
 * 支持的动作：
 * <ul>
 *   <li>navigate — 导航到指定 URL</li>
 *   <li>click_element — 通过 CSS 选择器点击元素</li>
 *   <li>type_text — 在指定输入框中输入文本</li>
 *   <li>extract_text — 提取页面文本内容</li>
 *   <li>execute_script — 执行 JavaScript 脚本</li>
 *   <li>get_page_source — 获取页面 HTML 源码</li>
 *   <li>wait_for_element — 等待元素出现</li>
 *   <li>screenshot — 浏览器级截图（比全屏截图更精准）</li>
 *   <li>get_active_tab — 获取当前活动标签页信息</li>
 *   <li>list_tabs — 列出所有标签页</li>
 * </ul>
 *
 * @see ChromeBridgeNode
 * @see ComputerUseTool
 */
public class BrowserTool implements Tool<BrowserTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    /** 浏览器命令响应的最大等待时间（秒） */
    private static final long RESPONSE_TIMEOUT_SECONDS = 15;

    private final ChromeBridgeNode bridge;

    /** 响应等待机制 — 浏览器扩展执行完命令后回写结果 */
    private volatile CompletableFuture<String> pendingResponse;

    public BrowserTool(ChromeBridgeNode bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public String description() {
        return "DOM-level browser control via Chrome extension bridge. "
                + "Supports navigate, click_element, type_text, extract_text, execute_script, "
                + "get_page_source, wait_for_element, screenshot, get_active_tab, list_tabs. "
                + "More precise than pixel-level computer_use for web automation.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["navigate", "click_element", "type_text", "extract_text",
                               "execute_script", "get_page_source", "wait_for_element",
                               "screenshot", "get_active_tab", "list_tabs"],
                      "description": "Browser action to perform"
                    },
                    "url": { "type": "string", "description": "URL to navigate to (for navigate action)" },
                    "selector": { "type": "string", "description": "CSS selector for target element" },
                    "text": { "type": "string", "description": "Text to type (for type_text action)" },
                    "script": { "type": "string", "description": "JavaScript to execute (for execute_script action)" },
                    "timeout_ms": { "type": "integer", "description": "Timeout in ms for wait_for_element (default 5000)" }
                  },
                  "required": ["action"]
                }""";
    }

    @Override
    public boolean readOnly() {
        return false; // navigate/click/type 都会修改浏览器状态
    }

    @Override
    public String prompt() {
        return """
                ## Browser Tool — DOM-Level Web Automation

                Use this tool for precise web page interaction via Chrome extension bridge.
                This is more reliable than pixel-level `computer_use` for web tasks.

                ### Actions:
                - `navigate`: Go to a URL. Params: url
                - `click_element`: Click an element by CSS selector. Params: selector
                - `type_text`: Type text into an input element. Params: selector, text
                - `extract_text`: Extract visible text from page or element. Params: selector (optional)
                - `execute_script`: Run arbitrary JavaScript in the page. Params: script
                - `get_page_source`: Get the full HTML source of the current page.
                - `wait_for_element`: Wait for an element to appear. Params: selector, timeout_ms
                - `screenshot`: Take a browser-level screenshot of the current page.
                - `get_active_tab`: Get info about the currently active browser tab.
                - `list_tabs`: List all open browser tabs.

                ### Strategy:
                1. First use `get_active_tab` or `list_tabs` to understand current browser state
                2. Use `navigate` to go to the target page
                3. Use `wait_for_element` to ensure the page is loaded
                4. Use `click_element` / `type_text` for form interactions
                5. Use `extract_text` or `execute_script` to read page content
                6. If browser is not connected, fall back to `computer_use` tool
                """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        if (!bridge.isConnected()) {
            return ToolOutput.fail("Browser not connected. Chrome extension bridge is offline. "
                    + "Install the AIOS Chrome extension and ensure it's connected via WebSocket. "
                    + "Falling back to computer_use is recommended.");
        }

        String action = input.action();
        log.info("[BrowserTool] Executing action: {}", action);

        try {
            return switch (action) {
                case "navigate" -> executeNavigate(input);
                case "click_element" -> executeClickElement(input);
                case "type_text" -> executeTypeText(input);
                case "extract_text" -> executeExtractText(input);
                case "execute_script" -> executeScript(input);
                case "get_page_source" -> executeGetPageSource();
                case "wait_for_element" -> executeWaitForElement(input);
                case "screenshot" -> executeScreenshot();
                case "get_active_tab" -> executeGetActiveTab();
                case "list_tabs" -> executeListTabs();
                default -> ToolOutput.fail("Unknown browser action: " + action);
            };
        } catch (Exception e) {
            log.error("[BrowserTool] Action '{}' failed: {}", action, e.getMessage());
            return ToolOutput.fail("Browser action '" + action + "' failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  动作实现
    // ════════════════════════════════════════════════════════════════

    private ToolOutput executeNavigate(Input input) {
        if (input.url() == null || input.url().isBlank()) {
            return ToolOutput.fail("URL is required for navigate action");
        }
        String command = buildCommand("navigate", """
                {"action":"navigate","url":"%s"}""".stripIndent().formatted(
                        escapeJson(input.url())));
        return sendCommandAndWait(command, "navigate");
    }

    private ToolOutput executeClickElement(Input input) {
        if (input.selector() == null || input.selector().isBlank()) {
            return ToolOutput.fail("CSS selector is required for click_element action");
        }
        String command = buildCommand("click_element", """
                {"action":"click_element","selector":"%s"}""".stripIndent().formatted(
                        escapeJson(input.selector())));
        return sendCommandAndWait(command, "click_element");
    }

    private ToolOutput executeTypeText(Input input) {
        if (input.selector() == null || input.selector().isBlank()) {
            return ToolOutput.fail("CSS selector is required for type_text action");
        }
        if (input.text() == null) {
            return ToolOutput.fail("Text is required for type_text action");
        }
        String command = buildCommand("type_text", """
                {"action":"type_text","selector":"%s","text":"%s"}""".stripIndent().formatted(
                        escapeJson(input.selector()), escapeJson(input.text())));
        return sendCommandAndWait(command, "type_text");
    }

    private ToolOutput executeExtractText(Input input) {
        String selector = input.selector();
        String command;
        if (selector != null && !selector.isBlank()) {
            command = buildCommand("extract_text", """
                    {"action":"extract_text","selector":"%s"}""".stripIndent().formatted(
                            escapeJson(selector)));
        } else {
            command = buildCommand("extract_text", """
                    {"action":"extract_text"}""");
        }
        return sendCommandAndWait(command, "extract_text");
    }

    private ToolOutput executeScript(Input input) {
        if (input.script() == null || input.script().isBlank()) {
            return ToolOutput.fail("Script is required for execute_script action");
        }
        String command = buildCommand("execute_script", """
                {"action":"execute_script","script":"%s"}""".stripIndent().formatted(
                        escapeJson(input.script())));
        return sendCommandAndWait(command, "execute_script");
    }

    private ToolOutput executeGetPageSource() {
        String command = buildCommand("get_page_source", """
                {"action":"get_page_source"}""");
        return sendCommandAndWait(command, "get_page_source");
    }

    private ToolOutput executeWaitForElement(Input input) {
        if (input.selector() == null || input.selector().isBlank()) {
            return ToolOutput.fail("CSS selector is required for wait_for_element action");
        }
        long timeoutMs = input.timeoutMs() > 0 ? input.timeoutMs() : 5000;
        String command = buildCommand("wait_for_element", """
                {"action":"wait_for_element","selector":"%s","timeout_ms":%d}""".stripIndent().formatted(
                        escapeJson(input.selector()), timeoutMs));
        return sendCommandAndWait(command, "wait_for_element");
    }

    private ToolOutput executeScreenshot() {
        String command = buildCommand("screenshot", """
                {"action":"screenshot"}""");
        return sendCommandAndWait(command, "screenshot");
    }

    private ToolOutput executeGetActiveTab() {
        // 直接从 ChromeBridgeNode 缓存读取，无需发送命令
        String result = bridge.readSubPath("active_tab");
        return ToolOutput.ok(result);
    }

    private ToolOutput executeListTabs() {
        // 直接从 ChromeBridgeNode 缓存读取
        String result = bridge.readSubPath("tabs");
        return ToolOutput.ok(result);
    }

    // ════════════════════════════════════════════════════════════════
    //  命令发送与响应等待
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建带请求 ID 的命令（用于请求-响应匹配）。
     */
    private String buildCommand(String action, String payload) {
        return payload; // ChromeBridgeNode.write() 会广播到所有 session
    }

    /**
     * 发送命令到浏览器扩展并等待响应。
     * <p>
     * 使用 CompletableFuture 实现异步等待，超时后返回失败。
     */
    private ToolOutput sendCommandAndWait(String command, String actionName) {
        // 创建响应等待 Future
        pendingResponse = new CompletableFuture<>();

        // 通过 ChromeBridgeNode 发送命令
        boolean sent = bridge.write(command);
        if (!sent) {
            pendingResponse = null;
            return ToolOutput.fail("Failed to send command to browser: no active session");
        }

        // 等待浏览器扩展响应
        try {
            String response = pendingResponse.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pendingResponse = null;
            return ToolOutput.ok(response);
        } catch (TimeoutException e) {
            pendingResponse = null;
            log.warn("[BrowserTool] Timeout waiting for browser response: action={}", actionName);
            return ToolOutput.fail("Browser response timeout for action: " + actionName
                    + " (waited " + RESPONSE_TIMEOUT_SECONDS + "s). "
                    + "The Chrome extension may be unresponsive.");
        } catch (Exception e) {
            pendingResponse = null;
            return ToolOutput.fail("Error waiting for browser response: " + e.getMessage());
        }
    }

    /**
     * 接收浏览器扩展的响应 — 由 ChromeBridgeNode 回调。
     */
    public void onBrowserResponse(String response) {
        CompletableFuture<String> future = pendingResponse;
        if (future != null && !future.isDone()) {
            future.complete(response);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 浏览器工具输入参数。
     */
    public record Input(
            String action,
            String url,
            String selector,
            String text,
            String script,
            long timeoutMs
    ) implements ToolInput {
        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            if (url != null) sb.append(",\"url\":\"").append(url).append("\"");
            if (selector != null) sb.append(",\"selector\":\"").append(selector).append("\"");
            if (text != null) sb.append(",\"text\":\"").append(text).append("\"");
            if (script != null) sb.append(",\"script\":\"").append(script).append("\"");
            if (timeoutMs > 0) sb.append(",\"timeout_ms\":").append(timeoutMs);
            sb.append("}");
            return sb.toString();
        }
    }
}
