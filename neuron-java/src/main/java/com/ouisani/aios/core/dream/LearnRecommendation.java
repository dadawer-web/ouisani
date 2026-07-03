package com.ouisani.aios.core.dream;

/**
 * 学习推荐规则 — 借鉴 Headroom learn/models.py 的 Recommendation。
 * <p>
 * 表示从失败会话中提取的一条修正规则。
 * <p>
 * <b>可变类（非 record）：</b>因为 {@link LoopDetector#applyLoopWeighting}
 * 需要在循环检测后原地修改 estimatedTokensSaved 和 isLoopGuardrail 字段。
 * 使用 {@code volatile} 保证跨线程可见性。
 */
public class LearnRecommendation {

    /** 规则目标 */
    public enum Target {
        CONTEXT_FILE,   // 写入上下文规则文件（如 AGENTS.md）
        MEMORY_FILE     // 写入记忆文件
    }

    private final Target target;
    private final String section;       // 规则标题
    private final String content;       // 规则正文
    private volatile double confidence; // 置信度 0.0-1.0
    private volatile int evidenceCount; // 证据数
    private volatile int estimatedTokensSaved; // 预估每会话节省 token
    private volatile boolean loopGuardrail;   // 是否为循环护栏
    private volatile int loopOccurrences;     // 循环出现次数

    public LearnRecommendation(Target target, String section, String content,
                                double confidence, int evidenceCount,
                                int estimatedTokensSaved) {
        this.target = target;
        this.section = section;
        this.content = content;
        this.confidence = confidence;
        this.evidenceCount = evidenceCount;
        this.estimatedTokensSaved = estimatedTokensSaved;
        this.loopGuardrail = false;
        this.loopOccurrences = 0;
    }

    /** 快速构造（默认置信度 0.5，无循环标记） */
    public LearnRecommendation(String section, String content, int estimatedTokensSaved) {
        this(Target.CONTEXT_FILE, section, content, 0.5, 1, estimatedTokensSaved);
    }

    // ── Getters ──

    public Target target() { return target; }
    public String section() { return section; }
    public String content() { return content; }
    public double confidence() { return confidence; }
    public int evidenceCount() { return evidenceCount; }
    public int estimatedTokensSaved() { return estimatedTokensSaved; }
    public boolean isLoopGuardrail() { return loopGuardrail; }
    public int loopOccurrences() { return loopOccurrences; }

    // ── Setters（供 applyLoopWeighting 使用）──

    public void setEstimatedTokensSaved(int val) { this.estimatedTokensSaved = val; }
    public void setLoopGuardrail(boolean val) { this.loopGuardrail = val; }
    public void setLoopOccurrences(int val) { this.loopOccurrences = val; }

    @Override
    public String toString() {
        String tag = loopGuardrail ? " [LOOP GUARDRAIL x" + loopOccurrences + "]" : "";
        return String.format("[%s] %s (saves ~%d tokens%s)", target, section, estimatedTokensSaved, tag);
    }
}
