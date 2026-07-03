package com.ouisani.aios.core.task;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * SOP (Standard Operating Procedure) 管理器 — L2 动态加载 SOP 模板。
 * <p>
 * 负责根据传入的 {@link ExpertDomain}，从 VFS 加载对应的 Markdown 格式 SOP 规则文本。
 * 就像加载驱动程序一样：确定了任务类别后，内核从 VFS 中提取对应领域的 SOP 提示词模板。
 * <p>
 * SOP 文件存储在 VFS 路径 {@code /vfs/system/sops/{domain}_sop.md} 下。
 * 首次访问时自动初始化内置 SOP 模板。
 *
 * @see ExpertDomain
 * @see com.ouisani.aios.user.apps.omnifactory.TopologyCompiler
 */
public class SopManager {

    private static final Logger log = LoggerFactory.getLogger(SopManager.class);

    /** VFS 中的 SOP 存储根目录 — 类比 Linux /lib/modules/ 驱动目录 */
    private static final String SOP_VFS_ROOT = "/vfs/system/sops";

    /** SOP 缓存：domain → SOP 文本 */
    private final ConcurrentHashMap<ExpertDomain, String> sopCache = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    private static final class Holder {
        static final SopManager INSTANCE = new SopManager();
    }

    public static SopManager getInstance() {
        return Holder.INSTANCE;
    }

    private SopManager() {
    }

    /**
     * 初始化 SOP 库 — 将内置 SOP 模板写入 VFS。
     */
    public synchronized void initialize() {
        if (initialized) return;

        VfsManager vfs = VfsManager.instance();

        // 为每个专家领域写入 SOP 模板（如果 VFS 中不存在）
        for (ExpertDomain domain : ExpertDomain.values()) {
            String vfsPath = getSopVfsPath(domain);
            if (!vfs.exists(vfsPath)) {
                String sopContent = getBuiltinSop(domain);
                vfs.writeText(vfsPath, sopContent);
                log.info("[SopManager] SOP 模板已初始化: {} → {}", domain.displayName(), vfsPath);
            }
        }

        initialized = true;
        log.info("[SopManager] SOP 库初始化完成，共 {} 个专家领域", ExpertDomain.values().length);
    }

    /**
     * 获取 SOP 在 VFS 中的路径。
     * <p>
     * 路径格式：{@code /vfs/system/sops/{domain}_sop.md}
     * 例如：{@code /vfs/system/sops/research_sop.md}
     */
    private String getSopVfsPath(ExpertDomain domain) {
        return SOP_VFS_ROOT + "/" + domain.sopFileName() + "_sop.md";
    }

    /**
     * 获取指定专家领域的 SOP 规则文本。
     * <p>
     * 优先从缓存读取，缓存未命中时从 VFS 加载。
     *
     * @param domain 专家领域
     * @return SOP 规则文本（Markdown 格式）
     */
    public String getSop(ExpertDomain domain) {
        if (!initialized) {
            initialize();
        }

        return sopCache.computeIfAbsent(domain, d -> {
            String vfsPath = getSopVfsPath(d);
            String content = VfsManager.instance().readText(vfsPath);
            if (content == null || content.isEmpty()) {
                log.warn("[SopManager] VFS 中未找到 SOP，使用内置模板: {}", d.displayName());
                content = getBuiltinSop(d);
                VfsManager.instance().writeText(vfsPath, content);
            }
            return content;
        });
    }

    /**
     * 融合多个专家的 SOP 文本。
     * <p>
     * 将多个专家的 SOP 规则拼接在一起，用于 MoE 融合编译。
     *
     * @param domains 专家领域列表
     * @return 融合后的 SOP 文本
     */
    public String blendSops(java.util.List<ExpertDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return getSop(ExpertDomain.SYSTEM_OPERATION);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 融合专家 SOP 规则\n\n");
        sb.append("以下是本次任务涉及的多个专家领域的 SOP 规则。");
        sb.append("请在拆解任务时，综合参考所有专家的规则。\n\n");

        for (ExpertDomain domain : domains) {
            String sop = getSop(domain);
            sb.append("---\n\n");
            sb.append(sop).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 刷新 SOP 缓存（热更新支持）。
     */
    public void refreshCache() {
        sopCache.clear();
        initialized = false;
        log.info("[SopManager] SOP 缓存已清空，下次访问将重新加载");
    }

    // ════════════════════════════════════════════════════════════════
    //  内置 SOP 模板
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取内置 SOP 模板。
     */
    private String getBuiltinSop(ExpertDomain domain) {
        return switch (domain) {
            case SOFTWARE_ENGINEERING -> SOFTWARE_ENGINEERING_SOP;
            case DATA_RESEARCH -> DATA_RESEARCH_SOP;
            case CONTENT_CREATION -> CONTENT_CREATION_SOP;
            case WORKFLOW_AUTOMATION -> WORKFLOW_AUTOMATION_SOP;
            case SYSTEM_OPERATION -> SYSTEM_OPERATION_SOP;
        };
    }

    // ── 软件工程专家 SOP ──
    private static final String SOFTWARE_ENGINEERING_SOP = """
            # 软件工程专家 SOP (Software Engineering)

            ## 核心原则：微任务化 (Micro-tasking)

            你是软件工程专家。在拆解任务时，必须遵循微任务化原则：
            每个节点只负责一个极其明确的单一职责，禁止宏大模糊的节点。

            ## 拆解规则

            1. **单文件原则**：每个节点最多生成或修改一个文件。
               - 错误：`node: build_entire_backend`（太模糊）
               - 正确：`node: create_pom_xml` → `node: create_main_application` → `node: create_user_controller`

            2. **依赖链必须显式**：节点间通过 VFS 路径传递数据。
               - 上游节点将产物写入 `/vfs/workspace/{taskId}/src/Main.java`
               - 下游节点通过 `file_read` 工具读取该路径

            3. **验证节点独立**：每个代码生成节点后，必须有独立的验证节点。
               - `node: compile_check` — 编译检查
               - `node: syntax_lint` — 语法检查

            4. **禁止跨层跳跃**：不允许从"设计"直接跳到"部署"。
               必须经过：设计 → 生成 → 验证 → 测试 → 部署

            ## 节点输出格式

            每个代码生成节点必须输出：
            - 文件路径（VFS 路径）
            - 文件内容（完整代码）
            - 依赖说明（上游文件列表）
            """;

    // ── 数据调研专家 SOP ──
    private static final String DATA_RESEARCH_SOP = """
            # 数据调研专家 SOP (Data Research)

            ## 核心原则：微任务化 (Micro-tasking)

            你是数据调研专家。在拆解任务时，必须遵循微任务化原则：
            每个节点只负责一个数据操作（采集、清洗、分析、可视化）。

            ## 拆解规则

            1. **采集与处理分离**：
               - `node: fetch_data` — 只负责获取原始数据，写入 VFS
               - `node: clean_data` — 只负责清洗，读取上游 VFS 数据
               - `node: analyze_data` — 只负责统计分析

            2. **数据通过 VFS 流转**：
               - 原始数据 → `/vfs/workspace/{taskId}/raw/data.json`
               - 清洗后 → `/vfs/workspace/{taskId}/clean/data.json`
               - 分析结果 → `/vfs/workspace/{taskId}/analysis/report.md`

            3. **爬虫任务必须拆分**：
               - 错误：`node: crawl_and_summarize`（太模糊）
               - 正确：`node: fetch_page` → `node: parse_html` → `node: extract_data` → `node: summarize`

            4. **结果可追溯**：每个分析节点必须引用其数据来源路径。

            ## 节点输出格式

            每个数据节点必须输出：
            - 输入数据路径（VFS 路径）
            - 输出数据路径（VFS 路径）
            - 处理摘要（一句话描述做了什么）
            """;

    // ── 内容创作专家 SOP ──
    private static final String CONTENT_CREATION_SOP = """
            # 内容创作专家 SOP (Content Creation)

            ## 核心原则：微任务化 (Micro-tasking)

            你是内容创作专家。每个节点只负责一个内容生产步骤。

            ## 拆解规则

            1. **大纲先行**：第一个节点必须是 `node: create_outline`
            2. **分段撰写**：每个节点只写一个章节
            3. **最终审校**：最后一个节点必须是 `node: final_review`

            ## 节点输出格式

            每个内容节点必须输出：
            - 输出文件路径（VFS 路径）
            - 内容摘要（一句话描述）
            """;

    // ── 工作流自动化专家 SOP ──
    private static final String WORKFLOW_AUTOMATION_SOP = """
            # 工作流自动化专家 SOP (Workflow Automation)

            ## 核心原则：微任务化 (Micro-tasking)

            你是工作流自动化专家。每个节点只执行一个自动化操作。

            ## 拆解规则

            1. **触发器与执行分离**：
               - `node: setup_trigger` — 配置触发条件（定时/事件）
               - `node: execute_action` — 执行具体操作（发邮件/操作Excel/RPA点击）

            2. **RPA 操作拆分**：
               - 错误：`node: automate_excel_report`（太模糊）
               - 正确：`node: open_excel` → `node: fill_data` → `node: save_and_send`

            3. **错误处理独立**：每个自动化操作后必须有 `node: verify_result`

            ## 节点输出格式

            每个自动化节点必须输出：
            - 操作类型（email/excel/rpa）
            - 执行结果（成功/失败）
            - 日志路径（VFS 路径）
            """;

    // ── 系统操作专家 SOP ──
    private static final String SYSTEM_OPERATION_SOP = """
            # 系统操作专家 SOP (System Operation)

            ## 核心原则：微任务化 (Micro-tasking)

            你是系统操作专家。每个节点只执行一个系统操作。

            ## 拆解规则

            1. **检查先行**：第一个节点必须是环境检查
               - `node: check_environment` — 检查当前系统状态

            2. **操作分离**：部署、配置、启动分别独立节点
               - `node: install_dependency` — 安装依赖
               - `node: write_config` — 写入配置文件
               - `node: start_service` — 启动服务

            3. **验证收尾**：最后一个节点必须是健康检查
               - `node: health_check` — 验证服务是否正常运行

            ## 节点输出格式

            每个系统操作节点必须输出：
            - 操作命令
            - 执行结果（stdout/stderr）
            - 影响的文件路径（VFS 路径）
            """;
}
