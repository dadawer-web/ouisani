package com.ouisani.aios.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 巨石状态管理器 (The Boulder State Store)。
 * <p>
 * 负责在极低开销下，将检查点序列化到宿主机 ~/.aios/boulders/ 目录。
 * 对标 oh-my-openagent 的持久化机制：每个节点执行完毕后立即落盘，
 * 系统崩溃重启后通过检查点恢复现场，跳过已完成的节点。
 * <p>
 * 存储结构：
 * <pre>
 *   ~/.aios/boulders/
 *     ├── {workflowId}_{nodeId}.json    ← 单节点检查点
 *     └── ...
 * </pre>
 *
 * @see BoulderCheckpoint
 */
public class BoulderStateManager {

    private static final Logger log = LoggerFactory.getLogger(BoulderStateManager.class);

    private static final String STATE_DIR = System.getProperty("user.home") + "/.aios/boulders/";

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    // 静态初始化：确保目录存在
    static {
        try {
            Files.createDirectories(Paths.get(STATE_DIR));
            log.info("[Boulder State] State directory initialized: {}", STATE_DIR);
        } catch (Exception e) {
            log.error("[Boulder State] Failed to initialize state directory.", e);
        }
    }

    /**
     * 保存检查点 — 节点执行完毕后立即落盘。
     */
    public static void saveCheckpoint(BoulderCheckpoint checkpoint) {
        try {
            Path path = Paths.get(STATE_DIR, checkpoint.getWorkflowId() + "_" + checkpoint.getNodeId() + ".json");
            mapper.writeValue(path.toFile(), checkpoint);
            log.debug("[Boulder State] Checkpoint secured for node: {}", checkpoint.getNodeId());
        } catch (Exception e) {
            log.error("[Boulder State] Failed to save checkpoint for node: {}", checkpoint.getNodeId(), e);
        }
    }

    /**
     * 加载检查点 — 系统重启后恢复现场。
     */
    public static Optional<BoulderCheckpoint> loadCheckpoint(String workflowId, String nodeId) {
        try {
            File file = new File(STATE_DIR, workflowId + "_" + nodeId + ".json");
            if (file.exists()) {
                BoulderCheckpoint checkpoint = mapper.readValue(file, BoulderCheckpoint.class);
                log.debug("[Boulder State] Checkpoint loaded for node: {} (status={})", nodeId, checkpoint.getStatus());
                return Optional.of(checkpoint);
            }
        } catch (Exception e) {
            log.error("[Boulder State] Corruption detected in checkpoint file for node: {}", nodeId, e);
        }
        return Optional.empty();
    }

    /**
     * 删除指定工作流的所有检查点 — 工作流彻底完成后清理。
     */
    public static void cleanWorkflowCheckpoints(String workflowId) {
        try {
            File dir = new File(STATE_DIR);
            File[] files = dir.listFiles((d, name) -> name.startsWith(workflowId + "_"));
            if (files != null) {
                for (File file : files) {
                    Files.deleteIfExists(file.toPath());
                }
                log.info("[Boulder State] Cleaned {} checkpoints for workflow: {}", files.length, workflowId);
            }
        } catch (Exception e) {
            log.error("[Boulder State] Failed to clean checkpoints for workflow: {}", workflowId, e);
        }
    }

    /**
     * 删除单个节点的检查点。
     */
    public static void deleteCheckpoint(String workflowId, String nodeId) {
        try {
            Path path = Paths.get(STATE_DIR, workflowId + "_" + nodeId + ".json");
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.error("[Boulder State] Failed to delete checkpoint for node: {}", nodeId, e);
        }
    }

    /**
     * 列出指定工作流的所有检查点 — 用于断点续传时判断哪些节点已完成。
     */
    public static List<BoulderCheckpoint> listWorkflowCheckpoints(String workflowId) {
        List<BoulderCheckpoint> checkpoints = new ArrayList<>();
        File dir = new File(STATE_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith(workflowId + "_") && name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    checkpoints.add(mapper.readValue(file, BoulderCheckpoint.class));
                } catch (Exception e) {
                    log.warn("[Boulder State] Corrupted checkpoint file: {}", file.getName());
                }
            }
        }
        return checkpoints;
    }

    /**
     * 检查指定工作流是否有检查点（用于判断是否需要断点续传）。
     */
    public static boolean hasCheckpoints(String workflowId) {
        File dir = new File(STATE_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith(workflowId + "_") && name.endsWith(".json"));
        return files != null && files.length > 0;
    }

    /**
     * 获取状态目录路径。
     */
    public static String getStateDir() {
        return STATE_DIR;
    }
}
