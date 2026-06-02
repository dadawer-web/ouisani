package com.ouisani.aios.vfs;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;

import java.util.Map;
import java.util.function.Supplier;

public non-sealed class ProcFsNode implements VfsNode {

    private final String path;
    private final Supplier<String> reader;
    private int ownerUid;
    private int permissions;

    public ProcFsNode(String path, Supplier<String> reader) {
        this(path, reader, 0, 0444);
    }

    public ProcFsNode(String path, Supplier<String> reader, int ownerUid, int permissions) {
        this.path = path;
        this.reader = reader;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    public static ProcFsNode agents(TaskScheduler scheduler) {
        return new ProcFsNode("/proc/agents", () -> readAgents(scheduler));
    }

    public static ProcFsNode cgroups() {
        return new ProcFsNode("/proc/cgroups", ProcFsNode::readCgroups);
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        return reader.get();
    }

    @Override
    public boolean write(String data) {
        throw new UnsupportedOperationException(
                "[ProcFS] /proc is read-only: cannot write to " + path);
    }

    private static String readAgents(TaskScheduler scheduler) {
        Map<Integer, AgentTask> tasks = scheduler.activeTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"agents\":[");
        boolean first = true;
        for (Map.Entry<Integer, AgentTask> entry : tasks.entrySet()) {
            AgentTask t = entry.getValue();
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"pid\":").append(t.pid()).append(",")
              .append("\"status\":\"").append(t.status()).append("\",")
              .append("\"cgroup\":\"").append(t.cgroup()).append("\",")
              .append("\"type\":\"").append(t.type()).append("\",")
              .append("\"gasUsed\":").append(t.gasUsed()).append(",")
              .append("\"gasLimit\":").append(t.gasLimit())
              .append("}");
        }
        sb.append("],");
        TaskScheduler.SchedulerStats stats = scheduler.stats();
        sb.append("\"stats\":{")
          .append("\"totalSpawned\":").append(stats.totalSpawned()).append(",")
          .append("\"totalCompleted\":").append(stats.totalCompleted()).append(",")
          .append("\"totalCancelled\":").append(stats.totalCancelled()).append(",")
          .append("\"activeCount\":").append(stats.activeCount())
          .append("}}");
        return sb.toString();
    }

    private static String readCgroups() {
        CgroupManager mgr = CgroupManager.instance();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cgroups\":[");
        boolean first = true;
        for (String name : mgr.nodeNames()) {
            CgroupNode node = mgr.getNode(name);
            if (node == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"name\":\"").append(node.name()).append("\",")
              .append("\"quota\":").append(node.tokenQuota()).append(",")
              .append("\"consumed\":").append(node.tokenConsumed()).append(",")
              .append("\"remaining\":").append(node.tokenRemaining()).append(",")
              .append("\"parent\":").append(node.parent() != null
                      ? "\"" + node.parent().name() + "\"" : "null")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
