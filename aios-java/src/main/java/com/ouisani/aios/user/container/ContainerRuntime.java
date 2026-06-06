package com.ouisani.aios.user.container;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.crash.SemanticCrashAnalyzer;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.sandbox.DockerSandboxProvider;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.sandbox.SandboxProvider;
import com.ouisani.aios.core.security.ImpersonationContext;
import com.ouisani.aios.core.security.SecurityToken;
import com.ouisani.aios.vfs.MutableFileNode;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 隔离的容器运行时 — AIOS 的 Docker Engine。
 * <p>
 * 当调用 {@code ContainerRuntime.run(containerId, config)} 时，
 * 不在全局 OS 空间中直接运行 Agent，而是为该 Agent 创建一个全新的
 * {@link ContainerContext}，实现完全隔离的沙箱运行环境。
 *
 * <h3>隔离维度 (类比 Docker 的 Namespace)</h3>
 * <table>
 *   <tr><th>Docker Namespace</th><th>AIOS 隔离机制</th><th>说明</th></tr>
 *   <tr><td>PID Namespace</td><td>独立 PID + AgentTask</td><td>每个容器有独立的进程空间</td></tr>
 *   <tr><td>Mount Namespace</td><td>VFS chroot</td><td>容器只能看到自己的文件系统</td></tr>
 *   <tr><td>Network Namespace</td><td>NETWORK 组 (IPC 隔离)</td><td>同组 Agent 可通信，跨组隔离</td></tr>
 *   <tr><td>User Namespace</td><td>SecurityToken + ImpersonationContext</td><td>权限降级与隔离</td></tr>
 *   <tr><td>Cgroup</td><td>CgroupManager Token 配额</td><td>资源限制</td></tr>
 * </table>
 *
 * <h3>容器启动流程</h3>
 * <pre>
 * 1. 创建 CgroupNode (Token 配额)
 * 2. 创建 VFS Namespace (chroot 隔离)
 * 3. 挂载知识库和存储卷
 * 4. 预加载插件 (sys_insmod)
 * 5. 注入 PERSONA (系统提示词)
 * 6. 创建 IPC 桥 (NETWORK 组)
 * 7. 绑定 SecurityToken (权限降级)
 * 8. 启动 Agent 虚拟线程
 * </pre>
 */
public class ContainerRuntime {

    private static final Logger log = LoggerFactory.getLogger(ContainerRuntime.class);

    private final AtomicInteger pidSeq = new AtomicInteger(1000);
    private final ConcurrentHashMap<String, ContainerContext> containers = new ConcurrentHashMap<>();
    private final GraalWasmSandbox graalSandbox;
    private final TaskScheduler scheduler;

    /** 网络组 → 组内容器 ID 列表 (IPC 隔离) */
    private final ConcurrentHashMap<String, Set<String>> networkGroups = new ConcurrentHashMap<>();

    /** 默认 Token 配额（未指定 LIMIT_TOKENS 时使用） */
    private static final long DEFAULT_TOKEN_LIMIT = 50_000;

    public ContainerRuntime(TaskScheduler scheduler) {
        this.scheduler = scheduler;
        this.graalSandbox = new GraalWasmSandbox();
        this.graalSandbox.initContext();
    }

    // ════════════════════════════════════════════════════════════════
    //  核心方法: run — 启动一个隔离的 Agent 容器
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动一个隔离的 Agent 容器。
     * <p>
     * 类比 {@code docker run -d --name=xxx --memory=512m --network=bridge image}。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>创建 CgroupNode — 限制 Token 消耗</li>
     *   <li>创建 VFS Namespace — chroot 文件系统隔离</li>
     *   <li>挂载知识库和存储卷</li>
     *   <li>预加载插件 (sys_insmod)</li>
     *   <li>注入 PERSONA 系统提示词</li>
     *   <li>创建 IPC 桥 (NETWORK 组)</li>
     *   <li>绑定 SecurityToken</li>
     *   <li>启动 Agent 虚拟线程</li>
     * </ol>
     *
     * @param containerId 容器 ID（唯一标识）
     * @param config      镜像配置（由 AgentfileParser 解析生成）
     */
    public void run(String containerId, AgentImageConfig config) {
        if (containers.containsKey(containerId)) {
            throw new IllegalStateException(
                    "[Container] Container '" + containerId + "' already exists");
        }

        // ── 打印启动横幅 ──
        printRunBanner(containerId, config);

        // ── Step 1: 创建 CgroupNode (Token 配额) ──
        long tokenLimit = config.tokenLimit() > 0 ? config.tokenLimit() : DEFAULT_TOKEN_LIMIT;
        CgroupNode cgroupNode = CgroupManager.instance().createNode(
                containerId, tokenLimit, "agents");
        System.out.printf("  ├─ [Cgroup] Created '%s' quota=%d (parent=agents)%n", containerId, tokenLimit);

        // ── Step 2: 创建 VFS Namespace (chroot 隔离) ──
        int pid = pidSeq.incrementAndGet();
        String containerRoot = "/containers/agent_" + pid;
        VfsManager.instance().createContainerNamespace(pid);
        System.out.printf("  ├─ [Namespace] Root: %s (chroot isolated)%n", containerRoot);

        // ── Step 3: 挂载知识库 (COPY 指令) ──
        mountKnowledgeBases(containerId, pid, config);

        // ── Step 4: 挂载存储卷 (MOUNT 指令) ──
        mountVolumes(containerId, pid, config);

        // ── Step 5: 预加载插件 (RUN sys_insmod) ──
        preloadPlugins(containerId, config);

        // ── Step 6: 注入 PERSONA (系统提示词) ──
        injectPersona(containerId, pid, config);

        // ── Step 7: 创建 IPC 桥 (NETWORK 组) ──
        setupNetworkBridge(containerId, config);

        // ── Step 8: 创建 AgentTask 并启动 ──
        AgentTask task = new AgentTask(pid, AgentTask.TaskStatus.READY,
                containerId, "/dev/null", "/dev/null", List.of());

        // 将 PERSONA 注入到任务上下文历史
        if (config.persona() != null && !config.persona().isEmpty()) {
            task.appendHistory("[SYSTEM_PERSONA] " + config.persona());
        }
        // 将 ENTRYPOINT 注入
        if (config.entrypoint() != null) {
            task.appendHistory("[ENTRYPOINT] " + config.entrypoint());
        }

        ContainerContext ctx = new ContainerContext(
                containerId, containerRoot, cgroupNode, task,
                config.persona(), config.plugins(), config.networkGroup(),
                pid);
        containers.put(containerId, ctx);

        // ── Step 9: 在虚拟线程中启动容器 ──
        final String rootPath = containerRoot;
        scheduler.spawn(task, () -> {
            try {
                // chroot: 绑定容器的 VFS 根目录
                VfsManager.AGENT_ROOT.set(rootPath);
                // 绑定 Cgroup
                CgroupManager.CURRENT_CGROUP.set(cgroupNode);
                // 权限降级：以用户令牌运行（非内核级）
                SecurityToken containerToken = SecurityToken.userToken("container_" + containerId);

                System.out.printf("  ├─ [Namespace] AGENT_ROOT locked to %s%n", rootPath);
                System.out.printf("  ├─ [Cgroup] Bound to '%s' (quota=%d)%n", containerId, tokenLimit);
                System.out.printf("  ├─ [Security] Token level=%d (user)%n", containerToken.privilegeLevel());

                // 在 ImpersonationContext 中执行 — 权限降级
                ImpersonationContext.runAs(containerToken, () -> {
                    executeContainerLogic(containerId, config);
                });

            } catch (SecurityException e) {
                System.err.printf("  🚨 [Security] Container '%s' blocked by BpfManager: %s%n",
                        containerId, e.getMessage());
                SemanticCrashAnalyzer.instance().kernelPanic(containerId, e);
            } finally {
                VfsManager.AGENT_ROOT.remove();
                CgroupManager.CURRENT_CGROUP.remove();
                System.out.printf("  ── [Namespace] AGENT_ROOT cleaned up for '%s'%n", containerId);
            }
        }, rootPath);

        System.out.printf("  ✓ [Container] '%s' started (PID=%d, quota=%d, network=%s)%n%n",
                containerId, pid, tokenLimit,
                config.networkGroup() != null ? config.networkGroup() : "none");
    }

    // ════════════════════════════════════════════════════════════════
    //  容器启动子步骤
    // ════════════════════════════════════════════════════════════════

    /**
     * 挂载知识库 — 将 COPY 指令指定的文件向量化并挂载到容器的只读 VFS 节点。
     * <p>
     * 类比 Docker 的 COPY — 将构建上下文中的文件复制到镜像中。
     * 在 AIOS 中，知识库以只读 VFS 节点的形式挂载到容器的命名空间中。
     */
    private void mountKnowledgeBases(String containerId, int pid, AgentImageConfig config) {
        for (Map.Entry<String, String> mount : config.knowledgeMounts().entrySet()) {
            String srcPath = mount.getKey();
            String containerPath = mount.getValue();

            // 在容器命名空间下创建知识库节点
            String fullContainerPath = "/containers/agent_" + pid + containerPath;
            String nodeName = containerPath.substring(containerPath.lastIndexOf('/') + 1);
            String parentDir = fullContainerPath.substring(0, fullContainerPath.lastIndexOf('/'));

            // 尝试从宿主 VFS 读取源文件内容
            Optional<VfsNode> srcNode = VfsManager.instance().resolve(srcPath);
            if (srcNode.isPresent()) {
                // 挂载宿主节点到容器命名空间（只读）
                VfsManager.instance().mount(parentDir, nodeName + "_kb", srcNode.get());
                System.out.printf("  ├─ [Knowledge] %s → %s (read-only)%n", srcPath, containerPath);
            } else {
                // 源路径不存在，创建空的知识库节点
                MutableFileNode kbNode = new MutableFileNode(fullContainerPath);
                kbNode.write("[Knowledge Base: " + srcPath + " — content pending vectorization]");
                VfsManager.instance().mount(parentDir, nodeName + "_kb", kbNode);
                System.out.printf("  ├─ [Knowledge] %s → %s (empty, pending vectorization)%n", srcPath, containerPath);
            }
        }
    }

    /**
     * 挂载存储卷 — 将 MOUNT 指令指定的 VFS 路径挂载到容器内。
     * <p>
     * 类比 Docker 的 VOLUME — 声明挂载点。
     */
    private void mountVolumes(String containerId, int pid, AgentImageConfig config) {
        for (Map.Entry<String, String> mount : config.volumeMounts().entrySet()) {
            String hostPath = mount.getKey();
            String containerPath = mount.getValue();

            String fullContainerPath = "/containers/agent_" + pid + containerPath;
            Optional<VfsNode> hostNode = VfsManager.instance().resolve(hostPath);

            if (hostNode.isPresent()) {
                String nodeName = containerPath.substring(containerPath.lastIndexOf('/') + 1);
                String parentDir = fullContainerPath.substring(0, fullContainerPath.lastIndexOf('/'));
                VfsManager.instance().mount(parentDir, nodeName, hostNode.get());
                System.out.printf("  ├─ [Volume] %s → %s%n", hostPath, containerPath);
            } else {
                System.out.printf("  ├─ [Volume] %s → %s (host not found, skipped)%n", hostPath, containerPath);
            }
        }
    }

    /**
     * 预加载插件 — 执行 RUN sys_insmod 指令。
     * <p>
     * 类比 Docker 的 RUN apt-get install — 在构建时安装软件包。
     * 在容器启动时，将指定的插件模块加载到 Agent 的工具链中。
     */
    private void preloadPlugins(String containerId, AgentImageConfig config) {
        if (config.plugins().isEmpty()) return;

        PluginManager pm = PluginManager.getInstance();
        int loaded = 0;
        for (String pluginName : config.plugins()) {
            try {
                var tool = pm.insmodByName(containerId, pluginName);
                if (tool != null) {
                    loaded++;
                    System.out.printf("  ├─ [Plugin] sys_insmod %s → loaded (%s)%n",
                            pluginName, tool.type());
                } else {
                    System.out.printf("  ├─ [Plugin] sys_insmod %s → not found in catalog%n", pluginName);
                }
            } catch (Exception e) {
                System.out.printf("  ├─ [Plugin] sys_insmod %s → failed: %s%n", pluginName, e.getMessage());
            }
        }
        System.out.printf("  ├─ [Plugin] %d/%d plugins loaded for '%s'%n",
                loaded, config.plugins().size(), containerId);
    }

    /**
     * 注入 PERSONA — 将系统提示词写入容器的 VFS 节点。
     * <p>
     * 类比 Docker 的 ENV — 设置环境变量。
     * PERSONA 是 Agent 的"灵魂"，决定了它的行为模式。
     */
    private void injectPersona(String containerId, int pid, AgentImageConfig config) {
        if (config.persona() == null || config.persona().isEmpty()) return;

        // 将 PERSONA 写入容器的 /etc/persona 文件
        String personaPath = "/containers/agent_" + pid + "/etc/persona";
        try {
            MutableFileNode personaNode = new MutableFileNode(personaPath);
            personaNode.write(config.persona());
            VfsManager.instance().mount("/containers/agent_" + pid + "/etc", "persona", personaNode);
            System.out.printf("  ├─ [Persona] Injected (%d chars) → /etc/persona%n", config.persona().length());
        } catch (Exception e) {
            System.out.printf("  ├─ [Persona] Injection failed: %s%n", e.getMessage());
        }
    }

    /**
     * 创建 IPC 桥 — 设置 NETWORK 组。
     * <p>
     * 类比 Docker 的 --network=bridge — 指定网络模式。
     * 同一 NETWORK 组的 Agent 可以通过共享内存互相通信，
     * 不同组的 Agent 完全隔离。
     */
    private void setupNetworkBridge(String containerId, AgentImageConfig config) {
        String networkGroup = config.networkGroup();
        if (networkGroup == null || networkGroup.isEmpty()) {
            System.out.printf("  ├─ [Network] No network group (isolated mode)%n");
            return;
        }

        // 将容器加入网络组
        networkGroups.computeIfAbsent(networkGroup, k -> ConcurrentHashMap.newKeySet())
                .add(containerId);

        // 为网络组创建共享内存桥
        String shmKey = "net_bridge_" + networkGroup;
        try {
            SharedMemoryManager shmMgr = SharedMemoryManager.instance();
            if (shmMgr.getSegment(shmKey) == null) {
                shmMgr.getOrCreateSegment(shmKey);
                System.out.printf("  ├─ [Network] Created shared memory bridge: %s%n", shmKey);
            }
        } catch (Exception e) {
            System.out.printf("  ├─ [Network] Bridge setup skipped: %s%n", e.getMessage());
        }

        // 列出同组容器
        Set<String> peers = networkGroups.get(networkGroup);
        if (peers != null && peers.size() > 1) {
            System.out.printf("  ├─ [Network] Group '%s' peers: %s%n", networkGroup,
                    peers.stream().filter(id -> !id.equals(containerId)).toList());
        } else {
            System.out.printf("  ├─ [Network] Group '%s' (first member)%n", networkGroup);
        }
    }

    /**
     * 执行容器核心逻辑 — 在 chroot + ImpersonationContext 中运行。
     */
    private void executeContainerLogic(String containerId, AgentImageConfig config) {
        if (config.wasmPath() != null && config.entrypoint() != null) {
            SandboxProvider sandbox = selectSandbox(config);
            System.out.printf("  ├─ [Sandbox] Provider: %s%n", sandbox.providerName());
            System.out.printf("  ├─ [Sandbox] Loading: %s%n", config.wasmPath());
            System.out.printf("  ├─ [Sandbox] Entrypoint: %s%n", config.entrypoint());

            try {
                if (sandbox instanceof GraalWasmSandbox graal) {
                    byte[] wasmBytes = loadWasmBytes(config.wasmPath());
                    Value result = graal.execute(wasmBytes, config.entrypoint());
                    System.out.printf("  └─ [WASM] Complete. Result: %s%n", result);
                } else {
                    String code = loadCodeString(config.wasmPath());
                    String result = sandbox.executeCode(code, config.entrypoint());
                    String preview = result.length() > 500 ? result.substring(0, 500) + "..." : result;
                    System.out.printf("  └─ [Cloud] Output (%d chars): %s%n", result.length(), preview);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.printf("  ⚠ [Sandbox] Container '%s' interrupted%n", containerId);
            } catch (PolyglotException e) {
                System.err.printf("  🚨 [WASM] PolyglotException in '%s': %s%n", containerId, e.getMessage());
                SemanticCrashAnalyzer.instance().kernelPanic(containerId, e);
                throw e;
            } catch (Exception e) {
                System.err.printf("  🚨 [Sandbox] Execution failed in '%s': %s%n", containerId, e.getMessage());
                throw new RuntimeException(e);
            }
        } else {
            System.out.printf("  └─ [Agent] Container '%s' idle (no WASM entrypoint)%n", containerId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  容器管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 停止容器 — 清理 Cgroup、VFS Namespace、IPC 桥。
     * <p>
     * 类比 {@code docker stop <container>}。
     */
    public void stop(String containerId) {
        ContainerContext ctx = containers.remove(containerId);
        if (ctx == null) {
            System.out.printf("  ⚠ [Container] '%s' not found%n", containerId);
            return;
        }

        // 清理 Cgroup
        CgroupManager.instance().removeNode(containerId);

        // 清理 VFS Namespace
        VfsManager.instance().destroyContainerNamespace(ctx.pid());

        // 从网络组中移除
        if (ctx.networkGroup() != null) {
            Set<String> group = networkGroups.get(ctx.networkGroup());
            if (group != null) {
                group.remove(containerId);
                if (group.isEmpty()) {
                    networkGroups.remove(ctx.networkGroup());
                }
            }
        }

        // 清理插件上下文
        try {
            PluginManager pm = PluginManager.getInstance();
            for (String plugin : ctx.plugins()) {
                pm.rmmod(containerId, plugin);
            }
        } catch (Exception e) {
            log.warn("[Container] Plugin cleanup failed for '{}': {}", containerId, e.getMessage());
        }

        System.out.printf("  ■ [Container] '%s' stopped and cleaned up (PID=%d)%n", containerId, ctx.pid());
    }

    /**
     * 列出运行中的容器。
     * <p>
     * 类比 {@code docker ps}。
     */
    public List<ContainerContext> ps() {
        return List.copyOf(containers.values());
    }

    /**
     * 检查容器是否可以向目标发送 IPC 信号。
     * <p>
     * IPC 隔离规则：
     * <ul>
     *   <li>同一 NETWORK 组 → 允许通信</li>
     *   <li>不同 NETWORK 组 → 拒绝</li>
     *   <li>无 NETWORK 组 → 完全隔离</li>
     * </ul>
     */
    public boolean canCommunicate(String fromContainer, String toContainer) {
        ContainerContext from = containers.get(fromContainer);
        ContainerContext to = containers.get(toContainer);
        if (from == null || to == null) return false;

        String fromNet = from.networkGroup();
        String toNet = to.networkGroup();

        // 两者都没有网络组 → 隔离
        if (fromNet == null || toNet == null) return false;

        // 同一网络组 → 允许
        return fromNet.equals(toNet);
    }

    /**
     * 获取指定网络组内的所有容器 ID。
     */
    public Set<String> getNetworkPeers(String networkGroup) {
        return networkGroups.getOrDefault(networkGroup, Set.of());
    }

    public ContainerContext getContainer(String containerId) {
        return containers.get(containerId);
    }

    public Set<String> runningContainers() {
        return containers.keySet();
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    private SandboxProvider selectSandbox(AgentImageConfig config) {
        String baseImage = config.baseImage();
        if (baseImage != null && baseImage.startsWith("docker:")) {
            String dockerImage = baseImage.substring("docker:".length());
            System.out.printf("  ├─ [Cloud] FROM %s → DockerSandboxProvider(%s)%n", baseImage, dockerImage);
            return new DockerSandboxProvider(dockerImage);
        }
        return graalSandbox;
    }

    private byte[] loadWasmBytes(String wasmPath) {
        Optional<VfsNode> node = VfsManager.instance().resolve(wasmPath);
        if (node.isPresent()) {
            String content = node.get().read();
            if (content != null && !content.isEmpty()) return content.getBytes();
        }
        return MOCK_WASM_42;
    }

    private String loadCodeString(String path) {
        Optional<VfsNode> node = VfsManager.instance().resolve(path);
        if (node.isPresent()) {
            String content = node.get().read();
            if (content != null && !content.isEmpty()) return content;
        }
        return "print('AIOS Container - no code at " + path + "')";
    }

    private void printRunBanner(String containerId, AgentImageConfig config) {
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Container] Starting '%s'%n", containerId);
        System.out.printf("  ║  FROM        : %s%n", config.baseImage());
        if (config.persona() != null)
            System.out.printf("  ║  PERSONA     : %s%n",
                    config.persona().length() > 40 ? config.persona().substring(0, 40) + "..." : config.persona());
        if (!config.plugins().isEmpty())
            System.out.printf("  ║  PLUGINS     : %s%n", config.plugins());
        if (!config.knowledgeMounts().isEmpty())
            System.out.printf("  ║  KNOWLEDGE   : %d mounts%n", config.knowledgeMounts().size());
        System.out.printf("  ║  TOKEN_LIMIT : %d%n", config.tokenLimit() > 0 ? config.tokenLimit() : DEFAULT_TOKEN_LIMIT);
        if (config.networkGroup() != null)
            System.out.printf("  ║  NETWORK     : %s%n", config.networkGroup());
        System.out.printf("  ║  ENTRYPOINT  : %s%n", config.entrypoint());
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
    }

    private static final byte[] MOCK_WASM_42 = new byte[]{
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
            0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
            0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
            0x04, 0x00, 0x41, 0x2a, 0x0b
    };

    // ════════════════════════════════════════════════════════════════
    //  ContainerContext — 容器运行时上下文
    // ════════════════════════════════════════════════════════════════

    /**
     * 容器运行时上下文 — 一个运行中容器的完整状态。
     * <p>
     * 类比 Docker 的 Container Inspect 输出 — 包含容器的所有元数据。
     */
    public record ContainerContext(
            /** 容器 ID */
            String containerId,
            /** VFS chroot 根路径 */
            String rootPath,
            /** Cgroup 节点（Token 配额） */
            CgroupNode cgroup,
            /** Agent 任务 */
            AgentTask task,
            /** 系统提示词/人设 */
            String persona,
            /** 已加载的插件列表 */
            List<String> plugins,
            /** 网络组 */
            String networkGroup,
            /** 容器 PID */
            int pid
    ) {}
}
