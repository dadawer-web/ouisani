package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 6 层：运行时回退 — API 429/503/配额耗尽时切换模型。
 * <p>
 * 对标 omo 的 runtime-fallback hook（32 个文件）。
 * 反应式地在 API 提供商返回错误时切换到回退模型。
 * <p>
 * 回退链：P_CORE → E_CORE（类比 ARM big.LITTLE 热切换）
 * 冷却机制：失败模型进入 60s 冷却期
 */
public class RuntimeFallbackRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(RuntimeFallbackRecovery.class);

    /** 冷却时间 (ms) */
    private static final long COOLDOWN_MS = 60_000;

    /** 模型冷却状态：modelId → 冷却截止时间 */
    private final Map<String, Long> modelCooldowns = new ConcurrentHashMap<>();

    @Override
    public String name() { return "RuntimeFallbackRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.category() == RecoveryOrchestrator.ErrorCategory.RATE_LIMITED;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[RuntimeFallbackRecovery] 正在为 Agent 尝试模型回退 {}", context.agentId());

        // 将当前模型加入冷却
        String currentModel = (String) context.metadata().getOrDefault("currentModel", "unknown");
        modelCooldowns.put(currentModel, System.currentTimeMillis() + COOLDOWN_MS);
        log.info("[RuntimeFallbackRecovery] 模型 '{}' 已进入冷却，时长 {}ms", currentModel, COOLDOWN_MS);

        // 尝试获取 E_CORE 提供者 — 降级到经济模型
        try {
            LlmRouter router = LlmRouterHolder.get();
            if (router != null) {
                String modifier = "\n\n[SYSTEM NOTICE - MODEL FALLBACK]:\n"
                        + "The primary model is temporarily unavailable (rate limited or overloaded).\n"
                        + "The system will automatically route to a fallback model. Continue your task normally.\n";
                context.appendPromptModifier(modifier);
                return RecoveryResult.ok("Runtime fallback: LLM router will auto-downgrade to E_CORE", modifier);
            }
        } catch (Exception e) {
            log.warn("[RuntimeFallbackRecovery] 模型回退检查失败: {}", e.getMessage());
        }

        // 无法切换模型 — 建议退避重试
        String modifier = "\n\n[SYSTEM WARNING - RATE LIMITED]:\n"
                + "The API is currently rate limited or overloaded.\n"
                + "Please wait a moment and then retry. Keep your next request concise.\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Runtime fallback: rate limit advisory injected", modifier);
    }

    /** 检查模型是否在冷却中 */
    public boolean isInCooldown(String modelId) {
        Long cooldownEnd = modelCooldowns.get(modelId);
        return cooldownEnd != null && System.currentTimeMillis() < cooldownEnd;
    }
}
