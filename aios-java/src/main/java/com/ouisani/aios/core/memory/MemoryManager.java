package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.config.SemanticRegistry;
import com.ouisani.aios.core.memory.providers.Mem0Provider;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.TokenZramProvider;
import com.ouisani.aios.core.memory.providers.ZepProvider;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.syscall.schema.MemoryPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory Subsystem Manager — the unified dispatch center for all
 * memory operations in the AIOS kernel.
 * <p>
 * Reads the provider configuration from the VFS semantic registry
 * ({@code /proc/registry}) at startup. If no provider is configured,
 * defaults to the native {@link TokenZramProvider}.
 * <p>
 * Supported registry key:
 * <pre>
 *   HKEY_LOCAL_AIOS/Memory/Provider = "TokenZRAM" | "Mem0" | "Zep"
 * </pre>
 * <p>
 * All memory syscalls ({@code memory.store}, {@code memory.retrieve},
 * {@code memory.delete}) are routed through this manager to the
 * currently active {@link MemoryProvider}.
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
     * Read the VFS semantic registry and select the appropriate provider.
     * Falls back to TokenZramProvider if no configuration is found.
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
     * Get the currently active memory provider.
     */
    public MemoryProvider currentProvider() {
        return currentProvider;
    }

    /**
     * Switch the memory provider at runtime.
     * <p>
     * This enables hot-swapping memory backends without restarting the kernel.
     *
     * @param provider the new memory provider
     */
    public void switchProvider(MemoryProvider provider) {
        MemoryProvider old = this.currentProvider;
        this.currentProvider = provider;
        log.info("[Memory Subsystem] Provider switched: {} → {}", old.providerName(), provider.providerName());
        System.out.println("[Memory Subsystem] Provider switched: " + old.providerName()
                + " → " + provider.providerName());
    }

    /**
     * Process a memory syscall from the kernel dispatcher.
     * <p>
     * Routes the {@link MemoryPayload} to the appropriate method on
     * the current {@link MemoryProvider} based on the operation type.
     *
     * @param payload the typed memory syscall payload
     * @return the syscall response
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
     * Process a memory syscall with explicit agent identity.
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
