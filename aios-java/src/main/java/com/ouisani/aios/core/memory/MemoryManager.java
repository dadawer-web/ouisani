package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.config.SemanticRegistry;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.TokenZramProvider;
import com.ouisani.aios.drivers.memory.Mem0Provider;
import com.ouisani.aios.drivers.memory.ZepProvider;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.syscall.schema.MemoryPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆子系统管理器 — AIOS 内核所有记忆操作的统一调度中心。
 * <p>
 * 类比 Linux 的 VFS（虚拟文件系统）统一抽象层：不同文件系统
 * （ext4, xfs, tmpfs）通过 VFS 提供统一的文件操作接口，
 * 上层代码无需关心底层实现。
 * <p>
 * MemoryManager 做同样的事情：不同的记忆后端（TokenZRAM、Mem0、Zep）
 * 通过 {@link MemoryProvider} 接口提供统一的 store/retrieve/clear 操作，
 * AIOS 内核的 syscall 层只需调用 MemoryManager，无需关心底层是
 * 本地压缩存储还是云端向量数据库。
 * <p>
 * 启动时从 VFS 语义注册表 ({@code /proc/registry}) 读取配置：
 * <pre>
 *   HKEY_LOCAL_AIOS/Memory/Provider = "TokenZRAM" | "Mem0" | "Zep"
 * </pre>
 * 默认使用 {@link TokenZramProvider}。支持运行时热切换后端。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux</th><th>AIOS MemoryManager</th><th>说明</th></tr>
 *   <tr><td>VFS</td><td>MemoryManager</td><td>统一抽象层</td></tr>
 *   <tr><td>文件系统驱动</td><td>MemoryProvider</td><td>后端实现</td></tr>
 *   <tr><td>mount</td><td>switchProvider()</td><td>切换后端</td></tr>
 * </table>
 *
 * @see MemoryProvider
 * @see TokenZramProvider
 */
public final class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private static final String REGISTRY_KEY = "HKEY_LOCAL_AIOS/Memory/Provider";

    private static final class Holder {
        static final MemoryManager INSTANCE = new MemoryManager();
    }

    public static MemoryManager getInstance() {
        return Holder.INSTANCE;
    }

    private volatile MemoryProvider currentProvider;

    private MemoryManager() {
        initializeProvider();
    }

    /**
     * 从 VFS 语义注册表读取配置并选择对应的记忆后端。
     * 未配置时默认使用 TokenZramProvider。
     */
    private void initializeProvider() {
        String providerName = SemanticRegistry.instance().getValue(REGISTRY_KEY);

        if (providerName != null) {
            currentProvider = switch (providerName.trim()) {
                case "Mem0" -> new Mem0Provider();
                case "Zep"  -> new ZepProvider();
                default     -> new TokenZramProvider();
            };
        } else {
            currentProvider = new TokenZramProvider();
        }

        log.info("[Memory Subsystem] Unified Memory Provider initialized. Current backend: {}",
                currentProvider.providerName());
        System.out.println("[Memory Subsystem] Unified Memory Provider initialized. Current backend: "
                + currentProvider.providerName() + ".");
    }

    /**
     * 获取当前活跃的记忆后端。
     */
    public MemoryProvider currentProvider() {
        return currentProvider;
    }

    /**
     * 运行时切换记忆后端 — 无需重启内核即可热替换。
     *
     * @param provider 新的记忆后端
     */
    public void switchProvider(MemoryProvider provider) {
        MemoryProvider old = this.currentProvider;
        this.currentProvider = provider;
        log.info("[Memory Subsystem] Provider switched: {} → {}", old.providerName(), provider.providerName());
        System.out.println("[Memory Subsystem] Provider switched: " + old.providerName()
                + " → " + provider.providerName());
    }

    /**
     * 处理记忆系统调用 — 将 MemoryPayload 路由到当前后端的对应方法。
     *
     * @param payload 记忆系统调用的类型化载荷
     * @return 系统调用响应
     */
    public SyscallResponse processMemorySyscall(MemoryPayload payload) {
        if (payload == null) {
            return SyscallResponse.fail("MemoryPayload must not be null");
        }

        String operation = payload.operation();
        log.info("[MemoryManager] Processing syscall: operation='{}', provider='{}'",
                operation, currentProvider.providerName());

        return switch (operation) {
            case "store" -> {
                boolean ok = currentProvider.store("default", payload.memoryContent());
                yield ok
                        ? SyscallResponse.ok("Memory stored via " + currentProvider.providerName())
                        : SyscallResponse.fail("Memory store failed via " + currentProvider.providerName());
            }
            case "retrieve" -> {
                String result = currentProvider.retrieve("default", payload.query());
                yield SyscallResponse.ok(result);
            }
            case "delete" -> {
                currentProvider.clear("default");
                yield SyscallResponse.ok("Memory cleared via " + currentProvider.providerName());
            }
            default -> SyscallResponse.fail("Unknown memory operation: " + operation);
        };
    }

    /**
     * 处理带 Agent 身份的记忆系统调用 — 支持多 Agent 隔离存储。
     */
    public SyscallResponse processMemorySyscall(String agentId, MemoryPayload payload) {
        if (payload == null) {
            return SyscallResponse.fail("MemoryPayload must not be null");
        }

        String operation = payload.operation();
        log.info("[MemoryManager] Processing syscall: agent='{}', operation='{}', provider='{}'",
                agentId, operation, currentProvider.providerName());

        return switch (operation) {
            case "store" -> {
                boolean ok = currentProvider.store(agentId, payload.memoryContent());
                yield ok
                        ? SyscallResponse.ok("Memory stored via " + currentProvider.providerName())
                        : SyscallResponse.fail("Memory store failed via " + currentProvider.providerName());
            }
            case "retrieve" -> {
                String result = currentProvider.retrieve(agentId, payload.query());
                yield SyscallResponse.ok(result);
            }
            case "delete" -> {
                currentProvider.clear(agentId);
                yield SyscallResponse.ok("Memory cleared via " + currentProvider.providerName());
            }
            default -> SyscallResponse.fail("Unknown memory operation: " + operation);
        };
    }
}
