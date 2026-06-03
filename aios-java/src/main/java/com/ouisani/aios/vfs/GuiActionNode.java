package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GUI Action Node — accepts JSON actions to manipulate the host desktop.
 * <p>
 * Inspired by OSWorld's desktop environment manipulation, this node allows
 * Agents to "act" on the screen by writing action commands. The node parses
 * incoming JSON and simulates physical GUI interactions (click, type, scroll, etc.).
 * <p>
 * Mount point: {@code /dev/gui/action}
 * <p>
 * Supported actions:
 * <ul>
 *   <li>{@code {"action": "click", "id": "btn_1"}} — click a UI element</li>
 *   <li>{@code {"action": "type", "id": "input_query", "text": "hello"}} — type text into an input</li>
 *   <li>{@code {"action": "scroll", "id": "output_panel", "direction": "down"}} — scroll an element</li>
 *   <li>{@code {"action": "screenshot"}} — capture current screen state</li>
 * </ul>
 * <p>
 * Read returns the last action result. Write dispatches the action.
 */
public non-sealed class GuiActionNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(GuiActionNode.class);

    private static final Pattern ACTION_PATTERN = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DIR_PATTERN = Pattern.compile("\"direction\"\\s*:\\s*\"([^\"]+)\"");

    private final String path;
    private int ownerUid;
    private int permissions;
    private volatile String lastResult = "{\"status\": \"idle\"}";

    public GuiActionNode(String path) {
        this(path, 0, 0222);
    }

    public GuiActionNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    /**
     * Return the result of the last executed action.
     */
    @Override
    public String read() {
        return lastResult;
    }

    /**
     * Parse and execute a GUI action from JSON payload.
     * <p>
     * Simulates physical desktop interactions by printing action logs
     * and updating the last result state.
     */
    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        String action = extract(payload, ACTION_PATTERN);
        String id = extract(payload, ID_PATTERN);
        String text = extract(payload, TEXT_PATTERN);
        String direction = extract(payload, DIR_PATTERN);

        if (action == null) {
            lastResult = "{\"status\": \"error\", \"message\": \"Missing 'action' field\"}";
            log.warn("[RPA Engine] Invalid action payload: no 'action' field found");
            return false;
        }

        return switch (action) {
            case "click" -> executeClick(id);
            case "type" -> executeType(id, text);
            case "scroll" -> executeScroll(id, direction);
            case "screenshot" -> executeScreenshot();
            default -> {
                lastResult = "{\"status\": \"error\", \"message\": \"Unknown action: " + action + "\"}";
                log.warn("[RPA Engine] Unknown action: {}", action);
                yield false;
            }
        };
    }

    private boolean executeClick(String id) {
        if (id == null) {
            lastResult = "{\"status\": \"error\", \"message\": \"Missing 'id' for click\"}";
            return false;
        }

        System.out.printf("  🖱 [RPA Engine] Executing physical click on UI element: %s%n", id);
        log.info("[RPA Engine] Executing physical click on UI element: {}", id);

        lastResult = "{\"status\": \"ok\", \"action\": \"click\", \"id\": \"" + id + "\"}";
        return true;
    }

    private boolean executeType(String id, String text) {
        if (id == null) {
            lastResult = "{\"status\": \"error\", \"message\": \"Missing 'id' for type\"}";
            return false;
        }

        String safeText = text != null ? text : "";
        System.out.printf("  ⌨ [RPA Engine] Executing physical type on UI element: %s, text: \"%s\"%n", id, safeText);
        log.info("[RPA Engine] Executing physical type on UI element: {}, text length: {}", id, safeText.length());

        lastResult = "{\"status\": \"ok\", \"action\": \"type\", \"id\": \"" + id + "\", \"chars_typed\": " + safeText.length() + "}";
        return true;
    }

    private boolean executeScroll(String id, String direction) {
        String dir = direction != null ? direction : "down";
        String target = id != null ? id : "viewport";

        System.out.printf("  🔄 [RPA Engine] Executing physical scroll on UI element: %s, direction: %s%n", target, dir);
        log.info("[RPA Engine] Executing physical scroll on UI element: {}, direction: {}", target, dir);

        lastResult = "{\"status\": \"ok\", \"action\": \"scroll\", \"id\": \"" + target + "\", \"direction\": \"" + dir + "\"}";
        return true;
    }

    private boolean executeScreenshot() {
        System.out.println("  📸 [RPA Engine] Executing physical screenshot capture");
        log.info("[RPA Engine] Executing physical screenshot capture");

        lastResult = "{\"status\": \"ok\", \"action\": \"screenshot\", \"path\": \"/tmp/aios_screenshot_" + System.currentTimeMillis() + ".png\"}";
        return true;
    }

    private static String extract(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
