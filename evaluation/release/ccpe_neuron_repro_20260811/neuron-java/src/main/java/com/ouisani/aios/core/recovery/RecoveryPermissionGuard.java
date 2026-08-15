package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.provenance.RecoveryProvenanceRecorder;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;

/**
 * 恢复重试权限守卫 — 在恢复策略触发重试前，重新走一遍 {@link PermissionChecker} 校验。
 * <p>
 * <b>要解决的问题</b>：现有恢复机制（对标 omo 的 11 层设计）默认"失败=无害意外，重试=安全"。
 * 但在多租户、有权限边界的内核里这个假设可能被利用：一个恶意 app 能故意制造一个看似正常的
 * 失败，诱导 {@link ReflectionInjectionRecovery} 这类策略在"修复"过程中把攻击载荷当成"反思提示"
 * 注入下一轮上下文，从而绕过原本会拦截它的权限检查。
 * <p>
 * <b>防御策略</b>：每次触发恢复重试时，用原始失败请求的工具/输入/上下文重新调用
 * {@link PermissionChecker#checkPermission}。若重校验 DENY 或需要交互式 ASK（异步恢复路径
 * 无法同步询问用户），则拒绝本次重试，强制升级到人类介入。这样"恢复=安全"不再是假设，
 * 而是被权限子系统重新背书的不变式。
 * <p>
 * <b>异步路径下的 ASK 语义</b>：恢复重试常在虚拟线程中异步执行（见
 * {@link RecoveryOrchestrator#handleSemanticCrash}），无法同步弹窗询问用户。
 * 因此 ASK 决策一律按 DENY 处理并升级人类介入——宁可保守挂起，不在恢复通道里静默放行。
 * <p>
 * <b>与 {@link RecoveryPromptSanitizer} 的关系</b>：本守卫是核心防御（拦截越权重试），
 * 净化器是纵深防御（防止载荷诱导 LLM 生成新工具调用）。两层必须同时存在：
 * 净化器降低载荷被 LLM 采纳的概率，守卫确保即便被采纳也无法越权执行。
 */
public final class RecoveryPermissionGuard {

    /**
     * 守卫决策结果。
     *
     * @param allowed  是否允许进入恢复重试
     * @param reason   决策原因（用于日志/审计/provenance）
     * @param decision 原始 {@link PermissionDecision}；未实际调用 checker 时为 null
     */
    public record GuardResult(boolean allowed, String reason, PermissionDecision decision) {
        public static GuardResult allow(String reason, PermissionDecision d) {
            return new GuardResult(true, reason, d);
        }

        public static GuardResult deny(String reason, PermissionDecision d) {
            return new GuardResult(false, reason, d);
        }
    }

    /** 单例 — 无状态，安全共享。 */
    private static final RecoveryPermissionGuard INSTANCE = new RecoveryPermissionGuard();

    public static RecoveryPermissionGuard instance() {
        return INSTANCE;
    }

    private RecoveryPermissionGuard() {
    }

    /**
     * 重校验原始工具调用的权限。
     * <p>
     * 调用契约：
     * <ul>
     *   <li>{@code checker == null} → 放行（向后兼容：未接入权限子系统时维持原行为）</li>
     *   <li>{@code tool == null || input == null} → 放行（非工具调用触发的失败，无重校验对象）</li>
     *   <li>checker 返回 DENY → 拒绝重试</li>
     *   <li>checker 返回 ASK → 拒绝重试（异步路径无法交互询问，升级人类介入）</li>
     *   <li>checker 返回 ALLOW → 放行</li>
     * </ul>
     *
     * @param checker 权限校验器；null 表示未配置，放行
     * @param tool    原始失败的工具；null 表示非工具调用失败
     * @param input   原始失败的工具输入
     * @param ctx     原始失败的工具上下文（含 tenantId，用于跨租户所有权校验）
     * @return 守卫决策
     */
    public GuardResult recheck(PermissionChecker checker, Tool<?> tool, ToolInput input, ToolContext ctx) {
        GuardResult result = decide(checker, tool, input, ctx);
        recordDecision(ctx, result);
        return result;
    }

    /**
     * 重校验的纯决策逻辑 — {@link #recheck} 的内核，不含 provenance 记录。
     * 抽出以便 {@link #recheck} 在决策后插入审计埋点而不重复逻辑、不漏分支。
     */
    private GuardResult decide(PermissionChecker checker, Tool<?> tool, ToolInput input, ToolContext ctx) {
        if (checker == null) {
            return GuardResult.allow("No PermissionChecker configured — skip recheck (legacy mode)", null);
        }
        if (tool == null || input == null) {
            return GuardResult.allow("No original tool call to recheck — non-tool failure", null);
        }

        PermissionDecision decision;
        try {
            decision = uncheckedCheckPermission(checker, tool, input, ctx);
        } catch (Exception e) {
            // 校验器自身异常时保守拒绝 — 恢复通道绝不因校验器故障而静默放行
            return GuardResult.deny(
                    "PermissionChecker threw on recheck (conservative deny): " + e.getMessage(), null);
        }

        if (decision.isDenied()) {
            return GuardResult.deny(
                    "PermissionChecker DENIED recovery retry: " + decision.message(), decision);
        }
        if (decision.needsPrompt()) {
            // 异步恢复路径无法同步询问用户 — ASK 升级为拒绝，触发人类介入
            return GuardResult.deny(
                    "PermissionChecker ASK on retry — cannot prompt in async recovery path, "
                            + "escalating to human intervention: " + decision.message(), decision);
        }
        return GuardResult.allow(
                "PermissionChecker ALLOWED recovery retry: " + decision.message(), decision);
    }

    /**
     * 把守卫决策旁路进恢复 provenance 审计链（best-effort，永不抛）。
     * <p>
     * 这是 Vector A（重试越权）的决策点：RECOVERY_GUARD_DENIED = 越权重试被拦截，
     * RECOVERY_GUARD_ALLOWED = 重试放行。红队反查"载荷是否触达下游"时，DENY 记录证明攻击在
     * 守卫处被阻断；ALLOW 记录证明重试进入下游（需结合后续 RECOVERY_SUCCESS/FAILED 判断最终命运）。
     * <p>
     * agentId 取自 {@link ToolContext#agentId()}（scenario7 的 TOOL_CTX 第一参数）。
     */
    private static void recordDecision(ToolContext ctx, GuardResult result) {
        try {
            String agentId = ctx != null ? ctx.agentId() : "";
            String category = result.allowed() ? "RECOVERY_GUARD_ALLOWED" : "RECOVERY_GUARD_DENIED";
            RecoveryProvenanceRecorder.instance().onRecoveryDecision(
                    agentId, "RECOVERY_GUARD", category, result.allowed(), result.reason(), null);
        } catch (Throwable t) {
            // best-effort: 审计埋点绝不中断守卫主流程
        }
    }

    /**
     * 类型擦除边界调用 — {@link PermissionChecker#checkPermission} 是泛型方法
     * {@code <I extends ToolInput> checkPermission(Tool<I>, I, ToolContext)}，
     * 而 {@link RecoveryContext} 携带的是 {@code Tool<?>} + {@link ToolInput}。
     * 通过 raw type 调用，运行时由擦除保证正确派发。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PermissionDecision uncheckedCheckPermission(PermissionChecker checker,
                                                               Tool<?> tool, ToolInput input,
                                                               ToolContext ctx) {
        Tool rawTool = (Tool) tool;
        return checker.checkPermission(rawTool, input, ctx);
    }
}
