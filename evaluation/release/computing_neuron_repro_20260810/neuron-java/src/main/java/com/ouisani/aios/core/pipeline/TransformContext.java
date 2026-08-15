package com.ouisani.aios.core.pipeline;

import com.ouisani.aios.core.tool.ToolSdk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转换上下文 — 借鉴 Firecrawl 的 Meta 对象。
 * <p>
 * 在管道执行期间共享状态和配置。
 */
public class TransformContext {

    private final String agentId;
    private final ToolSdk sdk;
    private final String workingDir;
    private final Map<String, Object> metadata;
    private final Map<String, String> options;

    public TransformContext(String agentId, ToolSdk sdk, String workingDir) {
        this.agentId = agentId;
        this.sdk = sdk;
        this.workingDir = workingDir;
        this.metadata = new ConcurrentHashMap<>();
        this.options = new ConcurrentHashMap<>();
    }

    // Getters
    public String agentId() { return agentId; }
    public ToolSdk sdk() { return sdk; }
    public String workingDir() { return workingDir; }
    public Map<String, Object> metadata() { return metadata; }
    public Map<String, String> options() { return options; }

    // 便捷方法
    public void setMeta(String key, Object value) { metadata.put(key, value); }
    public Object getMeta(String key) { return metadata.get(key); }
    public String getMetaString(String key) { return metadata.get(key) != null ? metadata.get(key).toString() : null; }
    public void setOption(String key, String value) { options.put(key, value); }
    public String getOption(String key, String defaultValue) { return options.getOrDefault(key, defaultValue); }
}
