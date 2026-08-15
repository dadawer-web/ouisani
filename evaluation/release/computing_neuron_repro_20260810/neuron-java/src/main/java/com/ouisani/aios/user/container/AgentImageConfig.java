package com.ouisani.aios.user.container;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 镜像配置 — 不可变的容器蓝图。
 * <p>
 * 类比 Docker Image Config：一个 Docker 镜像包含了基础 OS、安装的软件、
 * 环境变量和启动命令。AgentImageConfig 包含了 Agent 运行所需的一切：
 * 底层模型、人设、插件、知识库和资源限制。
 *
 * <h3>Agentfile 语法示例</h3>
 * <pre>
 * FROM gpt-4o
 * PERSONA "你是一个资深的 Java 工程师..."
 * RUN sys_insmod github_search
 * RUN sys_insmod code_linter
 * COPY ./docs /knowledge_base
 * LIMIT_TOKENS 100000
 * NETWORK team_alpha
 * ENTRYPOINT ["等待用户输入"]
 * </pre>
 *
 * @see AgentfileParser
 * @see ContainerRuntime
 */
public record AgentImageConfig(

        /** 底层内核算力模型 (FROM gpt-4o) */
        String baseImage,

        /** 核心系统提示词/人设 (PERSONA "...") */
        String persona,

        /** 启动时预加载的插件模块列表 (RUN sys_insmod xxx) */
        List<String> plugins,

        /** 知识库挂载映射：本地路径 → 容器内只读 VFS 路径 (COPY ./docs /knowledge_base) */
        Map<String, String> knowledgeMounts,

        /** 存储卷挂载映射：宿主 VFS 路径 → 容器内路径 (MOUNT /host /container) */
        Map<String, String> volumeMounts,

        /** WASM/代码路径 (COPY ./tool.wasm /bin/tool.wasm) */
        String wasmPath,

        /** 入口命令 (ENTRYPOINT ["..."]) */
        String entrypoint,

        /** Token 硬限制 (LIMIT_TOKENS 100000) */
        long tokenLimit,

        /** 网络组/桥 — 同组 Agent 可互相通信 (NETWORK team_alpha) */
        String networkGroup

) {
    public AgentImageConfig {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        knowledgeMounts = knowledgeMounts == null ? Map.of() : Map.copyOf(knowledgeMounts);
        volumeMounts = volumeMounts == null ? Map.of() : Map.copyOf(volumeMounts);
    }

    /**
     * Builder — 流式构建 AgentImageConfig。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseImage;
        private String persona;
        private List<String> plugins = new java.util.ArrayList<>();
        private Map<String, String> knowledgeMounts = new java.util.LinkedHashMap<>();
        private Map<String, String> volumeMounts = new java.util.LinkedHashMap<>();
        private String wasmPath;
        private String entrypoint;
        private long tokenLimit;
        private String networkGroup;

        public Builder baseImage(String v) { this.baseImage = v; return this; }
        public Builder persona(String v) { this.persona = v; return this; }
        public Builder plugin(String v) { this.plugins.add(v); return this; }
        public Builder plugins(List<String> v) { this.plugins = new java.util.ArrayList<>(v); return this; }
        public Builder knowledgeMount(String host, String container) { this.knowledgeMounts.put(host, container); return this; }
        public Builder volumeMount(String host, String container) { this.volumeMounts.put(host, container); return this; }
        public Builder wasmPath(String v) { this.wasmPath = v; return this; }
        public Builder entrypoint(String v) { this.entrypoint = v; return this; }
        public Builder tokenLimit(long v) { this.tokenLimit = v; return this; }
        public Builder networkGroup(String v) { this.networkGroup = v; return this; }

        public AgentImageConfig build() {
            return new AgentImageConfig(baseImage, persona, plugins, knowledgeMounts,
                    volumeMounts, wasmPath, entrypoint, tokenLimit, networkGroup);
        }
    }

    @Override
    public String toString() {
        return "AgentImageConfig{FROM=" + baseImage
                + ", persona=" + (persona != null ? persona.length() + " chars" : "null")
                + ", plugins=" + plugins
                + ", knowledge=" + knowledgeMounts.size() + " mounts"
                + ", volumes=" + volumeMounts.size() + " mounts"
                + ", wasm=" + wasmPath
                + ", entry=" + entrypoint
                + ", tokens=" + tokenLimit
                + ", network=" + networkGroup
                + "}";
    }
}
