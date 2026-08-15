package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 语义层系统调用过滤器 — AIOS 的 Seccomp-BPF 语义升级版。
 * <p>
 * 拦截高危操作，并移交 AI 审核员进行判定。
 * 低危操作（如读文件、查时间）直接放行，节省 Token 成本。
 * <p>
 * OS 类比：Linux Seccomp 只能按 syscall 号做白/黑名单，
 * 而 SemanticSyscallFilter 将拦截提升到语义级别——
 * 不仅看工具名，还用 LLM 理解意图，判断操作是否具有破坏性。
 *
 * @see AiSecurityAuditor
 * @see SyscallFilter
 */
public class SemanticSyscallFilter implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(SemanticSyscallFilter.class);

    /**
     * 需要强制进入 AI 语义审查的"高危指令"白名单。
     * <p>
     * 这些工具具有破坏性或不可逆性：执行代码、写入/删除文件、访问外部网络。
     * 低危操作（如 fs_read、time_query）直接放行。
     */
    private static final Set<String> HIGH_RISK_ACTIONS = Set.of(
            "bash", "execute_python", "fs_write", "fs_delete", "web_fetch"
    );

    @Override
    public void preFilter(String agentId, SyscallRequest request) throws SecurityException {
        // 从 namespace.action 中提取 action 作为工具标识
        String action = request.action();

        // 如果是低危操作，直接放行，节省 Token 成本
        if (!HIGH_RISK_ACTIONS.contains(action.toLowerCase())) {
            return;
        }

        log.debug("[Syscall Filter] 检测到高危调用: {}，来自 Agent [{}]。正在转发至语义审核员...",
                request.fullAction(), agentId);

        // 提取参数 JSON
        String argsJson = request.payload() != null ? request.payload().toString() : "{}";

        // 从 payload 中尝试提取意图描述；若无则使用 fullAction 作为上下文
        String intent = extractIntent(request);

        AiSecurityAuditor.SecurityDecision decision =
                AiSecurityAuditor.evaluateIntent(agentId, action, argsJson, intent);

        if (!decision.safe()) {
            // 极其重要：抛出的异常将被 11 层自愈引擎捕获！
            throw new SecurityException(
                    "KERNEL_SECURITY_BLOCK: Your action was deemed unsafe and blocked by the Semantic Firewall. " +
                    "Reason: " + decision.reason() + ". You must change your strategy."
            );
        }

        log.debug("[Syscall Filter] 动作 [{}] 已被语义审核员放行，Agent [{}].", action, agentId);
    }

    /**
     * 从 SyscallRequest 中提取意图描述。
     * <p>
     * 优先从 payload 的 "intent" / "description" 字段提取；
     * 若无则回退到 fullAction 作为上下文标识。
     */
    private String extractIntent(SyscallRequest request) {
        Object intentObj = request.param("intent");
        if (intentObj != null && !intentObj.toString().isBlank()) {
            return intentObj.toString();
        }

        Object descObj = request.param("description");
        if (descObj != null && !descObj.toString().isBlank()) {
            return descObj.toString();
        }

        // 回退：使用 fullAction 作为上下文
        return "Action: " + request.fullAction();
    }
}
