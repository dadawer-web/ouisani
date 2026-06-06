package com.ouisani.aios.core.memory.providers;

/**
 * Top-level interface for all Memory Providers in the AIOS kernel.
 * <p>
 * Every memory backend (TokenZRAM, Mem0, Zep, etc.) must implement
 * this interface, enabling the {@link com.ouisani.aios.core.memory.MemoryManager}
 * to swap providers at runtime based on the VFS registry configuration.
 * <p>
 * Analogous to Linux's {@code address_space_operations} — a uniform
 * contract that abstracts away the underlying storage medium.
 */
public interface MemoryProvider {

    /**
     * Store a memory entry for the given agent.
     *
     * @param agentId       the agent identifier
     * @param memoryContent the content to store
     * @return {@code true} if stored successfully
     */
    boolean store(String agentId, String memoryContent);

    /**
     * Retrieve memories relevant to the given query.
     *
     * @param agentId the agent identifier
     * @param query   the semantic query
     * @return the retrieved memory content (may be empty)
     */
    String retrieve(String agentId, String query);

    /**
     * Clear all memories for the given agent.
     *
     * @param agentId the agent identifier
     */
    void clear(String agentId);

    /**
     * Return the provider name for logging and registry identification.
     */
    String providerName();
}
