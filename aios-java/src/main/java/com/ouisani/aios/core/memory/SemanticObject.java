package com.ouisani.aios.core.memory;

import java.util.*;

/**
 * 语义对象 — AIOS 上下文管理的基本单元。
 * <p>
 * 类比 OS 的内存页 (Page)：传统 OS 以 4KB 页为内存管理的基本单元，
 * AIOS 以 SemanticObject 为上下文管理的基本单元。
 * <p>
 * 一个 SemanticObject 代表一段逻辑连贯的对话片段，是不可分割的
 * 语义原子。例如：一次完整的"问题-思考-系统调用-结果"闭环，
 * 就是一个不可分割的语义对象。
 *
 * <h3>语义对象的生命周期</h3>
 * <pre>
 *   ┌──────────┐    折叠     ┌──────────┐    解压    ┌──────────┐
 *   │  ACTIVE  │ ──────────→│ FOLDED   │ ──────────→│  ACTIVE  │
 *   │ (完整)   │            │ (摘要)   │            │ (恢复)   │
 *   └──────────┘            └──────────┘            └──────────┘
 *        │                       │
 *        │  压缩                  │  换出
 *        ▼                       ▼
 *   TokenZram              SwapManager
 *   (内存压缩)             (磁盘换出)
 * </pre>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS 概念</th><th>AIOS SemanticObject</th><th>说明</th></tr>
 *   <tr><td>内存页 (4KB)</td><td>SemanticObject</td><td>管理基本单元</td></tr>
 *   <tr><td>Page Table Entry</td><td>objectId + metadata</td><td>页表项</td></tr>
 *   <tr><td>Present Bit</td><td>state == ACTIVE</td><td>是否在内存中</td></tr>
 *   <tr><td>Swap Entry</td><td>state == FOLDED</td><td>已换出/折叠</td></tr>
 *   <tr><td>Access Bit</td><td>accessCount</td><td>访问计数</td></tr>
 *   <tr><td>Dirty Bit</td><td>modified</td><td>是否被修改</td></tr>
 * </table>
 *
 * @see SomWindowController
 * @see TokenZram
 */
public final class SemanticObject {

    // ── 语义对象状态 ──

    /**
     * 语义对象状态 — 类比内存页的状态。
     */
    public enum State {
        /** 活跃 — 完整内容在上下文窗口中 */
        ACTIVE,
        /** 已折叠 — 替换为语义指针/摘要，原始数据在 TokenZram 中 */
        FOLDED,
        /** 已换出 — 原始数据在 SwapManager（磁盘）中 */
        SWAPPED
    }

    /**
     * 语义对象类型 — 描述该对象的逻辑角色。
     */
    public enum Type {
        /** 完整的问答闭环：用户提问 → Agent 思考 → 系统调用 → 返回结果 */
        QA_LOOP("问答闭环"),
        /** 纯推理/思考过程 */
        REASONING("推理过程"),
        /** 系统调用序列 */
        SYSCALL_CHAIN("系统调用链"),
        /** 上下文设定（System Prompt、角色设定等） */
        CONTEXT_SETTING("上下文设定"),
        /** 中间结果/工具输出 */
        TOOL_OUTPUT("工具输出"),
        /** 对话闲聊 */
        CHITCHAT("闲聊"),
        /** 其他 */
        OTHER("其他");

        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }
    }

    // ── 核心字段 ──

    /** 对象唯一 ID */
    private final String objectId;

    /** 语义对象类型 */
    private final Type type;

    /** 当前状态 */
    private volatile State state = State.ACTIVE;

    /** 完整内容 — 多条消息记录 */
    private final List<MessageEntry> messages;

    /** 折叠后的摘要/语义指针 */
    private volatile String foldedSummary;

    /** Token 估算（完整内容） */
    private volatile long estimatedTokens;

    /** 折叠后 Token 估算 */
    private volatile long foldedTokens;

    // ── 元数据 ──

    /** 创建时间戳 */
    private final long createdAt;

    /** 最后访问时间戳 */
    private volatile long lastAccessedAt;

    /** 访问计数 — 类比 Page Access Bit */
    private volatile int accessCount = 0;

    /** 是否被修改 — 类比 Dirty Bit */
    private volatile boolean modified = false;

    /** 核心度评分 (0.0~1.0) — 越高越不应该被折叠 */
    private volatile double coreScore = 0.5;

    /** 情绪标签 — 影响折叠优先级 */
    private volatile String emotionTag = "neutral";

    /** TokenZram 压缩句柄 — 折叠后原始数据的存储引用 */
    private volatile String zramHandle;

    /** SwapManager 换出指针 */
    private volatile String swapPointer;

    // ════════════════════════════════════════════════════════════════
    //  构造
    // ════════════════════════════════════════════════════════════════

    public SemanticObject(String objectId, Type type) {
        this.objectId = objectId;
        this.type = type;
        this.messages = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = this.createdAt;

        // 根据类型设置默认核心度
        this.coreScore = switch (type) {
            case CONTEXT_SETTING -> 1.0;  // 上下文设定永不折叠
            case QA_LOOP -> 0.7;          // 问答闭环较重要
            case REASONING -> 0.6;        // 推理过程中等
            case SYSCALL_CHAIN -> 0.5;    // 系统调用链中等
            case TOOL_OUTPUT -> 0.3;      // 工具输出较不重要
            case CHITCHAT -> 0.1;         // 闲聊最不重要
            case OTHER -> 0.4;
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  消息管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 追加一条消息到语义对象。
     */
    public void appendMessage(String role, String content) {
        messages.add(new MessageEntry(role, content, System.currentTimeMillis()));
        modified = true;
        recalculateTokens();
    }

    /**
     * 获取完整内容 — 类比读取内存页。
     */
    public String getFullContent() {
        touch();
        StringBuilder sb = new StringBuilder();
        for (MessageEntry msg : messages) {
            sb.append("[").append(msg.role).append("] ").append(msg.content).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 获取折叠后的摘要 — 类比读取 Swap Entry。
     */
    public String getFoldedSummary() {
        touch();
        return foldedSummary;
    }

    /**
     * 获取当前可见内容 — 根据状态返回完整内容或摘要。
     */
    public String getVisibleContent() {
        return switch (state) {
            case ACTIVE -> getFullContent();
            case FOLDED -> foldedSummary != null ? foldedSummary : "[FOLDED: " + objectId + "]";
            case SWAPPED -> swapPointer != null ? swapPointer : "[SWAPPED: " + objectId + "]";
        };
    }

    /**
     * 访问计数 +1 — 类比 Page Access Bit。
     */
    public void touch() {
        accessCount++;
        lastAccessedAt = System.currentTimeMillis();
    }

    // ════════════════════════════════════════════════════════════════
    //  折叠与恢复
    // ════════════════════════════════════════════════════════════════

    /**
     * 折叠语义对象 — 将完整内容替换为摘要/语义指针。
     * <p>
     * 类比 OS 的页面换出 (Page Out)：将内存页写入交换区，
     * 页表项标记为 not present，但保留足够的元数据
     * （语义指针）确保逻辑链路不断裂。
     *
     * @param summary 折叠后的摘要内容
     * @param zramHandle TokenZram 中的压缩存储句柄
     */
    public void fold(String summary, String zramHandle) {
        this.foldedSummary = summary;
        this.zramHandle = zramHandle;
        this.state = State.FOLDED;
        this.foldedTokens = Math.max(1, summary.length() / 4);

        logFold("FOLD");
    }

    /**
     * 换出语义对象 — 将数据移到 SwapManager（磁盘）。
     *
     * @param swapPointer SwapManager 返回的换出指针
     */
    public void swapOut(String swapPointer) {
        this.swapPointer = swapPointer;
        this.state = State.SWAPPED;
        this.foldedTokens = Math.max(1, swapPointer.length() / 4);

        logFold("SWAP_OUT");
    }

    /**
     * 恢复语义对象 — 从 TokenZram 解压恢复完整内容。
     * <p>
     * 类比 OS 的页面换入 (Page In)：从交换区读取数据，
     * 恢复内存页，页表项标记为 present。
     */
    public void restore() {
        this.state = State.ACTIVE;
        this.foldedSummary = null;
        this.zramHandle = null;
        this.swapPointer = null;
        this.foldedTokens = 0;
        recalculateTokens();
        touch();

        logFold("RESTORE");
    }

    /**
     * 使用 TokenZram 解压恢复 — 从压缩存储中还原完整内容。
     *
     * @param decompressedContent 解压后的完整内容
     */
    public void restoreFromZram(String decompressedContent) {
        // 重建消息列表
        messages.clear();
        for (String line : decompressedContent.split("\n")) {
            if (line.startsWith("[") && line.contains("] ")) {
                int bracketEnd = line.indexOf("] ");
                String role = line.substring(1, bracketEnd);
                String content = line.substring(bracketEnd + 2);
                messages.add(new MessageEntry(role, content, System.currentTimeMillis()));
            }
        }
        restore();
    }

    private void logFold(String action) {
        // 简化日志
    }

    // ════════════════════════════════════════════════════════════════
    //  折叠优先级计算
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算折叠优先级 — 分数越高越应该被折叠。
     * <p>
     * 综合因素：
     * <ul>
     *   <li>核心度 (coreScore) — 越低越容易折叠</li>
     *   <li>时间衰减 — 越早越容易折叠</li>
     *   <li>访问频率 — 越少访问越容易折叠</li>
     *   <li>情绪保护 — critical/important 不折叠</li>
     *   <li>Token 收益 — 折叠后节省越多越优先</li>
     * </ul>
     *
     * @return 折叠优先级 (0.0~1.0)，越高越优先折叠
     */
    public double foldingPriority() {
        if (state != State.ACTIVE) return -1.0; // 已折叠的对象不再参与
        if (type == Type.CONTEXT_SETTING) return -1.0; // 上下文设定永不折叠
        if ("critical".equals(emotionTag) || "important".equals(emotionTag)) return -1.0;

        // 核心度越低，折叠优先级越高
        double coreFactor = 1.0 - coreScore;

        // 时间衰减：越早的对象优先级越高
        long ageMs = System.currentTimeMillis() - createdAt;
        double timeFactor = Math.min(1.0, ageMs / 600_000.0); // 10 分钟满衰减

        // 访问频率：越少访问优先级越高
        double accessFactor = 1.0 / (1.0 + accessCount * 0.2);

        // Token 收益：折叠后节省的 Token 越多优先级越高
        long tokenSaving = estimatedTokens - foldedTokens;
        double savingFactor = Math.min(1.0, tokenSaving / 1000.0);

        return 0.3 * coreFactor + 0.25 * timeFactor + 0.2 * accessFactor + 0.25 * savingFactor;
    }

    // ── 内部辅助 ──

    private void recalculateTokens() {
        long total = 0;
        for (MessageEntry msg : messages) {
            total += Math.max(1, msg.content.length() / 4);
        }
        this.estimatedTokens = total;
    }

    // ════════════════════════════════════════════════════════════════
    //  Getters / Setters
    // ════════════════════════════════════════════════════════════════

    public String objectId() { return objectId; }
    public Type type() { return type; }
    public State state() { return state; }
    public List<MessageEntry> messages() { return Collections.unmodifiableList(messages); }
    public long estimatedTokens() { return estimatedTokens; }
    public long foldedTokens() { return foldedTokens; }
    public long tokenSaving() { return estimatedTokens - foldedTokens; }
    public long createdAt() { return createdAt; }
    public long lastAccessedAt() { return lastAccessedAt; }
    public int accessCount() { return accessCount; }
    public double coreScore() { return coreScore; }
    public String emotionTag() { return emotionTag; }
    public String zramHandle() { return zramHandle; }
    public String swapPointer() { return swapPointer; }

    public void setCoreScore(double score) { this.coreScore = Math.max(0, Math.min(1, score)); }
    public void setEmotionTag(String tag) { this.emotionTag = tag; }

    @Override
    public String toString() {
        return "SemanticObject{id=%s, type=%s, state=%s, tokens=%d, core=%.2f, priority=%.3f}".formatted(
                objectId, type, state, estimatedTokens, coreScore, foldingPriority());
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 消息条目 — 语义对象内的一条消息。
     */
    public record MessageEntry(
            String role,
            String content,
            long timestamp
    ) {}
}
