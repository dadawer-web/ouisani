import { useSystemStore } from "@/store/systemStore";
import {
  Cpu,
  HardDrive,
  Box,
  Circle,
  Radio,
  Terminal,
} from "lucide-react";

/** 状态圆点颜色映射 */
const statusColor: Record<string, string> = {
  Running: "bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.8)]",
  Sleeping: "bg-amber-400 shadow-[0_0_6px_rgba(251,191,36,0.6)]",
  Blocked: "bg-orange-400 shadow-[0_0_6px_rgba(251,146,60,0.6)]",
  Crashed: "bg-red-500 shadow-[0_0_6px_rgba(239,68,68,0.8)] animate-pulse",
  Killed: "bg-red-900",
};

const statusLabel: Record<string, string> = {
  Running: "RUN",
  Sleeping: "SLP",
  Blocked: "BLK",
  Crashed: "ERR",
  Killed: "KILL",
};

/** 格式化时间戳 */
function fmtTime(ts: number) {
  const d = new Date(ts);
  return d.toLocaleTimeString("en-US", { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

/** 格式化 RAM */
function fmtRam(tokens: number) {
  if (tokens >= 1_000_000) return (tokens / 1_000_000).toFixed(1) + "M";
  if (tokens >= 1_000) return (tokens / 1_000).toFixed(1) + "K";
  return String(tokens);
}

/** AIOS 内核监控面板 — 赛博朋克风格 */
export default function KernelMonitor() {
  const {
    cpuUsage,
    ramUsage,
    activeProcesses,
    processes,
    eventBusLogs,
    appOutputs,
    activeWorkflowNode,
    connected,
  } = useSystemStore();

  return (
    <div className="flex h-full flex-col gap-3 p-3 font-mono">
      {/* ═══ 顶栏：三张发光数字卡片 ═══ */}
      <div className="grid grid-cols-3 gap-3">
        {/* CPU Load */}
        <div className="relative overflow-hidden rounded-md border border-cyan-500/20 bg-black/80 px-4 py-3 backdrop-blur-md"
             style={{ boxShadow: "0 0 15px rgba(0,255,255,0.1), inset 0 0 30px rgba(0,255,255,0.03)" }}>
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-cyan-500/60">
            <Cpu className="h-3 w-3" />
            <span>CPU Load</span>
          </div>
          <div className="mt-1 text-2xl font-bold text-cyan-300"
               style={{ textShadow: "0 0 10px rgba(0,255,255,0.5)" }}>
            {cpuUsage}%
          </div>
          {/* 底部进度条 */}
          <div className="mt-2 h-1 w-full rounded-full bg-cyan-900/30">
            <div className="h-full rounded-full bg-cyan-400/80 transition-all duration-500"
                 style={{ width: `${Math.min(cpuUsage, 100)}%`, boxShadow: "0 0 8px rgba(0,255,255,0.6)" }} />
          </div>
        </div>

        {/* Memory */}
        <div className="relative overflow-hidden rounded-md border border-violet-500/20 bg-black/80 px-4 py-3 backdrop-blur-md"
             style={{ boxShadow: "0 0 15px rgba(139,92,246,0.1), inset 0 0 30px rgba(139,92,246,0.03)" }}>
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-violet-500/60">
            <HardDrive className="h-3 w-3" />
            <span>Memory</span>
          </div>
          <div className="mt-1 text-2xl font-bold text-violet-300"
               style={{ textShadow: "0 0 10px rgba(139,92,246,0.5)" }}>
            {fmtRam(ramUsage)}
          </div>
          <div className="mt-2 h-1 w-full rounded-full bg-violet-900/30">
            <div className="h-full rounded-full bg-violet-400/80 transition-all duration-500"
                 style={{ width: `${Math.min(ramUsage, 100)}%`, boxShadow: "0 0 8px rgba(139,92,246,0.6)" }} />
          </div>
        </div>

        {/* Active Sandboxes */}
        <div className="relative overflow-hidden rounded-md border border-emerald-500/20 bg-black/80 px-4 py-3 backdrop-blur-md"
             style={{ boxShadow: "0 0 15px rgba(52,211,153,0.1), inset 0 0 30px rgba(52,211,153,0.03)" }}>
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-emerald-500/60">
            <Box className="h-3 w-3" />
            <span>Active Sandboxes</span>
          </div>
          <div className="mt-1 text-2xl font-bold text-emerald-300"
               style={{ textShadow: "0 0 10px rgba(52,211,153,0.5)" }}>
            {activeProcesses}
          </div>
          <div className="mt-2 flex gap-1">
            {processes.slice(0, 12).map((p) => (
              <div key={p.pid} className={`h-1 w-1.5 rounded-full ${statusColor[p.status] ?? "bg-zinc-600"}`} />
            ))}
            {processes.length > 12 && (
              <span className="text-[8px] text-zinc-600">+{processes.length - 12}</span>
            )}
          </div>
        </div>
      </div>

      {/* ═══ 主内容区：左右两栏 ═══ */}
      <div className="grid min-h-0 flex-1 grid-cols-2 gap-3">

        {/* ── 左栏：Process Explorer / 沙箱管理器 ── */}
        <div className="flex flex-col overflow-hidden rounded-md border border-emerald-500/10 bg-black/80 backdrop-blur-md"
             style={{ boxShadow: "0 0 10px rgba(0,255,0,0.05)" }}>
          {/* 标题栏 */}
          <div className="flex items-center gap-2 border-b border-emerald-500/10 bg-emerald-950/20 px-3 py-2">
            <Radio className="h-3 w-3 text-emerald-400" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-emerald-400/80">
              Process Explorer
            </span>
            <span className="ml-auto text-[9px] text-emerald-600">
              {connected ? "LIVE" : "OFFLINE"}
            </span>
            <div className={`h-1.5 w-1.5 rounded-full ${connected ? "bg-emerald-400 animate-pulse" : "bg-red-500"}`} />
          </div>

          {/* 表头 */}
          <div className="grid grid-cols-[40px_1fr_60px_50px_50px_50px] gap-1 border-b border-emerald-500/5 bg-emerald-950/10 px-3 py-1.5 text-[9px] uppercase tracking-wider text-emerald-600/60">
            <span>PID</span>
            <span>Name</span>
            <span>Sandbox</span>
            <span>Status</span>
            <span>CPU</span>
            <span>RAM</span>
          </div>

          {/* 进程列表 */}
          <div className="flex-1 overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-emerald-800/30">
            {processes.length === 0 ? (
              <div className="flex h-full items-center justify-center text-[10px] text-emerald-800/50">
                No active processes
              </div>
            ) : (
              processes.map((p) => (
                <div key={p.pid}
                     className="grid grid-cols-[40px_1fr_60px_50px_50px_50px] gap-1 border-b border-emerald-500/5 px-3 py-1.5 text-[10px] text-emerald-300/80 transition-colors hover:bg-emerald-500/5">
                  <span className="text-cyan-400/60">{p.pid}</span>
                  <span className="truncate text-emerald-200/90">{p.agentName}</span>
                  <span className={p.sandboxType === "Wasm"
                    ? "text-violet-400/70"
                    : "text-cyan-400/70"}>
                    {p.sandboxType}
                  </span>
                  <span className="flex items-center gap-1">
                    <Circle className={`h-1.5 w-1.5 fill-current ${statusColor[p.status] ?? "bg-zinc-600"}`} />
                    <span className="text-[9px] text-zinc-500">{statusLabel[p.status] ?? "???"}</span>
                  </span>
                  <span className={p.cpu > 80 ? "text-red-400" : p.cpu > 50 ? "text-amber-400" : "text-emerald-400/70"}>
                    {p.cpu}%
                  </span>
                  <span className="text-emerald-400/50">{fmtRam(p.ram)}</span>
                </div>
              ))
            )}
          </div>
        </div>

        {/* ── 右栏：EventBus Live Stream / 总线监听器 ── */}
        <div className="flex flex-col overflow-hidden rounded-md border border-green-500/10 bg-black/90 backdrop-blur-md"
             style={{ boxShadow: "0 0 10px rgba(0,255,0,0.08)" }}>
          {/* 标题栏 */}
          <div className="flex items-center gap-2 border-b border-green-500/10 bg-green-950/20 px-3 py-2">
            <Terminal className="h-3 w-3 text-green-400" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-green-400/80">
              EventBus Stream
            </span>
            <span className="ml-auto text-[9px] text-green-700">
              {eventBusLogs.length}/50
            </span>
          </div>

          {/* 日志流 */}
          <div className="flex-1 overflow-y-auto overflow-x-hidden p-2 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-green-800/30">
            {eventBusLogs.length === 0 ? (
              <div className="flex h-full items-center justify-center">
                <span className="animate-pulse text-[10px] text-green-800/40">
                  Waiting for kernel events...
                </span>
              </div>
            ) : (
              eventBusLogs.map((log, i) => (
                <div key={`${log.timestamp}-${i}`}
                     className="flex gap-2 border-b border-green-500/5 py-0.5 text-[9px] leading-relaxed"
                     style={{ opacity: Math.max(0.3, 1 - i * 0.015) }}>
                  <span className="shrink-0 text-green-700/60">{fmtTime(log.timestamp)}</span>
                  <span className="shrink-0 text-cyan-500/70">[{log.topic}]</span>
                  <span className="truncate text-green-400/80">{log.payload}</span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* ═══ 底栏：Agent Stdout Viewport ═══ */}
      {Object.keys(appOutputs).length > 0 && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2 px-1">
            <Terminal className="h-3 w-3 text-green-400" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-green-400/80">
              Agent Stdout
            </span>
            <span className="ml-auto text-[9px] text-green-700">
              {Object.keys(appOutputs).length} agent(s)
            </span>
          </div>
          <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${Math.min(Object.keys(appOutputs).length, 3)}, 1fr)` }}>
            {Object.entries(appOutputs).map(([agentId, output]) => {
              // DAG_TRACE 中的节点 ID (如 agent_1) 可能出现在 appOutputs 的 key 或 output 文本中
              const isActive = activeWorkflowNode != null && (
                agentId === activeWorkflowNode
                || agentId.includes(activeWorkflowNode)
                || output.includes(`NODE_START: ${activeWorkflowNode}`)
              );
              return (
              <div key={agentId}
                   className={`overflow-hidden rounded-md border flex flex-col transition-all duration-300 ${
                     isActive
                       ? "border-green-500 shadow-[0_0_15px_rgba(34,197,94,0.6)] animate-pulse bg-[#0D0D0D]"
                       : "border-gray-800 bg-[#0D0D0D]"
                   }`}
                   style={isActive ? { boxShadow: "0 0 15px rgba(34,197,94,0.6)" } : { boxShadow: "0 0 15px rgba(0,255,0,0.1)" }}>
                <div className="flex items-center justify-between border-b border-gray-800 bg-gray-900 px-3 py-1">
                  <span className="text-[10px] font-mono text-gray-400 truncate max-w-[70%]">{agentId}</span>
                  <span className="flex items-center gap-2 text-[9px] text-gray-500 shrink-0">
                    {isActive ? (
                      <>
                        <span className="h-2 w-2 animate-pulse rounded-full bg-green-500 shadow-[0_0_6px_rgba(34,197,94,0.8)]" />
                        <span className="text-green-400 font-bold">ACTIVE</span>
                      </>
                    ) : (
                      <>
                        <span className="h-2 w-2 animate-pulse rounded-full bg-green-500" />
                        LIVE
                      </>
                    )}
                  </span>
                </div>
                <div className="h-48 overflow-y-auto p-3 font-mono text-xs whitespace-pre-wrap text-green-400">
                  {output || "Waiting for agent to produce output..."}
                </div>
              </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
