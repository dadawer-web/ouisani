# AIOS Architecture

## Core Components

### WorkflowEngine
- DAG execution engine using CompletableFuture + Java 21 Virtual Threads
- Layer middleware system (EventBusBridge, ExecutionLimits)
- Container directory isolation per workflow
- Supports iteration nodes with Semaphore concurrency control

### VfsManager
- Virtual file system with path tree and namespace isolation
- Physical workspace mapping (bind mount)
- VSS snapshot for crash consistency
- WAL journal recovery

### TeamRegistry
- Central mailbox system for agent communication
- Actor pattern with MailMessage protocol
- 5 message types: TASK_ASSIGN, STATUS_UPDATE, QUESTION, REPLY, POISON_PILL

### LlmRouter
- big.LITTLE architecture: P_CORE (flagship) / E_CORE (lightweight)
- Turbo Boost: auto-upgrade on E_CORE failure
- ComputeAffinity: REQUIRE_P_CORE, PREFER_P_CORE, AUTO, PREFER_E_CORE, REQUIRE_E_CORE

### RecoveryOrchestrator
- 11-layer recovery strategy chain
- Error classification: EMPTY_RESPONSE, PARSE_ERROR, EDIT_ERROR, TOOL_ERROR, etc.
- Circuit breaker: 5 consecutive failures → 5-minute cooldown

### QueryEngine
- Agent Loop: user input → LLM → tool parsing → tool execution → repeat
- DynamicToolBridge: auto-mount tools by query
- Parallel tool execution with CompletableFuture
- 3-tier history compression (recent/mid/old)

### TopologyCompiler
- Compiles natural language intent to DAG topology IR
- Two-stage: compile topology → validate blueprints
- Auto-generates missing blueprints via LLM
