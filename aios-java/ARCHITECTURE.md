# AIOS 架构图

> Agent = Process | Token = Memory | LLM = CPU | Context = Address Space | VFS = Filesystem | EventBus = IPC

---

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          Ring 3 — 用户空间 (User Space)                         │
│                                                                                 │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌──────────┐  ┌────────────┐  │
│  │  AiosShell │  │ OmniMother │  │ AutoMedic  │  │ 用户自定义│  │  HostRpa   │  │
│  │  (bash)    │  │   Agent    │  │   Agent    │  │   Agent   │  │  Manager   │  │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └────┬─────┘  └─────┬──────┘  │
│        │               │               │              │              │          │
│  ┌─────┴───────────────┴───────────────┴──────────────┴──────────────┴───────┐  │
│  │                         AiosSdk (libc/glibc)                              │  │
│  │   think() | query() | readFile() | writeFile() | spawn() | ...           │  │
│  └──────────────────────────────┬───────────────────────────────────────────┘  │
│                                 │ syscall                                      │
├─────────────────────────────────┼──────────────────────────────────────────────┤
│                          Ring 0 — 内核空间 (Kernel Space)                       │
│                                 │                                              │
│  ┌──────────────────────────────┴──────────────────────────────────────────┐   │
│  │                    SyscallDispatcher (syscall 分发表)                    │   │
│  │   dispatch(namespace.action, payload) → 权限检查 → 路由到子系统          │   │
│  └──┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬──────┘   │
│     │     │     │     │     │     │     │     │     │     │     │           │
│  ┌──┴──┐┌─┴───┐┌┴───┐┌┴────┐┌┴───┐┌┴────┐┌┴───┐┌┴────┐┌┴───┐┌┴────┐┌──┴──┐ │
│  │ LLM ││ VFS ││ IPC ││Task ││Mem ││Perm ││Hook ││Tele ││Net ││MCP  ││Trace│ │
│  │ CPU ││  FS ││Pipe ││Sched││MMU ││ Cap ││Hook ││Perf ││Net ││外设 ││strce│ │
│  └─────┘└─────┘└─────┘└─────┘└────┘└─────┘└─────┘└─────┘└────┘└─────┘└─────┘ │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                     SandboxProvider (沙箱隔离层)                          │   │
│  │            DockerSandboxProvider │ GraalWasmSandbox                       │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

```
┌───────────────────────────────────────────────────────────┐
│                        LlmRouter                          │
│                  (CPU 调度器 / Scheduler)                  │
│                                                           │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐      │
│  │   P-Core    │  │   E-Core    │  │  Numa Node   │      │
│  │  (GPT-4o)   │  │ (GPT-4o-   │  │  (本地模型)   │      │
│  │  复杂推理    │  │  mini)     │  │  低延迟       │      │
│  │  高成本      │  │  简单任务   │  │  零成本       │      │
│  └──────┬──────┘  └──────┬──────┘  └──────┬───────┘      │
│         └────────────────┼────────────────┘               │
│                    ┌─────┴──────┐                         │
│                    │ LlmProvider│  (指令集抽象)            │
│                    │ think()    │                         │
│                    │ embed()    │                         │
│                    └─────┬──────┘                         │
└──────────────────────────┼────────────────────────────────┘
                    ┌──────┴──────┐
           ┌────────┴───┐  ┌─────┴──────┐
           │  Ring 3     │  │  Ring 3    │
           │  OpenAI     │  │  DeepSeek  │  ← 可插拔驱动
           │  Adapter    │  │  Adapter   │    零内核修改
           └─────────────┘  └────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                          VfsManager                               │
│                   (虚拟文件系统 / VFS)                             │
│                                                                   │
│  mount() | unmount() | readText() | writeText() | exists()       │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │                     VfsNode (inode)                        │   │
│  │                                                            │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │   │
│  │  │Mutable   │ │  Pipe    │ │   Shm    │ │  Vector  │     │   │
│  │  │  File    │ │  Node    │ │  Node    │ │  Node    │     │   │
│  │  │(普通文件)│ │ (管道)   │ │(共享内存)│ │(向量存储)│     │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │   │
│  │  │  Http    │ │  ProcFs  │ │  Graph   │ │ Display  │     │   │
│  │  │  Node    │ │  Node    │ │  Node    │ │  Node    │     │   │
│  │  │(HTTP端点)│ │ (/proc)  │ │(知识图谱)│ │(/dev/fb0)│     │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │   │
│  │  │  Audio   │ │  Camera  │ │ Webhook  │ │WebSocket │     │   │
│  │  │  Node    │ │  Node    │ │  Node    │ │  Node    │     │   │
│  │  │(/dev/dsp)│ │(/dev/vd) │ │(事件触发)│ │(双向通道)│     │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                   │   │
│  │  │ Shadow   │ │Registry  │ │  Remote  │                   │   │
│  │  │  Copy    │ │   Fs     │ │  Device  │                   │   │
│  │  │(COW快照) │ │ (注册表) │ │(NFS挂载) │                   │   │
│  │  └──────────┘ └──────────┘ └──────────┘                   │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │          VfsJournal (WAL 写前日志 — 崩溃一致性)             │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  安全边界: FileReadTool / FileWriteTool / FileEditTool 只能操作 VFS │
│           永远不可能访问宿主机真实文件系统 (/etc/passwd 安全)       │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                          EventBus                                 │
│                 (进程间通信 / IPC Bus)                             │
│                                                                   │
│  broadcast(channel, message) → 所有订阅者收到通知                  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  系统频道                                                   │   │
│  │                                                            │   │
│  │  sys.crash.*        → 崩溃告警 (AutoMedic 监听)            │   │
│  │  sys.sandbox.bash.* → 沙箱输出 (替代 System.out)           │   │
│  │  sys.alert.*        → 系统警报 (前端大屏监听)               │   │
│  │  sys.hotpatch.*     → God Hand 热补丁 (Agent 监听)         │   │
│  │  ui.prompt.*        → 人类交互请求 (前端监听)               │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  SendMessageTool (kill/signal)                             │   │
│  │  Agent → EventBus → Target Agent                           │   │
│  │  替代已删除的 AskUserQuestionTool (阻塞式 I/O 已铲除)       │   │
│  └────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  SharedMemoryManager (shmget/mmap)                                │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  SemanticMemoryBlock → Agent 间共享语义对象                 │   │
│  └────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                       TaskScheduler                                │
│                (CFS 调度器 / 进程调度)                              │
│                                                                   │
│  spawn(task) → 严格运行时校验                                      │
│    │                                                              │
│    ├── ProcessPriority.REALTIME → 内核线程 (直接执行)              │
│    ├── ProcessPriority.NORMAL   → 强制路由到沙箱                   │
│    └── ProcessPriority.USER     → 强制路由到沙箱                   │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  SandboxProvider (沙箱隔离)                                 │   │
│  │                                                            │   │
│  │  ┌───────────────────────┐  ┌─────────────────────────┐   │   │
│  │  │ DockerSandboxProvider │  │   GraalWasmSandbox      │   │   │
│  │  │ --network none        │  │   GraalVM Polyglot      │   │   │
│  │  │ -m 256m               │  │   WASM 字节码隔离        │   │   │
│  │  │ --cpus 0.5            │  │                         │   │   │
│  │  │ --pids-limit 64       │  │                         │   │   │
│  │  │ --read-only           │  │                         │   │   │
│  │  └───────────────────────┘  └─────────────────────────┘   │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  I/O 劫持: stdout/stderr → EventBus (不直接打印到宿主机)           │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                       MemoryManager                                │
│                  (内存管理器 / MMU)                                 │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐      │
│  │  TokenZram   │  │ SwapManager  │  │  SemanticCache     │      │
│  │ (压缩内存)   │  │ (交换空间)   │  │  (页面缓存)         │      │
│  └──────────────┘  └──────────────┘  └────────────────────┘      │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐      │
│  │SessionMemory │  │  MemoryDir   │  │  CompactService    │      │
│  │(进程内存段)  │  │ (/memories/) │  │ (内存压缩/kswapd)  │      │
│  └──────────────┘  └──────────────┘  └────────────────────┘      │
│  ┌──────────────┐  ┌──────────────┐                               │
│  │CgroupManager │  │  CostTracker │                               │
│  │(cgroup 限制) │  │ (成本追踪)   │                               │
│  └──────────────┘  └──────────────┘                               │
└──────────────────────────────────────────────────────────────────┘
                    ┌──────┴──────┐
           ┌────────┴───┐  ┌─────┴──────┐
           │  Ring 3     │  │  Ring 3    │
           │    Zep      │  │   Mem0     │  ← 可插拔驱动
           │  Provider   │  │  Provider  │    零内核修改
           └─────────────┘  └────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                     PermissionChecker                              │
│               (权限检查 / Linux Capabilities)                      │
│                                                                   │
│  6 种权限模式:                                                    │
│  DEFAULT | PLAN_MODE | AUTO_ACCEPT | YOLO | BYPASS | STRICT      │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                     SyscallFilter (LSM)                            │
│  ┌─────────────────────┐  ┌─────────────────────────────────┐    │
│  │  PrivilegeSyscall   │  │    RateLimitSyscall             │    │
│  │  Filter (seccomp)   │  │    Filter (速率限制)             │    │
│  └─────────────────────┘  └─────────────────────────────────┘    │
│  ┌─────────────────────┐  ┌─────────────────────────────────┐    │
│  │  BpfManager (eBPF)  │  │  ObjectManager (SELinux)        │    │
│  └─────────────────────┘  └─────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  HostRpaManager — Ring 3 (已从内核驱逐)                            │
│  所有物理操作需要 SYS_ADMIN SecurityToken                          │
│  无令牌 → PermissionDeniedException                               │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│                       ToolRegistry                                 │
│                 (系统调用注册表 / Syscall Table)                    │
│                                                                   │
│  ┌─────────────────── 内核全局工具 (Ring 0) ─────────────────────┐│
│  │                                                               ││
│  │  BashTool      (execve)    FileReadTool   (read)             ││
│  │  FileWriteTool (write)     FileEditTool   (lseek+write)      ││
│  │  GrepTool      (grep)     GlobTool       (glob)             ││
│  │  WebFetchTool  (curl)     AgentTool      (fork)             ││
│  │  SendMessageTool(kill)    ConfigTool     (sysctl)           ││
│  │  LspTool       (诊断)     McpTool        (外设调用)          ││
│  │                                                               ││
│  │  已删除: AskUserQuestionTool (阻塞式 I/O 违反异步原则)        ││
│  └───────────────────────────────────────────────────────────────┘│
│                                                                   │
│  ┌─────────────────── 母体专属工具 (Ring 3) ─────────────────────┐│
│  │  TodoWriteTool │ NotebookEditTool │ PlanModeTool             ││
│  │  TaskTool      │ SkillTool                                  ││
│  │  仅 OmniMotherAgent 可用 (通过 QueryEngine extraTools)        ││
│  └───────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                   ToolExecutionPipeline                            │
│                (CPU 流水线 / 工具执行管线)                          │
│                                                                   │
│  beforeHook → permissionCheck → execute → afterHook → telemetry   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                      QueryEngine                                   │
│                (CPU 执行循环 / 推理执行循环)                        │
│                                                                   │
│  while (rounds < MAX_ROUNDS) {                                   │
│    1. LLM 推理 → 检测工具调用                                     │
│    2. 解析工具名 + 参数                                           │
│    3. ToolExecutionPipeline 执行                                  │
│    4. 结果反馈给 LLM                                              │
│    5. 检查终止条件                                                │
│  }                                                                │
│                                                                   │
│  母体自治闭环: write → test → fix → NODE_VERIFIED_AND_READY      │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌──────────────────────────────────────────────────────────────────┐
│  InitDaemon (PID 1 / systemd)                                     │
│                                                                   │
│  Phase 1: 内核初始化                                              │
│    ├── VfsManager.instance() — 挂载虚拟文件系统                   │
│    ├── EventBus.instance() — 启动 IPC 总线                        │
│    ├── LlmRouter.instance() — 注册 LLM 驱动                      │
│    └── TaskScheduler.instance() — 启动调度器                      │
│                                                                   │
│  Phase 2: 系统服务拉起                                            │
│    ├── SemanticEtw — 遥测                                         │
│    ├── SystemMonitorDaemon — 监控                                 │
│    ├── WatchdogDaemon — 看门狗                                    │
│    └── SystemTickGenerator — 时钟                                 │
│                                                                   │
│  Phase 3: VFS Manifest 驱动业务进程                               │
│    ├── 读取 /etc/init/startup_manifest.json                      │
│    ├── 不存在 → TemplateManager 生成默认 manifest                 │
│    └── WorkflowEngine.executeWorkflow(manifest) → 自动拉起       │
└──────────────────────────────────────────────────────────────────┘
```

---

```
┌─────────────┐    ┌──────────────┐    ┌──────────────┐    ┌────────────┐
│   Agent     │───→│ CrashAnalyzer│───→│  AutoMedic   │───→│  EventBus  │
│   崩溃      │    │  死亡计数器   │    │  熔断+修复   │    │  系统警报  │
└─────────────┘    └──────────────┘    └──────┬───────┘    └─────┬──────┘
                                               │                   │
                                          3次熔断               │
                                               │                   │
                                               ▼                   ▼
                                       ┌──────────────┐    ┌────────────┐
                                       │  人工介入     │    │ AppGateway │
                                       │(Human Escal) │    │→ 前端大屏   │
                                       └──────────────┘    └────────────┘
```

---

```
┌─────────────┐    WebSocket    ┌──────────────┐    EventBus    ┌──────────┐
│  前端大屏    │ ←────────────→ │  AppGateway  │ ─────────────→│  Agent   │
│  滑块调参    │   HOT_PATCH    │  热补丁路由   │  sys.hotpatch │  热重载  │
└─────────────┘                 └──────────────┘               └──────────┘

流程: UI Slider → WebSocket → AppGateway → EventBus → Agent 读取 VFS 配置 → 动态调参
```

---

```
┌──────────────────┬──────────────────────────────────────────────┐
│   Linux 概念      │   AIOS 对应                                  │
├──────────────────┼──────────────────────────────────────────────┤
│ task_struct      │ AgentTask                                    │
│ PID              │ AgentTask.pid()                              │
│ CPU              │ LlmProvider (OpenAI/DeepSeek/Local)          │
│ 指令集           │ LlmProvider.think()                          │
│ 指令解码器       │ InstructionDecoder                           │
│ 分支预测         │ SpeculativePredictor                         │
│ 内存 (RAM)       │ TokenZram (压缩 Token 内存)                  │
│ 虚拟内存         │ Context (上下文窗口)                          │
│ 共享内存         │ SharedMemoryManager + SemanticMemoryBlock    │
│ Swap             │ SwapManager + CompactService                 │
│ Page Cache       │ SemanticCacheManager                         │
│ VFS              │ VfsManager                                   │
│ inode            │ VfsNode (MutableFile/Pipe/Shm/Vector/...)    │
│ /proc            │ ProcFsNode                                   │
│ /dev/fb0         │ DisplayNode                                  │
│ pipe             │ PipeNode                                     │
│ shmget/mmap      │ ShmNode                                      │
│ syscall          │ SyscallDispatcher                            │
│ syscall table    │ ToolRegistry                                 │
│ execve()         │ BashTool                                     │
│ read()           │ FileReadTool                                 │
│ write()          │ FileWriteTool                                │
│ fork()           │ AgentTool                                    │
│ kill()/signal    │ SendMessageTool                              │
│ CFS Scheduler    │ TaskScheduler                                │
│ cgroup           │ CgroupManager                                │
│ OOM Killer       │ TokenSoftOomException                        │
│ nice             │ ProcessPriority                              │
│ NUMA             │ NumaAffinity                                 │
│ capabilities     │ PermissionChecker                            │
│ seccomp          │ PrivilegeSyscallFilter                       │
│ eBPF             │ BpfManager                                   │
│ SELinux          │ ObjectManager                                │
│ LSM              │ SyscallFilter                                │
│ PAM              │ AuthManager                                  │
│ systemd (PID 1)  │ InitDaemon                                   │
│ bash             │ AiosShell                                    │
│ libc             │ AiosSdk                                      │
│ Docker           │ DockerSandboxProvider                        │
│ WASM Runtime     │ GraalWasmSandbox                             │
│ Kernel Module    │ SkillLoader / PluginManager                  │
│ modprobe         │ SkillLoader                                  │
│ kswapd           │ CompactService / AutoDreamService            │
│ Kernel Panic     │ SemanticCrashAnalyzer                        │
│ Core Dump        │ SemanticCoreDump                             │
│ fsck             │ AutoMedicAgent                               │
│ strace/ftrace    │ TraceManager                                 │
│ perf             │ TelemetryService                             │
│ watchdog         │ WatchdogDaemon                               │
│ tick/timer       │ SystemTickGenerator                          │
│ WAL journal      │ VfsJournal                                   │
│ Dockerfile       │ AgentBlueprint                               │
│ containerd       │ ContainerRuntime                             │
│ apt/yum          │ AiosApt                                      │
│ /etc             │ SemanticRegistry                             │
│ Kernel Hook      │ HookManager                                  │
│ Raft Consensus   │ SemanticRaftNode                             │
│ Gateway          │ AppGateway                                   │
└──────────────────┴──────────────────────────────────────────────┘
```

---

```
com.ouisani.aios
│
├── core/                          ← Ring 0: 内核空间 (零厂商锁定, 零业务硬编码)
│   ├── AgentTask                  ← task_struct 进程控制块
│   ├── ProcessPriority            ← nice 值
│   ├── NumaAffinity               ← NUMA 亲和性
│   ├── TaskScheduler              ← CFS 调度器
│   ├── VfsManager                 ← VFS 虚拟文件系统
│   ├── VfsNode                    ← inode 接口
│   │
│   ├── llm/                       ← CPU 子系统
│   │   ├── LlmProvider            ← CPU 指令集抽象 (接口)
│   │   ├── LlmRouter              ← CPU 调度器
│   │   ├── InstructionDecoder     ← 指令解码器
│   │   ├── SpeculativePredictor   ← 分支预测
│   │   └── ComputeCore            ← CPU 核心枚举
│   │
│   ├── tool/                      ← 系统调用表
│   │   ├── ToolRegistry           ← syscall 注册表
│   │   ├── QueryEngine            ← CPU 执行循环
│   │   ├── ToolExecutionPipeline  ← CPU 流水线
│   │   ├── BashTool               ← execve()
│   │   ├── FileReadTool           ← read()
│   │   ├── FileWriteTool          ← write()
│   │   ├── FileEditTool           ← lseek()+write()
│   │   ├── AgentTool              ← fork()
│   │   └── ...                    ← 其他系统调用
│   │
│   ├── syscall/                   ← 系统调用层
│   │   ├── SyscallDispatcher      ← syscall 分发
│   │   ├── SyscallRequest         ← syscall 请求
│   │   ├── SyscallResponse        ← syscall 响应
│   │   └── schema/                ← syscall 参数 schema
│   │       ├── SyscallPayload     ← 载荷标记接口
│   │       ├── LlmPayload         ← LLM 载荷
│   │       ├── MemoryPayload      ← 内存载荷
│   │       ├── StoragePayload     ← 存储载荷
│   │       ├── ToolPayload        ← 工具载荷
│   │       └── RawPayload         ← 原始/遗留载荷
│   │
│   ├── memory/                    ← 内存管理
│   │   ├── MemoryManager          ← MMU
│   │   ├── TokenZram              ← zram 压缩内存
│   │   ├── SessionMemoryService   ← 进程内存段
│   │   ├── MemoryDir              ← /memories/ 持久化
│   │   ├── CompactService         ← kswapd 压缩
│   │   └── providers/             ← 内存后端接口
│   │       ├── MemoryProvider     ← 抽象接口 (保留在 core)
│   │       └── TokenZramProvider  ← 内核自带实现
│   │
│   ├── cgroup/                    ← 资源控制
│   ├── ipc/                       ← 进程间通信
│   ├── network/                   ← 网络层 (EventBus/AppGateway)
│   ├── security/                  ← 安全框架 (seccomp/eBPF/SELinux)
│   ├── permission/                ← 权限系统
│   ├── hook/                      ← 生命周期钩子
│   ├── telemetry/                 ← 遥测 (perf)
│   ├── crash/                     ← 崩溃分析
│   ├── dream/                     ← 梦境整合 (kswapd)
│   ├── swarm/                     ← 多 Agent 协作
│   ├── mcp/                       ← MCP 协议
│   ├── skill/                     ← 技能加载 (modprobe)
│   ├── lsp/                       ← LSP 代码智能
│   ├── plugin/                    ← 插件管理 (内核模块)
│   ├── trace/                     ← 追踪 (strace)
│   ├── cache/                     ← 语义缓存
│   ├── sandbox/                   ← 沙箱隔离
│   ├── config/                    ← 配置管理
│   ├── cost/                      ← 成本追踪
│   ├── cluster/                   ← 分布式共识
│   ├── context/                   ← 上下文构建
│   ├── transport/                 ← 传输层
│   ├── remote/                    ← 远程会话
│   ├── rtos/                      ← 实时监控
│   ├── tick/                      ← 时钟中断
│   ├── snapshot/                  ← 进程快照
│   ├── pkg/                       ← 包管理
│   └── bridge/                    ← REPL 桥接
│
├── drivers/                       ← Ring 3: 设备驱动 (厂商实现, 可插拔)
│   ├── llm/
│   │   └── OpenAiAdapter          ← OpenAI 驱动
│   └── memory/
│       ├── ZepProvider            ← Zep 记忆驱动
│       └── Mem0Provider           ← Mem0 记忆驱动
│
├── vfs/                           ← VFS 节点实现 (设备文件)
│   ├── MutableFileNode            ← 普通文件
│   ├── PipeNode                   ← 管道
│   ├── ShmNode                    ← 共享内存
│   ├── VectorNode                 ← 向量存储
│   ├── HttpNode                   ← HTTP 端点
│   ├── ProcFsNode                 ← /proc
│   ├── GraphNode                  ← 知识图谱
│   ├── DisplayNode                ← /dev/fb0
│   ├── AudioNode                  ← /dev/dsp
│   ├── CameraNode                 ← /dev/video0
│   ├── WebhookNode                ← 事件触发
│   ├── WebSocketNode              ← 双向通道
│   └── ShadowCopyNode             ← COW 快照
│
└── user/                          ← Ring 3: 用户空间
    ├── init/
    │   └── InitDaemon             ← PID 1 (systemd)
    ├── cli/
    │   ├── AiosShell              ← bash shell
    │   └── IntentRouter           ← LLM 语义路由
    ├── sdk/
    │   ├── AiosSdk                ← libc/glibc
    │   └── AbstractAgent          ← 进程基类
    ├── apps/omnifactory/
    │   ├── OmniMotherAgent        ← 母体 (init 进程工厂)
    │   ├── AutoMedicAgent         ← 医疗 (fsck)
    │   ├── WorkflowEngine         ← 作业调度器
    │   ├── TopologyCompiler       ← 拓扑编译器
    │   ├── TemplateManager        ← 模板管理器
    │   └── tools/                 ← 母体专属认知工具
    │       ├── TodoWriteTool
    │       ├── NotebookEditTool
    │       ├── PlanModeTool
    │       ├── TaskTool
    │       └── SkillTool
    ├── bin/
    │   ├── AiosAppManager         ← apt
    │   ├── AiosApt                ← apt-get
    │   └── CoreUtils              ← coreutils
    ├── bridge/rpa/
    │   ├── HostRpaManager         ← 宿主 RPA (需 SYS_ADMIN Token)
    │   ├── SecurityToken          ← 安全令牌
    │   └── PermissionDeniedException
    └── container/
        ├── ContainerRuntime       ← containerd
        ├── AgentfileParser        ← Dockerfile 解析
        └── AgentImageConfig       ← 镜像配置
```
