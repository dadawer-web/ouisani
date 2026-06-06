package com.ouisani.aios.user.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentfile 语法解析器 — AIOS 的 Dockerfile Parser。
 * <p>
 * 解析类似于 Dockerfile 的自定义语法，将 Agent 的运行环境
 * （系统提示词、知识库、挂载的插件）标准化封装为 {@link AgentImageConfig}。
 *
 * <h3>支持的指令</h3>
 * <table>
 *   <tr><th>指令</th><th>语法</th><th>说明</th><th>Docker 类比</th></tr>
 *   <tr><td>FROM</td><td>{@code FROM gpt-4o}</td><td>指定底层内核算力模型</td><td>FROM ubuntu:22.04</td></tr>
 *   <tr><td>PERSONA</td><td>{@code PERSONA "你是一个..."}</td><td>注入核心系统提示词/人设</td><td>ENV + LABEL</td></tr>
 *   <tr><td>RUN</td><td>{@code RUN sys_insmod github_search}</td><td>启动时预加载插件内核模块</td><td>RUN apt-get install</td></tr>
 *   <tr><td>COPY</td><td>{@code COPY ./docs /knowledge_base}</td><td>将本地文件向量化并挂载到知识库</td><td>COPY src dst</td></tr>
 *   <tr><td>MOUNT</td><td>{@code MOUNT /host/path /container/path}</td><td>挂载 VFS 存储卷</td><td>VOLUME /data</td></tr>
 *   <tr><td>LIMIT_TOKENS</td><td>{@code LIMIT_TOKENS 100000}</td><td>Token 硬限制 (Cgroup)</td><td>--memory=512m</td></tr>
 *   <tr><td>NETWORK</td><td>{@code NETWORK team_alpha}</td><td>网络组/桥 (IPC 隔离)</td><td>--network=bridge</td></tr>
 *   <tr><td>ENTRYPOINT</td><td>{@code ENTRYPOINT ["等待用户输入"]}</td><td>入口命令</td><td>ENTRYPOINT ["python"]</td></tr>
 * </table>
 *
 * <h3>Agentfile 示例</h3>
 * <pre>
 * # Java 高级工程师
 * FROM gpt-4o
 * PERSONA "你是一个资深的 Java 工程师，精通 Spring Boot 和分布式系统。"
 * RUN sys_insmod github_search
 * RUN sys_insmod code_linter
 * COPY ./project_docs /knowledge_base
 * LIMIT_TOKENS 100000
 * NETWORK dev_team
 * ENTRYPOINT ["等待用户输入"]
 * </pre>
 *
 * @see AgentImageConfig
 */
public class AgentfileParser {

    /**
     * 解析 Agentfile 文本内容，构建不可变的 AgentImageConfig。
     *
     * @param agentfileContent Agentfile 文本内容
     * @return 解析后的镜像配置
     * @throws IllegalArgumentException 如果语法错误
     */
    public AgentImageConfig parse(String agentfileContent) {
        AgentImageConfig.Builder builder = AgentImageConfig.builder();

        String[] lines = agentfileContent.split("\\R");
        int lineNumber = 0;

        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.strip();

            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // 提取指令关键字
            String directive = extractDirective(line);
            String remainder = line.substring(directive.length()).strip();

            switch (directive.toUpperCase()) {
                case "FROM" -> parseFrom(builder, remainder, lineNumber);
                case "PERSONA" -> parsePersona(builder, remainder, lineNumber);
                case "RUN" -> parseRun(builder, remainder, lineNumber);
                case "COPY" -> parseCopy(builder, remainder, lineNumber);
                case "MOUNT" -> parseMount(builder, remainder, lineNumber);
                case "LIMIT_TOKENS" -> parseLimitTokens(builder, remainder, lineNumber);
                case "NETWORK" -> parseNetwork(builder, remainder, lineNumber);
                case "ENTRYPOINT" -> parseEntrypoint(builder, remainder, lineNumber);
                default -> throw new IllegalArgumentException(
                        "[Agentfile] Line " + lineNumber + ": Unknown directive '" + directive + "'");
            }
        }

        // 校验必填字段
        if (builder.build().baseImage() == null) {
            throw new IllegalArgumentException("[Agentfile] Missing required FROM directive");
        }

        AgentImageConfig config = builder.build();
        System.out.println("[Agentfile] Parse complete → " + config);
        return config;
    }

    // ════════════════════════════════════════════════════════════════
    //  指令解析器
    // ════════════════════════════════════════════════════════════════

    /**
     * FROM gpt-4o — 指定底层内核算力模型。
     * <p>
     * 类比 Docker 的 FROM ubuntu:22.04 — 选择基础镜像。
     * 支持的格式：
     * <ul>
     *   <li>{@code FROM gpt-4o} — OpenAI GPT-4o</li>
     *   <li>{@code FROM aios/graalwasm} — GraalVM WASM 沙箱</li>
     *   <li>{@code FROM docker:python:3.10} — Docker 容器</li>
     * </ul>
     */
    private void parseFrom(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": FROM requires an image name");
        }
        String[] parts = remainder.split("\\s+");
        builder.baseImage(parts[0]);
        System.out.println("[Agentfile] FROM " + parts[0]);
    }

    /**
     * PERSONA "你是一个资深的 Java 工程师..." — 注入核心系统提示词/人设。
     * <p>
     * 类比 Docker 的 ENV + LABEL — 设置容器的环境变量和元数据。
     * PERSONA 是 Agent 的"灵魂"，决定了它的行为模式和知识边界。
     * <p>
     * 支持两种格式：
     * <ul>
     *   <li>{@code PERSONA "引号包裹的文本"}</li>
     *   <li>{@code PERSONA 无引号的文本（到行尾）}</li>
     * </ul>
     */
    private void parsePersona(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": PERSONA requires a persona text");
        }

        String persona;
        if (remainder.startsWith("\"")) {
            // 引号包裹：提取引号内的内容
            int closingQuote = remainder.indexOf('"', 1);
            if (closingQuote < 0) {
                throw new IllegalArgumentException(
                        "[Agentfile] Line " + line + ": PERSONA has unclosed quote");
            }
            persona = remainder.substring(1, closingQuote);
        } else {
            // 无引号：整行作为 persona
            persona = remainder;
        }

        builder.persona(persona);
        System.out.println("[Agentfile] PERSONA \"" + truncate(persona, 60) + "...\"");
    }

    /**
     * RUN sys_insmod github_search — 启动时预加载插件内核模块。
     * <p>
     * 类比 Docker 的 RUN apt-get install — 在构建时安装软件包。
     * RUN 指令在容器启动时执行，将指定的插件模块加载到 Agent 的工具链中。
     * <p>
     * 支持的格式：
     * <ul>
     *   <li>{@code RUN sys_insmod github_search} — 加载指定插件</li>
     *   <li>{@code RUN sys_insmod code_linter} — 可多次使用</li>
     * </ul>
     */
    private void parseRun(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": RUN requires a command");
        }

        // 提取 sys_insmod 后的插件名
        String pluginName;
        if (remainder.startsWith("sys_insmod")) {
            pluginName = remainder.substring("sys_insmod".length()).strip();
            if (pluginName.isEmpty()) {
                throw new IllegalArgumentException(
                        "[Agentfile] Line " + line + ": sys_insmod requires a module name");
            }
        } else {
            // 其他 RUN 命令暂不支持
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": Unsupported RUN command '" + remainder
                    + "'. Only 'sys_insmod <module>' is supported.");
        }

        builder.plugin(pluginName);
        System.out.println("[Agentfile] RUN sys_insmod " + pluginName);
    }

    /**
     * COPY ./docs /knowledge_base — 将本地文件向量化并挂载到只读的知识库 VFS 节点。
     * <p>
     * 类比 Docker 的 COPY src dst — 将构建上下文中的文件复制到镜像中。
     * 在 AIOS 中，COPY 将本地文件内容向量化后挂载到容器的知识库路径，
     * Agent 可以通过 VFS 读取这些知识。
     * <p>
     * 格式：{@code COPY <local_path> <container_path>}
     * <ul>
     *   <li>{@code COPY ./docs /knowledge_base} — 文档挂载为知识库</li>
     *   <li>{@code COPY ./tool.wasm /bin/tool.wasm} — WASM 模块</li>
     * </ul>
     */
    private void parseCopy(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": COPY requires <src> <dst>");
        }

        String[] parts = remainder.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": COPY requires <src> <dst>");
        }

        String src = parts[0];
        String dst = parts[1];

        // 判断是知识库挂载还是 WASM 模块
        if (dst.endsWith(".wasm") || src.endsWith(".wasm")) {
            builder.wasmPath(dst);
            System.out.println("[Agentfile] COPY " + src + " → " + dst + " (WASM module)");
        } else {
            builder.knowledgeMount(src, dst);
            System.out.println("[Agentfile] COPY " + src + " → " + dst + " (knowledge base)");
        }
    }

    /**
     * MOUNT /host/path /container/path — 挂载 VFS 存储卷。
     * <p>
     * 类比 Docker 的 VOLUME /data — 声明挂载点。
     */
    private void parseMount(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": MOUNT requires <host_path> <container_path>");
        }

        String[] parts = remainder.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": MOUNT requires <host_path> <container_path>");
        }

        builder.volumeMount(parts[0], parts[1]);
        System.out.println("[Agentfile] MOUNT " + parts[0] + " → " + parts[1]);
    }

    /**
     * LIMIT_TOKENS 100000 — Token 硬限制 (Cgroup)。
     * <p>
     * 类比 Docker 的 --memory=512m — 限制容器内存使用。
     * 在 AIOS 中，"内存"就是 Token。LIMIT_TOKENS 映射到 CgroupManager 的配额。
     */
    private void parseLimitTokens(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": LIMIT_TOKENS requires a number");
        }

        try {
            long limit = Long.parseLong(remainder.split("\\s+")[0]);
            builder.tokenLimit(limit);
            System.out.println("[Agentfile] LIMIT_TOKENS " + limit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": LIMIT_TOKENS value is not a valid number: " + remainder);
        }
    }

    /**
     * NETWORK team_alpha — 网络组/桥 (IPC 隔离)。
     * <p>
     * 类比 Docker 的 --network=bridge — 指定网络模式。
     * 同一 NETWORK 组的 Agent 可以通过共享内存/信号互相通信，
     * 不同组的 Agent 完全隔离。
     */
    private void parseNetwork(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": NETWORK requires a network name");
        }

        String networkName = remainder.split("\\s+")[0];
        builder.networkGroup(networkName);
        System.out.println("[Agentfile] NETWORK " + networkName);
    }

    /**
     * ENTRYPOINT ["等待用户输入"] — 入口命令。
     * <p>
     * 类比 Docker 的 ENTRYPOINT ["python", "app.py"]。
     * 支持两种格式：
     * <ul>
     *   <li>{@code ENTRYPOINT ["等待用户输入"]} — JSON 数组格式</li>
     *   <li>{@code ENTRYPOINT 等待用户输入} — 纯文本格式</li>
     * </ul>
     */
    private void parseEntrypoint(AgentImageConfig.Builder builder, String remainder, int line) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": ENTRYPOINT requires a command");
        }

        String entrypoint;
        if (remainder.startsWith("[")) {
            // JSON 数组格式：ENTRYPOINT ["cmd1", "cmd2"]
            entrypoint = parseJsonArray(remainder, line);
        } else {
            // 纯文本格式
            entrypoint = remainder;
        }

        builder.entrypoint(entrypoint);
        System.out.println("[Agentfile] ENTRYPOINT \"" + truncate(entrypoint, 50) + "\"");
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 提取指令关键字（第一个 token）。
     */
    private String extractDirective(String line) {
        int spaceIdx = line.indexOf(' ');
        return spaceIdx > 0 ? line.substring(0, spaceIdx) : line;
    }

    /**
     * 解析 JSON 数组格式的 ENTRYPOINT。
     * 简化实现：提取方括号内的所有引号字符串，用空格连接。
     */
    private String parseJsonArray(String text, int line) {
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": Invalid JSON array format: " + text);
        }

        String inner = text.substring(1, text.length() - 1).strip();
        if (inner.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": ENTRYPOINT array is empty");
        }

        // 提取所有引号内的字符串
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < inner.length()) {
            if (inner.charAt(i) == '"') {
                int end = inner.indexOf('"', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "[Agentfile] Line " + line + ": Unclosed quote in ENTRYPOINT");
                }
                parts.add(inner.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }

        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Agentfile] Line " + line + ": No valid strings in ENTRYPOINT array");
        }

        return String.join(" ", parts);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    //  AppManifest 解析（兼容旧接口）
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析通用 OS 应用清单。
     * <p>
     * 支持的指令：
     * <ul>
     *   <li>{@code APP_NAME xxx}</li>
     *   <li>{@code SPAWN worker 50}</li>
     *   <li>{@code BUDGET 5000}</li>
     *   <li>{@code MOUNT /shared/data:/var/mem}</li>
     *   <li>{@code ENTRYPOINT xxx}</li>
     * </ul>
     */
    public static AppManifest parseManifest(String content) {
        AppManifest.Builder builder = AppManifest.builder();

        String[] lines = content.split("\\R");
        int lineNumber = 0;

        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+");
            String directive = parts[0].toUpperCase();

            switch (directive) {
                case "APP_NAME" -> {
                    if (parts.length < 2) throw new IllegalArgumentException(
                            "[Manifest] Line " + lineNumber + ": APP_NAME requires a name");
                    builder.appName(parts[1]);
                }
                case "SPAWN" -> {
                    if (parts.length < 3) throw new IllegalArgumentException(
                            "[Manifest] Line " + lineNumber + ": SPAWN requires <role> <count>");
                    try {
                        builder.spawnCount(Integer.parseInt(parts[2]));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "[Manifest] Line " + lineNumber + ": SPAWN count invalid: " + parts[2]);
                    }
                }
                case "BUDGET" -> {
                    if (parts.length < 2) throw new IllegalArgumentException(
                            "[Manifest] Line " + lineNumber + ": BUDGET requires a number");
                    try {
                        builder.tokenBudget(Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "[Manifest] Line " + lineNumber + ": BUDGET invalid: " + parts[1]);
                    }
                }
                case "MOUNT" -> {
                    if (parts.length < 2) throw new IllegalArgumentException(
                            "[Manifest] Line " + lineNumber + ": MOUNT requires <host>:<container>");
                    String mountSpec = parts[1];
                    int colonIdx = mountSpec.indexOf(':');
                    if (colonIdx <= 0 || colonIdx >= mountSpec.length() - 1) {
                        throw new IllegalArgumentException(
                                "[Manifest] Line " + lineNumber + ": MOUNT format: <host_path>:<container_path>");
                    }
                    builder.mount(mountSpec.substring(0, colonIdx), mountSpec.substring(colonIdx + 1));
                }
                case "ENTRYPOINT" -> {
                    if (parts.length < 2) throw new IllegalArgumentException(
                            "[Manifest] Line " + lineNumber + ": ENTRYPOINT requires a command");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) {
                        if (i > 1) sb.append(' ');
                        sb.append(parts[i]);
                    }
                    builder.entrypoint(sb.toString());
                }
                default -> throw new IllegalArgumentException(
                        "[Manifest] Line " + lineNumber + ": Unknown directive '" + parts[0] + "'");
            }
        }

        AppManifest manifest = builder.build();
        System.out.println("[Manifest Parser] Parsed: " + manifest);
        return manifest;
    }
}
