package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.role.RoleBlueprint;

/**
 * 权限画像比较器 — 比较两个角色的权限等级，判定"目标角色是否构成相对当前角色的越权"。
 * <p>
 * <b>为何需要</b>：{@link com.ouisani.aios.core.recovery.TopologyMutationStrategy} 把 LLM 诊断
 * 吐出的 {@code suggested_role} 直接用于节点角色替换，无任何权限校验（洞2）。本类提供
 * "目标角色权限 ≤ 当前角色权限"的可达性约束 —— 作为"角色可达最小权限集合"的实用代理：
 * 不需要手工维护角色层级图，而是用结构化权限画像（{@link PermissionProfile}）计算一个
 * 权限分数，target 分数 &gt; current 即判定越权。
 * <p>
 * <b>权限分数模型</b>（保守、可解释）：
 * <ul>
 *   <li>{@link PermissionMode#BYPASS} → +1000（绕过一切，最高权）</li>
 *   <li>其它可写模式（{@link PermissionMode#allowsWrite ACCEPT_EDITS/AUTO}）→ +50</li>
 *   <li>{@code deny: ["*"]}（默认拒绝所有）→ −500（最受限，仅 allow 规则凿洞）</li>
 *   <li>每条 allow 规则 → +2（凿洞能力，封顶 10 条防 inflate）</li>
 *   <li>每条具体 deny 规则（非 {@code "*"}）→ −5</li>
 * </ul>
 * <p>
 * <b>设计取舍</b>：分数模型是启发式近似，不是形式化能力模型。它正确覆盖关键场景：
 * 只读 reviewer（deny {@code *}）无法被突变成可写 coder；任何角色无法突变到 BYPASS。
 * 对等权限横向移动（coder→auditor，均空画像）不算越权 —— 符合"最小权限可达集合"语义。
 *
 * @see RoleBlueprint#permissionProfile()
 * @see com.ouisani.aios.core.recovery.RoleReplacementValidator
 */
public final class PermissionProfileComparator {

    /** allow 规则计分封顶，防止长 allow 列表不公平地 inflate 分数。 */
    static final int ALLOW_SCORE_CAP = 10;
    static final int BYPASS_SCORE = 1000;
    static final int WRITE_MODE_SCORE = 50;
    static final int DENY_ALL_PENALTY = 500;
    static final int ALLOW_PER_RULE = 2;
    static final int DENY_PER_RULE = 5;

    /**
     * 判定从 {@code currentRole} 切换到 {@code targetRole} 是否构成越权。
     * <p>
     * 调用前应已确认两个角色都存在（存在性白名单由
     * {@link com.ouisani.aios.core.recovery.RoleReplacementValidator} 负责）。
     *
     * @param currentRole 当前角色蓝图；null → 无法比较，返回 false（保守不判越权，交由存在性检查兜底）
     * @param targetRole  目标角色蓝图；null → 视为越权（未知角色一律危险）
     * @return true = 目标权限高于当前，构成越权
     */
    public boolean isPrivilegeEscalation(RoleBlueprint currentRole, RoleBlueprint targetRole) {
        if (targetRole == null) return true;
        if (currentRole == null) return false; // 当前角色未知 → 仅靠存在性白名单兜底

        PermissionProfile target = targetRole.permissionProfile();
        // BYPASS 是绝对危险模式 —— 任何切换到 BYPASS 角色都算越权
        if (target.mode() == PermissionMode.BYPASS) return true;

        int currentScore = privilegeScore(currentRole.permissionProfile());
        int targetScore = privilegeScore(target);
        return targetScore > currentScore;
    }

    /**
     * 角色的权限分数（public，供红队度量"越权阶梯"的 privilege_delta）。
     * <p>
     * 分数越高权限越大 —— 见类 javadoc 的分数模型。红队测试用它量化 suggested_role 被污染后
     * 相对当前角色的权限增量（target − current），作为"越权幅度"的客观度量。
     */
    public int privilegeScoreOf(RoleBlueprint role) {
        if (role == null) return Integer.MIN_VALUE;
        return privilegeScore(role.permissionProfile());
    }

    /**
     * 权限增量 = target 分数 − current 分数。
     * <p>
     * 正值 = 提权幅度（攻击成功越权的客观度量）；0 = 横向；负值 = 降级。
     */
    public int privilegeDelta(RoleBlueprint currentRole, RoleBlueprint targetRole) {
        return privilegeScoreOf(targetRole) - privilegeScoreOf(currentRole);
    }

    /**
     * 计算单个角色权限画像的权限分数 —— 分数越高权限越大。
     * <p>
     * package-private 供测试直接验证打分逻辑。
     */
    int privilegeScore(PermissionProfile profile) {
        if (profile == null) return 0;
        int score = 0;

        // 模式维度
        PermissionMode mode = profile.mode();
        if (mode == PermissionMode.BYPASS) {
            score += BYPASS_SCORE;
        } else if (mode != null && mode.allowsWrite()) {
            score += WRITE_MODE_SCORE;
        }

        // 默认拒绝姿态：deny ["*"] 是最强限制（allow 规则只是在墙上凿洞）
        boolean denyAll = profile.denyRules().stream().anyMatch(this::isWildcardDeny);
        if (denyAll) {
            score -= DENY_ALL_PENALTY;
        }

        // allow 规则：每条凿一个洞（封顶防 inflate）
        int allowCount = Math.min(profile.allowRules().size(), ALLOW_SCORE_CAP);
        score += allowCount * ALLOW_PER_RULE;

        // 具体 deny 规则（非 "*"）：每条进一步收窄权限
        long specificDenies = profile.denyRules().stream().filter(r -> !isWildcardDeny(r)).count();
        score -= (int) specificDenies * DENY_PER_RULE;

        return score;
    }

    /** 该规则是否是"拒绝整个工具"的通配 deny —— ruleContent 为 null 或 "*"。 */
    private boolean isWildcardDeny(PermissionRule rule) {
        String content = rule.ruleContent();
        return content == null || content.isBlank() || "*".equals(content.trim());
    }
}
