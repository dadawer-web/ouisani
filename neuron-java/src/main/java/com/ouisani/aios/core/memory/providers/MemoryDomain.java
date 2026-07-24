package com.ouisani.aios.core.memory.providers;

/**
 * 记忆域标记 — 区分"用户域"记忆与"智能体域"记忆。
 * <p>
 * 借鉴 Step AOS 的"用户域 / 智能体域"二分法：
 * <ul>
 *   <li><b>USER</b>：来自用户的输入、偏好、事实陈述。
 *       高可信、低可变性，写入时通常不需要 Agent 二次确认。</li>
 *   <li><b>AGENT</b>：Agent 自己推断、总结、产生的内容。
 *       可信度依赖推理质量，可被后续覆盖或修正。</li>
 * </ul>
 * <p>
 * 这个标记是后续"记忆查看器"按域过滤、按域审计、按域设置保留策略的基础。
 * 旧的 {@code store(String)} 接口默认打 {@link #AGENT} 标签，
 * 因为历史上绝大多数写入来自 Agent 推理产物。
 */
public enum MemoryDomain {
    /** 用户域：用户输入、偏好、显式事实。 */
    USER,
    /** 智能体域：Agent 推断、总结、产生的内容。 */
    AGENT
}
