package com.ouisani.aios.core.task;

/**
 * MoE (Mixture of Experts) 专家领域枚举 — L1 意图分类门。
 * <p>
 * 系统升级为混合专家架构，将全人类的任务抽象为 5 大系统级宏概念 (Macro-Tasks)。
 * 每个领域对应一个专家，拥有独立的 SOP (Standard Operating Procedure) 规则文本。
 *
 * @see com.ouisani.aios.core.task.SopManager
 */
public enum ExpertDomain {

    /** 软件工程 — 写代码、Debug、部署、架构设计、重构 */
    SOFTWARE_ENGINEERING("software_engineering", "软件工程专家"),

    /** 数据调研 — 联网搜索、爬虫、总结、报表、统计分析 */
    DATA_RESEARCH("data_research", "数据调研专家"),

    /** 内容创作 — 写长文、PPT、翻译、摘要、创意写作 */
    CONTENT_CREATION("content_creation", "内容创作专家"),

    /** 工作流自动化 — 定时发邮件、操作 Excel、RPA 物理点击 */
    WORKFLOW_AUTOMATION("workflow_automation", "工作流自动化专家"),

    /** 系统操作 — 管理 VFS 文件、配置网络、安装依赖 */
    SYSTEM_OPERATION("system_operation", "系统操作专家");

    private final String sopFileName;
    private final String displayName;

    ExpertDomain(String sopFileName, String displayName) {
        this.sopFileName = sopFileName;
        this.displayName = displayName;
    }

    /**
     * 获取 SOP 文件名（不含扩展名，如 "software_engineering"）。
     */
    public String sopFileName() {
        return sopFileName;
    }

    /**
     * 获取专家显示名称。
     */
    public String displayName() {
        return displayName;
    }
}
