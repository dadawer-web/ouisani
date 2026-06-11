package com.ouisani.aios.user.bridge.rpa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主 RPA 桥接器 — 赋予 AIOS 对宿主机的物理控制能力。
 * <p>
 * 已从内核空间 (core.sandbox.rpa) 降级至用户空间 (user.bridge.rpa)。
 * 这是物理级沙箱逃逸后门，任何调用都必须持有 SYS_ADMIN 级别的 SecurityToken。
 * <p>
 * 封装 {@link java.awt.Robot}，提供两大基础能力：
 * <ul>
 *   <li><b>视觉</b> — 全屏截图捕获，返回 Base64 编码的 JPEG</li>
 *   <li><b>执行器</b> — 鼠标移动、点击和键盘输入</li>
 * </ul>
 * <p>
 * <b>安全机制：</b>
 * <ul>
 *   <li>所有方法调用前必须通过 {@link #requireSysAdmin(SecurityToken)} 鉴权</li>
 *   <li>SecurityToken 只在系统启动时由 InitDaemon 签发给受信组件</li>
 *   <li>未授权调用直接抛出 {@link PermissionDeniedException}</li>
 * </ul>
 *
 * @see SecurityToken
 * @see PermissionDeniedException
 */
public final class HostRpaManager {

    private static final Logger log = LoggerFactory.getLogger(HostRpaManager.class);

    /** 执行器操作间的默认延迟（毫秒），防止竞态条件。 */
    private static final int DEFAULT_ACTUATOR_DELAY_MS = 50;

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
    private volatile boolean initialized = false;

    private HostRpaManager() {
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

    /**
     * 签发 SYS_ADMIN 级别的 SecurityToken。
     * <p>
     * 此方法只能在系统启动时由 InitDaemon 调用，为受信组件签发物理访问令牌。
     * 签发的令牌必须妥善保管，泄露等同于宿主机被完全接管。
     *
     * @param requester 请求者标识（如 "InitDaemon", "OmniMotherAgent"）
     * @return SYS_ADMIN SecurityToken
     */
    public SecurityToken issueSysAdminToken(String requester) {
        SecurityToken token = new SecurityToken(requester, SecurityToken.Capability.SYS_ADMIN);
        authorizedTokens.add(token);
        log.warn("[RPA Bridge] SYS_ADMIN token issued to: {} (tokenId={})", requester, token.id());
        return token;
    }

    /**
     * 撤销 SecurityToken。
     */
    public void revokeToken(SecurityToken token) {
        authorizedTokens.remove(token);
        log.info("[RPA Bridge] Token revoked: tokenId={}", token.id());
    }

    /**
     * 验证 SecurityToken 是否具有 SYS_ADMIN 权限。
     *
     * @param token 待验证的令牌
     * @throws PermissionDeniedException 如果令牌无效或权限不足
     */
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
    //  VISION — Screenshot Capture (requires SYS_ADMIN)
    // ════════════════════════════════════════════════════════════════

    public String takeScreenshotBase64(SecurityToken token) {
        requireSysAdmin(token);
        return takeScreenshotBase64Internal();
    }

    public String takeScreenshotBase64(SecurityToken token, int x, int y, int width, int height) {
        requireSysAdmin(token);
        return takeScreenshotBase64Internal(x, y, width, height);
    }

    public Dimension getScreenSize() {
        return screenSize;
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Mouse Control (requires SYS_ADMIN)
    // ════════════════════════════════════════════════════════════════

    public void mouseMove(SecurityToken token, int x, int y) {
        requireSysAdmin(token);
        ensureAvailable();

        if (x < 0 || x > screenSize.width || y < 0 || y > screenSize.height) {
            log.warn("[RPA Bridge] mouseMove out of bounds: ({}, {}) screen={}x{}",
                    x, y, screenSize.width, screenSize.height);
        }

        robot.mouseMove(x, y);
        log.debug("[RPA Bridge] mouseMove: ({}, {})", x, y);
    }

    public void mouseClick(SecurityToken token) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log.debug("[RPA Bridge] mouseClick (left)");
    }

    public void mouseRightClick(SecurityToken token) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        log.debug("[RPA Bridge] mouseRightClick");
    }

    public void mouseClickAt(SecurityToken token, int x, int y) {
        mouseMove(token, x, y);
        mouseClick(token);
        log.debug("[RPA Bridge] mouseClickAt: ({}, {})", x, y);
    }

    public void mouseScroll(SecurityToken token, int amount) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.mouseWheel(amount);
        log.debug("[RPA Bridge] mouseScroll: {}", amount);
    }

    // ════════════════════════════════════════════════════════════════
    //  ACTUATORS — Keyboard Control (requires SYS_ADMIN)
    // ════════════════════════════════════════════════════════════════

    public void keyboardType(SecurityToken token, String text) {
        requireSysAdmin(token);
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
            if (!needsShift) {
                needsShift = isShiftRequired(c);
            }

            int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
            if (keyCode == KeyEvent.VK_UNDEFINED) {
                log.debug("[RPA Bridge] Cannot map char '{}' (0x{}) to key code, skipping",
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

        log.info("[RPA Bridge] keyboardType: textLen={}", text.length());
    }

    public void keyPress(SecurityToken token, int keyCode) {
        requireSysAdmin(token);
        ensureAvailable();
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        log.debug("[RPA Bridge] keyPress: VK_{}", keyCode);
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

        log.debug("[RPA Bridge] keyCombo: modifiers={}, VK_{}", modifiers, keyCode);
    }

    // ── Internal Helpers (no auth required) ──

    private String takeScreenshotBase64Internal() {
        ensureAvailable();
        Rectangle screenRect = new Rectangle(screenSize);
        BufferedImage image = robot.createScreenCapture(screenRect);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] jpegBytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(jpegBytes);
            log.info("[RPA Bridge] Screenshot captured: {}x{}, jpegSize={} bytes", screenSize.width, screenSize.height, jpegBytes.length);
            return base64;
        } catch (Exception e) {
            log.error("[RPA Bridge] Failed to encode screenshot: {}", e.getMessage());
            throw new RuntimeException("Screenshot encoding failed: " + e.getMessage(), e);
        }
    }

    private String takeScreenshotBase64Internal(int x, int y, int width, int height) {
        ensureAvailable();
        Rectangle region = new Rectangle(x, y, width, height);
        BufferedImage image = robot.createScreenCapture(region);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] jpegBytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(jpegBytes);
            log.info("[RPA Bridge] Region screenshot: ({},{}) {}x{}, jpegSize={} bytes", x, y, width, height, jpegBytes.length);
            return base64;
        } catch (Exception e) {
            log.error("[RPA Bridge] Failed to encode region screenshot: {}", e.getMessage());
            throw new RuntimeException("Screenshot encoding failed: " + e.getMessage(), e);
        }
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("RPA subsystem not available — java.awt.Robot initialization failed");
        }
    }

    private boolean isShiftRequired(char c) {
        return switch (c) {
            case '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
                 '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~' -> true;
            default -> false;
        };
    }
}
