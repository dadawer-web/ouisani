package com.ouisani.aios.core.tool;

/**
 * 声明式组件端口 — 借鉴 Langflow 的 Edge 端口路由机制。
 * <p>
 * 每个端口有 name（端口名）、dataType（数据类型，如 "MarkdownText", "JsonData", "UrlList"）
 * 和 description（端口职责描述）。
 * Edge 连接源端口到目标端口，实现类型检查和精确路由。
 * <p>
 * 强类型 I/O 契约：节点不再是黑盒，而是像流水线上的机器，
 * 明确知道吃进去什么（inputPorts），吐出来什么（outputPorts）。
 *
 * @see DataTypes
 */
public class Port {
    private final String name;
    private final String dataType;
    private final String description;
    private final boolean required;

    /** 兼容旧调用：无 description 时默认为空字符串，required 默认 true */
    public Port(String name, String dataType) {
        this(name, dataType, "", true);
    }

    /** 兼容调用：有 description 但未指定 required，默认 required=true */
    public Port(String name, String dataType, String description) {
        this(name, dataType, description, true);
    }

    /** 完整构造函数 — name + dataType + description + required */
    public Port(String name, String dataType, String description, boolean required) {
        this.name = name;
        this.dataType = dataType != null ? dataType : "any";
        this.description = description != null ? description : "";
        this.required = required;
    }

    public String name() { return name; }
    public String dataType() { return dataType; }
    public String description() { return description; }

    /** type() 是 dataType() 的语义别名，供强类型 I/O 契约使用 */
    public String type() { return dataType; }

    /** 是否为必填端口（仅对 inputPorts 有意义：必填端口必须被连线连接） */
    public boolean required() { return required; }

    /** 检查此端口的输出是否可以连接到目标端口的输入 */
    public boolean isCompatibleWith(Port target) {
        if ("any".equals(this.dataType) || "any".equals(target.dataType)) return true;
        if (this.dataType.equals(target.dataType)) return true;
        return DataTypes.isCompatible(this.dataType, target.dataType);
    }

    @Override
    public String toString() {
        String req = required ? "" : "?";
        return name + req + ":" + dataType + (description.isEmpty() ? "" : " (" + description + ")");
    }
}
