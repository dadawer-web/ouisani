package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.tool.*;
import com.ouisani.aios.operator.vision.VisionService;
import com.ouisani.aios.user.bridge.rpa.HostRpaManager;
import com.ouisani.aios.user.bridge.rpa.SecurityToken;

import java.awt.Dimension;

/**
 * 电脑操作工具 — 对标 OpenClaw 的 Computer Use 能力。
 * <p>
 * 这是 OperatorAgent 的核心工具，将底层 {@link HostRpaManager}（java.awt.Robot）
 * 暴露为 LLM 可调用的 Tool 接口，赋予第二母体物理操作宿主机的能力：
 * <ul>
 *   <li>视觉：截图（全屏/区域）</li>
 *   <li>鼠标：移动、左键点击、右键点击、滚轮</li>
 *   <li>键盘：输入文本、单键、组合键</li>
 * </ul>
 * <p>
 * 安全机制：所有操作通过 SYS_ADMIN SecurityToken 鉴权，
 * BpfManager 在 syscall 层做二次拦截。
 * <p>
 * OS 类比：相当于 /dev/input — 用户态程序通过此设备文件
 * 访问物理输入设备，内核负责权限检查。
 */
public class ComputerUseTool implements Tool<ComputerUseTool.Input> {

    private final HostRpaManager rpa;
    private final SecurityToken token;
    private VisionService visionService;

    /**
     * @param token SYS_ADMIN 级别的安全令牌，由 InitDaemon 签发
     */
    public ComputerUseTool(SecurityToken token) {
        this.rpa = HostRpaManager.getInstance();
        this.token = token;
    }

    /** 注入视觉服务 — 截图后自动调用多模态模型理解屏幕 */
    public void setVisionService(VisionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public String name() { return "computer_use"; }

    @Override
    public String description() {
        return "Control the host computer physically. Actions: screenshot, mouse_move, mouse_click, mouse_right_click, "
                + "click_at, scroll, type_text, key_press, key_combo, "
                + "get_mouse_position, get_pixel_color, "
                + "list_windows, activate_window, close_window, minimize_window, maximize_window. "
                + "Use 'screenshot' to see the screen, 'click_at' to click at coordinates, "
                + "'type_text' to type text, 'key_combo' for keyboard shortcuts (Ctrl+C etc), "
                + "'list_windows' to see all open windows, 'activate_window' to bring a window to front. "
                + "IMPORTANT: Always take a screenshot first before interacting with the screen to verify coordinates.";
    }

    @Override
    public String inputSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["screenshot","mouse_move","mouse_click","mouse_right_click","click_at","scroll","type_text","key_press","key_combo",
                       "get_mouse_position","get_pixel_color","list_windows","activate_window","close_window","minimize_window","maximize_window"],
              "description": "The computer use action to perform"
            },
            "x": { "type": "integer", "description": "X coordinate (for mouse_move, click_at, screenshot region, get_pixel_color)" },
            "y": { "type": "integer", "description": "Y coordinate (for mouse_move, click_at, screenshot region, get_pixel_color)" },
            "width": { "type": "integer", "description": "Width for region screenshot" },
            "height": { "type": "integer", "description": "Height for region screenshot" },
            "text": { "type": "string", "description": "Text to type (for type_text) or window title (for window actions)" },
            "keyCode": { "type": "integer", "description": "Java KeyEvent VK code (for key_press, key_combo)" },
            "amount": { "type": "integer", "description": "Scroll amount (positive=down, negative=up)" },
            "ctrl": { "type": "boolean", "description": "Hold Ctrl (for key_combo)" },
            "alt": { "type": "boolean", "description": "Hold Alt (for key_combo)" },
            "shift": { "type": "boolean", "description": "Hold Shift (for key_combo)" },
            "meta": { "type": "boolean", "description": "Hold Meta/Super (for key_combo)" }
          },
          "required": ["action"]
        }
        """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        // 检查 RPA 是否可用
        if (!rpa.isAvailable()) {
            return ToolOutput.fail("RPA subsystem not available — java.awt.Robot initialization failed (headless mode?)");
        }

        try {
            return switch (input.action()) {
                case "screenshot" -> handleScreenshot(input);
                case "mouse_move" -> handleMouseMove(input);
                case "mouse_click" -> handleClick();
                case "mouse_right_click" -> handleRightClick();
                case "click_at" -> handleClickAt(input);
                case "scroll" -> handleScroll(input);
                case "type_text" -> handleTypeText(input);
                case "key_press" -> handleKeyPress(input);
                case "key_combo" -> handleKeyCombo(input);
                // ── 窗口管理与辅助 ──
                case "get_mouse_position" -> handleGetMousePosition();
                case "get_pixel_color" -> handleGetPixelColor(input);
                case "list_windows" -> handleListWindows();
                case "activate_window" -> handleActivateWindow(input);
                case "close_window" -> handleCloseWindow(input);
                case "minimize_window" -> handleMinimizeWindow(input);
                case "maximize_window" -> handleMaximizeWindow(input);
                default -> ToolOutput.fail("Unknown action: " + input.action());
            };
        } catch (SecurityException e) {
            return ToolOutput.fail("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            return ToolOutput.fail("Computer use error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  VISION — 截图
    // ════════════════════════════════════════════════════════════════

    private ToolOutput handleScreenshot(Input input) {
        String base64;
        int sentW, sentH;

        if (input.x() >= 0 && input.y() >= 0 && input.width() > 0 && input.height() > 0) {
            // 区域截图（zoom）
            base64 = rpa.takeScreenshotBase64(token, input.x(), input.y(), input.width(), input.height());
        } else {
            // 全屏截图
            base64 = rpa.takeScreenshotBase64(token);
        }

        // 获取缩放后的尺寸（模型看到的坐标空间）
        sentW = rpa.getSentWidth();
        sentH = rpa.getSentHeight();

        StringBuilder result = new StringBuilder();
        result.append("Screenshot captured. Image size: ").append(sentW).append("x").append(sentH);
        result.append(" (coordinates in this image space, origin top-left).");

        // ── 调用多模态模型理解屏幕内容 ──
        if (visionService != null && visionService.isAvailable()) {
            result.append("\n\n--- Screen Analysis (via multimodal model) ---\n");
            String analysis = visionService.analyzeScreenshot(base64, sentW, sentH);
            result.append(analysis);
        } else {
            result.append("\n[Note: Multimodal vision not available — screenshot captured but not analyzed. "
                    + "Configure MULTIMODAL_* env vars to enable screen understanding.]");
        }

        return ToolOutput.ok(result.toString());
    }

    // ════════════════════════════════════════════════════════════════
    //  MOUSE — 鼠标操作
    // ════════════════════════════════════════════════════════════════

    private ToolOutput handleMouseMove(Input input) {
        if (input.x() < 0 || input.y() < 0) {
            return ToolOutput.fail("x and y coordinates are required for mouse_move");
        }
        rpa.mouseMove(token, input.x(), input.y());
        return ToolOutput.ok("Mouse moved to (" + input.x() + ", " + input.y()
                + ") in " + rpa.getSentWidth() + "x" + rpa.getSentHeight() + " image space");
    }

    private ToolOutput handleClick() {
        rpa.mouseClick(token);
        return ToolOutput.ok("Left click performed at current position");
    }

    private ToolOutput handleRightClick() {
        rpa.mouseRightClick(token);
        return ToolOutput.ok("Right click performed at current position");
    }

    private ToolOutput handleClickAt(Input input) {
        if (input.x() < 0 || input.y() < 0) {
            return ToolOutput.fail("x and y coordinates are required for click_at");
        }
        rpa.leftClick(token, input.x(), input.y());
        return ToolOutput.ok("Left clicked at (" + input.x() + ", " + input.y()
                + ") in " + rpa.getSentWidth() + "x" + rpa.getSentHeight() + " image space");
    }

    private ToolOutput handleScroll(Input input) {
        int amount = input.amount() != 0 ? input.amount() : 3;
        String direction = amount > 0 ? "down" : "up";
        rpa.scroll(token, input.x() >= 0 ? input.x() : 0, input.y() >= 0 ? input.y() : 0, direction, Math.abs(amount));
        return ToolOutput.ok("Scrolled " + direction + " by " + Math.abs(amount));
    }

    // ════════════════════════════════════════════════════════════════
    //  KEYBOARD — 键盘操作（剪贴板粘贴机制）
    // ════════════════════════════════════════════════════════════════

    private ToolOutput handleTypeText(Input input) {
        if (input.text() == null || input.text().isEmpty()) {
            return ToolOutput.fail("text is required for type_text");
        }
        rpa.typeViaClipboard(token, input.text());
        return ToolOutput.ok("Typed text via clipboard paste: \""
                + (input.text().length() > 50 ? input.text().substring(0, 50) + "..." : input.text()) + "\"");
    }

    private ToolOutput handleKeyPress(Input input) {
        if (input.keyCode() <= 0) {
            return ToolOutput.fail("keyCode is required for key_press (use Java KeyEvent.VK_ constants)");
        }
        rpa.keyPress(token, input.keyCode());
        return ToolOutput.ok("Key pressed: VK_" + input.keyCode());
    }

    private ToolOutput handleKeyCombo(Input input) {
        if (input.keyCode() <= 0) {
            return ToolOutput.fail("keyCode is required for key_combo");
        }
        int modifiers = 0;
        if (input.ctrl()) modifiers |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
        if (input.alt()) modifiers |= java.awt.event.InputEvent.ALT_DOWN_MASK;
        if (input.shift()) modifiers |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        if (input.meta()) modifiers |= java.awt.event.InputEvent.META_DOWN_MASK;

        rpa.keyCombo(token, modifiers, input.keyCode());

        StringBuilder combo = new StringBuilder();
        if (input.ctrl()) combo.append("Ctrl+");
        if (input.alt()) combo.append("Alt+");
        if (input.shift()) combo.append("Shift+");
        if (input.meta()) combo.append("Meta+");
        combo.append("VK_").append(input.keyCode());

        return ToolOutput.ok("Key combo pressed: " + combo);
    }

    // ════════════════════════════════════════════════════════════════
    //  WINDOW MANAGEMENT — 窗口管理 (对标 OpenClaw Desktop Control)
    // ════════════════════════════════════════════════════════════════

    private ToolOutput handleGetMousePosition() {
        var pos = rpa.getMousePosition(token);
        return ToolOutput.ok("Mouse position: (" + pos.x + ", " + pos.y
                + ") in " + rpa.getSentWidth() + "x" + rpa.getSentHeight() + " image space");
    }

    private ToolOutput handleGetPixelColor(Input input) {
        if (input.x() < 0 || input.y() < 0) {
            return ToolOutput.fail("x and y coordinates are required for get_pixel_color");
        }
        String color = rpa.getPixelColor(token, input.x(), input.y());
        return ToolOutput.ok("Pixel color at (" + input.x() + ", " + input.y() + "): " + color);
    }

    private ToolOutput handleListWindows() {
        String windows = rpa.listWindows(token);
        return ToolOutput.ok("Open windows:\n" + windows);
    }

    private ToolOutput handleActivateWindow(Input input) {
        String title = input.text();
        if (title == null || title.isBlank()) {
            return ToolOutput.fail("Window title (text field) is required for activate_window");
        }
        boolean ok = rpa.activateWindow(token, title);
        return ok ? ToolOutput.ok("Window activated: " + title)
                  : ToolOutput.fail("Failed to activate window: " + title + " (not found or wmctrl not installed)");
    }

    private ToolOutput handleCloseWindow(Input input) {
        String title = input.text();
        if (title == null || title.isBlank()) {
            return ToolOutput.fail("Window title (text field) is required for close_window");
        }
        boolean ok = rpa.closeWindow(token, title);
        return ok ? ToolOutput.ok("Window closed: " + title)
                  : ToolOutput.fail("Failed to close window: " + title);
    }

    private ToolOutput handleMinimizeWindow(Input input) {
        String title = input.text();
        if (title == null || title.isBlank()) {
            return ToolOutput.fail("Window title (text field) is required for minimize_window");
        }
        boolean ok = rpa.minimizeWindow(token, title);
        return ok ? ToolOutput.ok("Window minimized: " + title)
                  : ToolOutput.fail("Failed to minimize window: " + title);
    }

    private ToolOutput handleMaximizeWindow(Input input) {
        String title = input.text();
        if (title == null || title.isBlank()) {
            return ToolOutput.fail("Window title (text field) is required for maximize_window");
        }
        boolean ok = rpa.maximizeWindow(token, title);
        return ok ? ToolOutput.ok("Window maximized: " + title)
                  : ToolOutput.fail("Failed to maximize window: " + title);
    }

    @Override
    public boolean readOnly() {
        return false; // 鼠标/键盘操作会修改宿主机状态
    }

    @Override
    public String prompt() {
        return """
        ## Computer Use Tool (computer_use) — Anthropic Computer Use Spec + OpenClaw Desktop Control

        You have direct physical control of the host computer via java.awt.Robot.
        Coordinates are in the screenshot image space (origin top-left).
        The screenshot is automatically scaled down to max 1280px wide to save tokens.

        **Actions:**
        - `screenshot` — Capture screen. Returns image size (your coordinate space) + multimodal analysis.
        - `mouse_move` — Move cursor to (x, y) in image space.
        - `click_at` — Move + left click at (x, y) in image space. **Preferred over mouse_move + mouse_click.**
        - `mouse_click` — Left click at current position.
        - `mouse_right_click` — Right click at current position.
        - `scroll` — Scroll at position. Use `amount` (positive=down, negative=up).
        - `type_text` — Type text via **clipboard paste** (Ctrl+V). Supports Chinese, emoji, any characters!
        - `key_combo` — Press key combination by name (e.g. ctrl=true, keyCode=67 for Ctrl+C).
        - `key_press` — Press a single key by Java VK code.
        - `get_mouse_position` — Get current mouse cursor coordinates in image space.
        - `get_pixel_color` — Get the hex color of a pixel at (x, y).
        - `list_windows` — List all open windows on the desktop.
        - `activate_window` — Bring a window to front by title (use `text` field for title).
        - `close_window` — Close a window by title (use `text` field).
        - `minimize_window` — Minimize a window by title (use `text` field).
        - `maximize_window` — Maximize a window by title (use `text` field).

        **Critical Rules (Anthropic Computer Use Protocol):**
        1. **ALWAYS screenshot first** before any mouse/keyboard action.
        2. **Coordinates are in screenshot image space**, NOT physical screen pixels.
           The system automatically maps your coordinates to the real screen.
        3. **One action at a time** — screenshot → verify → click → screenshot → verify.
        4. **Use click_at(x, y)** for precise clicking — it moves and clicks in one step.
        5. **Use type_text for ALL text input** — it uses clipboard paste (Ctrl+V),
           which handles Chinese, special chars, and any input method perfectly.
        6. **Use list_windows + activate_window** to switch between desktop applications.
        7. **Common key codes**: Enter=10, Escape=27, Tab=9, Backspace=8, Delete=127,
           Up=38, Down=40, Left=37, Right=39, C=67, V=86, A=65, S=83.

        Example workflows:
        - **Desktop app**: screenshot → list_windows → activate_window("Firefox") → screenshot → click_at(500,300) → type_text("hello")
        - **Web page**: screenshot → click_at(500,45) → screenshot → type_text("hello world")
        - **Window switch**: list_windows → activate_window("Terminal") → screenshot → type_text("ls -la")
        """;
    }

    /** 工具输入 */
    public record Input(
            String action,
            int x,
            int y,
            int width,
            int height,
            String text,
            int keyCode,
            int amount,
            boolean ctrl,
            boolean alt,
            boolean shift,
            boolean meta
    ) implements ToolInput {
        public Input {
            if (action == null) throw new IllegalArgumentException("action is required");
            // 默认值：坐标为 -1 表示未指定
        }

        /** 便捷构造 — 仅 action */
        public Input(String action) {
            this(action, -1, -1, -1, -1, null, 0, 0, false, false, false, false);
        }

        /** 便捷构造 — action + 坐标 */
        public Input(String action, int x, int y) {
            this(action, x, y, -1, -1, null, 0, 0, false, false, false, false);
        }

        /** 便捷构造 — action + 文本 */
        public static Input text(String action, String text) {
            return new Input(action, -1, -1, -1, -1, text, 0, 0, false, false, false, false);
        }

        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            if (x >= 0) sb.append(",\"x\":").append(x);
            if (y >= 0) sb.append(",\"y\":").append(y);
            if (width > 0) sb.append(",\"width\":").append(width);
            if (height > 0) sb.append(",\"height\":").append(height);
            if (text != null) sb.append(",\"text\":\"").append(text.replace("\"", "\\\"")).append("\"");
            if (keyCode > 0) sb.append(",\"keyCode\":").append(keyCode);
            if (amount != 0) sb.append(",\"amount\":").append(amount);
            if (ctrl) sb.append(",\"ctrl\":true");
            if (alt) sb.append(",\"alt\":true");
            if (shift) sb.append(",\"shift\":true");
            if (meta) sb.append(",\"meta\":true");
            sb.append("}");
            return sb.toString();
        }
    }
}
