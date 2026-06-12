package com.ouisani.aios.openclaw.gateway;

/**
 * 操作员权限作用域 — 对标 OpenClaw 的 OperatorScope。
 * <p>
 * 权限隐含关系：ADMIN > WRITE > READ
 * <ul>
 *   <li>ADMIN 隐含所有其他 scope</li>
 *   <li>WRITE 隐含 READ</li>
 *   <li>其他 scope 独立</li>
 * </ul>
 */
public enum OperatorScope {

    /** 只读操作：health, status, config.get, node.list, sessions.list 等 */
    READ("operator.read"),

    /** 写操作：sessions.create, node.invoke, message.action 等（隐含 READ） */
    WRITE("operator.write"),

    /** 管理操作：config.apply, agents.create/delete, update.run 等（隐含 READ+WRITE） */
    ADMIN("operator.admin"),

    /** 审批操作：exec.approval.request/resolve, plugin.approval.* */
    APPROVALS("operator.approvals"),

    /** 设备配对操作：node.pair.request/approve/reject */
    PAIRING("operator.pairing"),

    /** Talk 密钥操作 */
    TALK_SECRETS("operator.talk.secrets");

    private final String value;

    OperatorScope(String value) {
        this.value = value;
    }

    public String value() { return value; }

    /**
     * 判断此 scope 是否隐含目标 scope。
     * <p>
     * ADMIN 隐含所有，WRITE 隐含 READ。
     */
    public boolean implies(OperatorScope other) {
        if (this == other) return true;
        if (this == ADMIN) return true;  // admin 隐含一切
        if (this == WRITE && other == READ) return true;
        return false;
    }

    /** 从字符串值解析 scope */
    public static OperatorScope fromValue(String value) {
        for (OperatorScope s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown OperatorScope: " + value);
    }
}
