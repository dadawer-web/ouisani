package com.ouisani.aios.core.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ouisani.aios.core.config.AiosPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VersionedPlan 持久化 — 镜像 jcode {@code swarm_persistence.rs}。
 * <p>
 * 使用 {@link java.nio.file.Files#writeString} / {@link java.nio.file.Files#readString}
 * 直接读写物理路径（绕过 VFS，避免测试期 VfsManager 未初始化导致的静默失败），
 * 重载时强制 {@code running → running_stale} 降级（镜像 swarm_persistence.rs:70-87）。
 * <p>
 * {@code updatedAt} 时间戳内嵌于 JSON，作为重载时 {@code stale_since} 的 fallback。
 */
final class VersionedPlanPersistence {

    private static final Logger log = LoggerFactory.getLogger(VersionedPlanPersistence.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 持久化 JSON 格式 — 镜像 jcode PersistedVersionedPlan。 */
    record PersistedPlan(
            long version,
            List<String> participants,
            List<PlanItem> items,
            Map<String, SwarmTaskProgress> taskProgress,
            long updatedAt
    ) {}

    private VersionedPlanPersistence() {}

    // ════════════════════════════════════════════════════════════════
    //  保存
    // ════════════════════════════════════════════════════════════════

    /**
     * 保存 VersionedPlan 到 VFS。
     *
     * @param swarmId swarm 标识
     * @param plan    版本化计划
     */
    static void save(String swarmId, VersionedPlan plan) {
        try {
            PersistedPlan persisted = new PersistedPlan(
                    plan.version(),
                    new ArrayList<>(plan.participants()),
                    plan.snapshotItems(),
                    plan.progressSnapshot(),
                    System.currentTimeMillis()
            );
            String json = GSON.toJson(persisted);
            java.nio.file.Path path = java.nio.file.Path.of(filePath(swarmId));
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, json);
            log.debug("[PlanPersistence] saved swarm={} v={} items={}",
                    swarmId, plan.version(), plan.snapshotItems().size());
        } catch (Exception e) {
            log.warn("[PlanPersistence] save failed for swarm={}: {}", swarmId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  加载 — 镜像 swarm_persistence.rs:70-87 from_persisted_plan
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 VFS 加载 VersionedPlan。
     * <p>
     * 重载降级：所有 {@code running} 状态强制降级为 {@code running_stale}，
     * {@code stale_since = get_or_insert(updatedAt)}。
     * 若文件不存在返回 null。
     *
     * @param swarmId swarm 标识
     * @return 恢复的 VersionedPlan，不存在返回 null
     */
    static VersionedPlan load(String swarmId) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(filePath(swarmId));
            if (!java.nio.file.Files.exists(path)) return null;
            String json = java.nio.file.Files.readString(path);
            if (json == null || json.isBlank()) return null;

            PersistedPlan persisted = GSON.fromJson(json, PersistedPlan.class);
            if (persisted == null) return null;

            long reloadTime = persisted.updatedAt() > 0
                    ? persisted.updatedAt()
                    : System.currentTimeMillis();

            return fromPersisted(persisted, reloadTime);
        } catch (Exception e) {
            log.warn("[PlanPersistence] load failed for swarm={}: {}", swarmId, e.getMessage());
            return null;
        }
    }

    /**
     * 从持久化格式恢复 — 强制 running→running_stale 降级。
     * <p>
     * 镜像 jcode swarm_persistence.rs:70-87 {@code from_persisted_plan}：
     * <ul>
     *   <li>所有 {@code running} 状态 → {@code running_stale}</li>
     *   <li>{@code stale_since = get_or_insert(reloadTime)}（SwarmTaskProgress.withStaleSince 已实现 get_or_insert）</li>
     * </ul>
     */
    private static VersionedPlan fromPersisted(PersistedPlan p, long reloadTime) {
        VersionedPlan plan = new VersionedPlan();

        for (PlanItem item : p.items()) {
            PlanItem restored = item;
            // running → running_stale 强制降级
            if ("running".equals(item.status())) {
                restored = item.withStatus("running_stale");

                // stale_since get_or_insert(reloadTime)
                SwarmTaskProgress prog = p.taskProgress() != null
                        ? p.taskProgress().getOrDefault(item.id(), SwarmTaskProgress.empty())
                        : SwarmTaskProgress.empty();
                prog = prog.withStaleSince(reloadTime);
                plan.putProgress(item.id(), prog);
            } else if (p.taskProgress() != null && p.taskProgress().containsKey(item.id())) {
                plan.putProgress(item.id(), p.taskProgress().get(item.id()));
            }

            plan.addItemRaw(restored);
        }

        plan.setVersionRaw(p.version());

        Set<String> participants = new HashSet<>();
        if (p.participants() != null) participants.addAll(p.participants());
        plan.addParticipantsRaw(participants);

        log.info("[PlanPersistence] loaded swarm: v={} items={} (running→running_stale degraded at {})",
                p.version(), p.items().size(), reloadTime);
        return plan;
    }

    /** 列出已持久化的 swarm id（通过扫描 planDir 下的 .json 文件）。 */
    static List<String> listSwarmIds() {
        List<String> ids = new ArrayList<>();
        try {
            String dir = AiosPaths.planDir();
            // VFS 不支持列目录，使用 java.nio.file 扫描物理路径
            java.nio.file.Path dirPath = java.nio.file.Path.of(dir);
            if (java.nio.file.Files.isDirectory(dirPath)) {
                try (var stream = java.nio.file.Files.list(dirPath)) {
                    stream.filter(path -> path.toString().endsWith(".json"))
                            .forEach(path -> {
                                String name = path.getFileName().toString();
                                ids.add(name.substring(0, name.length() - 5)); // 去掉 .json
                            });
                }
            }
        } catch (Exception e) {
            log.debug("[PlanPersistence] listSwarmIds: {}", e.getMessage());
        }
        return ids;
    }

    private static String filePath(String swarmId) {
        return AiosPaths.planDir() + "/" + sanitize(swarmId) + ".json";
    }

    private static String sanitize(String id) {
        if (id == null) return "default";
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
