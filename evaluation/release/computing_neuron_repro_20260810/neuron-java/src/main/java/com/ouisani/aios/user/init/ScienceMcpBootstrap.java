package com.ouisani.aios.user.init;

import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.ouisani.aios.core.mcp.McpConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 科研 MCP 引导器 — 启动时注册科研类 MCP 服务器默认配置。
 * <p>
 * 借鉴 ai4s-research/open-science 的"科研功能"设计：通过 MCP 协议接入
 * 学术数据库（arXiv / PubMed / Crossref / Semantic Scholar / bioRxiv 等），
 * 让 Agent 具备"读论文 / 查引用 / 审稿 / 可复现"四项科研能力。
 *
 * <h3>覆盖的数据源</h3>
 * 通过单个 {@code paper-search-mcp} Python MCP server（uvx 启动）覆盖 20+ 学术数据源：
 * <ul>
 *   <li>arXiv / PubMed / Crossref / Semantic Scholar（用户 R0 计划要求的 4 个）</li>
 *   <li>bioRxiv / medRxiv / PMC / Europe PMC / OpenAlex / DOAJ / DBLP</li>
 *   <li>Zenodo / HAL / SSRN / CORE / Unpaywall / IACR / CiteSeerX / OpenAIRE / BASE</li>
 * </ul>
 *
 * <h3>前置条件</h3>
 * 需在系统 PATH 中安装 {@code uvx}（{@code pip install uv} 或 {@code brew install uv}）。
 * 未安装时注册失败但主流程继续 — Agent 可正常使用其他 skill，仅失去学术数据源接入。
 *
 * <h3>OS 类比</h3>
 * 相当于 systemd 的 {@code mcp-science.service} 单元 — 开机自启的科研数据网关。
 *
 * @see McpConfigManager
 * @see McpClientRegistry
 */
public final class ScienceMcpBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ScienceMcpBootstrap.class);

    /** paper-search-mcp 启动命令（uvx 无安装、永远最新） */
    private static final List<String> PAPER_SEARCH_COMMAND = List.of("uvx", "paper-search-mcp");

    private ScienceMcpBootstrap() {}

    /**
     * 注册科研 MCP 默认配置。
     * <p>
     * 同时调用 {@link McpConfigManager#addServer}（存配置，供查询）
     * 和 {@link McpClientRegistry#register}（启动 stdio 子进程）。
     * 后者失败不抛异常 — 仅记日志并标记连接 FAILED。
     */
    public static void registerDefaults() {
        McpConfigManager.McpServerConfig paperSearch = new McpConfigManager.McpServerConfig(
                "paper-search",
                "stdio",
                null,
                PAPER_SEARCH_COMMAND,
                Map.of(),
                Map.of(),
                McpConfigManager.ConfigScope.USER
        );

        // 1. 注册到配置管理器（lazy 元数据）
        McpConfigManager.instance().addServer(paperSearch);

        // 2. 立即挂载到客户端注册表（eager 启动 uvx 子进程）
        //    失败不阻塞主流程 — McpClientRegistry.register 内部已 try-catch
        try {
            McpClientRegistry.instance().register("paper-search", paperSearch);
            log.info("[ScienceMcp] paper-search MCP 已注册（覆盖 arXiv/PubMed/Crossref/Semantic Scholar 等 20+ 学术数据源）");
            System.out.println("  ✓ ScienceMcp 已注册（paper-search：arXiv/PubMed/Crossref/Semantic Scholar 等）");
        } catch (Throwable t) {
            log.warn("[ScienceMcp] paper-search MCP 注册失败（不影响主流程）: {}", t.getMessage());
            System.out.println("  ⚠ ScienceMcp 注册失败: " + t.getMessage()
                    + "（如未安装 uvx，请运行: pip install uv）");
        }
    }
}
