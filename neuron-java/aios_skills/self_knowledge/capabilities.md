# AIOS Capabilities

## What AIOS Can Do

### Workflow Orchestration
- Compile natural language to DAG topology
- Execute nodes in parallel (virtual threads)
- Conditional routing (skip nodes based on conditions)
- Frozen cache (reuse completed node outputs)
- Iteration nodes (loop over arrays)

### Agent Collaboration
- Multi-agent task delegation via TeamRegistry
- Actor pattern messaging (TASK_ASSIGN, REPLY, etc.)
- Concurrent agent execution in virtual threads

### File Operations
- VFS with namespace isolation
- Physical workspace mapping
- Path sanitization and traversal prevention
- VSS snapshots for crash recovery

### LLM Operations
- Multi-provider routing (P_CORE/E_CORE)
- Turbo Boost for quality upgrade
- Noop fallback for degraded mode
- Rate limiting and API key rotation

### Self-Healing
- 11-layer recovery strategy chain
- Circuit breaker with cooldown
- Error classification and targeted recovery
- JSON parse error recovery
- Context window recovery

### Memory
- 3-tier history compression (recent/mid/old)
- Memory consolidation (MERGE/REPLACE/UPDATE/SKIP)
- LRU cache with expiration
- Persistent memory in VFS /memories/

### Tools
- Bash execution
- File read/write/edit
- Code execution (Python via bash)
- Dynamic tool mounting
- Parallel tool execution
