package com.ouisani.aios.core.sandbox.rpa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 宿主 RPA 管理器 — 赋予 AIOS 对宿主机的物理控制能力。
 * <p>
 * 封装 {@link java.awt.Robot}，提供两大基础能力：
 * <ul>
 *   <li><b>视觉</b> — 全屏截图捕获，返回 Base64 编码的 JPEG</li>
 *   <li><b>执行器</b> — 鼠标移动、点击和键盘输入</li>
 * </ul>
 * <p>
 * 这是 AIOS 数字智能与物理世界之间的桥梁。当 Agent 发起 RPA 系统调用时，
 * 内核委托此管理器在宿主桌面执行真实的 GUI 操作。
 * <p>
 * <b>安全提示：</b>所有执行器方法包含可配置的前置延迟以防止失控自动化。
 * 生产环境中，Seccomp 过滤链应控制对此子系统的访问。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>概念</th><th>AIOS HostRpaManager</th><th>说明</th></tr>
 *   <tr><td>设备驱动</td><td>HostRpaManager</td><td>内核→硬件的桥梁</td></tr>
 *   <tr><td>GPU 帧缓冲</td><td>takeScreenshotBase64()</td><td>屏幕捕获</td></tr>
 *   <tr><td>输入设备</td><td>mouse / keyboard</td><td>鼠标/键盘控制</td></tr>
 * </table>
 */
public final class HostRpaManager {

    private static final Logger log = LoggerFactory.getLogger(HostRpaManager.class);

    /** 执行器操作间的默认延迟（毫秒），防止竞态条件。 */
    private static final int DEFAULT_ACTUATOR_DELAY_MS = 50;

    private static final class Holder {
        static final HostRpaManager INSTANCE = new HostRpaManager();
    }

    public static HostRpaManager getInstance() {
        return Holder.INSTANCE;
    }

    private final Robot robot;
    private final Dimension screenSize;
    private volatile boolean initialized = false;

    private HostRpaManager() {
        Robot r;
        try {
            r = new Robot();
            r.setAutoWaitForIdle(true);
            r.setAutoDelay(DEFAULT_ACTUATOR_DELAY_MS);
        } catch (AWTException e) {
            log.error("[RPA Subsystem] Failed to initialize java.awt.Robot: {}", e.getMessage());
            r = null;
        }
        this.robot = r;
        this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        if (robot != null) {
            initialized = true;
            log.info("[RPA Subsystem] Host GUI Actuators and Vision initialized. AIOS now has physical control capabilities.");
            System.out.println("[RPA Subsystem] Host GUI Actuators and Vision initialized. AIOS now has physical control capabilities.");
        } else {
            log.warn("[RPA Subsystem] Robot initialization failed — RPA capabilities disabled.");
            System.out.println("[RPA Subsystem] WARNING: Robot initialization failed — RPA capabilities disabled.");
        }
    }

    /** 检查 RPA 子系统是否可用。 */
    public boolean isAvailable() {
        return initialized && robot != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  VISION — Screenshot Capture
    // ════════════════════════════════════════════════════════════════

    /**
     * 全屏截图并返回 Base64 编码的 JPEG — AIOS 的"眼睛"。
     *
     * @return 当前屏幕的 Base64 编码 JPEG 字符串
     * @throws IllegalStateException 如果 Robot 未初始化
     */
    public String takeScreenshotBase64() {
        ensureAvailable();

        Rectangle screenRect = new Rectangle(screenSize);
        BufferedImage image = robot.createScreenCapture(screenRect);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] jpegBytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(jpegBytes);

            log.info("[RPA Vision] Screenshot captured: {}x{}, jpegSize={} bytes, base64Len={}",
                    screenSize.width, screenSize.height, jpegBytes.length, base64.length());

            return base64;
        } catch (Exception e) {
            log.error("[RPA Vision] Failed to encode screenshot: {}", e.getMessage());
            throw new RuntimeException("Screenshot encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * 截取指定区域的屏幕截图。
     *
     * @param x      区域 x 坐标
     * @param y      区域 y 坐标
     * @param width  区域宽度
     * @param height 区域高度
     * @return 截取区域的 Base64 编码 JPEG 字符串
     */
    public String takeScreenshotBase64(int x, int y, int width, int height) {
        ensureAvailable();

        Rectangle region = new Rectangle(x, y, width, height);
        BufferedImage image = robot.createScreenCapture(region);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] jpegBytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(jpegBytes);

            log.info("[RPA Vision] Region screenshot captured: ({},{}) {}x{}, jpegSize={} bytes",
                    x, y, width, height, jpegBytes.length);

            return base64;
        } catch (Exception e) {
            log.error("[RPA Vision] Failed to encode region screenshot: {}", e.getMessage());
            throw new RuntimeException("Screenshot encoding failed: " + e.getMessage(), e);
        }
    }

    /** 获取宿主机屏幕尺寸。 */
    public Dimension getScreenSize() {
        return screenSize;
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Mouse Control
    // ════════════════════════════════════════════════════════════════

    /**
     * 将鼠标移动到指定屏幕坐标。
     *
     * @param x 屏幕 x 坐标
     * @param y 屏幕 y 坐标
     */
    public void mouseMove(int x, int y) {
        ensureAvailable();

        if (x < 0 || x > screenSize.width || y < 0 || y > screenSize.height) {
            log.warn("[RPA Actuator] mouseMove out of bounds: ({}, {}) screen={}x{}",
                    x, y, screenSize.width, screenSize.height);
        }

        robot.mouseMove(x, y);
        log.debug("[RPA Actuator] mouseMove: ({}, {})", x, y);
    }

    /** 在当前光标位置执行鼠标左键点击。 */
    public void mouseClick() {
        ensureAvailable();

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.debug("[RPA Actuator] mouseClick (left)");
    }

    /** 在当前光标位置执行鼠标右键点击。 */
    public void mouseRightClick() {
        ensureAvailable();

        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        log.debug("[RPA Actuator] mouseRightClick");
    }

    /**
     * 在指定坐标执行鼠标点击 — 组合 mouseMove + mouseClick 的便捷方法。
     *
     * @param x 屏幕 x 坐标
     * @param y 屏幕 y 坐标
     */
    public void mouseClickAt(int x, int y) {
        mouseMove(x, y);
        mouseClick();
        log.debug("[RPA Actuator] mouseClickAt: ({}, {})", x, y);
    }

    /**
     * 执行鼠标滚轮操作。
     *
     * @param amount 滚动格数（负值=向上，正值=向下）
     */
    public void mouseScroll(int amount) {
        ensureAvailable();

        robot.mouseWheel(amount);
        log.debug("[RPA Actuator] mouseScroll: {}", amount);
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Keyboard Control
    // ════════════════════════════════════════════════════════════════

    /**
     * 通过键盘模拟输入一段文本。
     * <p>
     * 对每个字符尝试通过 {@link KeyEvent#getExtendedKeyCodeForChar(int)}
     * 解析扩展键码。无法映射的字符将被跳过。
     * 大写字母和需要 Shift 的符号会自动按下/释放 Shift 键。
     *
     * @param text 要输入的文本
     */
    public void keyboardType(String text) {
        ensureAvailable();

        if (text == null || text.isEmpty()) {
            return;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
                continue;
            }

            if (c == '\t') {
                robot.keyPress(KeyEvent.VK_TAB);
                robot.keyRelease(KeyEvent.VK_TAB);
                continue;
            }

            if (c == '\b') {
                robot.keyPress(KeyEvent.VK_BACK_SPACE);
                robot.keyRelease(KeyEvent.VK_BACK_SPACE);
                continue;
            }

            if (c == ' ') {
                robot.keyPress(KeyEvent.VK_SPACE);
                robot.keyRelease(KeyEvent.VK_SPACE);
                continue;
            }

            boolean needsShift = Character.isUpperCase(c);

            // Check if the character requires Shift (symbols, uppercase)
            if (!needsShift) {
                needsShift = isShiftRequired(c);
            }

            int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);

            if (keyCode == KeyEvent.VK_UNDEFINED) {
                log.debug("[RPA Actuator] Cannot map char '{}' (0x{}) to key code, skipping",
                        c, String.format("%04x", (int) c));
                continue;
            }

            if (needsShift) {
                robot.keyPress(KeyEvent.VK_SHIFT);
            }

            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);

            if (needsShift) {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        }

        log.info("[RPA Actuator] keyboardType: textLen={}", text.length());
    }

    /**
     * 按下并释放单个按键。
     *
     * @param keyCode KeyEvent.VK_* 常量
     */
    public void keyPress(int keyCode) {
        ensureAvailable();
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        log.debug("[RPA Actuator] keyPress: VK_{}", keyCode);
    }

    /**
     * 按下组合键（如 Ctrl+C）。
     *
     * @param modifiers 修饰键（InputEvent.*_DOWN_MASK 的位或组合）
     * @param keyCode   KeyEvent.VK_* 常量
     */
    public void keyCombo(int modifiers, int keyCode) {
        ensureAvailable();

        // Press modifiers
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_CONTROL);
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_ALT);
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_META);
        }

        // Press and release the key
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);

        // Release modifiers (reverse order)
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_META);
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_SHIFT);
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_ALT);
        }
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_CONTROL);
        }

        log.debug("[RPA Actuator] keyCombo: modifiers={}, VK_{}", modifiers, keyCode);
    }

    // ── Internal Helpers ──

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "RPA subsystem not available — java.awt.Robot initialization failed");
        }
    }

    /**
     * 判断字符是否需要按住 Shift 键 — 覆盖标准美式键盘布局中需要 Shift 的常见符号。
     */
    private boolean isShiftRequired(char c) {
        return switch (c) {
            case '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
                 '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~' -> true;
            default -> false;
        };
    }
}
