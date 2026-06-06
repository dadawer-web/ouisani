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
 * Host RPA Manager — gives AIOS physical control over the host machine.
 * <p>
 * Wraps {@link java.awt.Robot} to provide two fundamental capabilities:
 * <ul>
 *   <li><b>Vision</b> — full-screen screenshot capture, returned as Base64 JPEG</li>
 *   <li><b>Actuators</b> — mouse movement, clicking, and keyboard typing</li>
 * </ul>
 * <p>
 * This is the bridge between AIOS's digital intelligence and the physical
 * world. When an Agent issues an RPA syscall, the kernel delegates here
 * to execute real GUI operations on the host desktop.
 * <p>
 * <b>Safety note:</b> All actuator methods include a configurable pre-delay
 * to prevent runaway automation. In production, the Seccomp filter chain
 * should gate access to this subsystem.
 */
public final class HostRpaManager {

    private static final Logger log = LoggerFactory.getLogger(HostRpaManager.class);

    /** Default delay (ms) between actuator operations to prevent race conditions. */
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

    /**
     * Check whether the RPA subsystem is available.
     */
    public boolean isAvailable() {
        return initialized && robot != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  VISION — Screenshot Capture
    // ════════════════════════════════════════════════════════════════

    /**
     * Capture a full-screen screenshot and return it as a Base64-encoded JPEG.
     * <p>
     * This is the "eye" of AIOS — the Agent can issue a vision syscall
     * to see what's currently on the host display.
     *
     * @return Base64-encoded JPEG string of the current screen
     * @throws IllegalStateException if the Robot is not initialized
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
     * Capture a screenshot of a specific screen region.
     *
     * @param x      region x
     * @param y      region y
     * @param width  region width
     * @param height region height
     * @return Base64-encoded JPEG string of the captured region
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

    /**
     * Get the host screen dimensions.
     */
    public Dimension getScreenSize() {
        return screenSize;
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Mouse Control
    // ════════════════════════════════════════════════════════════════

    /**
     * Move the mouse cursor to the specified screen coordinates.
     *
     * @param x screen x coordinate
     * @param y screen y coordinate
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

    /**
     * Perform a left mouse button click at the current cursor position.
     */
    public void mouseClick() {
        ensureAvailable();

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.debug("[RPA Actuator] mouseClick (left)");
    }

    /**
     * Perform a right mouse button click at the current cursor position.
     */
    public void mouseRightClick() {
        ensureAvailable();

        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        log.debug("[RPA Actuator] mouseRightClick");
    }

    /**
     * Perform a mouse click at the specified coordinates.
     * <p>
     * Convenience method combining {@link #mouseMove(int, int)} and {@link #mouseClick()}.
     *
     * @param x screen x coordinate
     * @param y screen y coordinate
     */
    public void mouseClickAt(int x, int y) {
        mouseMove(x, y);
        mouseClick();
        log.debug("[RPA Actuator] mouseClickAt: ({}, {})", x, y);
    }

    /**
     * Perform a mouse scroll operation.
     *
     * @param amount number of scroll notches (negative = up, positive = down)
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
     * Simulate typing a string of text via the keyboard.
     * <p>
     * For each character, the method attempts to resolve the extended
     * key code via {@link KeyEvent#getExtendedKeyCodeForChar(int)}.
     * Characters that cannot be mapped to a key code are skipped with
     * a debug log warning.
     * <p>
     * For uppercase letters and symbols requiring Shift, the method
     * automatically presses and releases Shift around the key event.
     *
     * @param text the text to type
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
     * Press and release a single key by its VK code.
     *
     * @param keyCode the KeyEvent.VK_* constant
     */
    public void keyPress(int keyCode) {
        ensureAvailable();
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        log.debug("[RPA Actuator] keyPress: VK_{}", keyCode);
    }

    /**
     * Press a key combination (e.g., Ctrl+C).
     *
     * @param modifiers modifier keys (bitwise OR of InputEvent.*_DOWN_MASK)
     * @param keyCode   the KeyEvent.VK_* constant
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
     * Determine if a character requires the Shift key to be held.
     * <p>
     * Covers common symbols that need Shift on standard US keyboard layouts.
     */
    private boolean isShiftRequired(char c) {
        return switch (c) {
            case '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
                 '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~' -> true;
            default -> false;
        };
    }
}
