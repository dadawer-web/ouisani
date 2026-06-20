import { useEffect, useState } from "react";
import { AIOS_API_URL } from "../config";
import {
  Activity,
  Cpu,
  HardDrive,
  Clock,
  Shield,
  AlertTriangle,
} from "lucide-react";

/** 内核状态数据结构 */
interface KernelStatus {
  uptimeMs: number;
  uptimeHuman: string;
  activeAgents: number;
  runningAgents: number;
  blockedAgents: number;
  tokensUsed: number;
  watchdogHealthy: boolean;
  watchdogMsSinceLastPing: number;
  systemTick: number;
  llmAvailable: boolean;
}

/** 顶部内核状态栏 — 实时显示后端工作状态 */
export default function KernelStatusBar() {
  const [status, setStatus] = useState<KernelStatus | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const poll = async () => {
      try {
        const res = await fetch(
          `${AIOS_API_URL}/api/kernel/status`,
          { headers: { Authorization: "Bearer AIOS-SUPER-SECRET-KEY" } }
        );
        if (res.ok) {
          const data: KernelStatus = await res.json();
          setStatus(data);
          setConnected(true);
        } else {
          setConnected(false);
        }
      } catch {
        setConnected(false);
      }
    };

    poll();
    const interval = setInterval(poll, 3000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="flex h-9 items-center gap-4 border-b border-zinc-800/80 bg-[#0a0a0f]/95 px-4 text-[10px] font-mono tracking-wide backdrop-blur-sm">
      {/* 连接状态 */}
      <div className="flex items-center gap-1.5">
        <div
          className={`h-1.5 w-1.5 rounded-full ${
            connected
              ? "animate-pulse bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.5)]"
              : "bg-red-500"
          }`}
        />
        <span className={connected ? "text-emerald-400" : "text-red-400"}>
          {connected ? "KERNEL ONLINE" : "KERNEL OFFLINE"}
        </span>
      </div>

      <div className="h-3 w-px bg-zinc-700" />

      {/* 运行时间 */}
      {status && (
        <>
          <div className="flex items-center gap-1 text-zinc-400">
            <Clock className="h-3 w-3 text-cyan-500/70" />
            <span>{status.uptimeHuman}</span>
          </div>

          <div className="h-3 w-px bg-zinc-700" />

          {/* Agent 状态 */}
          <div className="flex items-center gap-1 text-zinc-400">
            <Activity className="h-3 w-3 text-violet-400/70" />
            <span>
              <span className="text-violet-300">{status.activeAgents}</span>
              <span className="text-zinc-600"> agents</span>
              {status.runningAgents > 0 && (
                <span className="ml-1 text-emerald-400">
                  ({status.runningAgents} running)
                </span>
              )}
              {status.blockedAgents > 0 && (
                <span className="ml-1 text-amber-400">
                  ({status.blockedAgents} blocked)
                </span>
              )}
            </span>
          </div>

          <div className="h-3 w-px bg-zinc-700" />

          {/* Token 消耗 */}
          <div className="flex items-center gap-1 text-zinc-400">
            <HardDrive className="h-3 w-3 text-amber-400/70" />
            <span>
              <span className="text-amber-300">
                {status.tokensUsed.toLocaleString()}
              </span>
              <span className="text-zinc-600"> tokens</span>
            </span>
          </div>

          <div className="h-3 w-px bg-zinc-700" />

          {/* LLM 状态 */}
          <div className="flex items-center gap-1 text-zinc-400">
            <Cpu
              className={`h-3 w-3 ${
                status.llmAvailable
                  ? "text-cyan-400/70"
                  : "text-red-400/70"
              }`}
            />
            <span
              className={
                status.llmAvailable ? "text-cyan-300" : "text-red-400"
              }
            >
              {status.llmAvailable ? "LLM READY" : "LLM DOWN"}
            </span>
          </div>

          <div className="h-3 w-px bg-zinc-700" />

          {/* 看门狗 */}
          <div className="flex items-center gap-1 text-zinc-400">
            {status.watchdogHealthy ? (
              <Shield className="h-3 w-3 text-emerald-400/70" />
            ) : (
              <AlertTriangle className="h-3 w-3 animate-pulse text-red-400" />
            )}
            <span
              className={
                status.watchdogHealthy ? "text-emerald-400" : "text-red-400"
              }
            >
              {status.watchdogHealthy ? "WDT OK" : "WDT TIMEOUT"}
            </span>
          </div>

          <div className="h-3 w-px bg-zinc-700" />

          {/* SysTick */}
          <div className="flex items-center gap-1 text-zinc-500">
            <span>
              tick <span className="text-zinc-400">#{status.systemTick}</span>
            </span>
          </div>
        </>
      )}

      {/* 右侧：AIOS 标识 */}
      <div className="ml-auto flex items-center gap-1.5 text-zinc-600">
        <span className="text-[9px] font-bold tracking-widest uppercase">
          AIOS v1.0
        </span>
      </div>
    </div>
  );
}
