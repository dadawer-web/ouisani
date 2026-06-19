package com.ouisani.aios.core.llm;

import com.ouisani.aios.core.llm.decode.DecodeStrategy;
import com.ouisani.aios.core.llm.decode.SemanticFuzzyDecodeStrategy;
import com.ouisani.aios.core.llm.decode.StrictDecodeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 指令解码器 — AIOS 内核的"指令取指单元"。
 * <p>
 * 将 LLM 的原始文本输出翻译为类型化的 Java 对象，采用<b>双轨制解码管线</b>：
 *
 * <h3>轨道 1：严格解码器（Strict Decoder）</h3>
 * 快速路径 — 使用 Jackson 进行标准 JSON 解析。
 * 处理 Markdown 代码块和嵌入式 JSON 提取。
 * 如果 LLM 遵循 Schema 约定，只需此轨道即可。
 *
 * <h3>轨道 2：语义模糊解码器（Semantic Fuzzy Decoder）</h3>
 * 弹性回退 — 当严格解析失败时，使用正则深度清洗、字段名模糊匹配
 * 和片段组装，从格式不规范或非正式的 LLM 输出中提取意图。
 *
 * <h3>责任链模式</h3>
 * 两条轨道由 {@link DecodeStrategy} 责任链实现，按优先级顺序依次尝试，
 * 直到某个策略成功。这使得解码管线可扩展 — 新策略无需修改现有代码。
 *
 * <h3>OS 类比：Trap → Fault → Panic</h3>
 * <pre>
 *   严格解析成功    →  TLB 命中（快速路径）
 *   严格失败，模糊成功  →  Page Fault（慢但可恢复）
 *   全部失败       →  Kernel Panic（InstructionDecodeException）
 * </pre>
 *
 * @see DecodeStrategy
 * @see StrictDecodeStrategy
 * @see SemanticFuzzyDecodeStrategy
 * @see InstructionDecodeException
 */
public class InstructionDecoder {

    private static final Logger log = LoggerFactory.getLogger(InstructionDecoder.class);

    /** 按优先级排序的解码策略链 */
    private static final List<DecodeStrategy> strategyChain = new ArrayList<>();

    static {
        // 初始化双轨制解码管线
        strategyChain.add(new StrictDecodeStrategy());
        strategyChain.add(new SemanticFuzzyDecodeStrategy());

        // 按优先级排序（数值越小优先级越高）
        strategyChain.sort(Comparator.comparingInt(DecodeStrategy::priority));

        log.info("[InstructionDecoder] 双轨解码管线已初始化: {}",
                strategyChain.stream().map(DecodeStrategy::name).toList());
        System.out.println("  \u001B[36m[InstructionDecoder] 双轨解码管线已挂载: "
                + strategyChain.stream().map(DecodeStrategy::name).toList() + "\u001B[0m");
    }

    private InstructionDecoder() {}

    // ════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * 使用双轨制管线将 LLM 输出解码为类型化的 Java 对象（含自愈重试）。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>严格解码器（含自愈重试）</li>
     *   <li>语义模糊解码器（3 阶段模糊管线）</li>
     * </ol>
     * 如果所有策略均失败，抛出 {@link InstructionDecodeException}。
     *
     * @param llmOutput   LLM 的原始文本输出
     * @param targetClass 期望的 Java 类型
     * @param llmProvider LLM 提供者（用于自愈重试），可为 null
     * @return 解码后的对象
     * @throws InstructionDecodeException 所有策略均失败时抛出
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass, LlmProvider llmProvider) {
        log.info("[InstructionDecoder] 正在解码: type={}, inputLen={}, strategies={}",
                targetClass.getSimpleName(), llmOutput != null ? llmOutput.length() : 0,
                strategyChain.size());

        for (DecodeStrategy strategy : strategyChain) {
            try {
                T result = strategy.decode(llmOutput, targetClass, llmProvider);
                if (result != null) {
                    log.info("[InstructionDecoder] 通过策略 '{}' 解码成功: type={}",
                            strategy.name(), targetClass.getSimpleName());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[InstructionDecoder] 策略 '{}' 抛出异常: {}",
                        strategy.name(), e.getMessage());
                // 继续尝试下一个策略 — 不让单个策略的异常导致整个管线崩溃
            }
        }

        // 所有策略已耗尽 — 内核恐慌
        String fatalMsg = "All decode strategies failed for type " + targetClass.getSimpleName()
                + ". Tried: " + strategyChain.stream().map(DecodeStrategy::name).toList();
        log.error("[InstructionDecoder] FATAL: {}", fatalMsg);
        System.err.printf("  \u001B[31m[InstructionDecoder] FATAL: %s\u001B[0m%n", fatalMsg);
        throw new InstructionDecodeException(fatalMsg, strategyChain.size());
    }

    /**
     * 不含自愈的解码 — 每个策略只尝试一次。
     * 适用于调用方没有 LlmProvider 可用于自愈重试的场景。
     *
     * @param llmOutput   LLM 的原始文本输出
     * @param targetClass 期望的 Java 类型
     * @return 解码后的对象
     * @throws InstructionDecodeException 所有策略均失败时抛出
     */
    public static <T> T decodeJson(String llmOutput, Class<T> targetClass) {
        return decodeJson(llmOutput, targetClass, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  Pipeline Management
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册自定义解码策略到管线中。
     * 注册后策略会按优先级重新排序。
     *
     * @param strategy 要添加的策略
     */
    public static void registerStrategy(DecodeStrategy strategy) {
        strategyChain.add(strategy);
        strategyChain.sort(Comparator.comparingInt(DecodeStrategy::priority));
        log.info("[InstructionDecoder] 策略已注册: name={}, priority={}, pipeline={}",
                strategy.name(), strategy.priority(),
                strategyChain.stream().map(DecodeStrategy::name).toList());
    }

    /**
     * 按名称移除策略。
     *
     * @param name 要移除的策略名称
     * @return 是否成功移除
     */
    public static boolean removeStrategy(String name) {
        boolean removed = strategyChain.removeIf(s -> s.name().equals(name));
        if (removed) {
            log.info("[InstructionDecoder] 策略已移除: name={}, pipeline={}",
                    name, strategyChain.stream().map(DecodeStrategy::name).toList());
        }
        return removed;
    }

    /** 获取当前管线配置的策略名称列表 */
    public static List<String> getPipelineNames() {
        return strategyChain.stream().map(DecodeStrategy::name).toList();
    }
}
