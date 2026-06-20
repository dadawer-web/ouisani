package com.ouisani.aios.operator;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具装配器 — 对标 OpenClaw 的 createOpenClawTools。
 * <p>
 * 根据 {@link ToolAssemblyContext} 将内核全局工具 + 插件工具 + 额外工具
 * 合并为最终的工具清单，并应用策略过滤（白名单/黑名单）。
 * <p>
 * OS 类比：相当于 Linux 的 insmod — 根据当前进程的权限和能力
 * 组装可用的系统调用子集。
 */
public class ToolAssembler {

    private static final Logger log = LoggerFactory.getLogger(ToolAssembler.class);

    /**
     * 装配工具清单。
     * <p>
     * 合并顺序：
     * 1. 内核全局工具（ToolRegistry.instance().all()）
     * 2. 插件注册的工具（context.pluginRegistry().allTools()）
     * 3. 额外工具（context.extraTools()）
     * <p>
     * 然后应用策略过滤：
     * - 黑名单优先拒绝
     * - 白名单（如果非空）只允许匹配的
     * <p>
     * Operator 模式下排除代码生成类认知工具。
     *
     * @param context 装配上下文
     * @return 过滤后的工具列表
     */
    public static List<Tool<? extends ToolInput>> assemble(ToolAssemblyContext context) {
        Map<String, Tool<? extends ToolInput>> merged = new LinkedHashMap<>();

        // 1. 内核全局工具
        for (Tool<? extends ToolInput> tool : ToolRegistry.instance().all()) {
            merged.put(tool.name(), tool);
        }

        // 2. 插件注册的工具（可覆盖内核工具）
        for (Tool<? extends ToolInput> tool : context.pluginRegistry().allTools()) {
            merged.put(tool.name(), tool);
        }

        // 3. 额外工具（最高优先级，可覆盖一切）
        for (Tool<? extends ToolInput> tool : context.extraTools()) {
            merged.put(tool.name(), tool);
        }

        // 4. Operator 模式下排除认知工具
        if (context.operatorMode()) {
            Set<String> cognitiveTools = Set.of(
                    "todo_write", "notebook_edit", "plan_mode", "task", "skill"
            );
            cognitiveTools.forEach(merged::remove);
            log.info("[ToolAssembler] Operator mode: excluded {} cognitive tools", cognitiveTools.size());
        }

        // 5. 策略过滤
        List<Tool<? extends ToolInput>> result = new ArrayList<>();
        for (Map.Entry<String, Tool<? extends ToolInput>> entry : merged.entrySet()) {
            if (context.isToolAllowed(entry.getKey())) {
                result.add(entry.getValue());
            } else {
                log.debug("[ToolAssembler] Tool '{}' 工具被策略拒绝", entry.getKey());
            }
        }

        log.info("[ToolAssembler] Assembled {} tools for agent '{}' (operator={})",
                result.size(), context.agentId(), context.operatorMode());
        System.out.printf("[ToolAssembler] Assembled %d tools for agent '%s' (operator=%b)%n",
                result.size(), context.agentId(), context.operatorMode());

        return result;
    }
}
