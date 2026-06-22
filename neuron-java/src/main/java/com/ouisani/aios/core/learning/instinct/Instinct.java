package com.ouisani.aios.core.learning.instinct;

import java.util.Set;

/**
 * 原子化本能 — 渐进学习的最小单元。
 * <p>
 * 借鉴 ECC (Everything Claude Code) 的 instinct 设计：
 * 不直接生成完整 skill，而是通过原子化 instinct 渐进学习。
 * 每个 instinct 是一个带置信度的原子行为。
 *
 * <h3>Instinct 生命周期</h3>
 * <ol>
 *   <li>观察层: 钩子捕获工具调用到 JSONL</li>
 *   <li>模式检测: 后台 observer 检测用户纠正/错误解决/重复工作流</li>
 *   <li>Instinct 生成: 带置信度 (0.3-0.9) 的原子行为</li>
 *   <li>项目隔离: 按 projectHash 隔离，避免跨项目污染</li>
 *   <li>置信度衰减: +0.05 确认, -0.1 反驳, -0.02/周衰减</li>
 *   <li>晋升机制: 2+ 项目出现且置信度 >= 0.8 时晋升为 global</li>
 * </ol>
 *
 * <h3>OS 类比: Linux Kernel Module Parameters</h3>
 * 类似 Linux 内核模块的可调参数，instinct 是 Agent 行为的可调参数。
 * 置信度类似参数的权重，高置信度的 instinct 会被优先应用。
 *
 * @see InstinctStore
 * @see InstinctObserver
 */
public final class Instinct {

    /** 最小置信度 — 低于此值的 instinct 会被淘汰 */
    public static final double MIN_CONFIDENCE = 0.2;

    /** 晋升全局的最小置信度 */
    public static final double PROMOTION_THRESHOLD = 0.8;

    /** 晋升全局所需的最小项目数 */
    public static final int PROMOTION_PROJECT_COUNT = 2;

    /** 确认操作的置信度增量 */
    public static final double CONFIRM_DELTA = 0.05;

    /** 反驳操作的置信度减量 */
    public static final double REFUTE_DELTA = 0.10;

    /** 每周衰减量 */
    public static final double WEEKLY_DECAY = 0.02;

    /** 初始置信度 */
    public static final double INITIAL_CONFIDENCE = 0.3;

    private final String id;
    private final String pattern;
    private final String action;
    private final String description;
    private volatile double confidence;
    private final Set<String> projectHashes;
    private volatile long lastConfirmedAt;
    private final long createdAt;
    private volatile boolean global;

    /**
     * @param id            唯一标识（pattern + action 的哈希）
     * @param pattern       触发模式（如 "user_correction:use_X_instead_of_Y"）
     * @param action        建议行为（如 "prefer_file_read_over_bash_cat"）
     * @param description   人类可读描述
     * @param confidence    初始置信度 (0.3-0.9)
     * @param projectHash   项目哈希（git remote URL 的哈希）
     */
    public Instinct(String id, String pattern, String action, String description,
                    double confidence, String projectHash) {
        this.id = id;
        this.pattern = pattern;
        this.action = action;
        this.description = description;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.projectHashes = new java.util.concurrent.CopyOnWriteArraySet<>();
        if (projectHash != null && !projectHash.isBlank()) {
            this.projectHashes.add(projectHash);
        }
        this.lastConfirmedAt = System.currentTimeMillis();
        this.createdAt = System.currentTimeMillis();
        this.global = false;
    }

    /** 确认此 instinct — 置信度 +0.05 */
    public synchronized void confirm() {
        this.confidence = Math.min(1.0, this.confidence + CONFIRM_DELTA);
        this.lastConfirmedAt = System.currentTimeMillis();
    }

    /** 反驳此 instinct — 置信度 -0.10 */
    public synchronized void refute() {
        this.confidence = Math.max(0.0, this.confidence - REFUTE_DELTA);
    }

    /**
     * 应用每周衰减 — 置信度 -0.02。
     * <p>
     * 衰减是基于时间的，长期未被确认的 instinct 置信度会逐渐降低。
     *
     * @param weeksElapsed 距上次确认的周数
     */
    public synchronized void applyDecay(int weeksElapsed) {
        if (weeksElapsed <= 0) return;
        double totalDecay = WEEKLY_DECAY * weeksElapsed;
        this.confidence = Math.max(0.0, this.confidence - totalDecay);
    }

    /**
     * 在新项目中观察到相同模式 — 添加项目哈希。
     * <p>
     * 当同一模式在 2+ 项目出现且置信度 >= 0.8 时，可晋升为 global。
     *
     * @param projectHash 新项目的哈希
     * @return true 如果添加后满足晋升条件
     */
    public synchronized boolean observeInProject(String projectHash) {
        if (projectHash == null || projectHash.isBlank()) return false;
        this.projectHashes.add(projectHash);

        // 检查晋升条件
        if (!global && projectHashes.size() >= PROMOTION_PROJECT_COUNT
                && confidence >= PROMOTION_THRESHOLD) {
            this.global = true;
            return true;
        }
        return false;
    }

    /** 此 instinct 是否已过期（置信度低于最小值） */
    public boolean isExpired() {
        return confidence < MIN_CONFIDENCE;
    }

    /** 此 instinct 是否已晋升为全局 */
    public boolean isGlobal() {
        return global;
    }

    /** 手动晋升为全局 */
    public synchronized void promoteToGlobal() {
        this.global = true;
    }

    // ── Getters ──

    public String id() { return id; }
    public String pattern() { return pattern; }
    public String action() { return action; }
    public String description() { return description; }
    public double confidence() {
        synchronized (this) {
            return confidence;
        }
    }
    public Set<String> projectHashes() {
        return java.util.Collections.unmodifiableSet(projectHashes);
    }
    public long lastConfirmedAt() { return lastConfirmedAt; }
    public long createdAt() { return createdAt; }

    /**
     * 生成 YAML 格式的 instinct 表示。
     */
    public String toYaml() {
        return String.format("""
                - id: %s
                  pattern: "%s"
                  action: "%s"
                  description: "%s"
                  confidence: %.2f
                  global: %s
                  projects: %d
                  last_confirmed: %d
                  created: %d
                """,
                id, escapeYaml(pattern), escapeYaml(action), escapeYaml(description),
                confidence, global, projectHashes.size(),
                lastConfirmedAt, createdAt);
    }

    private static String escapeYaml(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    public String toString() {
        return "Instinct{" + id + ", conf=" + String.format("%.2f", confidence)
                + ", global=" + global + ", projects=" + projectHashes.size() + "}";
    }
}
