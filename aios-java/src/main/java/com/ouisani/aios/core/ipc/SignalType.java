package com.ouisani.aios.core.ipc;

/**
 * Semantic POSIX signal types for AIOS Agent processes.
 * <p>
 * Signals are the hardware-interrupt-level IPC mechanism in AIOS.
 * They are near-zero-overhead, non-blocking, and can interrupt
 * an Agent's current operation without polling.
 * <p>
 * <b>Signal Hierarchy:</b>
 * <ul>
 *   <li>{@link #SIGTERM} — Kill: force-terminate the agent</li>
 *   <li>{@link #SIGINT}  — Interrupt: graceful interruption</li>
 *   <li>{@link #SIGUSR1} — User signal: inject system interrupt into LLM prompt</li>
 *   <li>{@link #SIG_CONTEXT_UPDATE} — Subconscious update: shared context has changed</li>
 * </ul>
 * <p>
 * <h3>SIG_CONTEXT_UPDATE: The Neural Interrupt</h3>
 * This signal is the cornerstone of AIOS's "shared memory + hardware
 * interrupt" IPC model. When Agent A writes to a SemanticMemoryBlock,
 * it sends SIG_CONTEXT_UPDATE to Agent B. Agent B's signal handler
 * reads the updated block — <b>zero polling, zero message passing,
 * zero text copying</b>.
 * <p>
 * This is the neural equivalent of a cache coherence interrupt in
 * a multi-core CPU: when one core modifies a cache line, the other
 * cores receive an invalidation signal and can reload the data.
 */
public enum SignalType {

    /** Terminate: force-kill the agent (throws InterruptedException). */
    SIGTERM,

    /** Interrupt: graceful interruption of the current operation. */
    SIGINT,

    /** User-defined signal 1: injects a system interrupt into the next prompt. */
    SIGUSR1,

    /**
     * Context Update: the shared subconscious has been modified.
     * <p>
     * Sent by a writer agent (e.g., PmAgent) after updating a
     * SemanticMemoryBlock. The receiving agent (e.g., CoderAgent)
     * should read the updated block from SharedMemoryManager
     * without polling or message passing.
     * <p>
     * The signal carries metadata about which block was updated
     * and the new version number, stored in the AgentTask's
     * signal metadata map.
     */
    SIG_CONTEXT_UPDATE,

    /**
     * I/O Possible: a UI interaction event is pending.
     * <p>
     * Sent by {@link com.ouisani.aios.vfs.GuiActionNode} when a user
     * clicks a button or types into an input on the frontend dashboard.
     * The Agent should read the pending action from
     * {@code /dev/gui/action} to handle the user interaction.
     * <p>
     * This is the AIOS equivalent of POSIX SIGIO: the kernel notifies
     * a process that I/O is possible on a file descriptor without
     * requiring the process to poll.
     */
    SIGIO,

    /**
     * System Tick: the hardware clock crystal has fired a tick interrupt.
     * <p>
     * Sent by {@link com.ouisani.aios.core.tick.SystemTickGenerator} on each
     * clock cycle (default: every 60 seconds). This is the AIOS equivalent
     * of ARM's SysTick timer or x86's LAPIC timer interrupt — the heartbeat
     * that drives all time-dependent kernel subsystems:
     * <ul>
     *   <li>Memory decay — SemanticCacheManager applies Ebbinghaus curve decay</li>
     *   <li>Scheduler preemption — time slice accounting</li>
     *   <li>Tick sleep wakeup — agents sleeping on a tick count are woken</li>
     * </ul>
     * <p>
     * Unlike other signals which are directed at a specific PID, SIG_TICK
     * is a <b>broadcast</b> signal — it is sent to ALL active agents and
     * published on the EventBus as a "sig_tick" event.
     *
     * @see com.ouisani.aios.core.tick.SystemTickGenerator
     */
    SIG_TICK,

    /**
     * Alarm: a previously registered timer has expired.
     * <p>
     * Sent by the {@link com.ouisani.aios.core.tick.TickSleepRegistry} when
     * an agent's sleep timer expires. This is the AIOS equivalent of POSIX
     * SIGALRM / alarm() — the agent called {@code sys_nanosleep(tickCount)}
     * and the requested number of ticks has elapsed.
     * <p>
     * Unlike {@link #SIG_TICK} which is broadcast to all agents, SIG_ALRM
     * is <b>directed</b> at a specific PID — only the sleeping agent receives it.
     *
     * @see com.ouisani.aios.core.tick.TickSleepRegistry
     */
    SIG_ALRM,

    /**
     * Segmentation Fault — 沙箱内代码发生了非法内存访问或运行时异常。
     * <p>
     * 当 {@link com.ouisani.aios.core.sandbox.GraalWasmSandbox} 中的 Ring 3
     * 代码发生除零、越界访问、内存超限等异常时，沙箱被销毁，
     * 向拥有该沙箱的 Agent 进程发送 SIGSEGV。
     * <p>
     * 类比 POSIX SIGSEGV：进程收到此信号后可以选择捕获处理或终止。
     *
     * @see com.ouisani.aios.core.sandbox.GraalWasmSandbox
     */
    SIGSEGV
}
