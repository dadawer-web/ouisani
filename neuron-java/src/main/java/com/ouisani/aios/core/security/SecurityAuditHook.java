package com.ouisani.aios.core.security;

import com.ouisani.aios.core.hook.HookManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 安全审计钩子 — 将 BypassSecurityAuditor 接入 HookManager。
 * <p>
 * 借鉴 ECC 的 AgentShield 设计：当 VFS 发生写入时，自动触发旁路安全审计。
 * <p>
 * 注册为 PostToolUse 钩子，在 FileWriteTool 成功写入后，
 * BypassSecurityAuditor 静默读取刚写入的代码，检测密钥泄漏、后门、SQL 注入等。
 *
 * <h3>OS 类比: Linux Kernel inotify + auditd</h3>
 * 类似 Linux 内核的 inotify 机制监控文件变更，
 * 变更后触发 auditd 进行安全审计。
 *
 * @see BypassSecurityAuditor
 * @see HookManager
 */
public final class SecurityAuditHook implements HookManager.HookHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditHook.class);

    /** 需要审计的工具名 */
    private static final String FILE_WRITE_TOOL = "file_write";
    private static final String FILE_EDIT_TOOL = "file_edit";

    public SecurityAuditHook() {}

    @Override
    public HookManager.HookResult handle(HookManager.HookEvent event, Map<String, Object> data) {
        // 仅处理 PostToolUse 事件
        if (event != HookManager.HookEvent.POST_TOOL_USE) {
            return HookManager.HookResult.ok();
        }

        // 仅审计成功的工具调用
        Object successObj = data.get("success");
        if (!(successObj instanceof Boolean success) || !success) {
            return HookManager.HookResult.ok();
        }

        // 检查是否是文件写入工具
        String toolName = (String) data.get("toolName");
        if (!FILE_WRITE_TOOL.equals(toolName) && !FILE_EDIT_TOOL.equals(toolName)) {
            return HookManager.HookResult.ok();
        }

        // 从钩子数据中提取路径
        String agentId = (String) data.get("agentId");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) data.get("args");
        if (args == null) return HookManager.HookResult.ok();

        String path = extractPath(args);
        if (path == null || path.isBlank()) return HookManager.HookResult.ok();

        // 异步执行旁路审计(虚拟线程，不阻塞主路径)
        Thread.startVirtualThread(() -> {
            try {
                BypassSecurityAuditor.instance().auditFileWrite(path, agentId);
            } catch (Exception e) {
                log.warn("[SecurityAuditHook] 旁路审计异常: path={}, error={}", path, e.getMessage());
            }
        });

        return HookManager.HookResult.ok();
    }

    /**
     * 从工具参数中提取文件路径。
     */
    private String extractPath(Map<String, Object> args) {
        Object path = args.get("path");
        if (path == null) path = args.get("file");
        if (path == null) path = args.get("file_path");
        return path != null ? path.toString() : null;
    }

    /**
     * 注册此钩子到 HookManager。
     * <p>
     * 应该在系统启动时调用。
     */
    public static void register() {
        HookManager.instance().register(
                HookManager.HookEvent.POST_TOOL_USE,
                new SecurityAuditHook(),
                50  // 高优先级，尽早审计
        );
        log.info("[SecurityAuditHook] 已注册 PostToolUse 安全审计钩子");
    }
}
