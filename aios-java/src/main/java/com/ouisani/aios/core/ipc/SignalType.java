package com.ouisani.aios.core.ipc;

/**
 * 语义 POSIX 信号类型 — AIOS Agent 进程的硬件中断级 IPC 机制。
 * <p>
 * 信号是 AIOS 中开销极低、非阻塞的 IPC 机制，可以中断 Agent 的当前操作
 * 而无需轮询。
 *
 * <h3>OS 类比: POSIX Signals</h3>
 * Linux 的信号 (signal) 是进程间通信的最底层机制：
 * SIGKILL 终止进程、SIGINT 中断操作、SIGUSR1 用户自定义。
 * AIOS 的信号将此模型提升到语义级别，增加了 SIG_CONTEXT_UPDATE（共享潜意识更新）、
 * SIGIO（UI 交互事件）、SIG_TICK（系统时钟节拍）等 AIOS 专属信号。
 *
 * <h3>信号层级：</h3>
 * <ul>
 *   <li>{@link #SIGTERM} — 终止：强制终止 Agent</li>
 *   <li>{@link #SIGINT}  — 中断：优雅中断当前操作</li>
 *   <li>{@link #SIGUSR1} — 用户信号：向 LLM Prompt 注入系统中断</li>
 *   <li>{@link #SIG_CONTEXT_UPDATE} — 潜意识更新：共享上下文已变更</li>
 * </ul>
 *
 * <h3>SIG_CONTEXT_UPDATE: 神经中断</h3>
 * 这是 AIOS "共享内存 + 硬件中断" IPC 模型的基石。当 Agent A 写入
 * SemanticMemoryBlock 后，向 Agent B 发送 SIG_CONTEXT_UPDATE。
 * Agent B 的信号处理器读取更新的 Block — <b>零轮询、零消息传递、零文本复制</b>。
 * <p>
 * 这类似于多核 CPU 的缓存一致性中断：当一个核心修改了缓存行，
 * 其他核心收到失效信号并重新加载数据。
 *
 * @see SignalInterceptor
 * @see SharedMemoryManager
 */
public enum SignalType {

    /** 终止：强制终止 Agent（抛出 InterruptedException） */
    SIGTERM,

    /** 中断：优雅中断当前操作 */
    SIGINT,

    /** 用户信号 1：向下一个 Prompt 注入系统中断 */
    SIGUSR1,

    /**
     * 上下文更新：共享潜意识已被修改。
     * <p>
     * 由写入 Agent（如 PmAgent）在更新 SemanticMemoryBlock 后发送。
     * 接收 Agent（如 CoderAgent）应从 SharedMemoryManager 读取更新的 Block，
     * 无需轮询或消息传递。
     * <p>
     * 信号携带元数据（哪个 Block 被更新、新版本号），
     * 存储在 AgentTask 的信号元数据 Map 中。
     */
    SIG_CONTEXT_UPDATE,

    /**
     * I/O 就绪：有 UI 交互事件待处理。
     * <p>
     * 由 {@link com.ouisani.aios.vfs.GuiActionNode} 在用户点击按钮或输入时发送。
     * Agent 应从 {@code /dev/gui/action} 读取待处理的操作。
     * <p>
     * 类比 POSIX SIGIO：内核通知进程某个文件描述符上有 I/O 可用，
     * 无需进程轮询。
     */
    SIGIO,

    /**
     * 系统节拍：硬件时钟晶振触发的节拍中断。
     * <p>
     * 由 {@link com.ouisani.aios.core.tick.SystemTickGenerator} 在每个时钟周期
     * （默认 60 秒）触发。类比 ARM 的 SysTick 定时器或 x86 的 LAPIC 定时器中断 —
     * 驱动所有时间相关的内核子系统的心跳：
     * <ul>
     *   <li>记忆衰减 — SemanticCacheManager 应用艾宾浩斯曲线衰减</li>
     *   <li>调度器抢占 — 时间片记账</li>
     *   <li>节拍睡眠唤醒 — 等待节拍数的 Agent 被唤醒</li>
     * </ul>
     * <p>
     * 与其他信号不同，SIG_TICK 是<b>广播</b>信号 — 发送给所有活跃 Agent，
     * 并在 EventBus 上发布为 "sig_tick" 事件。
     *
     * @see com.ouisani.aios.core.tick.SystemTickGenerator
     */
    SIG_TICK,

    /**
     * 闹钟：之前注册的定时器已到期。
     * <p>
     * 由 {@link com.ouisani.aios.core.tick.TickSleepRegistry} 在 Agent 的
     * 睡眠定时器到期时发送。类比 POSIX SIGALRM / alarm() — Agent 调用了
     * {@code sys_nanosleep(tickCount)}，请求的节拍数已过。
     * <p>
     * 与 {@link #SIG_TICK}（广播）不同，SIG_ALRM 是<b>定向</b>信号 —
     * 只有正在睡眠的 Agent 才会收到。
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
