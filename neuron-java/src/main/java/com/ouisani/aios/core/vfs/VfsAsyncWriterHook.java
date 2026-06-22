package com.ouisani.aios.core.vfs;

import com.ouisani.aios.core.hook.HookManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * VFS 异步写入钩子 — 将文件写入操作自动路由到 VfsAsyncWriter。
 * <p>
 * 借鉴 ECC 的 Actor 模式设计：当 FileWriteTool 发起写入时，
 * 不直接调用 VfsManager.writeText (会持有 writeLock 阻塞其他读操作)，
 * 而是将写操作扔进 VfsAsyncWriter 的无锁队列，由单一 Actor 线程批量落盘。
 * <p>
 * 注册为 PreToolUse 钩子，在 file_write 工具执行前拦截，
 * 将写入操作重定向到异步队列，然后返回 deny 短路原始工具执行
 * (因为写入已经由 Actor 处理了)。
 *
 * <h3>OS 类比: Linux Kernel pdflush + writeback</h3>
 * 类似 Linux 内核的脏页写入机制：
 * 业务线程标记脏页(扔进队列)后立即返回，
 * pdflush 线程(Actor)在后台批量刷盘。
 *
 * @see VfsAsyncWriter
 * @see HookManager
 */
public final class VfsAsyncWriterHook implements HookManager.HookHandler {

    private static final Logger log = LoggerFactory.getLogger(VfsAsyncWriterHook.class);

    /** 需要异步化的工具名 */
    private static final String FILE_WRITE_TOOL = "file_write";

    /** 是否启用异步写入(可通过环境变量关闭) */
    private static final boolean ENABLED = !"false".equals(System.getenv("AIOS_VFS_ASYNC_DISABLE"));

    public VfsAsyncWriterHook() {}

    @Override
    public HookManager.HookResult handle(HookManager.HookEvent event, Map<String, Object> data) {
        if (!ENABLED) return HookManager.HookResult.ok();
        if (event != HookManager.HookEvent.PRE_TOOL_USE) return HookManager.HookResult.ok();

        String toolName = (String) data.get("toolName");
        if (!FILE_WRITE_TOOL.equals(toolName)) return HookManager.HookResult.ok();

        // 从钩子数据中提取路径和内容
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) data.get("args");
        if (args == null) return HookManager.HookResult.ok();

        String path = extractPath(args);
        String content = extractContent(args);

        if (path == null || content == null) return HookManager.HookResult.ok();

        // 检查 VfsAsyncWriter 是否已启动
        VfsAsyncWriter writer = VfsAsyncWriter.getInstance();
        if (!writer.isRunning()) {
            // Actor 未启动 → 放行，让工具正常执行
            return HookManager.HookResult.ok();
        }

        // 提交到异步队列
        boolean accepted = writer.submitAsync(path, content);

        if (accepted) {
            // 已提交到队列 → 短路原始工具执行
            // Actor 线程会在后台处理这个写入
            log.debug("[VfsAsyncWriterHook] 写入已异步化: path={}", path);
            return HookManager.HookResult.deny("ASYNC_WRITTEN: 写入已提交到异步队列");
        } else {
            // 队列满(背压) → 放行，让工具同步执行(降级)
            log.warn("[VfsAsyncWriterHook] 队列满，降级为同步写入: path={}", path);
            return HookManager.HookResult.ok();
        }
    }

    private String extractPath(Map<String, Object> args) {
        Object path = args.get("path");
        if (path == null) path = args.get("file");
        if (path == null) path = args.get("file_path");
        return path != null ? path.toString() : null;
    }

    private String extractContent(Map<String, Object> args) {
        Object content = args.get("content");
        if (content == null) content = args.get("data");
        if (content == null) content = args.get("text");
        return content != null ? content.toString() : null;
    }

    /**
     * 注册此钩子并启动 Actor 线程。
     * <p>
     * 应该在系统启动时(VfsManager.init() 之后)调用。
     */
    public static void register() {
        if (!ENABLED) {
            log.info("[VfsAsyncWriterHook] 异步写入已通过环境变量禁用");
            return;
        }

        // 启动 Actor 线程
        VfsAsyncWriter.getInstance().start();

        // 注册 PreToolUse 钩子
        HookManager.instance().register(
                HookManager.HookEvent.PRE_TOOL_USE,
                new VfsAsyncWriterHook(),
                200  // 低优先级，在安全审计之后执行
        );

        log.info("[VfsAsyncWriterHook] 已注册 PreToolUse 异步写入钩子");
    }
}
