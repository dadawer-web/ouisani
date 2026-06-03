package com.ouisani.aios.core.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final class Holder {
        static final SnapshotManager INSTANCE = new SnapshotManager();
    }

    private SnapshotManager() {}

    public static SnapshotManager instance() {
        return Holder.INSTANCE;
    }

    /**
     * 进程快照 Record：包含迁移所需的全部状态
     */
    public record ProcessSnapshot(
            int pid,
            String status,
            String cgroup,
            List<String> contextHistory,
            long cgroupQuota,
            long cgroupConsumed,
            double cgroupSoftLimitRatio,
            String agentRoot,
            int gasLimit,
            int gasUsed,
            long timestamp
    ) {}

    /**
     * 将进程状态导出为 Base64 编码的 JSON 快照
     */
    public String exportSnapshot(AgentTask task, CgroupNode cgroup, String agentRoot) {
        try {
            ProcessSnapshot snapshot = new ProcessSnapshot(
                    task.pid(),
                    task.status().name(),
                    task.cgroup(),
                    new ArrayList<>(task.contextHistory()),
                    cgroup != null ? cgroup.tokenQuota() : 0,
                    cgroup != null ? cgroup.tokenConsumed() : 0,
                    cgroup != null ? cgroup.softLimitRatio() : 0.8,
                    agentRoot != null ? agentRoot : "/",
                    task.gasLimit(),
                    task.gasUsed(),
                    System.currentTimeMillis()
            );

            String json = OBJECT_MAPPER.writeValueAsString(snapshot);
            String base64 = Base64.getEncoder().encodeToString(json.getBytes());

            System.out.printf("  📸 [Live Migration] Process #%d serialized to snapshot (%d bytes, %d history entries)%n",
                    task.pid(), json.length(), snapshot.contextHistory().size());
            System.out.printf("  📸 [Live Migration] Cgroup: quota=%d, consumed=%d, agentRoot=%s%n",
                    snapshot.cgroupQuota(), snapshot.cgroupConsumed(), snapshot.agentRoot());
            log.info("[Live Migration] Exported snapshot: pid={}, historySize={}, jsonLen={}, base64Len={}",
                    task.pid(), snapshot.contextHistory().size(), json.length(), base64.length());

            return base64;
        } catch (Exception e) {
            throw new RuntimeException("Snapshot export failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Base64 快照恢复进程
     */
    public void restoreSnapshot(String base64Snapshot) {
        try {
            byte[] jsonBytes = Base64.getDecoder().decode(base64Snapshot);
            ProcessSnapshot snapshot = OBJECT_MAPPER.readValue(jsonBytes, ProcessSnapshot.class);

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.printf("  ║  🔄 [Live Migration] Resurrecting agent #%d from snapshot  ║%n", snapshot.pid());
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
            System.out.printf("  🔄 [Live Migration] Original status: %s, history: %d entries%n",
                    snapshot.status(), snapshot.contextHistory().size());
            System.out.printf("  🔄 [Live Migration] Restoring Cgroup: quota=%d, consumed=%d%n",
                    snapshot.cgroupQuota(), snapshot.cgroupConsumed());
            log.info("[Live Migration] Restoring snapshot: pid={}, status={}, historySize={}",
                    snapshot.pid(), snapshot.status(), snapshot.contextHistory().size());

            // 恢复 Cgroup 配额
            CgroupManager cgroupManager = CgroupManager.instance();
            String cgroupName = "agent_" + snapshot.pid();
            CgroupNode cgroupNode = cgroupManager.getNode(cgroupName);
            if (cgroupNode == null) {
                cgroupNode = cgroupManager.createNode(cgroupName, snapshot.cgroupQuota(), "agents");
            } else {
                cgroupNode.setTokenQuota(snapshot.cgroupQuota());
            }
            // 恢复消耗量：通过 consumeTokens 补齐到快照时的 consumed
            long currentConsumed = cgroupNode.tokenConsumed();
            long delta = snapshot.cgroupConsumed() - currentConsumed;
            if (delta > 0) {
                cgroupNode.consumeTokens(delta);
            }

            // 创建新 AgentTask
            AgentTask restoredTask = new AgentTask(
                    snapshot.pid(),
                    AgentTask.TaskStatus.READY,
                    snapshot.cgroup(),
                    "/dev/null",
                    "/dev/null",
                    new ArrayList<>()
            );
            restoredTask.setGasLimit(snapshot.gasLimit());
            restoredTask.setGasUsed(snapshot.gasUsed());

            // 恢复 contextHistory
            List<String> history = snapshot.contextHistory();
            for (String entry : history) {
                restoredTask.appendHistory(entry);
            }

            // 获取 TaskScheduler 并 spawn
            TaskScheduler scheduler = getTaskScheduler();
            if (scheduler == null) {
                throw new RuntimeException("TaskScheduler not available for snapshot restoration");
            }

            List<String> restoredHistory = new ArrayList<>(history);
            scheduler.spawn(restoredTask, () -> {
                System.out.printf("  ✅ [Live Migration] Agent #%d resurrected! Restored %d context entries%n",
                        snapshot.pid(), restoredHistory.size());
                log.info("[Live Migration] Agent #{} alive with {} restored history entries",
                        snapshot.pid(), restoredHistory.size());
            }, snapshot.agentRoot());

            System.out.printf("  ✅ [Live Migration] Process #%d spawned from snapshot, Cgroup restored%n", snapshot.pid());

        } catch (Exception e) {
            System.err.printf("  💀 [Live Migration] FATAL: Snapshot restoration failed: %s%n", e.getMessage());
            log.error("[Live Migration] Restoration failed", e);
            throw new RuntimeException("Snapshot restoration failed: " + e.getMessage(), e);
        }
    }

    /**
     * 仅反序列化快照，不执行恢复（用于检查/审计）
     */
    public ProcessSnapshot inspectSnapshot(String base64Snapshot) {
        try {
            byte[] jsonBytes = Base64.getDecoder().decode(base64Snapshot);
            return OBJECT_MAPPER.readValue(jsonBytes, ProcessSnapshot.class);
        } catch (Exception e) {
            throw new RuntimeException("Snapshot inspection failed: " + e.getMessage(), e);
        }
    }

    private volatile TaskScheduler taskSchedulerRef;

    public void configureTaskScheduler(TaskScheduler scheduler) {
        this.taskSchedulerRef = scheduler;
    }

    private TaskScheduler getTaskScheduler() {
        return taskSchedulerRef;
    }
}
