package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SOM 窗口控制器 — AIOS 的语义级上下文窗口管理器。
 * <p>
 * 借鉴 VCPToolBox 的 SOM (Semantic Object Memory) 算法，
 * 彻底抛弃"按条数硬截断"或"粗暴总结"的做法，实现
 * <b>语义边界折叠 (Semantic Folding)</b>：
 * <ul>
 *   <li>当 Token 使用率达到警戒水位（85%）时，不再直接抛出 OOM</li>
 *   <li>扫描当前上下文中的 {@link SemanticObject}，按折叠优先级排序</li>
 *   <li>优先挑选时间较早且非核心的语义对象进行"折叠"</li>
 *   <li>折叠 = 替换为高维浓缩的"语义指针/摘要"，逻辑链路不断裂</li>
 *   <li>原始数据送入 {@link TokenZram} 压缩存储，可瞬间解压恢复</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS 概念</th><th>AIOS SomWindowController</th><th>说明</th></tr>
 *   <tr><td>虚拟内存</td><td>语义折叠 + TokenZram</td><td>地址空间 > 物理内存</td></tr>
 *   <tr><td>Page Fault</td><td>expandFoldedObject()</td><td>缺页中断 → 换入</td></tr>
 *   <tr><td>kswapd</td><td>triggerFolding()</td><td>后台回收</td></tr>
 *   <tr><td>Page Table</td><td>objectIndex</td><td>页表映射</td></tr>
 *   <tr><td>Swap Space</td><td>TokenZram + SwapManager</td><td>交换区</td></tr>
 *   <tr><td>OOM Killer</td><td>最后手段：强制截断</td><td>内存耗尽</td></tr>
 * </table>
 *
 * @see SemanticObject
 * @see TokenZram
 */
public final class SomWindowController {

    private static final Logger log = LoggerFactory.getLogger(SomWindowController.class);

    // ── 配置 ──

    /** 触发折叠的 Token 使用率阈值 */
    private static final double FOLDING_THRESHOLD = 0.85;

    /** 折叠后目标使用率（留出安全余量） */
    private static final double TARGET_RATIO = 0.65;

    /** 语义指针模板 */
    private static final String POINTER_TEMPLATE = "§[→%s|摘要:%s|原始%d tokens]§";

    // ── Singleton ──

    private static final class Holder {
        static final SomWindowController INSTANCE = new SomWindowController();
    }

    public static SomWindowController instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** LLM Provider — 用于生成摘要 */
    private volatile LlmProvider llmProvider;

    /** 语义对象索引：objectId → SemanticObject */
    private final ConcurrentHashMap<String, SemanticObject> objectIndex = new ConcurrentHashMap<>();

    /** Agent 的语义对象列表：pid → 有序 SemanticObject 列表 */
    private final ConcurrentHashMap<Integer, List<SemanticObject>> agentObjects = new ConcurrentHashMap<>();

    // ── 统计 ──

    private final AtomicLong totalFoldingEvents = new AtomicLong(0);
    private final AtomicLong totalObjectsFolded = new AtomicLong(0);
    private final AtomicLong totalTokensSaved = new AtomicLong(0);
    private final AtomicLong totalExpansions = new AtomicLong(0);

    private SomWindowController() {}

    /**
     * 配置 LLM Provider — 用于生成语义摘要。
     */
    public void configure(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
        log.info("[SomWindow] 已配置 LlmProvider");
    }

    // ════════════════════════════════════════════════════════════════
    //  语义对象注册
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册语义对象到 Agent 的上下文中。
     */
    public void registerObject(int pid, SemanticObject obj) {
        objectIndex.put(obj.objectId(), obj);
        agentObjects.computeIfAbsent(pid, k -> new ArrayList<>()).add(obj);
    }

    /**
     * 获取 Agent 的所有语义对象。
     */
    public List<SemanticObject> getObjects(int pid) {
        return Collections.unmodifiableList(
                agentObjects.getOrDefault(pid, Collections.emptyList()));
    }

    /**
     * 获取 Agent 的活跃（未折叠）语义对象。
     */
    public List<SemanticObject> getActiveObjects(int pid) {
        return agentObjects.getOrDefault(pid, Collections.emptyList()).stream()
                .filter(obj -> obj.state() == SemanticObject.State.ACTIVE)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    //  核心逻辑：语义边界折叠 (Semantic Folding)
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查并触发语义折叠 — 当 Token 使用率超过 85% 时调用。
     * <p>
     * 类比 Linux 的 kswapd 内核线程：当内存使用超过水位线时，
     * kswapd 被唤醒，扫描 LRU 链表，选择合适的页面换出。
     * <p>
     * SomWindowController 的扫描策略：
     * <ol>
     *   <li>计算所有活跃 SemanticObject 的折叠优先级</li>
     *   <li>按优先级降序排列</li>
     *   <li>依次折叠，直到 Token 使用率降到目标水位（65%）</li>
     * </ol>
     *
     * @param task   当前 Agent 任务
     * @param cgroup Cgroup 节点（Token 配额信息）
     * @return 折叠后节省的 Token 数
     */
    public long triggerFolding(AgentTask task, CgroupNode cgroup) {
        int pid = task.pid();
        long quota = cgroup.tokenQuota();
        long consumed = cgroup.tokenConsumed();
        double usageRatio = (double) consumed / quota;

        if (usageRatio < FOLDING_THRESHOLD) {
            return 0; // 未达警戒水位，无需折叠
        }

        totalFoldingEvents.incrementAndGet();

        long targetConsumed = (long) (quota * TARGET_RATIO);
        long needToSave = consumed - targetConsumed;

        log.info("[SomWindow] ╔══════════════════════════════════════════════════╗");
        log.info("[SomWindow] ║  语义折叠已触发                                  ║");
        log.info("[SomWindow] ║  PID={}, usage={:.1f}%, target={:.1f}%            ║",
                pid, usageRatio * 100, TARGET_RATIO * 100);
        log.info("[SomWindow] ║  需节省约 {} tokens                             ║", needToSave);
        log.info("[SomWindow] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("SOM", "FOLDING_TRIGGERED",
                "pid=" + pid + " usage=" + String.format("%.1f%%", usageRatio * 100)
                + " needToSave=" + needToSave);

        // ── Step 1: 计算折叠优先级并排序 ──
        List<SemanticObject> candidates = new ArrayList<>(
                agentObjects.getOrDefault(pid, Collections.emptyList()));
        candidates.removeIf(obj -> obj.state() != SemanticObject.State.ACTIVE);
        candidates.sort((a, b) -> Double.compare(b.foldingPriority(), a.foldingPriority()));

        if (candidates.isEmpty()) {
            log.warn("[SomWindow] 无可折叠对象 PID={}", pid);
            return 0;
        }

        // ── Step 2: 依次折叠，直到节省足够的 Token ──
        long totalSaved = 0;
        int foldedCount = 0;

        for (SemanticObject obj : candidates) {
            if (totalSaved >= needToSave) break;

            long saved = foldObject(obj, pid);
            if (saved > 0) {
                totalSaved += saved;
                foldedCount++;
                totalObjectsFolded.incrementAndGet();
            }
        }

        // ── Step 3: 退还节省的 Token ──
        if (totalSaved > 0) {
            long refunded = cgroup.refundTokens(totalSaved);
            totalTokensSaved.addAndGet(totalSaved);

            log.info("[SomWindow] 折叠完成: PID={}, folded={}, saved={} tokens, refunded={}",
                    pid, foldedCount, totalSaved, refunded);
        }

        // ── Step 4: 更新 AgentTask 的 contextHistory ──
        rebuildContextHistory(task, pid);

        return totalSaved;
    }

    /**
     * 折叠单个语义对象 — 核心折叠逻辑。
     * <p>
     * 折叠不是删除，而是将完整内容替换为"语义指针"：
     * <pre>
     * 折叠前: [用户] 请帮我写一个排序算法
     *         [Agent] 好的，我来实现快速排序...
     *         [系统] sys_tool_call: code_runner
     *         [结果] 代码执行成功，输出: [1,2,3]
     *
     * 折叠后: §[→som-42|摘要:用户请求排序算法,Agent实现快排并执行成功|原始128 tokens]§
     * </pre>
     * 逻辑链路依然连贯，但 Token 占用从 128 降到 ~20。
     *
     * @param obj 要折叠的语义对象
     * @param pid Agent PID
     * @return 节省的 Token 数
     */
    private long foldObject(SemanticObject obj, int pid) {
        String fullContent = obj.getFullContent();
        long originalTokens = obj.estimatedTokens();

        // ── 生成摘要 ──
        String summary = generateSummary(obj);

        // ── 构造语义指针 ──
        String pointer = String.format(POINTER_TEMPLATE,
                obj.objectId(),
                summary.length() > 60 ? summary.substring(0, 60) + "..." : summary,
                originalTokens);

        // ── 将原始数据送入 TokenZram 压缩存储 ──
        String zramHandle = TokenZram.instance().compressToZram(pid, obj.objectId(), fullContent);

        // ── 执行折叠 ──
        obj.fold(pointer, zramHandle);

        long savedTokens = originalTokens - obj.foldedTokens();

        log.info("[SomWindow] 已折叠: obj={}, type={}, saved={} tokens, zram={}",
                obj.objectId(), obj.type(), savedTokens, zramHandle);

        return savedTokens;
    }

    /**
     * 生成语义摘要 — 使用 LLM 或启发式方法。
     */
    private String generateSummary(SemanticObject obj) {
        // 尝试使用 LLM 生成高质量摘要
        if (llmProvider != null && llmProvider.isAvailable()) {
            try {
                String content = obj.getFullContent();
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...";
                }

                return llmProvider.think(
                        "请用一句话概括以下对话的核心内容，不超过50字：\n" + content,
                        "你是 AIOS 语义摘要引擎。");
            } catch (Exception e) {
                log.debug("[SomWindow] LLM 摘要失败，使用启发式: {}", e.getMessage());
            }
        }

        // 回退到启发式摘要
        return generateHeuristicSummary(obj);
    }

    /**
     * 启发式摘要生成 — 不依赖 LLM 的快速摘要。
     */
    private String generateHeuristicSummary(SemanticObject obj) {
        List<SemanticObject.MessageEntry> messages = obj.messages();

        if (messages.isEmpty()) {
            return obj.type().label();
        }

        // 提取第一条和最后一条消息的关键内容
        String firstContent = messages.get(0).content();
        String lastContent = messages.get(messages.size() - 1).content();

        String firstBrief = firstContent.length() > 30 ? firstContent.substring(0, 30) + "..." : firstContent;
        String lastBrief = lastContent.length() > 30 ? lastContent.substring(0, 30) + "..." : lastContent;

        return obj.type().label() + ": " + firstBrief + " → " + lastBrief;
    }

    // ════════════════════════════════════════════════════════════════
    //  语义指针展开 (Page Fault → Page In)
    // ════════════════════════════════════════════════════════════════

    /**
     * 展开折叠的语义对象 — 类比 Page Fault 处理。
     * <p>
     * 当 LLM 在推理中需要展开某个"语义指针"时，
     * 从 TokenZram 中解压并恢复完整的 SemanticObject。
     *
     * @param objectId 语义对象 ID
     * @param task     当前 Agent 任务
     * @param cgroup   Cgroup 节点
     * @return 是否成功展开
     */
    public boolean expandFoldedObject(String objectId, AgentTask task, CgroupNode cgroup) {
        SemanticObject obj = objectIndex.get(objectId);
        if (obj == null || obj.state() == SemanticObject.State.ACTIVE) {
            return false;
        }

        // 检查是否有足够的 Token 预算
        long neededTokens = obj.estimatedTokens();
        long available = cgroup.tokenRemaining();
        if (neededTokens > available) {
            // 先折叠其他对象腾出空间
            triggerFolding(task, cgroup);
        }

        // 从 TokenZram 解压
        String zramHandle = obj.zramHandle();
        if (zramHandle != null) {
            String decompressed = TokenZram.instance().decompressFromZram(zramHandle);
            if (decompressed != null) {
                obj.restoreFromZram(decompressed);
                totalExpansions.incrementAndGet();

                // 消耗 Token
                cgroup.consumeTokens(neededTokens);

                // 重建 contextHistory
                rebuildContextHistory(task, task.pid());

                log.info("[SomWindow] 已展开: obj={}, tokens={}", objectId, neededTokens);
                return true;
            }
        }

        // 从 SwapManager 换入
        String swapPtr = obj.swapPointer();
        if (swapPtr != null) {
            List<String> swappedContent = SwapManager.instance().swapIn(swapPtr);
            if (swappedContent != null && !swappedContent.isEmpty()) {
                String combined = String.join("\n", swappedContent);
                obj.restoreFromZram(combined);
                totalExpansions.incrementAndGet();
                cgroup.consumeTokens(neededTokens);
                rebuildContextHistory(task, task.pid());
                log.info("[SomWindow] 已换入: obj={}, tokens={}", objectId, neededTokens);
                return true;
            }
        }

        log.warn("[SomWindow] 展开失败: obj={}", objectId);
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  上下文重建
    // ════════════════════════════════════════════════════════════════

    /**
     * 重建 AgentTask 的 contextHistory — 将语义对象的可见内容
     * 按顺序写回 contextHistory。
     */
    private void rebuildContextHistory(AgentTask task, int pid) {
        List<SemanticObject> objects = agentObjects.getOrDefault(pid, Collections.emptyList());
        if (objects.isEmpty()) return;

        List<String> newHistory = new ArrayList<>();
        for (SemanticObject obj : objects) {
            String content = obj.getVisibleContent();
            newHistory.add(content);
        }

        // 替换 contextHistory
        task.contextHistory().clear();
        task.contextHistory().addAll(newHistory);
    }

    // ════════════════════════════════════════════════════════════════
    //  检查与统计
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查是否需要触发折叠。
     */
    public boolean needsFolding(CgroupNode cgroup) {
        if (cgroup == null) return false;
        double usage = (double) cgroup.tokenConsumed() / cgroup.tokenQuota();
        return usage >= FOLDING_THRESHOLD;
    }

    public long totalFoldingEvents() { return totalFoldingEvents.get(); }
    public long totalObjectsFolded() { return totalObjectsFolded.get(); }
    public long totalTokensSaved() { return totalTokensSaved.get(); }
    public long totalExpansions() { return totalExpansions.get(); }

    public String getStatsReport() {
        return """
                ┌─ SomWindowController Stats ─────────────────────────
                │  Folding Events      : %d
                │  Objects Folded      : %d
                │  Tokens Saved        : %d
                │  Expansions          : %d
                │  Folding Threshold   : %.0f%%
                │  Target Ratio        : %.0f%%
                │  Registered Objects  : %d
                └─────────────────────────────────────────────────"""
                .formatted(totalFoldingEvents.get(), totalObjectsFolded.get(),
                        totalTokensSaved.get(), totalExpansions.get(),
                        FOLDING_THRESHOLD * 100, TARGET_RATIO * 100,
                        objectIndex.size());
    }
}
