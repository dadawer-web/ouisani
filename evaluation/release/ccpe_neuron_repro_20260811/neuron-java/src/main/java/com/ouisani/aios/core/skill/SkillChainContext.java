package com.ouisani.aios.core.skill;

/**
 * SkillChain 执行上下文 — 一次链式执行的运行时环境。
 * <p>
 * 与 {@link MetaSkill}（声明性规格）正交：MetaSkill 描述"做什么"，
 * SkillChainContext 描述"在哪里、以谁的身份做"。
 * <p>
 * OS 类比：相当于 Linux 进程的 task_struct 中的身份字段 —
 * 同一份 init 脚本（MetaSkill）可以在不同的 PID/namespace（Context）下运行。
 *
 * @param agentId    调用方 Agent ID（用于 ProvenanceHook ThreadLocal，
 *                   将写入审计记录归属于此 agent）
 * @param sessionId  会话 ID（用于 ProvenanceHook ThreadLocal）
 * @param workingDir 工作目录（用于 SkillLoader 查找 PROJECT 来源的 skill）
 * @param slug       本次执行的 slug（用于输出路径隔离，
 *                   多次运行同一 MetaSkill 不会互相覆盖）
 * @param snapshotId 执行前捕获的 {@link com.ouisani.aios.core.snapshot.EnvironmentSnapshot}
 *                   ID（R3：用于 reproduce 时恢复环境状态；可空表示无快照）
 */
public record SkillChainContext(
        String agentId,
        String sessionId,
        String workingDir,
        String slug,
        String snapshotId
) {
    public SkillChainContext {
        if (agentId == null || agentId.isBlank()) agentId = "meta-skill";
        if (sessionId == null) sessionId = "";
        if (workingDir == null || workingDir.isBlank()) {
            workingDir = System.getProperty("user.dir", ".");
        }
        if (slug == null || slug.isBlank()) slug = "default";
        if (snapshotId == null) snapshotId = "";
    }

    /**
     * 4 参数便利构造器 — R2 向后兼容（snapshotId 默认空）。
     */
    public SkillChainContext(String agentId, String sessionId, String workingDir, String slug) {
        this(agentId, sessionId, workingDir, slug, "");
    }

    /** 便利构造器 — 仅 agentId + workingDir，slug 自动为 "default" */
    public SkillChainContext(String agentId, String workingDir) {
        this(agentId, "", workingDir, "default", "");
    }

    /** 便利构造器 — agentId + workingDir + slug（无 sessionId） */
    public SkillChainContext(String agentId, String workingDir, String slug) {
        this(agentId, "", workingDir, slug, "");
    }

    /** 是否携带环境快照 */
    public boolean hasSnapshot() {
        return snapshotId != null && !snapshotId.isBlank();
    }
}
