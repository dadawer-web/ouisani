/**
 * TelemetryRadar — AIOS 可观测性雷达大屏组件
 *
 * 实时可视化底层 Agent 协作与自愈状态：
 *   - Agent 节点：蓝色呼吸灯 (RUNNING) / 常亮绿色 (SUCCESS) / 红色震动 (HEALING)
 *   - 通讯飞梭：光点从 Sender 飞向 Receiver，贝塞尔曲线轨迹
 *   - 自愈警报：红色震动 + 气泡 Toast 显示重试信息
 *   - 操作流瀑布：打字机效果逐条打印 AST/文件操作日志
 *
 * 接入方式：在 App.tsx 中引入并放置到合适位置即可。
 *   import TelemetryRadar from "@/components/TelemetryRadar";
 *   <TelemetryRadar />
 *
 * 数据源：通过 useTelemetryStore 连接 WebSocket 接收后端事件。
 */

import { useEffect, useRef, useCallback, useState } from "react";
import { useTelemetryStore, type RadarAgent, type MailShuttle } from "@/store/telemetryStore";
import { cn } from "@/lib/utils";
import { Radio, Activity, Send, AlertTriangle, FileCode } from "lucide-react";

// ════════════════════════════════════════════════════════════════
//  常量
// ════════════════════════════════════════════════════════════════

const SHUTTLE_DURATION = 500; // 飞梭动画持续时间 (ms)
const TYPING_SPEED = 30;      // 打字机效果速度 (ms/字符)

// ════════════════════════════════════════════════════════════════
//  子组件：Agent 节点
// ════════════════════════════════════════════════════════════════

/** 节点外圈样式映射 */
const nodeStyles: Record<string, { ring: string; glow: string; label: string }> = {
  IDLE: {
    ring: "border-zinc-600",
    glow: "",
    label: "text-zinc-500",
  },
  RUNNING: {
    ring: "border-cyan-400/60",
    glow: "shadow-[0_0_20px_rgba(0,255,255,0.4),0_0_40px_rgba(0,255,255,0.15)]",
    label: "text-cyan-300",
  },
  SUCCESS: {
    ring: "border-emerald-400/70",
    glow: "shadow-[0_0_15px_rgba(52,211,153,0.5)]",
    label: "text-emerald-300",
  },
  HEALING: {
    ring: "border-red-500/80",
    glow: "shadow-[0_0_25px_rgba(239,68,68,0.6),0_0_50px_rgba(239,68,68,0.25)]",
    label: "text-red-300",
  },
  FAILED: {
    ring: "border-red-700",
    glow: "shadow-[0_0_20px_rgba(185,28,28,0.5)]",
    label: "text-red-400",
  },
};

function AgentNode({ agent }: { agent: RadarAgent }) {
  const style = nodeStyles[agent.status] || nodeStyles.IDLE;
  const isHealing = agent.status === "HEALING";
  const isRunning = agent.status === "RUNNING";

  return (
    <div
      className="absolute -translate-x-1/2 -translate-y-1/2"
      style={{ left: `${agent.x * 100}%`, top: `${agent.y * 100}%` }}
    >
      {/* 节点主体 */}
      <div
        className={cn(
          "relative flex h-14 w-14 items-center justify-center rounded-full border-2 transition-all duration-300",
          style.ring,
          style.glow,
          // RUNNING 蓝色呼吸灯
          isRunning && "animate-pulse",
          // HEALING 红色震动
          isHealing && "animate-shake"
        )}
        style={{
          background: "radial-gradient(circle, rgba(10,10,15,0.95) 0%, rgba(5,5,10,0.98) 100%)",
        }}
      >
        {/* 内圈指示灯 */}
        <div
          className={cn(
            "h-4 w-4 rounded-full transition-colors duration-300",
            agent.status === "IDLE" && "bg-zinc-600",
            agent.status === "RUNNING" && "bg-cyan-400 shadow-[0_0_8px_rgba(0,255,255,0.8)]",
            agent.status === "SUCCESS" && "bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.8)]",
            agent.status === "HEALING" && "bg-red-500 shadow-[0_0_8px_rgba(239,68,68,0.8)]",
            agent.status === "FAILED" && "bg-red-700"
          )}
        />
      </div>

      {/* 节点标签 */}
      <div
        className={cn(
          "mt-1 text-center text-[9px] font-mono font-bold uppercase tracking-wider whitespace-nowrap",
          style.label
        )}
      >
        {agent.label.length > 12 ? agent.label.slice(0, 12) + "…" : agent.label}
      </div>

      {/* 自愈气泡 */}
      {agent.healingBubble?.visible && (
        <div className="absolute -top-16 left-1/2 -translate-x-1/2 animate-fade-in">
          <div className="relative rounded-lg border border-red-500/50 bg-red-950/90 px-3 py-2 shadow-[0_0_20px_rgba(239,68,68,0.3)] backdrop-blur-sm">
            <div className="flex items-center gap-1.5 text-[9px] font-mono">
              <AlertTriangle className="h-3 w-3 text-red-400 shrink-0" />
              <span className="text-red-200 font-bold">
                Attempt {agent.healingBubble.attempt}/{agent.healingBubble.maxAttempts}
              </span>
            </div>
            <div className="mt-0.5 text-[8px] text-red-400/70 max-w-[160px] truncate">
              Injecting context and healing...
            </div>
            {/* 气泡箭头 */}
            <div className="absolute -bottom-1 left-1/2 -translate-x-1/2 h-2 w-2 rotate-45 border-b border-r border-red-500/50 bg-red-950/90" />
          </div>
        </div>
      )}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════
//  子组件：飞梭动画 (Canvas 叠加层)
// ════════════════════════════════════════════════════════════════

function ShuttleCanvas({
  agents,
  shuttles,
}: {
  agents: Map<string, RadarAgent>;
  shuttles: MailShuttle[];
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animFrameRef = useRef<number>(0);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, rect.width, rect.height);

    const now = Date.now();

    for (const shuttle of shuttles) {
      const sender = agents.get(shuttle.senderId);
      const receiver = agents.get(shuttle.receiverId);
      if (!sender || !receiver) continue;

      const elapsed = now - shuttle.startedAt;
      const progress = Math.min(elapsed / shuttle.duration, 1);

      // 贝塞尔曲线控制点：从 sender 到 receiver，中间偏移形成弧线
      const sx = sender.x * rect.width;
      const sy = sender.y * rect.height;
      const ex = receiver.x * rect.width;
      const ey = receiver.y * rect.height;

      // 控制点偏移 — 垂直于连线方向
      const mx = (sx + ex) / 2;
      const my = (sy + ey) / 2;
      const dx = ex - sx;
      const dy = ey - sy;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const offset = dist * 0.25; // 弧线偏移量
      const cx = mx - (dy / dist) * offset;
      const cy = my + (dx / dist) * offset;

      // 二次贝塞尔曲线上的点
      const t = progress;
      const px = (1 - t) * (1 - t) * sx + 2 * (1 - t) * t * cx + t * t * ex;
      const py = (1 - t) * (1 - t) * sy + 2 * (1 - t) * t * cy + t * t * ey;

      // 绘制轨迹线 (渐隐)
      const trailSteps = 8;
      for (let i = 0; i < trailSteps; i++) {
        const tt = Math.max(0, t - (i / trailSteps) * 0.15);
        const tx = (1 - tt) * (1 - tt) * sx + 2 * (1 - tt) * tt * cx + tt * tt * ex;
        const ty = (1 - tt) * (1 - tt) * sy + 2 * (1 - tt) * tt * cy + tt * tt * ey;
        const alpha = (1 - i / trailSteps) * 0.3;

        ctx.beginPath();
        ctx.arc(tx, ty, 2 - i * 0.15, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(0, 255, 255, ${alpha})`;
        ctx.fill();
      }

      // 绘制飞梭光点
      const glowRadius = 6 + Math.sin(now / 100) * 2;
      const gradient = ctx.createRadialGradient(px, py, 0, px, py, glowRadius);
      gradient.addColorStop(0, "rgba(0, 255, 255, 0.9)");
      gradient.addColorStop(0.4, "rgba(0, 255, 255, 0.4)");
      gradient.addColorStop(1, "rgba(0, 255, 255, 0)");

      ctx.beginPath();
      ctx.arc(px, py, glowRadius, 0, Math.PI * 2);
      ctx.fillStyle = gradient;
      ctx.fill();

      // 中心亮点
      ctx.beginPath();
      ctx.arc(px, py, 2, 0, Math.PI * 2);
      ctx.fillStyle = "rgba(255, 255, 255, 0.95)";
      ctx.fill();
    }

    animFrameRef.current = requestAnimationFrame(draw);
  }, [agents, shuttles]);

  useEffect(() => {
    animFrameRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animFrameRef.current);
  }, [draw]);

  return (
    <canvas
      ref={canvasRef}
      className="pointer-events-none absolute inset-0 h-full w-full"
    />
  );
}

// ════════════════════════════════════════════════════════════════
//  子组件：操作流瀑布 (Log Waterfall)
// ════════════════════════════════════════════════════════════════

function LogWaterfall() {
  const logs = useTelemetryStore((s) => s.logs);
  const scrollRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs.length]);

  return (
    <div className="flex h-full flex-col overflow-hidden rounded-md border border-green-500/10 bg-black/90 backdrop-blur-md">
      {/* 标题栏 */}
      <div className="flex items-center gap-2 border-b border-green-500/10 bg-green-950/20 px-3 py-2">
        <FileCode className="h-3 w-3 text-green-400" />
        <span className="text-[10px] uppercase tracking-[0.2em] text-green-400/80">
          Operation Stream
        </span>
        <span className="ml-auto text-[9px] text-green-700">
          {logs.length}/{100}
        </span>
      </div>

      {/* 日志流 */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto p-2 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-green-800/30"
      >
        {logs.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <span className="animate-pulse text-[10px] text-green-800/40">
              Waiting for operations...
            </span>
          </div>
        ) : (
          logs.map((log) => (
            <LogLine key={log.id} entry={log} />
          ))
        )}
      </div>
    </div>
  );
}

/** 单条日志 — 打字机效果 */
function LogLine({ entry }: { entry: { id: string; text: string; typing: boolean } }) {
  const [displayedText, setDisplayedText] = useState("");
  const hasTyped = useRef(false);

  useEffect(() => {
    if (!entry.typing || hasTyped.current) {
      setDisplayedText(entry.text);
      return;
    }

    hasTyped.current = true;
    let i = 0;
    const timer = setInterval(() => {
      i++;
      setDisplayedText(entry.text.slice(0, i));
      if (i >= entry.text.length) {
        clearInterval(timer);
      }
    }, TYPING_SPEED);

    return () => clearInterval(timer);
  }, [entry.text, entry.typing]);

  const isHeal = entry.text.includes("[SELF-HEAL]");
  const isAst = entry.text.includes("[AST]");

  return (
    <div
      className={cn(
        "border-b border-green-500/5 py-0.5 text-[9px] leading-relaxed font-mono",
        isHeal && "text-red-400/80",
        isAst && "text-cyan-400/80",
        !isHeal && !isAst && "text-green-400/80"
      )}
    >
      {displayedText}
      {displayedText.length < entry.text.length && (
        <span className="animate-pulse text-green-300">▌</span>
      )}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════
//  主组件：TelemetryRadar
// ════════════════════════════════════════════════════════════════

export default function TelemetryRadar() {
  const agents = useTelemetryStore((s) => s.agents);
  const shuttles = useTelemetryStore((s) => s.shuttles);
  const connected = useTelemetryStore((s) => s.connected);
  const connect = useTelemetryStore((s) => s.connect);

  // 连接 WebSocket
  useEffect(() => {
    connect();
  }, [connect]);

  // 清理过期飞梭
  useEffect(() => {
    const timer = setInterval(() => {
      const now = Date.now();
      useTelemetryStore.setState((state) => ({
        shuttles: state.shuttles.filter(
          (s) => now - s.startedAt < s.duration + 200
        ),
      }));
    }, 500);
    return () => clearInterval(timer);
  }, []);

  const agentList = Array.from(agents.values());

  return (
    <div className="flex h-full w-full flex-col gap-3 p-3 font-mono">
      {/* ═══ 顶栏：雷达标题 + 连接状态 ═══ */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 rounded-md border border-cyan-500/20 bg-cyan-950/20 px-3 py-1.5">
          <Radio className="h-3.5 w-3.5 text-cyan-400" />
          <span className="text-[10px] font-bold uppercase tracking-[0.25em] text-cyan-300">
            Telemetry Radar
          </span>
        </div>

        <div className="flex items-center gap-2 text-[9px] text-zinc-500">
          <Activity className="h-3 w-3" />
          <span>{agentList.length} agents</span>
          <span className="text-zinc-700">|</span>
          <span>{shuttles.length} in-flight</span>
        </div>

        <div className="ml-auto flex items-center gap-1.5">
          <div
            className={cn(
              "h-1.5 w-1.5 rounded-full",
              connected ? "bg-emerald-400 animate-pulse" : "bg-red-500"
            )}
          />
          <span className="text-[9px] text-zinc-600">
            {connected ? "LIVE" : "OFFLINE"}
          </span>
        </div>
      </div>

      {/* ═══ 主内容区：雷达画布 + 操作流瀑布 ═══ */}
      <div className="grid min-h-0 flex-1 grid-cols-[1fr_280px] gap-3">
        {/* ── 左侧：雷达画布 ── */}
        <div
          className="relative overflow-hidden rounded-md border border-cyan-500/10 bg-[#050510]/95 backdrop-blur-xl"
          style={{
            boxShadow:
              "0 0 30px rgba(0,255,255,0.05), inset 0 0 60px rgba(0,255,255,0.02)",
          }}
        >
          {/* 雷达网格背景 */}
          <div className="absolute inset-0 opacity-20">
            {/* 同心圆 */}
            {[0.15, 0.3, 0.45].map((r, i) => (
              <div
                key={i}
                className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full border border-cyan-500/20"
                style={{
                  width: `${r * 2 * 100}%`,
                  height: `${r * 2 * 100}%`,
                }}
              />
            ))}
            {/* 十字线 */}
            <div className="absolute left-1/2 top-0 h-full w-px -translate-x-1/2 bg-gradient-to-b from-transparent via-cyan-500/20 to-transparent" />
            <div className="absolute left-0 top-1/2 h-px w-full -translate-y-1/2 bg-gradient-to-r from-transparent via-cyan-500/20 to-transparent" />
          </div>

          {/* 中心标记 */}
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
            <div className="h-1.5 w-1.5 rounded-full bg-cyan-500/40" />
          </div>

          {/* Agent 节点层 */}
          {agentList.map((agent) => (
            <AgentNode key={agent.id} agent={agent} />
          ))}

          {/* 飞梭动画 Canvas 层 */}
          <ShuttleCanvas agents={agents} shuttles={shuttles} />

          {/* 空状态 */}
          {agentList.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="text-center">
                <Send className="mx-auto h-8 w-8 text-cyan-800/30" />
                <p className="mt-2 text-[10px] text-cyan-700/40">
                  Waiting for telemetry events...
                </p>
                <p className="mt-1 text-[9px] text-cyan-800/30">
                  Deploy a workflow to see agent activity
                </p>
              </div>
            </div>
          )}
        </div>

        {/* ── 右侧：操作流瀑布 ── */}
        <LogWaterfall />
      </div>
    </div>
  );
}
