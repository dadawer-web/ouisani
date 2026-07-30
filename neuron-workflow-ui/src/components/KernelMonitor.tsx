import { useSystemStore } from "@/store/systemStore";
import { Cpu, HardDrive, Box, Radio, Terminal } from "lucide-react";
import { cn } from "@/lib/utils";

/** 状态圆点颜色映射 —— 对齐 cc-haha 语义色 */
const statusColor: Record<string, string> = {
  Running: "bg-tertiary",
  Sleeping: "bg-primary-fixed",
  Blocked: "bg-primary",
  Crashed: "bg-error animate-soft-pulse",
  Killed: "bg-error/40",
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

/** AIOS 内核监控面板 —— 暖纸 / 古铜 重绘 */
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
    <div className="flex h-full flex-col gap-3 font-mono">
      {/* ═══ 顶栏：三张数字卡片 ═══ */}
      <div className="grid grid-cols-3 gap-3">
        {/* CPU Load */}
        <div className="rounded-lg bg-surface-container-low px-4 py-3">
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-outline">
            <Cpu className="h-3 w-3" />
            <span>CPU Load</span>
          </div>
          <div className="mt-1 font-mono text-2xl font-bold text-on-surface">{cpuUsage}%</div>
          <div className="mt-2 h-1 w-full rounded-full bg-surface-container-high">
            <div
              className="h-full rounded-full bg-primary transition-all duration-500"
              style={{ width: `${Math.min(cpuUsage, 100)}%` }}
            />
          </div>
        </div>

        {/* Memory */}
        <div className="rounded-lg bg-surface-container-low px-4 py-3">
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-outline">
            <HardDrive className="h-3 w-3" />
            <span>Memory</span>
          </div>
          <div className="mt-1 font-mono text-2xl font-bold text-on-surface">{fmtRam(ramUsage)}</div>
          <div className="mt-2 h-1 w-full rounded-full bg-surface-container-high">
            <div
              className="h-full rounded-full bg-secondary transition-all duration-500"
              style={{ width: `${Math.min(ramUsage, 100)}%` }}
            />
          </div>
        </div>

        {/* Active Sandboxes */}
        <div className="rounded-lg bg-surface-container-low px-4 py-3">
          <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest text-outline">
            <Box className="h-3 w-3" />
            <span>Active Sandboxes</span>
          </div>
          <div className="mt-1 font-mono text-2xl font-bold text-on-surface">{activeProcesses}</div>
          <div className="mt-2 flex gap-1">
            {processes.slice(0, 12).map((p) => (
              <div key={p.pid} className={cn("h-1 w-1.5 rounded-full", statusColor[p.status] ?? "bg-outline/40")} />
            ))}
            {processes.length > 12 && (
              <span className="text-[8px] text-outline">+{processes.length - 12}</span>
            )}
          </div>
        </div>
      </div>

      {/* ═══ 主内容区：左右两栏 ═══ */}
      <div className="grid min-h-0 flex-1 grid-cols-2 gap-3">
        {/* ── 左栏：Process Explorer ── */}
        <div className="flex flex-col overflow-hidden rounded-lg bg-surface-container-low">
          <div className="flex items-center gap-2 bg-surface-container px-3 py-2">
            <Radio className="h-3 w-3 text-tertiary" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-on-surface-variant">
              Process Explorer
            </span>
            <span className="ml-auto text-[9px] text-outline">{connected ? "LIVE" : "OFFLINE"}</span>
            <div className={cn("h-1.5 w-1.5 rounded-full", connected ? "bg-tertiary animate-soft-pulse" : "bg-error")} />
          </div>

          {/* 表头 */}
          <div className="grid grid-cols-[40px_1fr_60px_50px_50px_50px] gap-1 bg-surface-container/60 px-3 py-1.5 text-[9px] uppercase tracking-wider text-outline">
            <span>PID</span>
            <span>Name</span>
            <span>Sandbox</span>
            <span>Status</span>
            <span>CPU</span>
            <span>RAM</span>
          </div>

          {/* 进程列表 */}
          <div className="custom-scrollbar flex-1 overflow-y-auto">
            {processes.length === 0 ? (
              <div className="flex h-full items-center justify-center text-[10px] text-outline/50">
                No active processes
              </div>
            ) : (
              processes.map((p) => (
                <div
                  key={p.pid}
                  className="grid grid-cols-[40px_1fr_60px_50px_50px_50px] gap-1 px-3 py-1.5 text-[10px] text-on-surface-variant transition-colors hover:bg-surface-container-high"
                >
                  <span className="text-primary/70">{p.pid}</span>
                  <span className="truncate text-on-surface">{p.agentName}</span>
                  <span className={p.sandboxType === "Wasm" ? "text-secondary" : "text-primary/80"}>
                    {p.sandboxType}
                  </span>
                  <span className="flex items-center gap-1">
                    <span className={cn("h-1.5 w-1.5 rounded-full", statusColor[p.status] ?? "bg-outline/40")} />
                    <span className="text-[9px] text-outline">{statusLabel[p.status] ?? "???"}</span>
                  </span>
                  <span className={p.cpu > 80 ? "text-error" : p.cpu > 50 ? "text-primary" : "text-tertiary"}>
                    {p.cpu}%
                  </span>
                  <span className="text-outline">{fmtRam(p.ram)}</span>
                </div>
              ))
            )}
          </div>
        </div>

        {/* ── 右栏：EventBus Live Stream ── */}
        <div className="flex flex-col overflow-hidden rounded-lg bg-surface-dim">
          <div className="flex items-center gap-2 bg-surface-container px-3 py-2">
            <Terminal className="h-3 w-3 text-tertiary" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-on-surface-variant">
              EventBus Stream
            </span>
            <span className="ml-auto text-[9px] text-outline">{eventBusLogs.length}/50</span>
          </div>

          <div className="custom-scrollbar flex-1 overflow-y-auto overflow-x-hidden p-2">
            {eventBusLogs.length === 0 ? (
              <div className="flex h-full items-center justify-center">
                <span className="animate-soft-pulse text-[10px] text-outline/50">
                  Waiting for kernel events…
                </span>
              </div>
            ) : (
              eventBusLogs.map((log, i) => (
                <div
                  key={`${log.timestamp}-${i}`}
                  className="flex gap-2 py-0.5 text-[9px] leading-relaxed"
                  style={{ opacity: Math.max(0.35, 1 - i * 0.015) }}
                >
                  <span className="shrink-0 text-outline/60">{fmtTime(log.timestamp)}</span>
                  <span className="shrink-0 text-primary/80">[{log.topic}]</span>
                  <span className="truncate text-on-surface-variant">{log.payload}</span>
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
            <Terminal className="h-3 w-3 text-tertiary" />
            <span className="text-[10px] uppercase tracking-[0.2em] text-on-surface-variant">
              Agent Stdout
            </span>
            <span className="ml-auto text-[9px] text-outline">
              {Object.keys(appOutputs).length} agent(s)
            </span>
          </div>
          <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${Math.min(Object.keys(appOutputs).length, 3)}, 1fr)` }}>
            {Object.entries(appOutputs).map(([agentId, output]) => {
              const isActive = activeWorkflowNode != null && (
                agentId === activeWorkflowNode
                || agentId.includes(activeWorkflowNode)
                || output.includes(`NODE_START: ${activeWorkflowNode}`)
              );
              return (
                <div
                  key={agentId}
                  className={cn(
                    "flex flex-col overflow-hidden rounded-lg transition-all duration-300",
                    isActive ? "bg-surface-container-lowest ring-1 ring-tertiary/50" : "bg-surface-dim",
                  )}
                >
                  <div className="flex items-center justify-between bg-surface-container px-3 py-1">
                    <span className="max-w-[70%] truncate font-mono text-[10px] text-on-surface-variant">{agentId}</span>
                    <span className="flex shrink-0 items-center gap-2 text-[9px] text-outline">
                      {isActive ? (
                        <>
                          <span className="h-2 w-2 animate-soft-pulse rounded-full bg-tertiary" />
                          <span className="font-bold text-tertiary">ACTIVE</span>
                        </>
                      ) : (
                        <>
                          <span className="h-2 w-2 animate-soft-pulse rounded-full bg-tertiary/60" />
                          LIVE
                        </>
                      )}
                    </span>
                  </div>
                  <div className="custom-scrollbar h-48 overflow-y-auto whitespace-pre-wrap p-3 font-mono text-xs text-tertiary">
                    {output || "Waiting for agent to produce output…"}
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
