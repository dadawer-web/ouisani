package com.ouisani.aios.operator.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.tool.*;
import com.ouisani.aios.operator.vision.VisionService;
import com.ouisani.aios.user.bridge.rpa.HostRpaManager;
import com.ouisani.aios.user.bridge.rpa.SecurityToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 桌面 GUI 控制工具 — 双引擎降级策略。
 * <p>
 * 引擎一：OS 原生无障碍 API（AT-SPI / UIAutomation / AppleScript）
 *         通过 Python 探针 os_ui_bridge.py 查找 UI 控件坐标
 * <p>
 * 引擎二：VLM 视觉模型降级
 *         截图 → 多模态模型分析 → 提取目标元素坐标
 * <p>
 * 适用场景：需要按名称/语义定位桌面应用 UI 控件时，
 * 比 ComputerUseTool 的纯像素坐标操作更精准。
 * <p>
 * 调用链路：
 * LLM 调用 desktop_gui(action, app_name, element_name)
 *   → 引擎一: python3 os_ui_bridge.py find <app> <element>
 *     → 命中 → Robot 点击
 *   → 未命中 → 引擎二: 截图 + VLM 分析坐标
 *     → Robot 点击
 *
 * @see ComputerUseTool
 * @see VisionService
 */
public class DesktopGuiTool implements Tool<DesktopGuiTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(DesktopGuiTool.class);

    private final VisionService visionService;
    private final HostRpaManager rpa;
    private final SecurityToken token;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Python 探针路径 */
    private static final String BRIDGE_SCRIPT = "scripts/os_ui_bridge.py";

    /** Python 进程超时（秒） */
    private static final int BRIDGE_TIMEOUT_SECONDS = 10;

    public DesktopGuiTool(VisionService visionService, SecurityToken token) {
        this.visionService = visionService;
        this.rpa = HostRpaManager.getInstance();
        this.token = token;
    }

    @Override
    public String name() {
        return "desktop_gui";
    }

    @Override
    public String description() {
        return "Desktop GUI control with dual-engine element location. "
                + "Actions: find_and_click, find_and_type, find_element. "
                + "Engine 1: OS Accessibility API (AT-SPI/UIAutomation). "
                + "Engine 2: VLM Vision fallback. "
                + "Use this when you need to interact with a specific UI element by name, "
                + "rather than by pixel coordinates.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["find_and_click", "find_and_type", "find_element"],
                      "description": "GUI action to perform"
                    },
                    "app_name": { "type": "string", "description": "Target application name (e.g. 'Firefox', '微信')" },
                    "element_name": { "type": "string", "description": "UI element to find (e.g. '地址栏', 'Send button')" },
                    "text": { "type": "string", "description": "Text to type (for find_and_type action)" }
                  },
                  "required": ["action", "app_name", "element_name"]
                }""";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return """
                ## Desktop GUI Tool (desktop_gui) — Dual-Engine Element Location

                Use this tool to interact with desktop UI elements by name/semantics,
                rather than by pixel coordinates. It uses two engines:

                **Engine 1 (Accessibility API):** Queries the OS accessibility tree
                (AT-SPI on Linux, UIAutomation on Windows) to find exact element coordinates.
                Fast and precise, but only works with accessible applications.

                **Engine 2 (VLM Vision):** Takes a screenshot and asks the multimodal model
                to locate the target element. Works with any application, but slower.

                **Actions:**
                - `find_and_click`: Find element and click it.
                - `find_and_type`: Find element, click it, then type text.
                - `find_element`: Just find and return coordinates (no click).

                **When to use:**
                - When you know the name/label of a UI element (e.g. "Save button", "地址栏")
                - When ComputerUseTool pixel coordinates are unreliable
                - When interacting with desktop apps (not browser — use `browser` tool for that)

                **Example:**
                - desktop_gui(action="find_and_click", app_name="Firefox", element_name="地址栏")
                - desktop_gui(action="find_and_type", app_name="微信", element_name="消息输入框", text="Hello!")
                """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        if (!rpa.isAvailable()) {
            return ToolOutput.fail("RPA subsystem not available — cannot control desktop");
        }

        String appName = input.appName();
        String elementName = input.elementName();

        log.info("[DesktopGui] Intent: {} on '{}' in app '{}'", input.action(), elementName, appName);

        // ════════════════════════════════════════════════════════════
        //  引擎一：OS 原生无障碍 API
        // ════════════════════════════════════════════════════════════
        ElementLocation location = tryAccessibilityApi(appName, elementName);

        // ════════════════════════════════════════════════════════════
        //  引擎二：VLM 视觉降级
        // ════════════════════════════════════════════════════════════
        if (!location.found) {
            log.warn("[DesktopGui] Engine 1 MISS: {}. Falling back to VLM Vision...", location.error);
            location = tryVlmVision(elementName);
        }

        if (!location.found) {
            return ToolOutput.fail("Both engines failed. Accessibility: " + location.error);
        }

        // ════════════════════════════════════════════════════════════
        //  执行物理操作
        // ════════════════════════════════════════════════════════════
        return executePhysicalAction(input.action(), location, input.text());
    }

    // ───────────────────────────────────────────────────────────────
    //  引擎一：OS 无障碍 API（Python 探针）
    // ───────────────────────────────────────────────────────────────

    private ElementLocation tryAccessibilityApi(String appName, String elementName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", BRIDGE_SCRIPT, "find", appName, elementName
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ElementLocation(false, -1, -1, 0, 0, "Python bridge timeout", "accessibility");
            }

            String jsonOutput = output.toString().trim();
            if (jsonOutput.isEmpty()) {
                return new ElementLocation(false, -1, -1, 0, 0, "Python bridge returned empty output", "accessibility");
            }

            JsonNode result = mapper.readTree(jsonOutput);

            if (result.path("found").asBoolean(false)) {
                int x = result.path("x").asInt(-1);
                int y = result.path("y").asInt(-1);
                int w = result.path("width").asInt(0);
                int h = result.path("height").asInt(0);
                String method = result.path("method").asText("accessibility");

                log.info("[DesktopGui] Engine 1 HIT! Coords: ({}, {}) size: {}x{} via {}",
                        x, y, w, h, method);

                // 如果有宽高，点击中心点
                if (w > 0 && h > 0) {
                    x = x + w / 2;
                    y = y + h / 2;
                }

                return new ElementLocation(true, x, y, w, h, null, method);
            } else {
                String error = result.path("error").asText("Unknown error");
                return new ElementLocation(false, -1, -1, 0, 0, error, "accessibility");
            }

        } catch (Exception e) {
            log.error("[DesktopGui] Python bridge failed: {}", e.getMessage());
            return new ElementLocation(false, -1, -1, 0, 0, e.getMessage(), "accessibility");
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  引擎二：VLM 视觉降级
    // ───────────────────────────────────────────────────────────────

    private ElementLocation tryVlmVision(String elementName) {
        if (visionService == null || !visionService.isAvailable()) {
            return new ElementLocation(false, -1, -1, 0, 0,
                    "VisionService not available (no multimodal provider)", "vlm_vision");
        }

        try {
            // 1. 截图
            String screenshotBase64 = rpa.takeScreenshotBase64(token);
            int sentW = rpa.getSentWidth();
            int sentH = rpa.getSentHeight();

            // 2. 调用 VLM 分析目标元素坐标
            String coords = visionService.analyzeScreenshotForElement(
                    screenshotBase64, sentW, sentH, elementName);

            // 3. 解析坐标（格式：x,y）
            if (coords == null || coords.isBlank()) {
                return new ElementLocation(false, -1, -1, 0, 0,
                        "VLM returned empty coordinates", "vlm_vision");
            }

            String[] parts = coords.split(",");
            if (parts.length < 2) {
                return new ElementLocation(false, -1, -1, 0, 0,
                        "VLM returned invalid format: " + coords, "vlm_vision");
            }

            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());

            log.info("[DesktopGui] Engine 2 HIT! VLM calculated coords: ({}, {})", x, y);

            return new ElementLocation(true, x, y, 0, 0, null, "vlm_vision");

        } catch (Exception e) {
            log.error("[DesktopGui] VLM 视觉失败: {}", e.getMessage());
            return new ElementLocation(false, -1, -1, 0, 0, e.getMessage(), "vlm_vision");
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  物理执行
    // ───────────────────────────────────────────────────────────────

    private ToolOutput executePhysicalAction(String action, ElementLocation loc, String text) {
        try {
            switch (action) {
                case "find_and_click" -> {
                    rpa.leftClick(token, loc.x, loc.y);
                    return ToolOutput.ok("Clicked '" + loc.elementHint() + "' at ("
                            + loc.x + ", " + loc.y + ") via " + loc.method);
                }
                case "find_and_type" -> {
                    // 先点击聚焦
                    rpa.leftClick(token, loc.x, loc.y);
                    rpa.typeViaClipboard(token, text != null ? text : "");
                    return ToolOutput.ok("Clicked and typed at (" + loc.x + ", " + loc.y
                            + ") via " + loc.method + ": \"" + (text != null && text.length() > 50
                            ? text.substring(0, 50) + "..." : text) + "\"");
                }
                case "find_element" -> {
                    return ToolOutput.ok("Element found at (" + loc.x + ", " + loc.y
                            + ") size: " + loc.width + "x" + loc.height
                            + " via " + loc.method);
                }
                default -> {
                    return ToolOutput.fail("Unknown action: " + action);
                }
            }
        } catch (Exception e) {
            return ToolOutput.fail("Physical execution failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据类
    // ════════════════════════════════════════════════════════════════

    private record ElementLocation(boolean found, int x, int y, int width, int height,
                                   String error, String method) {
        String elementHint() {
            return method != null ? "[" + method + "]" : "";
        }
    }

    /** 工具输入 */
    public record Input(
            String action,
            String appName,
            String elementName,
            String text
    ) implements ToolInput {
        public Input {
            if (action == null) throw new IllegalArgumentException("action is required");
            if (appName == null) throw new IllegalArgumentException("app_name is required");
            if (elementName == null) throw new IllegalArgumentException("element_name is required");
        }

        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            sb.append(",\"app_name\":\"").append(escape(appName)).append("\"");
            sb.append(",\"element_name\":\"").append(escape(elementName)).append("\"");
            if (text != null) sb.append(",\"text\":\"").append(escape(text)).append("\"");
            sb.append("}");
            return sb.toString();
        }

        private String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
