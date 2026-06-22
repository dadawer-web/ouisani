package com.ouisani.aios.core.learning.instinct;

import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Instinct 存储 — 项目隔离的本能记忆仓库。
 * <p>
 * 借鉴 ECC 的 instinct 项目隔离设计：
 * instinct 默认 project-scoped（按 git remote URL hash 隔离），避免跨项目污染。
 * 当同一模式在 2+ 项目出现且置信度 >= 0.8 时，可晋升为 global。
 *
 * <h3>存储结构</h3>
 * <ul>
 *   <li>{@code projectInstincts} — projectHash → (instinctId → Instinct) 的嵌套映射</li>
 *   <li>{@code globalInstincts} — instinctId → Instinct 的全局映射</li>
 * </ul>
 *
 * <h3>OS 类比: Linux Kernel Per-Namespace Tunables</h3>
 * 类似 Linux 内核的 per-namespace 可调参数：
 * 每个项目（命名空间）有自己的 instinct 集合，全局 instinct 对所有项目生效。
 *
 * @see Instinct
 * @see InstinctObserver
 */
public final class InstinctStore {

    private static final Logger log = LoggerFactory.getLogger(InstinctStore.class);

    private static final InstinctStore INSTANCE = new InstinctStore();

    /** 项目隔离的 instinct 存储: projectHash → (instinctId → Instinct) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Instinct>> projectInstincts
            = new ConcurrentHashMap<>();

    /** 全局 instinct 存储: instinctId → Instinct */
    private final ConcurrentHashMap<String, Instinct> globalInstincts = new ConcurrentHashMap<>();

    /** 衰减检查的间隔（默认7天） */
    private volatile long decayIntervalMs = TimeUnit.DAYS.toMillis(7);

    /** 上次衰减执行时间 */
    private volatile long lastDecayRun = System.currentTimeMillis();

    private InstinctStore() {}

    public static InstinctStore instance() { return INSTANCE; }

    /**
     * 计算项目的哈希值。
     * <p>
     * 借鉴 ECC 的项目隔离设计：按 git remote URL 哈希隔离。
     * 如果没有 git remote URL，使用工作目录路径。
     *
     * @param gitRemoteUrl git remote URL（如 "https://github.com/user/repo.git"）
     * @param fallbackPath 回退路径（工作目录）
     * @return 项目哈希（16位十六进制）
     */
    public static String computeProjectHash(String gitRemoteUrl, String fallbackPath) {
        String input = (gitRemoteUrl != null && !gitRemoteUrl.isBlank())
                ? gitRemoteUrl
                : (fallbackPath != null ? fallbackPath : "unknown");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 一定存在，但以防万一
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 记录或更新一个 instinct。
     * <p>
     * 如果同 id 的 instinct 已存在，更新置信度；否则创建新的。
     *
     * @param pattern     触发模式
     * @param action      建议行为
     * @param description 人类可读描述
     * @param confidence  初始置信度
     * @param projectHash 项目哈希
     * @return 被记录的 instinct
     */
    public Instinct record(String pattern, String action, String description,
                           double confidence, String projectHash) {
        String id = computeInstinctId(pattern, action);

        // 先检查全局存储
        Instinct global = globalInstincts.get(id);
        if (global != null) {
            global.confirm();
            log.debug("[InstinctStore] 全局 instinct 确认: {}", global);
            return global;
        }

        // 检查项目存储
        ConcurrentHashMap<String, Instinct> projectMap =
                projectInstincts.computeIfAbsent(projectHash, k -> new ConcurrentHashMap<>());
        Instinct existing = projectMap.get(id);
        if (existing != null) {
            existing.confirm();
            // 检查是否满足晋升条件
            if (existing.observeInProject(projectHash)) {
                promoteToGlobal(id, existing);
            }
            log.debug("[InstinctStore] 项目 instinct 确认: {}", existing);
            return existing;
        }

        // 创建新 instinct
        Instinct newInstinct = new Instinct(id, pattern, action, description, confidence, projectHash);
        projectMap.put(id, newInstinct);

        SemanticEtw.getInstance().logEvent("LEARNING", "INSTINCT_CREATED",
                "id=" + id + " pattern=" + pattern + " project=" + projectHash);
        log.info("[InstinctStore] 新 instinct 创建: {}", newInstinct);

        return newInstinct;
    }

    /**
     * 反驳一个 instinct — 置信度 -0.10。
     *
     * @param instinctId  instinct ID
     * @param projectHash 项目哈希
     */
    public void refute(String instinctId, String projectHash) {
        Instinct global = globalInstincts.get(instinctId);
        if (global != null) {
            global.refute();
            if (global.isExpired()) {
                globalInstincts.remove(instinctId);
                log.info("[InstinctStore] 全局 instinct 过期淘汰: {}", instinctId);
            }
            return;
        }

        ConcurrentHashMap<String, Instinct> projectMap = projectInstincts.get(projectHash);
        if (projectMap != null) {
            Instinct instinct = projectMap.get(instinctId);
            if (instinct != null) {
                instinct.refute();
                if (instinct.isExpired()) {
                    projectMap.remove(instinctId);
                    log.info("[InstinctStore] 项目 instinct 过期淘汰: {}", instinctId);
                }
            }
        }
    }

    /**
     * 获取项目的所有 instinct（项目级 + 全局级）。
     *
     * @param projectHash 项目哈希
     * @return instinct 列表（按置信度降序）
     */
    public List<Instinct> getForProject(String projectHash) {
        List<Instinct> result = new ArrayList<>();

        // 全局 instinct
        result.addAll(globalInstincts.values());

        // 项目级 instinct
        ConcurrentHashMap<String, Instinct> projectMap = projectInstincts.get(projectHash);
        if (projectMap != null) {
            result.addAll(projectMap.values());
        }

        // 按置信度降序排序
        result.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return result;
    }

    /**
     * 获取所有全局 instinct。
     */
    public List<Instinct> getGlobal() {
        List<Instinct> result = new ArrayList<>(globalInstincts.values());
        result.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return result;
    }

    /**
     * 手动晋升一个 instinct 为全局。
     *
     * @param instinctId  instinct ID
     * @param projectHash 项目哈希
     * @return true 如果晋升成功
     */
    public boolean promote(String instinctId, String projectHash) {
        ConcurrentHashMap<String, Instinct> projectMap = projectInstincts.get(projectHash);
        if (projectMap == null) return false;

        Instinct instinct = projectMap.get(instinctId);
        if (instinct == null) return false;

        instinct.promoteToGlobal();
        promoteToGlobal(instinctId, instinct);
        return true;
    }

    /**
     * 执行周期性衰减。
     * <p>
     * 应该由系统定时器（如 SysTick）定期调用。
     * 对所有 instinct 应用基于时间的衰减，淘汰过期的。
     */
    public void runDecayCycle() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastDecayRun;
        if (elapsed < decayIntervalMs) return;

        int weeksElapsed = (int) (elapsed / TimeUnit.DAYS.toMillis(7));
        if (weeksElapsed <= 0) return;

        log.info("[InstinctStore] 执行衰减周期: {} 周前", weeksElapsed);

        // 衰减全局 instinct
        int globalExpired = 0;
        Iterator<Map.Entry<String, Instinct>> globalIt = globalInstincts.entrySet().iterator();
        while (globalIt.hasNext()) {
            Instinct instinct = globalIt.next().getValue();
            instinct.applyDecay(weeksElapsed);
            if (instinct.isExpired()) {
                globalIt.remove();
                globalExpired++;
            }
        }

        // 衰减项目 instinct
        int projectExpired = 0;
        for (ConcurrentHashMap<String, Instinct> projectMap : projectInstincts.values()) {
            Iterator<Map.Entry<String, Instinct>> it = projectMap.entrySet().iterator();
            while (it.hasNext()) {
                Instinct instinct = it.next().getValue();
                instinct.applyDecay(weeksElapsed);
                if (instinct.isExpired()) {
                    it.remove();
                    projectExpired++;
                }
            }
        }

        lastDecayRun = now;

        SemanticEtw.getInstance().logEvent("LEARNING", "INSTINCT_DECAY",
                "weeks=" + weeksElapsed + " global_expired=" + globalExpired
                        + " project_expired=" + projectExpired);

        log.info("[InstinctStore] 衰减完成: 全局淘汰={}, 项目淘汰={}", globalExpired, projectExpired);
    }

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("global_count", globalInstincts.size());
        stats.put("project_count", projectInstincts.size());
        stats.put("total_project_instincts", projectInstincts.values().stream()
                .mapToInt(Map::size).sum());
        stats.put("last_decay", lastDecayRun);
        return stats;
    }

    /**
     * 导出所有 instinct 为 YAML 格式。
     */
    public String exportYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Instinct Store Export\n");
        sb.append("# Generated: ").append(new Date()).append("\n\n");

        sb.append("# Global Instincts\n");
        for (Instinct i : globalInstincts.values()) {
            sb.append(i.toYaml());
        }

        sb.append("\n# Project-Scoped Instincts\n");
        for (Map.Entry<String, ConcurrentHashMap<String, Instinct>> entry : projectInstincts.entrySet()) {
            sb.append("# Project: ").append(entry.getKey()).append("\n");
            for (Instinct i : entry.getValue().values()) {
                sb.append(i.toYaml());
            }
        }

        return sb.toString();
    }

    // ── 内部方法 ──

    private void promoteToGlobal(String id, Instinct instinct) {
        globalInstincts.put(id, instinct);
        // 从所有项目存储中移除
        for (ConcurrentHashMap<String, Instinct> projectMap : projectInstincts.values()) {
            projectMap.remove(id);
        }
        SemanticEtw.getInstance().logEvent("LEARNING", "INSTINCT_PROMOTED",
                "id=" + id + " confidence=" + instinct.confidence());
        log.info("[InstinctStore] instinct 晋升为全局: {}", instinct);
    }

    private static String computeInstinctId(String pattern, String action) {
        String input = pattern + "|" + action;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
