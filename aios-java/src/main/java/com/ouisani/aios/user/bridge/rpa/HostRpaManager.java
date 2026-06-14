package com.ouisani.aios.user.bridge.rpa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主 RPA 桥接器 — 对标 Anthropic Computer Use 规范，1:1 复刻三大核心动作。
 * <p>
 * 已从内核空间 (core.sandbox.rpa) 降级至用户空间 (user.bridge.rpa)。
 * 这是物理级沙箱逃逸后门，任何调用都必须持有 SYS_ADMIN 级别的 SecurityToken。
 * <p>
 * 封装 {@link java.awt.Robot}，对标 Anthropic computer.py 的三大核心能力：
 * <ul>
 *   <li><b>screenshot</b> — 全屏截图 + 自动缩放（防止 Token 爆炸），返回 Base64 JPEG</li>
 *   <li><b>left_click</b> — 移动 + 左键单击，支持坐标缩放映射</li>
 *   <li><b>type</b> — 剪贴板粘贴机制，完美支持中文/特殊字符/任何输入法</li>
 * </ul>
 * <p>
 * <b>设计决策（对标 Anthropic computer.py）：</b>
 * <ul>
 *   <li>截图自动缩放至 maxWidth=1280，JPEG quality=0.72 — 平衡清晰度与 Token 消耗</li>
 *   <li>打字用剪贴板 Ctrl+V 粘贴 — 避开输入法/布局/特殊字符的所有坑</li>
 *   <li>坐标缩放：模型看到缩小后的坐标，通过 sentW/sentH 映射回真实屏幕像素</li>
 * </ul>
 *
 * @see SecurityToken
 * @see PermissionDeniedException
 */
public final class HostRpaManager {

    private static final Logger log = LoggerFactory.getLogger(HostRpaManager.class);

    /** 执行器操作间的默认延迟（毫秒），模拟人类节奏，防止被反作控系统拦截 */
    private static final int DEFAULT_ACTUATOR_DELAY_MS = 50;

    /** 截图缩放最大宽度 — 对标 Anthropic computer.py 的 target_image_size */
    private static final int SCREENSHOT_MAX_WIDTH = 1280;

    /** JPEG 压缩质量 (0.0-1.0) — 对标 Anthropic 的 0.72 */
    private static final float JPEG_QUALITY = 0.72f;

    /** 持有 SYS_ADMIN 权限的 SecurityToken 集合 */
    private static final Set<SecurityToken> authorizedTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final class Holder {
        static final HostRpaManager INSTANCE = new HostRpaManager();
    }

    public static HostRpaManager getInstance() {
        return Holder.INSTANCE;
    }

    private final Robot robot;
    private final Dimension screenSize;

    /** 最近一次发送给模型的截图尺寸（缩放后），用于坐标映射 */
    private volatile int sentW = 0;
    private volatile int sentH = 0;

    private volatile boolean initialized = false;

    private HostRpaManager() {
        // Wayland/X11 兼容：确保 DISPLAY 环境变量已设置
        if (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank()) {
            // 尝试自动检测 XWayland 显示号
            java.io.File x11Dir = new java.io.File("/tmp/.X11-unix");
            if (x11Dir.exists() && x11Dir.listFiles() != null) {
                for (var f : x11Dir.listFiles()) {
                    String name = f.getName();
                    if (name.startsWith("X")) {
                        String displayNum = name.substring(1);
                        System.setProperty("DISPLAY", ":" + displayNum);
                        log.info("[RPA Bridge] Auto-detected DISPLAY=:{}", displayNum);
                        break;
                    }
                }
            }
            if (System.getProperty("DISPLAY") == null) {
                System.setProperty("DISPLAY", ":0");
                log.info("[RPA Bridge] Fallback DISPLAY=:0");
            }
        }

        // 强制关闭无头模式
        System.setProperty("java.awt.headless", "false");

        Robot r;
        try {
            r = new Robot();
            r.setAutoWaitForIdle(true);
            r.setAutoDelay(DEFAULT_ACTUATOR_DELAY_MS);
        } catch (AWTException e) {
            log.error("[RPA Bridge] Failed to initialize java.awt.Robot: {}", e.getMessage());
            r = null;
        }
        this.robot = r;
        this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        if (robot != null) {
            initialized = true;
            log.info("[RPA Bridge] Host GUI Actuators and Vision initialized (user-space bridge).");
            System.out.println("[Security] Host RPA Manager demoted from core sandbox. Strict SYS_ADMIN capabilities now required for physical host access.");
        } else {
            log.warn("[RPA Bridge] Robot initialization failed — RPA capabilities disabled.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Security — SYS_ADMIN Token 鉴权
    // ════════════════════════════════════════════════════════════════

    public SecurityToken issueSysAdminToken(String requester) {
        SecurityToken token = new SecurityToken(requester, SecurityToken.Capability.SYS_ADMIN);
        authorizedTokens.add(token);
        log.warn("[RPA Bridge] SYS_ADMIN token issued to: {} (tokenId={})", requester, token.id());
        return token;
    }

    public void revokeToken(SecurityToken token) {
        authorizedTokens.remove(token);
        log.info("[RPA Bridge] Token revoked: tokenId={}", token.id());
    }

    public void requireSysAdmin(SecurityToken token) {
        if (token == null) {
            log.error("[RPA Bridge] Permission DENIED: null token");
            throw new PermissionDeniedException("Host RPA access requires SYS_ADMIN SecurityToken. Null token provided.");
        }
        if (!authorizedTokens.contains(token)) {
            log.error("[RPA Bridge] Permission DENIED: invalid/revoked token (id={})", token.id());
            throw new PermissionDeniedException("Host RPA access requires valid SYS_ADMIN SecurityToken. Token not found or revoked.");
        }
        if (token.capability() != SecurityToken.Capability.SYS_ADMIN) {
            log.error("[RPA Bridge] Permission DENIED: insufficient capability (has={}, required=SYS_ADMIN)", token.capability());
            throw new PermissionDeniedException("Host RPA access requires SYS_ADMIN capability. Token has: " + token.capability());
        }
        if (token.isExpired()) {
            authorizedTokens.remove(token);
            log.error("[RPA Bridge] Permission DENIED: expired token (id={})", token.id());
            throw new PermissionDeniedException("Host RPA access requires non-expired SYS_ADMIN SecurityToken. Token has expired.");
        }
    }

    /** 检查 RPA 子系统是否可用。 */
    public boolean isAvailable() {
        return initialized && robot != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  VISION — Screenshot Capture (对标 Anthropic computer.py)
    //
    //  关键设计：截图后自动缩放至 maxWidth=1280，JPEG quality=0.72
    //  这样可以大幅减少 Base64 体积，防止多模态模型 Token 爆炸
    // ════════════════════════════════════════════════════════════════

    /**
     * 全屏截图 — 对标 Anthropic computer.py 的 screenshot action。
     * <p>
     * 截图后自动缩放至 {@link #SCREENSHOT_MAX_WIDTH}，JPEG 压缩至 {@link #JPEG_QUALITY}，
     * 返回 Base64 编码。同时更新 sentW/sentH 供后续坐标映射使用。
     *
     * @param token SYS_ADMIN 令牌
     * @return Base64 编码的 JPEG 截图
     */
    public String takeScreenshotBase64(SecurityToken token) {
        requireSysAdmin(token);
        ensureAvailable();

        Rectangle screenRect = new Rectangle(screenSize);
        BufferedImage fullImage = robot.createScreenCapture(screenRect);

        // ── 缩放 — 对标 Anthropic 的 resize_and_encode ──
        BufferedImage scaled = resizeImage(fullImage, SCREENSHOT_MAX_WIDTH);

        sentW = scaled.getWidth();
        sentH = scaled.getHeight();

        String base64 = encodeToJpegBase64(scaled);
        log.info("[RPA Bridge] Screenshot: screen={}x{}, sent={}x{}, jpegSize={} bytes",
                screenSize.width, screenSize.height, sentW, sentH,
                Base64.getDecoder().decode(base64).length);
        return base64;
    }

    /**
     * 区域截图 — 对标 Anthropic computer.py 的 zoom action。
     */
    public String takeScreenshotBase64(SecurityToken token, int x, int y, int width, int height) {
        requireSysAdmin(token);
        ensureAvailable();

        Rectangle region = new Rectangle(x, y, width, height);
        BufferedImage regionImage = robot.createScreenCapture(region);

        BufferedImage scaled = resizeImage(regionImage, SCREENSHOT_MAX_WIDTH);

        sentW = scaled.getWidth();
        sentH = scaled.getHeight();

        String base64 = encodeToJpegBase64(scaled);
        log.info("[RPA Bridge] Region screenshot: ({},{}) {}x{}, sent={}x{}",
                x, y, width, height, sentW, sentH);
        return base64;
    }

    public Dimension getScreenSize() {
        return screenSize;
    }

    /** 获取最近发送给模型的截图尺寸（缩放后），供坐标映射使用 */
    public int getSentWidth() { return sentW; }
    public int getSentHeight() { return sentH; }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Mouse Control (对标 Anthropic computer.py)
    //
    //  关键设计：坐标缩放映射
    //  模型看到的是缩放后的截图坐标，需要映射回真实屏幕像素
    // ════════════════════════════════════════════════════════════════

    /**
     * 左键点击 — 对标 Anthropic computer.py 的 left_click action。
     * <p>
     * 接收模型空间的坐标（缩放后截图的像素），自动映射到真实屏幕像素。
     *
     * @param token SYS_ADMIN 令牌
     * @param imageX 模型空间 X 坐标（截图像素）
     * @param imageY 模型空间 Y 坐标（截图像素）
     */
    public void leftClick(SecurityToken token, int imageX, int imageY) {
        requireSysAdmin(token);
        ensureAvailable();

        // ── 坐标缩放：模型空间 → 真实屏幕 ──
        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);

        robot.mouseMove(screenX, screenY);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        log.info("[RPA Bridge] leftClick: image=({},{}), screen=({},{}), sentSize={}x{}",
                imageX, imageY, screenX, screenY, sentW, sentH);
    }

    /**
     * 双击 — 对标 Anthropic computer.py 的 double_click action。
     */
    public void doubleClick(SecurityToken token, int imageX, int imageY) {
        requireSysAdmin(token);
        ensureAvailable();

        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);

        robot.mouseMove(screenX, screenY);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        log.info("[RPA Bridge] doubleClick: image=({},{}), screen=({},{})", imageX, imageY, screenX, screenY);
    }

    /**
     * 右键点击 — 对标 Anthropic computer.py 的 right_click action。
     */
    public void rightClick(SecurityToken token, int imageX, int imageY) {
        requireSysAdmin(token);
        ensureAvailable();

        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);

        robot.mouseMove(screenX, screenY);
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);

        log.info("[RPA Bridge] rightClick: image=({},{}), screen=({},{})", imageX, imageY, screenX, screenY);
    }

    /**
     * 鼠标移动 — 对标 Anthropic computer.py 的 mouse_move action。
     */
    public void mouseMove(SecurityToken token, int imageX, int imageY) {
        requireSysAdmin(token);
        ensureAvailable();

        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);

        robot.mouseMove(screenX, screenY);
        log.debug("[RPA Bridge] mouseMove: image=({},{}), screen=({},{})", imageX, imageY, screenX, screenY);
    }

    /**
     * 左键拖拽 — 对标 Anthropic computer.py 的 left_click_drag action。
     */
    public void leftClickDrag(SecurityToken token, int startImageX, int startImageY, int endImageX, int endImageY) {
        requireSysAdmin(token);
        ensureAvailable();

        int startScreenX = scaleToScreen(startImageX, sentW, screenSize.width);
        int startScreenY = scaleToScreen(startImageY, sentH, screenSize.height);
        int endScreenX = scaleToScreen(endImageX, sentW, screenSize.width);
        int endScreenY = scaleToScreen(endImageY, sentH, screenSize.height);

        robot.mouseMove(startScreenX, startScreenY);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);
        robot.mouseMove(endScreenX, endScreenY);
        robot.delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        log.info("[RPA Bridge] leftClickDrag: ({},{}) -> ({},{})", startScreenX, startScreenY, endScreenX, endScreenY);
    }

    /**
     * 滚轮 — 对标 Anthropic computer.py 的 scroll action。
     */
    public void scroll(SecurityToken token, int imageX, int imageY, String direction, int amount) {
        requireSysAdmin(token);
        ensureAvailable();

        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);

        robot.mouseMove(screenX, screenY);

        int notches = switch (direction.toLowerCase()) {
            case "up" -> -amount;
            case "down" -> amount;
            default -> amount;
        };
        robot.mouseWheel(notches);

        log.info("[RPA Bridge] scroll: direction={}, amount={}, at screen=({},{})", direction, amount, screenX, screenY);
    }

    // ── 兼容旧接口 ──

    public void mouseClick(SecurityToken token) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void mouseRightClick(SecurityToken token) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
    }

    public void mouseClickAt(SecurityToken token, int x, int y) {
        mouseMove(token, x, y);
        mouseClick(token);
    }

    public void mouseScroll(SecurityToken token, int amount) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mouseWheel(amount);
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Keyboard Control (对标 Anthropic computer.py)
    //
    //  关键设计：type 用剪贴板粘贴机制！
    //  逐字符 keyPress 在中文/日文/特殊字符下必崩，
    //  剪贴板 + Ctrl+V 是唯一可靠的跨平台方案
    // ════════════════════════════════════════════════════════════════

    /**
     * 打字 — 对标 Anthropic computer.py 的 type action。
     * <p>
     * <b>核心设计：剪贴板粘贴机制</b>
     * <ol>
     *   <li>将文本写入系统剪贴板</li>
     *   <li>模拟 Ctrl+V (Linux/Windows) 或 Meta+V (macOS) 粘贴</li>
     * </ol>
     * 这样可以完美支持中文、日文、Emoji、特殊字符 — 任何输入法都能正确处理。
     * <p>
     * 对标 Anthropic computer.py 的 _type_text() 方法，但更简洁：
     * Python 版需要 Quartz CGEvent + Unicode string，Java 版只需 Clipboard + Ctrl+V。
     */
    public void typeViaClipboard(SecurityToken token, String text) {
        requireSysAdmin(token);
        ensureAvailable();

        if (text == null || text.isEmpty()) return;

        // ── 1. 保存当前剪贴板内容（防止覆盖用户数据） ──
        String oldClipboard = null;
        Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            oldClipboard = (String) systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            // 剪贴板可能为空或非文本格式，忽略
        }

        try {
            // ── 2. 将文本写入系统剪贴板 ──
            StringSelection selection = new StringSelection(text);
            systemClipboard.setContents(selection, null);

            // ── 3. 短暂等待确保剪贴板写入完成 ──
            robot.delay(50);

            // ── 4. 模拟 Ctrl+V 粘贴 ──
            boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            int pasteKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

            robot.keyPress(pasteKey);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(pasteKey);

            // ── 5. 等待粘贴完成 ──
            robot.delay(50);

            log.info("[RPA Bridge] typeViaClipboard: textLen={} (clipboard paste)", text.length());
        } finally {
            // ── 6. 恢复原始剪贴板内容 ──
            try {
                if (oldClipboard != null) {
                    systemClipboard.setContents(new StringSelection(oldClipboard), null);
                }
            } catch (Exception e) {
                // 恢复失败不影响主流程
            }
        }
    }

    /**
     * 按键组合 — 对标 Anthropic computer.py 的 key action。
     * <p>
     * 支持组合键如 "ctrl+shift+t"，按键名自动映射。
     */
    public void keyCombo(SecurityToken token, String keyCombo) {
        requireSysAdmin(token);
        ensureAvailable();

        String[] keys = keyCombo.split("\\+");
        int[] keyCodes = new int[keys.length];

        for (int i = 0; i < keys.length; i++) {
            keyCodes[i] = translateKeyName(keys[i].trim());
            if (keyCodes[i] == KeyEvent.VK_UNDEFINED) {
                throw new IllegalArgumentException("Unknown key: " + keys[i].trim());
            }
        }

        // 按下所有键
        for (int code : keyCodes) {
            robot.keyPress(code);
        }
        // 释放所有键（反序）
        for (int i = keyCodes.length - 1; i >= 0; i--) {
            robot.keyRelease(keyCodes[i]);
        }

        log.info("[RPA Bridge] keyCombo: {}", keyCombo);
    }

    // ── 兼容旧接口 ──

    public void keyboardType(SecurityToken token, String text) {
        // 旧接口直接转发到剪贴板粘贴
        typeViaClipboard(token, text);
    }

    public void keyPress(SecurityToken token, int keyCode) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    public void keyCombo(SecurityToken token, int modifiers, int keyCode) {
        requireSysAdmin(token);
        ensureAvailable();

        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_CONTROL);
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_ALT);
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_SHIFT);
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_META);

        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);

        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_META);
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_SHIFT);
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_ALT);
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_CONTROL);
    }

    // ════════════════════════════════════════════════════════════════
    //  CLIPBOARD — 剪贴板读写 (对标 Anthropic computer.py)
    // ════════════════════════════════════════════════════════════════

    /**
     * 读取剪贴板 — 对标 Anthropic computer.py 的 read_clipboard action。
     */
    public String readClipboard(SecurityToken token) {
        requireSysAdmin(token);
        try {
            Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            return (String) systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            log.warn("[RPA Bridge] readClipboard failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 写入剪贴板 — 对标 Anthropic computer.py 的 write_clipboard action。
     */
    public void writeClipboard(SecurityToken token, String text) {
        requireSysAdmin(token);
        Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        systemClipboard.setContents(new StringSelection(text), null);
        log.debug("[RPA Bridge] writeClipboard: textLen={}", text.length());
    }

    // ════════════════════════════════════════════════════════════════
    //  WINDOW MANAGEMENT — 窗口管理 (对标 OpenClaw Desktop Control)
    //
    //  通过 java.awt.Window / X11 wmctrl 实现窗口级操作
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取当前鼠标位置（模型空间坐标）。
     */
    public Point getMousePosition(SecurityToken token) {
        requireSysAdmin(token);
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) return new Point(0, 0);
        Point screenPoint = info.getLocation();
        // 反向映射：真实屏幕坐标 → 模型空间坐标
        int imageX = sentW > 0 ? Math.round((float) screenPoint.x * sentW / screenSize.width) : screenPoint.x;
        int imageY = sentH > 0 ? Math.round((float) screenPoint.y * sentH / screenSize.height) : screenPoint.y;
        return new Point(imageX, imageY);
    }

    /**
     * 获取指定坐标的像素颜色。
     */
    public String getPixelColor(SecurityToken token, int imageX, int imageY) {
        requireSysAdmin(token);
        ensureAvailable();
        int screenX = scaleToScreen(imageX, sentW, screenSize.width);
        int screenY = scaleToScreen(imageY, sentH, screenSize.height);
        Color color = robot.getPixelColor(screenX, screenY);
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * 列出所有可见窗口 — 通过 X11 wmctrl 命令实现。
     * <p>
     * 返回格式：每行一个窗口 "窗口ID 标题 (x,y,w,h)"
     */
    public String listWindows(SecurityToken token) {
        requireSysAdmin(token);
        try {
            ProcessBuilder pb = new ProcessBuilder("wmctrl", "-l", "-p");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return output.isBlank() ? "No windows found (wmctrl may not be installed)" : output;
        } catch (Exception e) {
            // 降级：尝试 xdotool
            try {
                ProcessBuilder pb = new ProcessBuilder("xdotool", "search", "--onlyvisible", "--name", ".*");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return output.isBlank() ? "No windows found" : output;
            } catch (Exception e2) {
                return "Window listing unavailable: " + e.getMessage();
            }
        }
    }

    /**
     * 激活窗口（将窗口置顶）— 通过 wmctrl 实现。
     */
    public boolean activateWindow(SecurityToken token, String windowTitle) {
        requireSysAdmin(token);
        try {
            ProcessBuilder pb = new ProcessBuilder("wmctrl", "-a", windowTitle);
            Process p = pb.start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            // 降级：尝试 xdotool
            try {
                ProcessBuilder pb = new ProcessBuilder("xdotool", "search", "--name", windowTitle, "windowactivate");
                Process p = pb.start();
                boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return ok && p.exitValue() == 0;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * 关闭窗口 — 通过 wmctrl 实现。
     */
    public boolean closeWindow(SecurityToken token, String windowTitle) {
        requireSysAdmin(token);
        try {
            ProcessBuilder pb = new ProcessBuilder("wmctrl", "-c", windowTitle);
            Process p = pb.start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 最小化窗口 — 通过 xdotool 实现。
     */
    public boolean minimizeWindow(SecurityToken token, String windowTitle) {
        requireSysAdmin(token);
        try {
            ProcessBuilder pb = new ProcessBuilder("xdotool", "search", "--name", windowTitle, "windowminimize");
            Process p = pb.start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 最大化窗口 — 通过 wmctrl 实现。
     */
    public boolean maximizeWindow(SecurityToken token, String windowTitle) {
        requireSysAdmin(token);
        try {
            ProcessBuilder pb = new ProcessBuilder("wmctrl", "-r", windowTitle, "-b", "add,maximized_vert,maximized_horz");
            Process p = pb.start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Internal Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * 缩放图片 — 对标 Anthropic computer.py 的 resize_and_encode。
     * <p>
     * 保持宽高比，将图片缩放至 maxWidth 以内。
     * 这样 1920x1080 的截图会缩放为 1280x720，Base64 体积减少约 60%。
     */
    private BufferedImage resizeImage(BufferedImage original, int maxWidth) {
        int origW = original.getWidth();
        int origH = original.getHeight();

        if (origW <= maxWidth) {
            return original; // 无需缩放
        }

        double scale = (double) maxWidth / origW;
        int newW = maxWidth;
        int newH = (int) Math.round(origH * scale);

        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();

        return scaled;
    }

    /**
     * 编码为 JPEG Base64 — 使用 JPEG 压缩减少体积。
     * <p>
     * 对标 Anthropic 的 quality=0.72。
     * Java ImageIO 默认 JPEG quality 约 0.75，接近目标值。
     */
    private String encodeToJpegBase64(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // 使用 JPEG ImageWriter 设置压缩质量
            var writers = ImageIO.getImageWritersByFormatName("jpg");
            if (writers.hasNext()) {
                var writer = writers.next();
                var param = writer.getDefaultWriteParam();
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                var output = ImageIO.createImageOutputStream(baos);
                writer.setOutput(output);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
                writer.dispose();
                output.close();
            } else {
                // 降级：使用默认 ImageIO
                ImageIO.write(image, "jpg", baos);
            }

            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Screenshot encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * 坐标缩放：模型空间 → 真实屏幕。
     * <p>
     * 对标 Anthropic computer.py 的 _scale_to_screen。
     * 模型看到的是缩放后的截图（sentW x sentH），坐标需要映射回真实屏幕像素。
     */
    private int scaleToScreen(int imageCoord, int sentDim, int screenDim) {
        if (sentDim <= 0) return imageCoord; // 未截图时直接返回
        int scaled = Math.round((float) imageCoord * screenDim / sentDim);
        return Math.max(0, Math.min(scaled, screenDim - 1));
    }

    /**
     * 按键名映射 — 对标 Anthropic computer.py 的 _KEY_ALIASES。
     * <p>
     * 将 xdotool 风格的按键名映射为 Java KeyEvent 常量。
     */
    private int translateKeyName(String key) {
        return switch (key.toLowerCase()) {
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "alt" -> KeyEvent.VK_ALT;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "meta", "super", "cmd", "command", "win", "windows" -> KeyEvent.VK_META;
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "tab" -> KeyEvent.VK_TAB;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "backspace", "delete" -> KeyEvent.VK_BACK_SPACE;
            case "forward_delete" -> KeyEvent.VK_DELETE;
            case "space" -> KeyEvent.VK_SPACE;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pageup", "page_up" -> KeyEvent.VK_PAGE_UP;
            case "pagedown", "page_down" -> KeyEvent.VK_PAGE_DOWN;
            case "f1" -> KeyEvent.VK_F1;
            case "f2" -> KeyEvent.VK_F2;
            case "f3" -> KeyEvent.VK_F3;
            case "f4" -> KeyEvent.VK_F4;
            case "f5" -> KeyEvent.VK_F5;
            case "f6" -> KeyEvent.VK_F6;
            case "f7" -> KeyEvent.VK_F7;
            case "f8" -> KeyEvent.VK_F8;
            case "f9" -> KeyEvent.VK_F9;
            case "f10" -> KeyEvent.VK_F10;
            case "f11" -> KeyEvent.VK_F11;
            case "f12" -> KeyEvent.VK_F12;
            case "a" -> KeyEvent.VK_A;
            case "b" -> KeyEvent.VK_B;
            case "c" -> KeyEvent.VK_C;
            case "d" -> KeyEvent.VK_D;
            case "e" -> KeyEvent.VK_E;
            case "f" -> KeyEvent.VK_F;
            case "g" -> KeyEvent.VK_G;
            case "h" -> KeyEvent.VK_H;
            case "i" -> KeyEvent.VK_I;
            case "j" -> KeyEvent.VK_J;
            case "k" -> KeyEvent.VK_K;
            case "l" -> KeyEvent.VK_L;
            case "m" -> KeyEvent.VK_M;
            case "n" -> KeyEvent.VK_N;
            case "o" -> KeyEvent.VK_O;
            case "p" -> KeyEvent.VK_P;
            case "q" -> KeyEvent.VK_Q;
            case "r" -> KeyEvent.VK_R;
            case "s" -> KeyEvent.VK_S;
            case "t" -> KeyEvent.VK_T;
            case "u" -> KeyEvent.VK_U;
            case "v" -> KeyEvent.VK_V;
            case "w" -> KeyEvent.VK_W;
            case "x" -> KeyEvent.VK_X;
            case "y" -> KeyEvent.VK_Y;
            case "z" -> KeyEvent.VK_Z;
            case "0" -> KeyEvent.VK_0;
            case "1" -> KeyEvent.VK_1;
            case "2" -> KeyEvent.VK_2;
            case "3" -> KeyEvent.VK_3;
            case "4" -> KeyEvent.VK_4;
            case "5" -> KeyEvent.VK_5;
            case "6" -> KeyEvent.VK_6;
            case "7" -> KeyEvent.VK_7;
            case "8" -> KeyEvent.VK_8;
            case "9" -> KeyEvent.VK_9;
            default -> KeyEvent.getExtendedKeyCodeForChar(key.charAt(0));
        };
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("RPA subsystem not available — java.awt.Robot initialization failed");
        }
    }
}
