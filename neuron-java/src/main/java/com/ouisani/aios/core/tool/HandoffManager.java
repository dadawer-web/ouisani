package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handoff 管理器 — 管理 LLM 驱动的 Agent 切换目标与历史记录。
 * <p>
 * 参考 OpenAI Agents Python 的 Handoff 设计：
 * <ul>
 *   <li>维护可用的 Handoff 目标清单（Agent 注册为 handoff target）</li>
 *   <li>记录 Handoff 历史，供审计与可视化大屏查询</li>
 *   <li>动态生成 Handoff 工具描述（包含可用目标列表，供 LLM 决策）</li>
 * </ul>
 * <p>
 * 与 DAG 拓扑互补：DAG 用于确定性的流水线（编译时固定），
 * Handoff 用于不确定性的探索性任务（运行时 LLM 自主决策）。
 * <p>
 * 单例模式 — 通过 {@link #instance()} 获取。
 */
public class HandoffManager {

    private static final Logger log = LoggerFactory.getLogger(HandoffManager.class);

    private static final class Holder {
        static final HandoffManager INSTANCE = new HandoffManager();
    }

    public static HandoffManager instance() {
        return Holder.INSTANCE;
    }

    /** 已注册的 Handoff 目标：agentId → 目标描述 */
    private final Map<String, HandoffTarget> targets = new ConcurrentHashMap<>();

    /** Handoff 历史记录（按时间顺序，线程安全） */
    private final List<HandoffRecord> history = new CopyOnWriteArrayList<>();

    private HandoffManager() {}

    // ════════════════════════════════════════════════════════════════
    //  Handoff 目标管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个 Handoff 目标。
     *
     * @param agentId     目标 Agent ID
     * @param role        目标 Agent 角色（如 "coder", "architect"）
     * @param description 目标 Agent 能力描述（供 LLM 决策）
     */
    public void registerHandoffTarget(String agentId, String role, String description) {
        if (agentId == null || agentId.isBlank()) {
            log.warn("[HandoffManager] 注册失败：agentId 为空");
            return;
        }
        HandoffTarget target = new HandoffTarget(agentId, role != null ? role : "", description != null ? description : "");
        targets.put(agentId, target);
        log.info("[HandoffManager] Handoff 目标已注册: agentId={}, role={}", agentId, role);
    }

    /**
     * 注销一个 Handoff 目标。
     */
    public void unregisterHandoffTarget(String agentId) {
        HandoffTarget removed = targets.remove(agentId);
        if (removed != null) {
            log.info("[HandoffManager] Handoff 目标已注销: agentId={}", agentId);
        }
    }

    /**
     * 获取所有可用的 Handoff 目标（只读视图）。
     */
    public Collection<HandoffTarget> getHandoffTargets() {
        return Collections.unmodifiableCollection(targets.values());
    }

    /**
     * 按角色或 ID 查找 Handoff 目标。
     * <p>
     * 查找顺序：先精确匹配 agentId，再匹配 role。
     *
     * @param key 目标 Agent 的角色或 ID
     * @return 匹配的目标，未找到返回 null
     */
    public HandoffTarget findTarget(String key) {
        if (key == null || key.isBlank()) return null;
        // 1. 精确匹配 agentId
        HandoffTarget byId = targets.get(key);
        if (byId != null) return byId;
        // 2. 匹配 role（忽略大小写）
        for (HandoffTarget t : targets.values()) {
            if (key.equalsIgnoreCase(t.role())) return t;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Handoff 历史记录
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一次 Handoff 事件。
     *
     * @param source         源 Agent ID
     * @param target         目标 Agent ID
     * @param reason         切换原因
     * @param contextSummary 上下文摘要
     */
    public void recordHandoff(String source, String target, String reason, String contextSummary) {
        HandoffRecord record = new HandoffRecord(
                source != null ? source : "",
                target != null ? target : "",
                reason != null ? reason : "",
                contextSummary != null ? contextSummary : "",
                System.currentTimeMillis()
        );
        history.add(record);
        log.info("[HandoffManager] Handoff 已记录: {} -> {} (reason={})", source, target, reason);
    }

    /**
     * 获取 Handoff 历史记录（只读视图，按时间倒序）。
     */
    public List<HandoffRecord> getHandoffHistory() {
        List<HandoffRecord> snapshot = new ArrayList<>(history);
        Collections.reverse(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 清空 Handoff 历史记录。
     */
    public void clearHistory() {
        history.clear();
        log.info("[HandoffManager] Handoff 历史已清空");
    }

    // ════════════════════════════════════════════════════════════════
    //  动态工具描述生成
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成 Handoff 工具的动态描述（包含可用目标列表）。
     * <p>
     * LLM 通过此描述了解可切换的 Agent 清单，从而自主决策。
     */
    public String generateToolDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("让 LLM 自主决定将控制权移交给另一个 Agent。");
        sb.append("当当前 Agent 无法处理用户请求时，可以调用此工具切换到更合适的 Agent。");
        if (!targets.isEmpty()) {
            sb.append("\n\n可用目标 Agent 列表：\n");
            for (HandoffTarget t : targets.values()) {
                sb.append("- agentId: ").append(t.agentId());
                if (!t.role().isBlank()) sb.append(" | role: ").append(t.role());
                if (!t.description().isBlank()) sb.append(" | desc: ").append(t.description());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据类
    // ════════════════════════════════════════════════════════════════

    /**
     * Handoff 目标描述。
     *
     * @param agentId     目标 Agent ID
     * @param role        目标 Agent 角色
     * @param description 目标 Agent 能力描述
     */
    public record HandoffTarget(String agentId, String role, String description) {}

    /**
     * Handoff 历史记录。
     *
     * @param source         源 Agent ID
     * @param target         目标 Agent ID
     * @param reason         切换原因
     * @param contextSummary 上下文摘要
     * @param timestamp      时间戳（毫秒）
     */
    public record HandoffRecord(String source, String target, String reason,
                                String contextSummary, long timestamp) {}
}
