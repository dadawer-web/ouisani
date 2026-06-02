package com.ouisani.aios.user.container;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import org.graalvm.polyglot.Value;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ContainerRuntime {

    private final AtomicInteger pidSeq = new AtomicInteger(1000);
    private final ConcurrentHashMap<String, ContainerContext> containers = new ConcurrentHashMap<>();
    private final GraalWasmSandbox sandbox;
    private final TaskScheduler scheduler;

    public ContainerRuntime(TaskScheduler scheduler) {
        this.scheduler = scheduler;
        this.sandbox = new GraalWasmSandbox();
        this.sandbox.initContext();
    }

    public void runContainer(String containerId, AgentImageConfig config) {
        if (containers.containsKey(containerId)) {
            throw new IllegalStateException(
                    "[Container Runtime] Container '" + containerId + "' already exists");
        }

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Docker Engine] Starting container '%s'%n", containerId);
        System.out.printf("  ║  Base Image  : %s%n", config.baseImage());
        System.out.printf("  ║  Token Limit : %d%n", config.tokenLimit());
        System.out.printf("  ║  WASM Path   : %s%n", config.wasmPath());
        System.out.printf("  ║  Entrypoint  : %s%n", config.entrypoint());
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        CgroupNode cgroupNode = CgroupManager.instance().createNode(
                containerId, config.tokenLimit(), "agents");
        System.out.printf("  ├─ [Cgroup] Created '%s' with quota=%d (parent=agents)%n",
                containerId, config.tokenLimit());

        String containerRoot = "/containers/" + containerId;
        VfsManager.instance().createContainerNamespace(pidSeq.incrementAndGet());
        System.out.printf("  ├─ [Namespace] Root path: %s%n", containerRoot);

        for (Map.Entry<String, String> mount : config.volumeMounts().entrySet()) {
            String hostPath = mount.getKey();
            String containerPath = mount.getValue();
            Optional<VfsNode> hostNode = VfsManager.instance().resolve(hostPath);
            if (hostNode.isPresent()) {
                String parentDir = containerPath.substring(0, containerPath.lastIndexOf('/'));
                String nodeName = containerPath.substring(containerPath.lastIndexOf('/') + 1);
                VfsManager.instance().mount(parentDir, nodeName, hostNode.get());
            } else {
                System.out.printf("  ├─ [Mount] Host path '%s' not found in VFS, skipping%n", hostPath);
            }
            System.out.printf("  ├─ [Mount] %s -> %s%n", hostPath, containerPath);
        }

        int pid = pidSeq.incrementAndGet();
        AgentTask task = new AgentTask(pid, AgentTask.TaskStatus.READY,
                containerId, "/dev/null", "/dev/null", java.util.List.of());

        ContainerContext ctx = new ContainerContext(containerId, containerRoot, cgroupNode, task);
        containers.put(containerId, ctx);

        scheduler.spawn(task, () -> {
            try {
                VfsManager.AGENT_ROOT.set(containerRoot);
                CgroupManager.CURRENT_CGROUP.set(cgroupNode);
                System.out.printf("  ├─ [Namespace] AGENT_ROOT locked to %s%n", containerRoot);
                System.out.printf("  ├─ [Cgroup] Bound to '%s' (quota=%d)%n",
                        containerId, config.tokenLimit());

                if (config.wasmPath() != null && config.entrypoint() != null) {
                    System.out.printf("  ├─ [WASM] Loading bytecode from %s%n", config.wasmPath());
                    System.out.printf("  ├─ [WASM] Executing entrypoint '%s'...%n", config.entrypoint());

                    byte[] wasmBytes = loadWasmBytes(config.wasmPath());
                    Value result = sandbox.execute(wasmBytes, config.entrypoint());

                    System.out.printf("  └─ [WASM] Execution complete. Result: %s%n", result);
                } else {
                    System.out.println("  └─ [WASM] No entrypoint specified, container idle");
                }
            } finally {
                VfsManager.AGENT_ROOT.remove();
                System.out.printf("  ── [Namespace] AGENT_ROOT cleaned up for '%s'%n", containerId);
            }
        }, containerRoot);

        System.out.printf("  ✓ [Docker Engine] Container '%s' started (PID=%d)%n%n", containerId, pid);
    }

    private byte[] loadWasmBytes(String wasmPath) {
        Optional<VfsNode> node = VfsManager.instance().resolve(wasmPath);
        if (node.isPresent()) {
            String content = node.get().read();
            if (content != null && !content.isEmpty()) {
                return content.getBytes();
            }
        }
        System.out.println("  ⚠ [WASM] Path not found in VFS, using mock bytecode (returns 42)");
        return MOCK_WASM_42;
    }

    public void stopContainer(String containerId) {
        ContainerContext ctx = containers.remove(containerId);
        if (ctx == null) {
            System.out.printf("  ⚠ [Docker Engine] Container '%s' not found%n", containerId);
            return;
        }
        CgroupManager.instance().removeNode(containerId);
        System.out.printf("  ■ [Docker Engine] Container '%s' stopped and cleaned up%n", containerId);
    }

    public ContainerContext getContainer(String containerId) {
        return containers.get(containerId);
    }

    public Set<String> runningContainers() {
        return containers.keySet();
    }

    private static final byte[] MOCK_WASM_42 = new byte[]{
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
            0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
            0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
            0x04, 0x00, 0x41, 0x2a, 0x0b
    };

    public record ContainerContext(
            String containerId,
            String rootPath,
            CgroupNode cgroup,
            AgentTask task
    ) {}
}
