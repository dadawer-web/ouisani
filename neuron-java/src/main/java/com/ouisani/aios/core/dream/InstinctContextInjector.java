package com.ouisani.aios.core.dream;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.learning.instinct.Instinct;
import com.ouisani.aios.core.learning.instinct.InstinctStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 本能上下文注入器 — 将相关本能自动挂载到 Agent 上下文。
 * <p>
 * 借鉴 ECC 的持续学习系统：第二天当 Agent 尝试类似任务时，
 * ContextInjector 会自动把相关本能(Instinct)挂载到上下文中。
 * <p>
 * 你的 AIOS 真正拥有了"进化"和"繁衍"技能的能力！
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>Agent 发起 LLM 请求</li>
 *   <li>ContextInjector.augmentPrompt() 被调用</li>
 *   <li>本注入器查询 InstinctStore 中与当前 prompt 相关的本能</li>
 *   <li>将高置信度本能的描述注入到 prompt 前置位置</li>
 * </ol>
 *
 * <h3>OS 类比: Linux Kernel Module Auto-Loading</h3>
 * 类似 Linux 内核的 modprobe：当内核检测到需要某个功能时，
 * 自动加载对应的内核模块。本注入器在 Agent 需要某类知识时，
 * 自动加载相关的本能(学习到的规则)到上下文中。
 *
 * @see InstinctStore
 * @see InstinctExtractor
 * @see com.ouisani.aios.core.memory.ContextInjector
 */
public final class InstinctContextInjector {

    private static final Logger log = LoggerFactory.getLogger(InstinctContextInjector.class);

    private static final InstinctContextInjector INSTANCE = new InstinctContextInjector();

    /** 注入本能的最大数量(避免上下文膨胀) */
    private static final int MAX_INJECTED_INSTINCTS = 3;

    /** 注入本能的最低置信度阈值 */
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.5;

    private volatile String currentProjectHash;

    private InstinctContextInjector() {}

    public static InstinctContextInjector getInstance() { return INSTANCE; }

    /**
     * 设置当前项目哈希(用于查询项目级本能)。
     *
     * @param projectHash 项目哈希
     */
    public void setCurrentProject(String projectHash) {
        this.currentProjectHash = projectHash;
    }

    /**
     * 增强原始 prompt — 注入相关本能。
     * <p>
     * 此方法应该在 ContextInjector.augmentPrompt() 中被调用，
     * 作为向量记忆注入的补充。
     *
     * @param originalPrompt 原始 prompt
     * @return 增强后的 prompt(如果无相关本能则原样返回)
     */
    public String augmentWithInstincts(String originalPrompt) {
        if (originalPrompt == null || originalPrompt.isBlank()) return originalPrompt;
        if (currentProjectHash == null) return originalPrompt;

        try {
            // 获取项目的本能列表(项目级 + 全局级)
            List<Instinct> instincts = InstinctStore.instance().getForProject(currentProjectHash);
            if (instincts.isEmpty()) return originalPrompt;

            // 筛选高置信度本能
            List<Instinct> relevant = instincts.stream()
                    .filter(i -> i.confidence() >= MIN_CONFIDENCE_THRESHOLD)
                    .limit(MAX_INJECTED_INSTINCTS)
                    .toList();

            if (relevant.isEmpty()) return originalPrompt;

            // 构建注入块
            StringBuilder injection = new StringBuilder();
            injection.append("[Learned Instincts (Muscle Memory):\n");
            for (int i = 0; i < relevant.size(); i++) {
                Instinct instinct = relevant.get(i);
                injection.append(String.format("  %d. (conf=%.2f) %s\n",
                        i + 1, instinct.confidence(), instinct.description()));
            }
            injection.append("]\n\n");

            log.debug("[InstinctContextInjector] 注入 {} 个本能", relevant.size());
            return injection.toString() + originalPrompt;

        } catch (Exception e) {
            log.warn("[InstinctContextInjector] 注入失败: {}", e.getMessage());
            return originalPrompt;
        }
    }

    /**
     * 从 VFS 加载本能文件。
     * <p>
     * 读取 /vfs/aios_skills/instincts/ 目录下的所有 .md 文件，
     * 作为本能的补充来源。
     *
     * @param projectHash 项目哈希
     * @return 加载的本能描述文本(供 prompt 注入)
     */
    public String loadInstinctsFromVfs(String projectHash) {
        try {
            VfsManager vfs = VfsManager.instance();
            List<String> files = vfs.listFilesUnder(InstinctExtractor.INSTINCT_VFS_DIR);
            if (files == null || files.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String filePath : files) {
                if (!filePath.endsWith(".md")) continue;
                if (count >= MAX_INJECTED_INSTINCTS) break;

                String content = vfs.readText(filePath);
                if (content != null && !content.isBlank()) {
                    sb.append(content).append("\n---\n");
                    count++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[InstinctContextInjector] VFS 加载失败: {}", e.getMessage());
            return "";
        }
    }
}
