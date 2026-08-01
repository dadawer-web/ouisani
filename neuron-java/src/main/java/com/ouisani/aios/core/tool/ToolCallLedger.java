package com.ouisani.aios.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用台账 — 记录工具被实际执行的次数，供恢复通道注入攻击的"金丝雀检测"。
 * <p>
 * <b>Phase 3 度量基础设施</b>：攻击载荷里嵌一个无害但可检测的"金丝雀动作"（要求调用一个特定的
 * 良性工具，如 {@code canary_probe}），下一轮 agent 若"听话执行"了注入指令，就会触发金丝雀工具调用。
 * 事后查本台账有没有该金丝雀被触发 → 客观判定注入是否成功影响 agent 行为（而非靠肉眼看 prompt 框架）。
 * <p>
 * <b>设计</b>：线程安全（{@link AtomicInteger} 计数），per-tool 计数，支持查询"是否被调用过"和"调用次数"。
 * 不与 {@link com.ouisani.aios.core.permission.PermissionChecker} 的 denial sink 混用 ——
 * 后者只记 DENY，本台账记<b>实际执行</b>（含 ALLOW 后真正跑了的），专供金丝雀/行为度量。
 * <p>
 * <b>非持久化</b>：进程内台账，测试间通过 {@link #reset()} 隔离。
 */
public final class ToolCallLedger {

    private final Map<String, AtomicInteger> counts = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 记录一次工具调用（计数 +1）。 */
    public void record(String toolName) {
        if (toolName == null) return;
        counts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 该工具是否被调用过（计数 &gt; 0）。 */
    public boolean wasCalled(String toolName) {
        AtomicInteger c = counts.get(toolName);
        return c != null && c.get() > 0;
    }

    /** 该工具被调用次数。 */
    public int callCount(String toolName) {
        AtomicInteger c = counts.get(toolName);
        return c == null ? 0 : c.get();
    }

    /** 不可变视图：tool → 调用次数。 */
    public Map<String, Integer> snapshot() {
        synchronized (counts) {
            Map<String, Integer> snap = new LinkedHashMap<>();
            counts.forEach((k, v) -> snap.put(k, v.get()));
            return Collections.unmodifiableMap(snap);
        }
    }

    /** 清空台账（测试隔离用）。 */
    public void reset() {
        counts.clear();
    }
}
