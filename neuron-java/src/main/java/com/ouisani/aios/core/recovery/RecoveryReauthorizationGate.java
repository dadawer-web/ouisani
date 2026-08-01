package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 恢复动作重新授权关卡 — 编排器层的统一"副作用重授权"层（Phase 4 defense #4）。
 * <p>
 * <b>设计动机</b>：用户审计指出，每个恢复策略各自零散判断权限（如 {@link TopologyMutationStrategy}
 * 内嵌 {@link RoleReplacementValidator}），新增策略容易漏加校验。本关卡在编排器层统一拦截所有
 * 声明 {@link RecoveryResult#requiresReauthorization()} 的结果 —— "凡是会产生实际副作用（角色变更、
 * 恢复后继续执行）的策略，结果必须先过这个关卡再生效"，新增恢复策略只要声明
 * {@code requiresReauthorization=true} 就自动被这层保护覆盖。
 * <p>
 * <b>层级关系</b>（defense-in-depth，互不替代）：
 * <ul>
 *   <li><b>Layer 1（策略内，默认开）</b>：{@link TopologyMutationStrategy#parseAndValidate} 内的
 *       {@link RoleReplacementValidator} —— 在副作用执行<b>前</b>拦截越权（PREVENT）。</li>
 *   <li><b>Layer 2（编排器层，opt-in）</b>：本关卡 —— 在结果生效<b>前</b>对声明副作用的结果重跑
 *       {@link PermissionChecker#checkRoleMutation}。即便 Layer 1 被关（Baseline 实验）或未来新增
 *       策略漏加内嵌校验，本关卡兜底拦截。</li>
 * </ul>
 * <p>
 * <b>opt-in 开关</b>：{@code aios.recovery.reauthGate}（默认 <b>false</b>）—— 默认关闭以保持论文1
 * 编排器逻辑字节级稳定；新论文实验置 true 启用。关闭时编排器行为与论文1 完全一致。
 * <p>
 * <b>已知限制</b>：{@link TopologyMutationStrategy} 的 {@code resumeNode} 副作用发生在 {@code apply()}
 * 内部（先于本关卡）。故本关卡对拓扑突变是"检测+升级"而非"阻止执行"—— 但对<b>未来</b>把副作用
 * 延后到编排器执行的策略（声明 requiresReauthorization 但不在 apply 内执行），本关卡是真正的
 * PREVENT 层。Layer 1 已在 apply 内 PREVENT 拓扑突变的越权。
 *
 * @see RecoveryResult#requiresReauthorization()
 * @see PermissionChecker#checkRoleMutation
 */
public final class RecoveryReauthorizationGate {

    private static final Logger log = LoggerFactory.getLogger(RecoveryReauthorizationGate.class);

    /**
     * opt-in 开关 —— 默认 false（论文1 字节稳定），新论文实验置 true。
     * <p>
     * <b>动态读取</b>（非 static final 缓存）：每次调用 {@link #isEnabled()} 读系统属性当前值，
     * 供测试隔离切换 + 运行时配置变更。原 {@code ENABLED} 常量保留为 backward-compat 入口
     * （= {@code isEnabled()} 的快照），新代码应用 {@link #isEnabled()}。
     */
    private static final String REAUTH_GATE_PROPERTY = "aios.recovery.reauthGate";

    /** Backward-compat 常量 — 类初始化时的开关快照。新代码应用 {@link #isEnabled()} 读动态值。 */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(REAUTH_GATE_PROPERTY, "false"));

    /**
     * 动态读取开关当前值 —— 供测试隔离（{@code System.setProperty} 即时生效）和运行时配置变更。
     * <p>
     * 生产路径与原 {@link #ENABLED} 常量等价（默认 false）；测试可在 {@code @BeforeEach} 里
     * {@code System.setProperty("aios.recovery.reauthGate", "true")} 即时开启，无需类重载。
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(REAUTH_GATE_PROPERTY, "false"));
    }

    /** metadata 键：策略把建议的 suggested_role 写入供关卡重校验。 */
    public static final String META_SUGGESTED_ROLE = "suggestedRole";

    private RecoveryReauthorizationGate() {
    }

    /** 重授权结果。 */
    public record ReauthResult(boolean allowed, String reason, String category) {
        static final String SKIP = "SKIP";
        static final String ALLOWED = "ALLOWED";
        static final String DENIED = "DENIED";

        static ReauthResult skip(String reason) { return new ReauthResult(true, reason, SKIP); }
        static ReauthResult allowed(String reason) { return new ReauthResult(true, reason, ALLOWED); }
        static ReauthResult denied(String reason, String category) { return new ReauthResult(false, reason, category); }
    }

    /**
     * 校验一个恢复结果是否可让其副作用生效。
     * <p>
     * 决策：
     * <ol>
     *   <li>关卡未启用 → skip（放行，保持论文1 行为）</li>
     *   <li>结果未声明 requiresReauthorization → skip（放行）</li>
     *   <li>结果声明副作用 → 按 context 里的 suggestedRole 重跑
     *       {@link PermissionChecker#checkRoleMutation}；越权/未知角色 → denied</li>
     * </ol>
     *
     * @param result  策略返回的恢复结果
     * @param context 恢复上下文（携带 suggestedRole/currentRole 元数据）
     * @param pc      PermissionChecker（统一权限管道入口）
     * @return 重授权结果
     */
    public static ReauthResult check(RecoveryResult result, RecoveryContext context, PermissionChecker pc) {
        if (!isEnabled()) {
            return ReauthResult.skip("reauth gate disabled (aios.recovery.reauthGate=false)");
        }
        if (result == null || !result.requiresReauthorization()) {
            return ReauthResult.skip("result does not require reauthorization");
        }

        String suggestedRole = context.metadata().get(META_SUGGESTED_ROLE) != null
                ? context.metadata().get(META_SUGGESTED_ROLE).toString() : null;
        String currentRole = context.metadata().get(TopologyMutationStrategy.META_CURRENT_ROLE) != null
                ? context.metadata().get(TopologyMutationStrategy.META_CURRENT_ROLE).toString() : null;

        if (suggestedRole == null) {
            // 声明了副作用但未携带 suggestedRole → 无法重校验，保守放行（交由策略内 Layer 1 兜底）
            log.debug("[ReauthGate] 结果声明 reauth 但无 suggestedRole，放行（Layer 1 已兜底）");
            return ReauthResult.allowed("no suggestedRole to recheck; Layer 1 already gated");
        }

        var vr = pc.checkRoleMutation(currentRole, suggestedRole);
        if (!vr.valid()) {
            log.warn("[ReauthGate] 拒绝恢复副作用生效: currentRole={}, suggestedRole={}, reason={}, category={}",
                    currentRole, suggestedRole, vr.reason(), vr.category());
            return ReauthResult.denied(vr.reason(), vr.category());
        }
        log.info("[ReauthGate] 恢复副作用重授权通过: {} → {}", currentRole, suggestedRole);
        return ReauthResult.allowed(vr.reason());
    }

    /** 便捷判定：关卡是否应拦截该结果（true = 拦截，编排器应升级人类介入）。 */
    public static boolean shouldBlock(RecoveryResult result, RecoveryContext context, PermissionChecker pc) {
        return !check(result, context, pc).allowed();
    }
}
