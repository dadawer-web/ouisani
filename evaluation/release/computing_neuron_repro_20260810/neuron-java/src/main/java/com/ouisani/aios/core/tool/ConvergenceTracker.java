package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收敛检测器 — 检测 Agent 陷入"反复重写同一文件"的循环。
 * <p>
 * 当 Agent 连续 2 次向同一文件写入相同内容时，判定为收敛（可能陷入循环），
 * 通知 QueryEngine 提前终止 Agent Loop，避免 v1→v5 的无意义重写。
 * <p>
 * 设计原则：
 * <ul>
 *   <li><b>per-file 追踪</b>：同一文件的连续写入比较 hash，跨文件写入不干扰</li>
 *   <li><b>content hash</b>：使用 {@code length + hashCode} 作为快速判等代理，
 *       不用 SHA-256（收敛检测不需要抗碰撞，需要快）</li>
 *   <li><b>一次性触发</b>：一旦检测到收敛，永久标记 {@code converged=true}，
 *       直到 {@link #reset()} 被调用</li>
 *   <li><b>线程安全</b>：使用 ConcurrentHashMap，支持并行工具执行场景</li>
 * </ul>
 * <p>
 * OS 类比：类似 CPU 的分支预测器 — 检测到重复模式时提前终止流水线。
 */
public class ConvergenceTracker {

    private static final Logger log = LoggerFactory.getLogger(ConvergenceTracker.class);

    /** 每个文件路径的上一次写入 hash */
    private final Map<String, String> lastHashByPath = new ConcurrentHashMap<>();

    /** 是否已检测到收敛（一旦触发，永久标记直到 reset） */
    private volatile boolean converged = false;

    /** 收敛原因（供 QueryEngine 返回给用户） */
    private volatile String convergenceReason = null;

    /**
     * 记录一次文件写入，并检测收敛。
     * <p>
     * 如果该文件的上一次写入 hash 与本次相同，标记为收敛。
     *
     * @param path    文件路径（VFS 或物理路径）
     * @param content 写入的内容
     */
    public void recordWrite(String path, String content) {
        if (path == null || content == null) return;

        String hash = contentHash(content);
        String prev = lastHashByPath.put(path, hash);

        if (prev != null && prev.equals(hash) && !converged) {
            converged = true;
            convergenceReason = "文件 '" + path + "' 连续 2 次写入相同内容，检测到收敛"
                    + "（Agent 可能陷入循环，已提前终止）";
            log.warn("[ConvergenceTracker] 收敛检测触发: {}", convergenceReason);
        }
    }

    /** 是否已收敛（应终止 Agent Loop） */
    public boolean isConverged() {
        return converged;
    }

    /** 收敛原因（供返回给用户），未收敛时返回 null */
    public String convergenceReason() {
        return convergenceReason;
    }

    /** 重置状态 — 每次 query() 调用开始时重置 */
    public void reset() {
        lastHashByPath.clear();
        converged = false;
        convergenceReason = null;
    }

    /** 快速内容 hash — length + Java hashCode，O(n) 但常数极小 */
    private static String contentHash(String content) {
        return content.length() + ":" + content.hashCode();
    }
}
