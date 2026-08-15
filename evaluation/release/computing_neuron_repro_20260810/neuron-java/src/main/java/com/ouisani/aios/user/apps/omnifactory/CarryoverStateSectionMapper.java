package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.CarryoverSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CarryoverState ↔ CarryoverSection 双向映射器。
 * <p>
 * {@link WorkflowContext.CarryoverState} 四字段(taskFocus/readFiles/invokedTools/
 * workLog)与 {@link CarryoverSection} 字段类型完全一致(无 Object 擦除),映射为
 * 深拷贝往返。fork 分支用 {@link #applyTo} 从 section 重建工作记忆;capture 用
 * {@link #toSection} 冻结工作记忆。
 */
public final class CarryoverStateSectionMapper {

    private CarryoverStateSectionMapper() {}

    /** CarryoverState → CarryoverSection(深拷贝,与运行态隔离)。 */
    public static CarryoverSection toSection(WorkflowContext.CarryoverState cs) {
        return new CarryoverSection(
                new LinkedHashMap<>(cs.getTaskFocus()),
                new LinkedHashMap<>(cs.getReadFiles()),
                copyInvokedTools(cs.getInvokedTools()),
                new ArrayList<>(cs.getWorkLog())
        );
    }

    /** CarryoverSection → 回填到既有 CarryoverState(clear + putAll,精确还原)。 */
    public static void applyTo(CarryoverSection section, WorkflowContext.CarryoverState cs) {
        cs.getTaskFocus().clear();
        cs.getTaskFocus().putAll(section.taskFocus());
        cs.getReadFiles().clear();
        cs.getReadFiles().putAll(section.readFiles());
        cs.getInvokedTools().clear();
        section.invokedTools().forEach((k, v) -> cs.getInvokedTools().put(k, new ArrayList<>(v)));
        cs.getWorkLog().clear();
        cs.getWorkLog().addAll(section.workLog());
    }

    /** CarryoverState → 扁平 Map(供 BoulderCheckpoint.carryoverSnapshot 使用)。 */
    public static Map<String, Object> toMap(WorkflowContext.CarryoverState cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskFocus", new LinkedHashMap<>(cs.getTaskFocus()));
        m.put("readFiles", new LinkedHashMap<>(cs.getReadFiles()));
        m.put("invokedTools", copyInvokedTools(cs.getInvokedTools()));
        m.put("workLog", new ArrayList<>(cs.getWorkLog()));
        return m;
    }

    /** 扁平 Map → 回填到既有 CarryoverState(clear + 类型安全 put)。 */
    public static void fromMap(Map<String, Object> m, WorkflowContext.CarryoverState cs) {
        cs.getTaskFocus().clear();
        cs.getReadFiles().clear();
        cs.getInvokedTools().clear();
        cs.getWorkLog().clear();
        if (m == null) return;
        if (m.get("taskFocus") instanceof Map<?, ?> tf) {
            tf.forEach((k, v) -> cs.getTaskFocus().put(String.valueOf(k), String.valueOf(v)));
        }
        if (m.get("readFiles") instanceof Map<?, ?> rf) {
            rf.forEach((k, v) -> cs.getReadFiles().put(String.valueOf(k), String.valueOf(v)));
        }
        if (m.get("invokedTools") instanceof Map<?, ?> it) {
            it.forEach((k, v) -> {
                if (v instanceof List<?> lst) {
                    List<String> copy = new ArrayList<>();
                    lst.forEach(x -> copy.add(String.valueOf(x)));
                    cs.getInvokedTools().put(String.valueOf(k), copy);
                }
            });
        }
        if (m.get("workLog") instanceof List<?> wl) {
            wl.forEach(x -> cs.getWorkLog().add(String.valueOf(x)));
        }
    }

    private static Map<String, List<String>> copyInvokedTools(Map<String, List<String>> src) {
        Map<String, List<String>> dst = new LinkedHashMap<>();
        src.forEach((k, v) -> dst.put(k, new ArrayList<>(v)));
        return dst;
    }
}
