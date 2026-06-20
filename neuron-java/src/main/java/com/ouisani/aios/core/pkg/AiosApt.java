package com.ouisani.aios.core.pkg;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.config.SemanticRegistry;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.user.container.AgentImageConfig;
import com.ouisani.aios.user.container.AgentfileParser;
import com.ouisani.aios.vfs.SemanticNode;
import com.ouisani.aios.vfs.VectorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIOS 语义包管理器 — Advanced Package Tool for AIOS。
 * <p>
 * 借鉴 Debian apt-get 和 Docker Hub 的设计思想，AiosApt 是 AIOS 的
 * 统一包管理器。它不仅是供人类使用的 CLI 工具，更是供 Agent 遇到
 * 能力瓶颈时自主下载扩容的"基因注入器"。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>{@code install} — 安装软件包（插件/Agent 镜像/知识库）</li>
 *   <li>{@code remove} — 卸载软件包</li>
 *   <li>{@code search} — 语义搜索软件包</li>
 *   <li>{@code update} — 更新软件源索引</li>
 *   <li>{@code list} — 列出已安装的包</li>
 * </ul>
 *
 * <h3>安装流程</h3>
 * <ol>
 *   <li>从 {@link PackageRepository} 查找包清单</li>
 *   <li>递归解决依赖关系</li>
 *   <li>根据包类型分发安装：
 *     <ul>
 *       <li>PLUGIN → {@link PluginManager#registerPlugin}</li>
 *       <li>AGENT_IMAGE → {@link AgentfileParser#parse} → 本地镜像库</li>
 *       <li>KNOWLEDGE_BASE → 向量化存入 VectorNode</li>
 *     </ul>
 *   </li>
 *   <li>知识库向量化 — 将附带文档 Embedding 存入 {@code /var/lib/apt/knowledge/}</li>
 *   <li>注册到 SemanticRegistry 和本地包数据库</li>
 * </ol>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Debian/Ubuntu</th><th>AIOS AiosApt</th><th>说明</th></tr>
 *   <tr><td>apt-get install</td><td>install()</td><td>安装软件包</td></tr>
 *   <tr><td>apt-get remove</td><td>remove()</td><td>卸载软件包</td></tr>
 *   <tr><td>apt-cache search</td><td>search()</td><td>语义搜索</td></tr>
 *   <tr><td>apt-get update</td><td>update()</td><td>更新软件源</td></tr>
 *   <tr><td>dpkg -l</td><td>list()</td><td>列出已安装包</td></tr>
 *   <tr><td>/var/lib/dpkg/</td><td>/var/lib/apt/</td><td>本地包数据库</td></tr>
 *   <tr><td>/var/cache/apt/</td><td>/var/lib/apt/knowledge/</td><td>知识库缓存</td></tr>
 *   <tr><td>sources.list</td><td>PackageRepository</td><td>软件源</td></tr>
 * </table>
 *
 * @see PackageManifest
 * @see PackageRepository
 */
public final class AiosApt {

    private static final Logger log = LoggerFactory.getLogger(AiosApt.class);

    // ── 路径常量 ──

    private static final String LOCAL_DB_PREFIX = "HKEY_LOCAL_AIOS/Apt/Installed/";
    private static final String KNOWLEDGE_VFS_PATH = "/var/lib/apt/knowledge";

    // ── Singleton ──

    private static final class Holder {
        static final AiosApt INSTANCE = new AiosApt();
    }

    public static AiosApt instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** 本地包数据库：packageName → PackageManifest */
    private final ConcurrentHashMap<String, PackageManifest> installedPackages = new ConcurrentHashMap<>();

    /** 本地 Agent 镜像库：packageName → AgentImageConfig */
    private final ConcurrentHashMap<String, AgentImageConfig> localImageStore = new ConcurrentHashMap<>();

    /** 软件源仓库 */
    private final PackageRepository repository;

    /** LLM Router — 用于知识库向量化 */
    private LlmRouter llmRouter;

    private AiosApt() {
        this.repository = new PackageRepository();
        initializeBuiltinPackages();
    }

    /**
     * 配置 LLM Router — 用于安装时的知识库向量化。
     */
    public void configure(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心指令：install
    // ════════════════════════════════════════════════════════════════

    /**
     * 安装软件包 — 从软件源拉取并安装。
     * <p>
     * 类比 {@code apt-get install <package>}。
     * <p>
     * 安装流程：
     * <ol>
     *   <li>从 PackageRepository 查找包清单</li>
     *   <li>检查是否已安装（跳过或升级）</li>
     *   <li>递归解决依赖关系</li>
     *   <li>根据包类型分发安装</li>
     *   <li>知识库向量化</li>
     *   <li>注册到本地包数据库</li>
     * </ol>
     *
     * @param packageName 包名
     * @return 安装结果
     */
    public InstallResult install(String packageName) {
        log.info("[AiosApt] install: {}", packageName);
        SemanticEtw.getInstance().logEvent("APT", "INSTALL_START", "package=" + packageName);

        long startTime = System.currentTimeMillis();

        // ── Step 1: 查找包清单 ──
        PackageManifest manifest = repository.fetch(packageName);
        if (manifest == null) {
            log.warn("[AiosApt] Package not found: {}", packageName);
            return InstallResult.notFound(packageName);
        }

        // ── Step 2: 检查是否已安装 ──
        PackageManifest existing = installedPackages.get(packageName);
        if (existing != null) {
            if (existing.version().equals(manifest.version())) {
                log.info("[AiosApt] {} already installed (v{})", packageName, manifest.version());
                return InstallResult.alreadyInstalled(packageName, manifest.version());
            }
            log.info("[AiosApt] Upgrading {} from v{} to v{}",
                    packageName, existing.version(), manifest.version());
        }

        // ── Step 3: 递归解决依赖 ──
        List<String> resolvedDeps = new ArrayList<>();
        if (!resolveDependencies(manifest, resolvedDeps, new HashSet<>())) {
            return InstallResult.dependencyError(packageName, "Unresolved dependencies: " + resolvedDeps);
        }

        // 先安装依赖
        for (String dep : resolvedDeps) {
            if (!installedPackages.containsKey(dep)) {
                InstallResult depResult = install(dep);
                if (!depResult.success()) {
                    return InstallResult.dependencyError(packageName,
                            "Failed to install dependency: " + dep);
                }
            }
        }

        // ── Step 4: 根据包类型分发安装 ──
        boolean installed = false;
        String installPath = "";

        switch (manifest.type()) {
            case PLUGIN -> {
                installPath = installPlugin(manifest);
                installed = installPath != null;
            }
            case AGENT_IMAGE -> {
                installPath = installAgentImage(manifest);
                installed = installPath != null;
            }
            case KNOWLEDGE_BASE -> {
                installPath = "/var/lib/apt/knowledge/" + manifest.name();
                installed = true; // 知识库在向量化步骤处理
            }
        }

        if (!installed) {
            return InstallResult.failed(packageName, "Type-specific installation failed");
        }

        // ── Step 5: 知识库向量化 ──
        if (manifest.hasKnowledge()) {
            vectorizeKnowledge(manifest);
        }

        // ── Step 6: 注册到本地包数据库 ──
        installedPackages.put(packageName, manifest);
        SemanticRegistry.instance().setValue(
                LOCAL_DB_PREFIX + packageName + "/Version", manifest.version());
        SemanticRegistry.instance().setValue(
                LOCAL_DB_PREFIX + packageName + "/Type", manifest.type().name());
        SemanticRegistry.instance().setValue(
                LOCAL_DB_PREFIX + packageName + "/InstallTime",
                String.valueOf(System.currentTimeMillis()));
        if (installPath != null) {
            SemanticRegistry.instance().setValue(
                    LOCAL_DB_PREFIX + packageName + "/Path", installPath);
        }

        long elapsed = System.currentTimeMillis() - startTime;

        log.info("[AiosApt] ✓ Installed: {} v{} ({}ms, deps={})",
                packageName, manifest.version(), elapsed, resolvedDeps.size());

        SemanticEtw.getInstance().logEvent("APT", "INSTALL_COMPLETE",
                "package=" + packageName + " version=" + manifest.version()
                + " type=" + manifest.type() + " elapsed=" + elapsed + "ms");

        return new InstallResult(true, packageName, manifest.version(),
                manifest.type(), installPath, resolvedDeps, elapsed, null);
    }

    /**
     * 安装插件类型包 — 通过 PluginManager 注册。
     */
    private String installPlugin(PackageManifest manifest) {
        try {
            PluginManager pm = PluginManager.getInstance();

            if (manifest.pluginBytecodeBase64() != null && !manifest.pluginBytecodeBase64().isBlank()) {
                // 有字节码 — 注册为 WASM 插件
                byte[] bytecode = Base64.getDecoder().decode(manifest.pluginBytecodeBase64());
                pm.registerPlugin(manifest.name(), bytecode);
                log.info("[AiosApt] 插件已注册: {} ({} bytes)", manifest.name(), bytecode.length);
            } else {
                // 无字节码 — 仅注册工具 Schema
                if (manifest.toolSchema() != null && !manifest.toolSchema().isBlank()) {
                    pm.registerToolDefinition(new com.ouisani.aios.core.plugin.ToolDefinition(
                            manifest.name(),
                            manifest.description(),
                            parseToolSchema(manifest.toolSchema()),
                            com.ouisani.aios.core.plugin.ToolDefinition.ToolType.NATIVE,
                            0,
                            "apt:" + manifest.name()
                    ));
                    log.info("[AiosApt] 工具 Schema 已注册: {}", manifest.name());
                }
            }

            return "/lib/plugins/" + manifest.name();

        } catch (Exception e) {
            log.error("[AiosApt] Plugin install failed: {} — {}", manifest.name(), e.getMessage());
            return null;
        }
    }

    /**
     * 安装 Agent 镜像类型包 — 解析 Agentfile 并存入本地镜像库。
     */
    private String installAgentImage(PackageManifest manifest) {
        try {
            if (manifest.agentfile() == null || manifest.agentfile().isBlank()) {
                log.error("[AiosApt] Agent image has no Agentfile: {}", manifest.name());
                return null;
            }

            AgentImageConfig config = new AgentfileParser().parse(manifest.agentfile());
            localImageStore.put(manifest.name(), config);

            log.info("[AiosApt] Agent image stored: {} (baseImage={}, tokenLimit={})",
                    manifest.name(), config.baseImage(), config.tokenLimit());

            return "/var/lib/apt/images/" + manifest.name();

        } catch (Exception e) {
            log.error("[AiosApt] Agent image install failed: {} — {}", manifest.name(), e.getMessage());
            return null;
        }
    }

    /**
     * 知识库向量化 — 将文档 Embedding 存入 VectorNode。
     * <p>
     * 这是 AiosApt 最核心的创新：安装完成后，系统不仅有了工具，
     * 还"学会"了如何使用这个工具。知识库文档被自动向量化存入
     * {@code /var/lib/apt/knowledge/}，使得 Agent 在后续工作中
     * 可以通过语义搜索检索到这些知识。
     */
    private void vectorizeKnowledge(PackageManifest manifest) {
        if (llmRouter == null) {
            log.warn("[AiosApt] No LlmRouter configured — skipping vectorization for {}", manifest.name());
            return;
        }

        try {
            VfsManager vfs = VfsManager.instance();

            // 确保 /var/lib/apt/knowledge/ 存在（使用 VectorNode）
            Optional<VfsNode> knowledgeNodeOpt = vfs.resolve(KNOWLEDGE_VFS_PATH);
            if (knowledgeNodeOpt.isEmpty()) {
                // 挂载一个专用的 VectorNode 作为知识库
                LlmProvider provider = null;
                if (llmRouter.isAvailable() && !llmRouter.getBackends().isEmpty()) {
                    provider = llmRouter.getBackends().values().iterator().next();
                }
                if (provider != null) {
                    VectorNode knowledgeVec = new VectorNode(KNOWLEDGE_VFS_PATH, provider);
                    vfs.mount("/var/lib/apt", "knowledge", knowledgeVec);
                    knowledgeNodeOpt = Optional.of(knowledgeVec);
                }
            }

            if (knowledgeNodeOpt.isPresent() && knowledgeNodeOpt.get() instanceof VectorNode vecNode) {
                // 将知识库文档分块写入 VectorNode
                String doc = manifest.knowledgeDoc();
                // 简单分块：按段落分割
                String[] chunks = doc.split("\n\n");
                for (int i = 0; i < chunks.length; i++) {
                    String chunk = chunks[i].strip();
                    if (chunk.isEmpty()) continue;

                    // 添加包名前缀作为元数据
                    String taggedChunk = "[" + manifest.name() + " v" + manifest.version() + "] " + chunk;
                    vecNode.write(taggedChunk);
                }

                log.info("[AiosApt] Knowledge vectorized: {} → {} chunks → {}",
                        manifest.name(), chunks.length, KNOWLEDGE_VFS_PATH);
            } else {
                log.warn("[AiosApt] 知识向量化已跳过: {}", manifest.name());
            }

        } catch (Exception e) {
            log.warn("[AiosApt] Knowledge vectorization failed for {}: {}", manifest.name(), e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  核心指令：remove
    // ════════════════════════════════════════════════════════════════

    /**
     * 卸载软件包。
     * <p>
     * 类比 {@code apt-get remove <package>}。
     */
    public boolean remove(String packageName) {
        PackageManifest manifest = installedPackages.get(packageName);
        if (manifest == null) {
            log.warn("[AiosApt] Package not installed: {}", packageName);
            return false;
        }

        // 检查是否有其他包依赖它
        for (Map.Entry<String, PackageManifest> entry : installedPackages.entrySet()) {
            if (entry.getValue().depends() != null
                    && entry.getValue().depends().contains(packageName)) {
                log.warn("[AiosApt] Cannot remove {}: {} depends on it",
                        packageName, entry.getKey());
                return false;
            }
        }

        // 根据类型卸载
        switch (manifest.type()) {
            case PLUGIN -> {
                try {
                    PluginManager.getInstance().unregisterPlugin(manifest.name());
                } catch (Exception e) {
                    log.warn("[AiosApt] Plugin unregister failed: {}", e.getMessage());
                }
            }
            case AGENT_IMAGE -> localImageStore.remove(packageName);
        }

        // 从本地数据库移除
        installedPackages.remove(packageName);
        SemanticRegistry.instance().removeKey(LOCAL_DB_PREFIX + packageName + "/Version");
        SemanticRegistry.instance().removeKey(LOCAL_DB_PREFIX + packageName + "/Type");
        SemanticRegistry.instance().removeKey(LOCAL_DB_PREFIX + packageName + "/InstallTime");
        SemanticRegistry.instance().removeKey(LOCAL_DB_PREFIX + packageName + "/Path");

        log.info("[AiosApt] ✓ Removed: {}", packageName);
        SemanticEtw.getInstance().logEvent("APT", "REMOVE", "package=" + packageName);
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心指令：search
    // ════════════════════════════════════════════════════════════════

    /**
     * 语义搜索软件包 — 支持关键词和语义匹配。
     * <p>
     * 类比 {@code apt-cache search <keyword>}。
     */
    public List<PackageManifest> search(String query) {
        return repository.search(query);
    }

    // ════════════════════════════════════════════════════════════════
    //  核心指令：update
    // ════════════════════════════════════════════════════════════════

    /**
     * 更新软件源索引。
     * <p>
     * 类比 {@code apt-get update}。
     */
    public void update() {
        repository.refresh();
        log.info("[AiosApt] 包索引已更新 ({} 个可用包)",
                repository.packageCount());
        SemanticEtw.getInstance().logEvent("APT", "UPDATE",
                "packages=" + repository.packageCount());
    }

    // ════════════════════════════════════════════════════════════════
    //  核心指令：list
    // ════════════════════════════════════════════════════════════════

    /**
     * 列出已安装的包。
     * <p>
     * 类比 {@code dpkg -l}。
     */
    public Map<String, PackageManifest> list() {
        return Collections.unmodifiableMap(installedPackages);
    }

    /**
     * 获取本地镜像库中的 Agent 镜像。
     */
    public Optional<AgentImageConfig> getLocalImage(String name) {
        return Optional.ofNullable(localImageStore.get(name));
    }

    /**
     * 获取本地镜像库中所有 Agent 镜像。
     */
    public Map<String, AgentImageConfig> localImages() {
        return Collections.unmodifiableMap(localImageStore);
    }

    // ════════════════════════════════════════════════════════════════
    //  依赖解决
    // ════════════════════════════════════════════════════════════════

    /**
     * 递归解决依赖关系 — 类比 apt-get 的依赖解析器。
     * <p>
     * 使用深度优先搜索遍历依赖图，检测循环依赖。
     *
     * @param manifest    当前包清单
     * @param resolved    已解析的依赖列表（拓扑排序结果）
     * @param visiting    正在访问的包（用于检测循环依赖）
     * @return 是否成功解决所有依赖
     */
    private boolean resolveDependencies(PackageManifest manifest,
                                         List<String> resolved,
                                         Set<String> visiting) {
        if (!manifest.hasDepends()) return true;

        for (String dep : manifest.depends()) {
            // 检测循环依赖
            if (visiting.contains(dep)) {
                log.error("[AiosApt] Circular dependency detected: {} → {}", manifest.name(), dep);
                return false;
            }

            // 已安装或已解析的跳过
            if (installedPackages.containsKey(dep) || resolved.contains(dep)) continue;

            visiting.add(dep);
            PackageManifest depManifest = repository.fetch(dep);
            if (depManifest == null) {
                log.error("[AiosApt] 依赖未找到: {} (被 {} 依赖)", dep, manifest.name());
                return false;
            }

            // 递归解析子依赖
            if (!resolveDependencies(depManifest, resolved, visiting)) {
                return false;
            }

            resolved.add(dep);
            visiting.remove(dep);
        }

        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  统计与报告
    // ════════════════════════════════════════════════════════════════

    public int installedCount() {
        return installedPackages.size();
    }

    public int availableCount() {
        return repository.packageCount();
    }

    public String getStatsReport() {
        return """
                ┌─ AiosApt Package Manager Stats ─────────────────────
                │  Installed Packages  : %d
                │  Available Packages  : %d
                │  Local Agent Images  : %d
                │  Knowledge Vectorized: /var/lib/apt/knowledge/
                └─────────────────────────────────────────────────"""
                .formatted(installedPackages.size(), repository.packageCount(),
                        localImageStore.size());
    }

    // ── 内部辅助 ──

    /**
     * 简单解析工具 Schema JSON 为 Map。
     */
    private Map<String, Object> parseToolSchema(String schemaJson) {
        try {
            // 简单解析：将 JSON 字符串包装为 input 参数描述
            Map<String, Object> schema = new HashMap<>();
            schema.put("input", Map.of(
                    "type", "object",
                    "description", schemaJson.length() > 200
                            ? schemaJson.substring(0, 200) + "..." : schemaJson
            ));
            return schema;
        } catch (Exception e) {
            return Map.of("input", Map.of("type", "string", "description", "JSON input"));
        }
    }

    /**
     * 初始化内置软件包 — 模拟远程软件源中的包。
     */
    private void initializeBuiltinPackages() {
        repository.addPackage(new PackageManifest(
                "github-mcp-plugin", "1.0.0",
                "GitHub MCP Plugin — 提供 GitHub API 操作能力（创建 Issue、搜索代码、管理 PR）",
                "aios-community",
                PackageManifest.PackageType.PLUGIN,
                List.of(),
                null,
                "# GitHub MCP Plugin\n\n提供 GitHub API 操作能力。\n\n## 功能\n- 搜索代码仓库\n- 创建/关闭 Issue\n- 管理 Pull Request\n- 查看提交历史\n\n## 使用方法\n在 Agent 中调用 sys_insmod github-mcp-plugin 加载。",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"search_repos\",\"create_issue\",\"list_prs\"]},\"query\":{\"type\":\"string\"}}}",
                null,
                Map.of("category", "devops", "tags", "github,mcp,api")
        ));

        repository.addPackage(new PackageManifest(
                "senior-java-coder-agent", "2.1.0",
                "高级 Java 编码 Agent — 精通 Spring Boot、JVM 调优、并发编程",
                "aios-core-team",
                PackageManifest.PackageType.AGENT_IMAGE,
                List.of("github-mcp-plugin"),
                "FROM gpt-4o\nPERSONA \"你是一个资深 Java 架构师，精通 Spring Boot、JVM 调优、并发编程和分布式系统设计。\"\nRUN sys_insmod github-mcp-plugin\nLIMIT_TOKENS 200000\nNETWORK dev_team\nENTRYPOINT [\"等待用户提出 Java 编码或架构问题\"]",
                "# Senior Java Coder Agent\n\n这是一个高级 Java 编码 Agent，具备以下能力：\n\n- Spring Boot 应用开发\n- JVM 性能调优\n- 并发编程（JUC）\n- 分布式系统设计\n- 代码审查与重构\n\n## 依赖\n- github-mcp-plugin: 用于访问 GitHub 仓库",
                null, null,
                Map.of("category", "coding", "tags", "java,spring,architecture")
        ));

        repository.addPackage(new PackageManifest(
                "web-research-plugin", "1.3.0",
                "Web 研究插件 — 提供 Web 搜索和网页摘要能力",
                "aios-community",
                PackageManifest.PackageType.PLUGIN,
                List.of(),
                null,
                "# Web Research Plugin\n\n提供 Web 搜索和网页摘要能力。\n\n## 功能\n- 关键词搜索\n- 网页内容摘要\n- 多源信息聚合\n\n## 使用方法\n在 Agent 中调用 sys_insmod web-research-plugin 加载。",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"search\",\"summarize\"]},\"query\":{\"type\":\"string\"}}}",
                null,
                Map.of("category", "research", "tags", "web,search,summary")
        ));

        repository.addPackage(new PackageManifest(
                "dream-daemon-config", "1.0.0",
                "Dream Daemon 配置包 — 记忆巩固守护进程的知识库",
                "aios-core-team",
                PackageManifest.PackageType.KNOWLEDGE_BASE,
                List.of(),
                null,
                "# Dream Daemon 知识库\n\n记忆巩固守护进程的运行手册。\n\n## 工作原理\nDream Daemon 在系统空闲时运行，将短期记忆整合为长期记忆。\n\n## 艾宾浩斯曲线\n记忆衰减遵循艾宾浩斯遗忘曲线：\n- 20分钟后遗忘42%\n- 1小时后遗忘56%\n- 1天后遗忘66%\n- 2天后遗忘72%\n\n## 触发条件\n- 系统进入 IDLE 状态\n- 收到 SIG_TICK 节拍信号\n- 缓存权重低于阈值",
                null, null,
                Map.of("category", "system", "tags", "memory,dream,consolidation")
        ));

        repository.addPackage(new PackageManifest(
                "data-analyst-agent", "1.5.0",
                "数据分析 Agent — 精通 SQL、Pandas、数据可视化",
                "aios-community",
                PackageManifest.PackageType.AGENT_IMAGE,
                List.of("web-research-plugin"),
                "FROM gpt-4o\nPERSONA \"你是一个数据分析专家，精通 SQL、Python Pandas、数据可视化和统计建模。\"\nRUN sys_insmod web-research-plugin\nLIMIT_TOKENS 150000\nNETWORK analytics_team\nENTRYPOINT [\"等待用户提供数据分析任务\"]",
                "# Data Analyst Agent\n\n数据分析专家，具备以下能力：\n\n- SQL 查询优化\n- Python Pandas 数据处理\n- Matplotlib/Seaborn 数据可视化\n- 统计建模与假设检验\n- 数据清洗与预处理",
                null, null,
                Map.of("category", "analytics", "tags", "data,sql,visualization")
        ));

        log.info("[AiosApt] Built-in repository initialized: {} packages", repository.packageCount());
    }

    // ════════════════════════════════════════════════════════════════
    //  安装结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 安装结果 — 描述一次 install 操作的完整结果。
     */
    public record InstallResult(
            boolean success,
            String packageName,
            String version,
            PackageManifest.PackageType type,
            String installPath,
            List<String> resolvedDeps,
            long elapsedMs,
            String error
    ) {
        static InstallResult notFound(String name) {
            return new InstallResult(false, name, null, null, null, List.of(), 0, "Package not found");
        }

        static InstallResult alreadyInstalled(String name, String version) {
            return new InstallResult(false, name, version, null, null, List.of(), 0, "Already installed");
        }

        static InstallResult dependencyError(String name, String error) {
            return new InstallResult(false, name, null, null, null, List.of(), 0, error);
        }

        static InstallResult failed(String name, String error) {
            return new InstallResult(false, name, null, null, null, List.of(), 0, error);
        }
    }
}
