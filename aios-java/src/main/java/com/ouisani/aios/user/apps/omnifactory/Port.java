package com.ouisani.aios.user.apps.omnifactory;

/**
 * 声明式组件端口 — 借鉴 Langflow 的 Edge 端口路由机制。
 * 每个端口有 name（端口名）和 dataType（数据类型，如 "text", "json", "file"）。
 * Edge 连接源端口到目标端口，实现类型检查和精确路由。
 */
public class Port {
    private final String name;
    private final String dataType;

    public Port(String name, String dataType) {
        this.name = name;
        this.dataType = dataType != null ? dataType : "any";
    }

    public String name() { return name; }
    public String dataType() { return dataType; }

    /** 检查此端口的输出是否可以连接到目标端口的输入 */
    public boolean isCompatibleWith(Port target) {
        if ("any".equals(this.dataType) || "any".equals(target.dataType)) return true;
        return this.dataType.equals(target.dataType);
    }

    @Override
    public String toString() { return name + ":" + dataType; }
}
