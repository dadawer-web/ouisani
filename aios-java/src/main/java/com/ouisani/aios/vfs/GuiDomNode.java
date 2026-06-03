package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;

/**
 * GUI DOM Node — exposes the current host screen's UI element tree as JSON.
 * <p>
 * Inspired by OSWorld's desktop environment manipulation, this node allows
 * Agents to "see" the screen by reading a structured DOM tree of UI elements.
 * Each element includes an id, type, label, position, and state — enough
 * information for an Agent to decide which element to interact with.
 * <p>
 * Mount point: {@code /dev/gui/dom}
 * <p>
 * Read returns a simulated UI DOM tree. Write is not supported (read-only).
 */
public non-sealed class GuiDomNode implements VfsNode {

    private final String path;
    private int ownerUid;
    private int permissions;

    public GuiDomNode(String path) {
        this(path, 0, 0444);
    }

    public GuiDomNode(String path, int ownerUid, int permissions) {
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
     * Return a simulated UI DOM tree of the current host screen.
     * <p>
     * The tree includes typical desktop application elements:
     * windows, menus, buttons, text inputs, labels, etc.
     */
    @Override
    public String read() {
        long ts = System.currentTimeMillis();
        return """
                {
                  "screen": {
                    "width": 1920,
                    "height": 1080,
                    "active_window": "AIOS Dashboard",
                    "timestamp": %d
                  },
                  "dom": [
                    {
                      "id": "menu_file",
                      "type": "menu",
                      "label": "File",
                      "rect": {"x": 0, "y": 0, "w": 60, "h": 24},
                      "state": "enabled"
                    },
                    {
                      "id": "menu_edit",
                      "type": "menu",
                      "label": "Edit",
                      "rect": {"x": 60, "y": 0, "w": 60, "h": 24},
                      "state": "enabled"
                    },
                    {
                      "id": "input_query",
                      "type": "text_input",
                      "label": "Enter your query...",
                      "rect": {"x": 100, "y": 60, "w": 400, "h": 32},
                      "state": "focused",
                      "value": ""
                    },
                    {
                      "id": "btn_1",
                      "type": "button",
                      "label": "Submit",
                      "rect": {"x": 520, "y": 60, "w": 80, "h": 32},
                      "state": "enabled"
                    },
                    {
                      "id": "btn_cancel",
                      "type": "button",
                      "label": "Cancel",
                      "rect": {"x": 620, "y": 60, "w": 80, "h": 32},
                      "state": "enabled"
                    },
                    {
                      "id": "output_panel",
                      "type": "text_area",
                      "label": "Output",
                      "rect": {"x": 100, "y": 120, "w": 600, "h": 400},
                      "state": "enabled",
                      "value": "Ready."
                    },
                    {
                      "id": "status_bar",
                      "type": "label",
                      "label": "AIOS v1.0 — Agent Online",
                      "rect": {"x": 0, "y": 1056, "w": 1920, "h": 24},
                      "state": "enabled"
                    }
                  ]
                }""".formatted(ts);
    }

    @Override
    public boolean write(String data) {
        throw new UnsupportedOperationException("GuiDomNode is read-only (screen observation): " + path);
    }
}
