package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 10 层：不稳定 Agent 看护 — 检测循环/震荡行为并干预。
 * <p>
 * 对标 omo 的 unstable-agent-babysitter + loop-detector。
 * 当 Agent 反复执行相同操作或陷入循环时：
 * 1. 检测循环模式（相同的工具调用/相同的错误）
 * 2. 注入"打破循环"提示
 * 3. 如果仍然无法摆脱，建议换一种完全不同的方法
 */
public class UnstableAgentBabysitterRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(UnstableAgentBabysitterRecovery.class);

    /** 每个 Agent 的最近错误指纹记录 */
    private final Map<String, String> lastErrorFingerprints = new ConcurrentHashMap<>();
    /** 每个 Agent 的循环检测计数 */
    private final Map<String, Integer> loopCounts = new ConcurrentHashMap<>();

    @Override
    public String name() { return "UnstableAgentBabysitterRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return detectLoop(context);
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        int loopCount = loopCounts.getOrDefault(context.agentId(), 0) + 1;
        loopCounts.put(context.agentId(), loopCount);

        log.warn("[UnstableAgentBabysitter] 检测到 Agent 循环 {} (count: {})",
                context.agentId(), loopCount);

        String modifier;
        if (loopCount <= 2) {
            // 轻度循环 — 提醒换方法
            modifier = "\n\n[SYSTEM WARNING - POTENTIAL LOOP DETECTED]:\n"
                    + "You appear to be repeating the same action without progress.\n"
                    + "Please try a COMPLETELY DIFFERENT approach:\n"
                    + "- If a tool keeps failing, try a different tool\n"
                    + "- If a file edit keeps failing, try deleting and recreating the file\n"
                    + "- If a command keeps erroring, try a different command or add --help\n"
                    + "Do NOT repeat the exact same action again!\n";
        } else {
            // 严重循环 — 强制换方向
            modifier = "\n\n[SYSTEM CRITICAL - LOOP DETECTED - CHANGE APPROACH]:\n"
                    + "You are stuck in a loop (detected " + loopCount + " repetitions).\n"
                    + "You MUST immediately change your entire approach:\n"
                    + "1. STOP what you are doing\n"
                    + "2. Re-read the original task requirements\n"
                    + "3. Choose a fundamentally different strategy\n"
                    + "4. If you cannot proceed, respond with what you have accomplished so far\n";
        }

        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Unstable agent babysitter: injected loop-breaking prompt", modifier);
    }

    /** 检测循环：如果当前错误指纹与上次相同，则判定为循环 */
    private boolean detectLoop(RecoveryContext context) {
        String currentFingerprint = fingerprint(context);
        String lastFingerprint = lastErrorFingerprints.put(context.agentId(), currentFingerprint);
        return currentFingerprint.equals(lastFingerprint);
    }

    /** 生成错误指纹（简化：取错误消息的前 100 字符） */
    private String fingerprint(RecoveryContext context) {
        String msg = context.exception().getMessage();
        if (msg == null) msg = "null";
        return msg.substring(0, Math.min(100, msg.length()));
    }

    /** 重置循环计数 */
    public void resetLoopCount(String agentId) {
        loopCounts.remove(agentId);
        lastErrorFingerprints.remove(agentId);
    }
}
