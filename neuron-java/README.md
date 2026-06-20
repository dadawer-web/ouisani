# Neuron

> 以操作系统内核隐喻编排 AI Agent 的 Java 21 运行时。

Neuron 把大模型推理基础设施映射为操作系统概念——Agent 是进程，LLM 是 CPU，Token 是内存，VFS 是文件系统，EventBus 是 IPC。在此之上实现进程调度、资源隔离、语义缓存、虚拟文件系统和插件沙箱，让多 Agent 协作像多进程并发一样可控。

## 核心映射

| OS 概念 | Neuron 对应 | 说明 |
|---------|------------|------|
| 进程 (task_struct) | AgentTask | Agent 运行时上下文，PID + 优先级 + Token 预算 |
| CPU | LlmRouter | 模型路由器，E_CORE(能效核)/P_CORE(旗舰核) 分级调度 |
| 内存 | TokenZram / SwapManager | 上下文压缩与换出，Token 即内存页 |
| 文件系统 | VfsManager | 统一抽象向量记忆、摄像头、显示器、网络为文件节点 |
| IPC | EventBus | Agent 间事件总线，非阻塞发布/订阅 |
| cgroup | CgroupManager | Token 预算隔离，OOM 杀死 |
| 系统调用 | SyscallDispatcher | Agent 通过 syscall 请求内核服务 |
| init (PID 1) | InitDaemon | 三阶段引导：硬件层 → 内核层 → 服务层 |
| shell | AiosShell | 自然语言交互终端，双模式(自然语言/极客模式) |

## 架构总览

```
┌─────────────────────────────────────────────────────┐
│                   用户空间 (user/)                   │
│  AiosShell ←→ IntentRouter ←→ SyscallDispatcher    │
│  OmniFactory (WorkflowEngine)  ContainerRuntime     │
├─────────────────────────────────────────────────────┤
│                    内核 (core/)                      │
│                                                     │
│  调度层    TaskScheduler / ProcessPriority / Numa   │
│  内存层    SemanticCacheManager / TokenZram / Swap  │
│  存储层    VfsManager / VfsJournal / KvCacheStore   │
│  通信层    EventBus / SignalType / ShmNode           │
│  安全层    BpfManager / ObjectManager / GraalSandbox │
│  可观测    SemanticEtw / TelemetryService / Trace    │
│  缓存层    EvictionPolicy / KvCacheRegistry / Blend  │
│  休眠层    HibernationManager / SnapshotSerializer   │
├─────────────────────────────────────────────────────┤
│                   驱动层 (drivers/)                   │
│  LLM: OpenAiAdapter    Memory: Mem0/Zep adapters    │
└─────────────────────────────────────────────────────┘
```

## 关键子系统

### 调度 — TaskScheduler

基于 Java 21 虚拟线程的轻量级 Agent 调度。每个 Agent 对应一个虚拟线程，支持优先级抢占、Token 预算限制 (cgroup)、OOM 杀死、推测执行和语义级 Kernel Panic 恢复。

### 虚拟文件系统 — VfsManager

将向量记忆、图记忆、摄像头、显示器、HTTP 端点、GUI DOM 等异构资源统一抽象为 VfsNode。Agent 通过标准路径 (`/dev/camera`, `/var/memory/vector`, `/proc/agents`) 访问一切，支持 mount namespace 隔离和 VSS 快照。

### 语义缓存 — SemanticCacheManager

基于艾宾浩斯遗忘曲线的记忆衰减策略，支持 STRICT_OS / BIONIC 淘汰策略。扩展层包含:
- **KvCacheRegistry** — KV Cache 张量引用注册表，支持 pin/unpin 引用计数和内容哈希匹配
- **CacheBlendEngine** — 非前缀复用，跨 Agent 公共上下文智能截断与拼装
- **PrefixOptimizedPromptBuilder** — 强制 `[静态系统] → [共享上下文] → [工具清单] → [动态任务]` 渲染顺序，最大化前缀缓存命中

### 休眠 — HibernationManager

`suspendToDisk()` 将 Agent 的变量池、任务队列、KV Cache 引用、上下文指针打包为 `.aios_snapshot` 文件写入 VFS。`resumeFromDisk()` 读取快照满血复活，实现工作区级别的数字生命持久化。

### 安全 — GraalWasmSandbox + BpfManager

WASM 插件在 GraalVM 沙箱中运行，BPF 风格的权限检查拦截非法系统调用。ObjectManager 实现能力模型 (capability-based security)。

## 引导流程

InitDaemon (PID 1) 按严格顺序引导系统:

1. **Phase 1 — 硬件层**: SystemTickGenerator 起振 (系统心跳)，LlmProvider 连接 (POST 自检)，VfsJournal WAL 就绪
2. **Phase 2 — 内核层**: 挂载 VFS (`/dev`, `/proc`, `/var/memory`)，启动 BpfManager 安全模块，初始化 CgroupManager
3. **Phase 3 — 服务层**: WatchdogDaemon (看门狗)，SystemMonitorDaemon (遥测)，CognitiveDreamDaemon (记忆巩固)，加载 WASM 插件
4. **RUNLEVEL 5**: 创建 AiosShell，移交控制权给交互式 Shell

## 快速开始

### 前置要求

- JDK 21+
- Maven 3.9+

### 构建

```bash
cd neuron-java
mvn clean package
```

### 运行

```bash
java --add-modules java.net.http -jar target/aios.jar
```

启动后进入 AiosShell 交互式终端:
- **自然语言模式**: 直接输入意图，E_CORE 模型路由到系统调用
- **极客模式**: 以 `/` 开头直接执行 Syscall

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Java 21 (虚拟线程, record, sealed class, switch expression) |
| 模块系统 | JPMS (module-info.java) |
| 序列化 | Gson + Jackson |
| Web 框架 | Javalin |
| 沙箱 | GraalVM Polyglot (WASM + JS) |
| 日志 | SLF4J |

## 项目结构

```
neuron-java/
├── src/main/java/com/ouisani/aios/
│   ├── core/                  # 内核
│   │   ├── TaskScheduler      # 进程调度
│   │   ├── VfsManager         # 虚拟文件系统
│   │   ├── cache/             # 语义缓存 + KV State + CacheBlend
│   │   ├── memory/            # 记忆提供者 + Connector 抽象
│   │   ├── llm/               # LLM 路由 + 推测解码
│   │   ├── cgroup/            # 资源隔离
│   │   ├── observability/     # 统一可观测性 (EventBus + 订阅者)
│   │   ├── hibernation/       # 语义核心休眠
│   │   ├── config/            # 声明式配置
│   │   ├── plugin/            # 插件自动发现
│   │   ├── syscall/           # 系统调用分发
│   │   ├── sandbox/           # WASM 沙箱
│   │   └── ...
│   ├── drivers/               # 驱动层 (LLM/Memory 适配器)
│   ├── operator/              # 运维层 (网关/会话/密钥)
│   ├── user/                  # 用户空间
│   │   ├── cli/               # AiosShell
│   │   ├── init/              # InitDaemon (PID 1)
│   │   ├── container/         # 容器运行时
│   │   └── apps/omnifactory/  # 工作流引擎
│   └── vfs/                   # VFS 节点实现
└── pom.xml
```

## License

Private
