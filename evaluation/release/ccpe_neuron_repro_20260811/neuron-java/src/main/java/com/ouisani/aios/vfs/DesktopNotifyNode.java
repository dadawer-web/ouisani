package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 桌面通知节点 — AIOS 的"打破第四面墙"物理层交互。
 * <p>
 * 挂载在 {@code /dev/host/notify}，这是一个只写（Write-Only）的设备节点。
 * Agent 只需要向这个"文件"写入 JSON，就能在用户的屏幕右下角弹出原生通知。
 *
 * <h3>"一切皆文件"的威力</h3>
 * <pre>
 *   Agent 调用:
 *   sys_write("/dev/host/notify", {
 *       "title": "任务完成",
 *       "message": "代码已经编译通过！",
 *       "level": "info"
 *   })
 *
 *   → 宿主机屏幕右下角弹出原生通知！
 * </pre>
 *
 * <h3>跨平台通知策略</h3>
 * <table>
 *   <tr><th>平台</th><th>命令</th><th>说明</th></tr>
 *   <tr><td>macOS</td><td>osascript -e 'display notification...'</td><td>AppleScript</td></tr>
 *   <tr><td>Linux</td><td>notify-send "title" "message"</td><td>libnotify</td></tr>
 *   <tr><td>Windows</td><td>powershell New-BurntToastNotification</td><td>BurntToast</td></tr>
 *   <tr><td>Java AWT</td><td>SystemTray + TrayIcon</td><td>回退方案</td></tr>
 * </table>
 */
public non-sealed class DesktopNotifyNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(DesktopNotifyNode.class);

    private final String path;
    private int ownerUid;
    private int permissions;

    /** 通知级别 */
    public enum NotifyLevel {
        INFO, WARNING, ERROR, CRITICAL
    }

    // ── 统计 ──
    private final AtomicLong totalNotifications = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failCount = new AtomicLong(0);

    public DesktopNotifyNode(String path) {
        this(path, 0, 0222); // 只写权限
    }

    public DesktopNotifyNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() { return VfsNodeType.DEVICE; }

    @Override
    public String path() { return path; }

    @Override
    public int ownerUid() { return ownerUid; }

    @Override
    public void setOwnerUid(int uid) { this.ownerUid = uid; }

    @Override
    public int permissions() { return permissions; }

    @Override
    public void setPermissions(int perm) { this.permissions = perm; }

    /**
     * 只写节点 — read 返回统计信息。
     */
    @Override
    public String read() {
        return "{\"path\":\"" + path + "\","
                + "\"type\":\"DESKTOP_NOTIFY\","
                + "\"totalNotifications\":" + totalNotifications.get() + ","
                + "\"successCount\":" + successCount.get() + ","
                + "\"failCount\":" + failCount.get() + ","
                + "\"platform\":\"" + detectPlatform() + "\"}";
    }

    /**
     * 写入通知 — 解析 JSON 并调用宿主机原生通知。
     * <p>
     * JSON 格式：
     * <pre>
     * {
     *   "title": "任务完成",
     *   "message": "代码已经编译通过！",
     *   "level": "info"        // info | warning | error | critical
     * }
     * </pre>
     */
    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        totalNotifications.incrementAndGet();

        // 解析 JSON
        String title = extractField(payload, "title");
        String message = extractField(payload, "message");
        String levelStr = extractField(payload, "level");

        if (title.isEmpty() && message.isEmpty()) {
            log.warn("[DesktopNotify] Empty notification payload");
            failCount.incrementAndGet();
            return false;
        }

        if (title.isEmpty()) title = "AIOS";
        if (message.isEmpty()) message = title;

        NotifyLevel level;
        try {
            level = NotifyLevel.valueOf(levelStr.toUpperCase());
        } catch (Exception e) {
            level = NotifyLevel.INFO;
        }

        // 发送原生通知
        boolean sent = sendNativeNotification(title, message, level);

        if (sent) {
            successCount.incrementAndGet();
            log.info("[DesktopNotify] Notification sent: title='{}', level={}", title, level);
        } else {
            failCount.incrementAndGet();
            log.warn("[DesktopNotify] Failed to send notification: title='{}'", title);
        }

        return sent;
    }

    // ════════════════════════════════════════════════════════════════
    //  原生通知发送
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据平台发送原生通知。
     */
    private boolean sendNativeNotification(String title, String message, NotifyLevel level) {
        String platform = detectPlatform();

        return switch (platform) {
            case "macos" -> sendMacOsNotification(title, message, level);
            case "linux" -> sendLinuxNotification(title, message, level);
            case "windows" -> sendWindowsNotification(title, message, level);
            default -> sendAwtNotification(title, message, level);
        };
    }

    /**
     * macOS — osascript display notification。
     */
    private boolean sendMacOsNotification(String title, String message, NotifyLevel level) {
        String sound = level == NotifyLevel.CRITICAL ? " sound name \"Sosumi\"" : "";
        String script = String.format(
                "display notification \"%s\" with title \"%s\"%s",
                escapeAppleScript(message), escapeAppleScript(title), sound);

        return execCommand("osascript", "-e", script);
    }

    /**
     * Linux — notify-send。
     */
    private boolean sendLinuxNotification(String title, String message, NotifyLevel level) {
        String urgency = switch (level) {
            case INFO -> "normal";
            case WARNING -> "normal";
            case ERROR -> "critical";
            case CRITICAL -> "critical";
        };

        return execCommand("notify-send", "-u", urgency, title, message);
    }

    /**
     * Windows — PowerShell BurntToast。
     */
    private boolean sendWindowsNotification(String title, String message, NotifyLevel level) {
        String psCommand = String.format(
                "New-BurntToastNotification -Text '%s', '%s'",
                escapePowerShell(title), escapePowerShell(message));

        return execCommand("powershell", "-Command", psCommand);
    }

    /**
     * Java AWT 回退 — SystemTray + TrayIcon。
     */
    private boolean sendAwtNotification(String title, String message, NotifyLevel level) {
        try {
            if (!java.awt.SystemTray.isSupported()) {
                log.debug("[DesktopNotify] 此平台不支持 SystemTray");
                return false;
            }

            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            java.awt.Image image = new java.awt.image.BufferedImage(16, 16,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);

            java.awt.TrayIcon.MessageType msgType = switch (level) {
                case INFO -> java.awt.TrayIcon.MessageType.INFO;
                case WARNING -> java.awt.TrayIcon.MessageType.WARNING;
                case ERROR, CRITICAL -> java.awt.TrayIcon.MessageType.ERROR;
            };

            java.awt.TrayIcon icon = new java.awt.TrayIcon(image, "AIOS");
            icon.setImageAutoSize(true);
            tray.add(icon);
            icon.displayMessage(title, message, msgType);

            // 延迟移除图标
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                tray.remove(icon);
            });

            return true;
        } catch (Exception e) {
            log.debug("[DesktopNotify] AWT notification failed: {}", e.getMessage());
            return false;
        }
    }

    // ── 平台检测 ──

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "macos";
        if (os.contains("linux")) return "linux";
        if (os.contains("win")) return "windows";
        return "unknown";
    }

    // ── 命令执行 ──

    private boolean execCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[DesktopNotify] Command failed: {}", e.getMessage());
            return false;
        }
    }

    // ── 转义 ──

    private String escapeAppleScript(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapePowerShell(String s) {
        return s.replace("'", "''");
    }

    // ── JSON 解析 ──

    private String extractField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start = json.indexOf(":", start) + 1;

        // 跳过空白
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return "";

        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : "";
        }

        // 无引号值
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).strip();
    }

    // ── 统计 ──

    public long totalNotifications() { return totalNotifications.get(); }
    public long successCount() { return successCount.get(); }
    public long failCount() { return failCount.get(); }
}
