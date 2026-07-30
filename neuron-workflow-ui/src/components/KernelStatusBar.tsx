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
import { cn } from "@/lib/utils";

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

/**
 * 顶栏右侧内核状态胶囊组 —— 实时显示后端工作状态（3s 轮询 /api/kernel/status）。
 * 由原独立 h-9 状态栏重构为可嵌入顶栏的紧凑胶囊列。
 */
export default function KernelStatusBar() {
  const [status, setStatus] = useState<KernelStatus | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const poll = async () => {
      try {
        const res = await fetch(`${AIOS_API_URL}/api/kernel/status`, {
          headers: { Authorization: "Bearer AIOS-SUPER-SECRET-KEY" },
        });
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
    <div className="flex items-center gap-1.5 font-mono text-[10px] tracking-wide">
      {/* 连接状态 */}
      <span
        className={cn(
          "pill",
          connected
            ? "bg-tertiary-container/20 text-tertiary"
            : "bg-error-container/30 text-error",
        )}
      >
        <span
          className={cn(
            "h-1.5 w-1.5 rounded-full",
            connected ? "bg-tertiary animate-soft-pulse" : "bg-error",
          )}
        />
        {connected ? "ONLINE" : "OFFLINE"}
      </span>

      {status && (
        <>
          <span className="pill bg-surface-container-high text-on-surface-variant">
            <Clock className="h-3 w-3" />
            {status.uptimeHuman}
          </span>

          <span className="pill bg-surface-container-high text-on-surface-variant">
            <Activity className="h-3 w-3" />
            {status.activeAgents}
            <span className="text-outline">agents</span>
            {status.runningAgents > 0 && (
              <span className="text-tertiary">{status.runningAgents}run</span>
            )}
            {status.blockedAgents > 0 && (
              <span className="text-primary">{status.blockedAgents}blk</span>
            )}
          </span>

          <span className="pill bg-surface-container-high text-on-surface-variant">
            <HardDrive className="h-3 w-3" />
            {status.tokensUsed.toLocaleString()}
            <span className="text-outline">tok</span>
          </span>

          <span
            className={cn(
              "pill",
              status.llmAvailable
                ? "bg-surface-container-high text-on-surface-variant"
                : "bg-error-container/30 text-error",
            )}
          >
            <Cpu className="h-3 w-3" />
            {status.llmAvailable ? "LLM" : "LLM DOWN"}
          </span>

          <span
            className={cn(
              "pill",
              status.watchdogHealthy
                ? "bg-surface-container-high text-on-surface-variant"
                : "bg-error-container/30 text-error",
            )}
          >
            {status.watchdogHealthy ? (
              <Shield className="h-3 w-3" />
            ) : (
              <AlertTriangle className="h-3 w-3 animate-soft-pulse" />
            )}
            {status.watchdogHealthy ? "WDT" : "WDT!"}
          </span>

          <span className="pill bg-surface-container text-outline">
            tick#{status.systemTick}
          </span>
        </>
      )}
    </div>
  );
}
