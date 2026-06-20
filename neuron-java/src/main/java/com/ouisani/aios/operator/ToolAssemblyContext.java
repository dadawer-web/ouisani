package com.ouisani.aios.operator;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;

import java.util.*;

/**
 * 工具装配上下文 — 对标 OpenClaw 的 createOpenClawTools options。
 * <p>
 * 将分散的 40+ 个配置字段收敛为一个不可变上下文对象，
 * 按职责分组为安全、投递、沙箱、模型四个维度。
 * <p>
 * OperatorAgent 通过此上下文决定装配哪些工具、应用哪些策略。
 */
public class ToolAssemblyContext {

    private final String agentId;
    private final String workingDir;
    private final boolean operatorMode;
    private final List<String> allowedToolPatterns;
    private final List<String> deniedToolPatterns;
    private final List<Tool<? extends ToolInput>> extraTools;
    private final PluginRegistry pluginRegistry;

    private ToolAssemblyContext(Builder builder) {
        this.agentId = builder.agentId;
        this.workingDir = builder.workingDir;
        this.operatorMode = builder.operatorMode;
        this.allowedToolPatterns = Collections.unmodifiableList(new ArrayList<>(builder.allowedToolPatterns));
        this.deniedToolPatterns = Collections.unmodifiableList(new ArrayList<>(builder.deniedToolPatterns));
        this.extraTools = Collections.unmodifiableList(new ArrayList<>(builder.extraTools));
        this.pluginRegistry = builder.pluginRegistry;
    }

    public String agentId() { return agentId; }
    public String workingDir() { return workingDir; }
    public boolean operatorMode() { return operatorMode; }
    public List<String> allowedToolPatterns() { return allowedToolPatterns; }
    public List<String> deniedToolPatterns() { return deniedToolPatterns; }
    public List<Tool<? extends ToolInput>> extraTools() { return extraTools; }
    public PluginRegistry pluginRegistry() { return pluginRegistry; }

    /**
     * 判断工具是否被策略允许。
     * <p>
     * 对标 OpenClaw 的 isToolAllowedByPolicyName。
     * 先检查黑名单（优先拒绝），再检查白名单（如果非空则只允许匹配的）。
     */
    public boolean isToolAllowed(String toolName) {
        // 黑名单优先
        for (String pattern : deniedToolPatterns) {
            if (matchPattern(toolName, pattern)) return false;
        }
        // 白名单为空则全部允许
        if (allowedToolPatterns.isEmpty()) return true;
        // 白名单匹配
        for (String pattern : allowedToolPatterns) {
            if (matchPattern(toolName, pattern)) return true;
        }
        return false;
    }

    /** Ant-style 通配符匹配 */
    private boolean matchPattern(String text, String pattern) {
        if (pattern.equals("*") || pattern.equals(text)) return true;
        if (pattern.endsWith("*") && text.startsWith(pattern.substring(0, pattern.length() - 1))) return true;
        if (pattern.startsWith("*") && text.endsWith(pattern.substring(1))) return true;
        return text.contains(pattern);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentId = "operator";
        private String workingDir = System.getProperty("user.dir");
        private boolean operatorMode = true;
        private final List<String> allowedToolPatterns = new ArrayList<>();
        private final List<String> deniedToolPatterns = new ArrayList<>();
        private final List<Tool<? extends ToolInput>> extraTools = new ArrayList<>();
        private PluginRegistry pluginRegistry = new PluginRegistry();

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder workingDir(String v) { this.workingDir = v; return this; }
        public Builder operatorMode(boolean v) { this.operatorMode = v; return this; }
        public Builder allowTool(String pattern) { this.allowedToolPatterns.add(pattern); return this; }
        public Builder denyTool(String pattern) { this.deniedToolPatterns.add(pattern); return this; }
        public Builder extraTool(Tool<? extends ToolInput> tool) { this.extraTools.add(tool); return this; }
        public Builder pluginRegistry(PluginRegistry v) { this.pluginRegistry = v; return this; }
        public ToolAssemblyContext build() { return new ToolAssemblyContext(this); }
    }
}
