/**
 * TelemetryRadar — AIOS 可观测性雷达大屏组件
 *
 * 实时可视化底层 Agent 协作与自愈状态：
 *   - Agent 节点：古铜呼吸灯 (RUNNING) / 常亮苔绿 (SUCCESS) / 朱红震动 (HEALING)
 *   - 通讯飞梭：光点从 Sender 飞向 Receiver，贝塞尔曲线轨迹
 *   - 自愈警报：朱红震动 + 气泡 Toast 显示重试信息
 *   - 操作流瀑布：打字机效果逐条打印 AST/文件操作日志
 *
 * 视觉语言对齐 cc-haha「Technical Atelier」：暖纸/古铜/苔绿/朱红，无霓虹发光。
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
//  CSS 变量读取 —— 让 canvas 颜色随明暗主题翻转
// ════════════════════════════════════════════════════════════════

/** 从 :root 读取形如 "143 72 47" 的 RGB 通道串，供 canvas 拼 rgba() 用。 */
function readRgbVar(name: string, fallback: string): string {
  if (typeof window === "undefined") return fallback;
  const raw = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
  return raw || fallback;
}

// 缓存主题色，避免每帧 getComputedStyle；每 500ms 刷新一次以捕捉主题切换。
let primaryRgbCache = "143 72 47";
let outlineVariantRgbCache = "218 193 186";
let lastVarRefresh = 0;
function refreshThemeVars(): void {
  const now = performance.now();
  if (now - lastVarRefresh < 500) return;
  lastVarRefresh = now;
  primaryRgbCache = readRgbVar("--primary", "143 72 47");
  outlineVariantRgbCache = readRgbVar("--outline-variant", "218 193 186");
}

// ════════════════════════════════════════════════════════════════
//  子组件：Agent 节点
// ════════════════════════════════════════════════════════════════

/** 节点外圈样式映射 —— 对齐 cc-haha 语义色，去霓虹发光。 */
const nodeStyles: Record<string, { ring: string; label: string; dot: string }> = {
  IDLE: {
    ring: "ring-outline/40",
    label: "text-outline",
    dot: "bg-outline/60",
  },
  RUNNING: {
    ring: "ring-primary/70",
    label: "text-primary",
    dot: "bg-primary",
  },
  SUCCESS: {
    ring: "ring-tertiary/70",
    label: "text-tertiary",
    dot: "bg-tertiary",
  },
  HEALING: {
    ring: "ring-error/80",
    label: "text-error",
    dot: "bg-error",
  },
  FAILED: {
    ring: "ring-error",
    label: "text-error",
    dot: "bg-error",
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
      {/* 节点主体 —— surface-container-lowest 浮起卡片 + 幽灵环 */}
      <div
        className={cn(
          "relative flex h-14 w-14 items-center justify-center rounded-full bg-surface-container-lowest ring-2 transition-all duration-300",
          style.ring,
          // RUNNING 古铜呼吸灯
          isRunning && "animate-soft-pulse",
          // HEALING 朱红震动
          isHealing && "animate-shake"
        )}
      >
        {/* 内圈指示灯 */}
        <div
          className={cn(
            "h-4 w-4 rounded-full transition-colors duration-300",
            style.dot
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

      {/* 自愈气泡 —— error-container 提示，无霓虹发光 */}
      {agent.healingBubble?.visible && (
        <div className="absolute -top-16 left-1/2 -translate-x-1/2 animate-fade-in">
          <div className="relative rounded-lg bg-error-container px-3 py-2 ambient-shadow-sm">
            <div className="flex items-center gap-1.5 text-[9px] font-mono">
              <AlertTriangle className="h-3 w-3 text-error shrink-0" />
              <span className="font-bold text-on-error-container">
                Attempt {agent.healingBubble.attempt}/{agent.healingBubble.maxAttempts}
              </span>
            </div>
            <div className="mt-0.5 text-[8px] text-on-error-container/70 max-w-[160px] truncate">
              Injecting context and healing...
            </div>
            {/* 气泡箭头 */}
            <div className="absolute -bottom-1 left-1/2 h-2 w-2 -translate-x-1/2 rotate-45 bg-error-container" />
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

    // 每帧刷新一次主题色缓存（内部有 500ms 节流）
    refreshThemeVars();

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

      // 绘制轨迹线 (渐隐) —— 古铜色尾迹
      const trailSteps = 8;
      for (let i = 0; i < trailSteps; i++) {
        const tt = Math.max(0, t - (i / trailSteps) * 0.15);
        const tx = (1 - tt) * (1 - tt) * sx + 2 * (1 - tt) * tt * cx + tt * tt * ex;
        const ty = (1 - tt) * (1 - tt) * sy + 2 * (1 - tt) * tt * cy + tt * tt * ey;
        const alpha = (1 - i / trailSteps) * 0.3;

        ctx.beginPath();
        ctx.arc(tx, ty, 2 - i * 0.15, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(${primaryRgbCache}, ${alpha})`;
        ctx.fill();
      }

      // 绘制飞梭光点 —— 古铜色辉光（无霓虹，仅柔和径向渐变）
      const glowRadius = 6 + Math.sin(now / 100) * 2;
      const gradient = ctx.createRadialGradient(px, py, 0, px, py, glowRadius);
      gradient.addColorStop(0, `rgba(${primaryRgbCache}, 0.9)`);
      gradient.addColorStop(0.4, `rgba(${primaryRgbCache}, 0.4)`);
      gradient.addColorStop(1, `rgba(${primaryRgbCache}, 0)`);

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
    <div className="flex h-full flex-col overflow-hidden rounded-lg bg-surface-dim ghost-border">
      {/* 标题栏 */}
      <div className="flex items-center gap-2 bg-surface-container px-3 py-2">
        <FileCode className="h-3 w-3 text-primary" />
        <span className="text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
          Operation Stream
        </span>
        <span className="ml-auto text-[9px] text-outline">
          {logs.length}/{100}
        </span>
      </div>

      {/* 日志流 */}
      <div
        ref={scrollRef}
        className="custom-scrollbar flex-1 overflow-y-auto p-2"
      >
        {logs.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <span className="animate-soft-pulse text-[10px] text-outline/50">
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
        "py-0.5 text-[9px] leading-relaxed font-mono",
        isHeal && "text-error",
        isAst && "text-primary",
        !isHeal && !isAst && "text-on-surface-variant"
      )}
    >
      {displayedText}
      {displayedText.length < entry.text.length && (
        <span className="animate-soft-pulse text-primary">▌</span>
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
    <div className="flex h-full w-full flex-col gap-3 p-3">
      {/* ═══ 顶栏：雷达标题 + 连接状态 ═══ */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2 rounded-lg bg-primary-container/30 px-3 py-1.5">
          <Radio className="h-3.5 w-3.5 text-primary" />
          <span className="font-headline text-[10px] font-bold uppercase tracking-[0.25em] text-primary">
            Telemetry Radar
          </span>
        </div>

        <div className="flex items-center gap-2 text-[9px] text-outline">
          <Activity className="h-3 w-3" />
          <span className="font-mono">{agentList.length} agents</span>
          <span className="text-outline/40">·</span>
          <span className="font-mono">{shuttles.length} in-flight</span>
        </div>

        <div className="ml-auto flex items-center gap-1.5">
          <div
            className={cn(
              "h-1.5 w-1.5 rounded-full",
              connected ? "bg-tertiary animate-soft-pulse" : "bg-error"
            )}
          />
          <span className="font-mono text-[9px] uppercase tracking-wider text-outline">
            {connected ? "LIVE" : "OFFLINE"}
          </span>
        </div>
      </div>

      {/* ═══ 主内容区：雷达画布 + 操作流瀑布 ═══ */}
      <div className="grid min-h-0 flex-1 grid-cols-[1fr_280px] gap-3">
        {/* ── 左侧：雷达画布 ── */}
        <div className="relative overflow-hidden rounded-lg bg-surface-dim ghost-border">
          {/* 雷达网格背景 */}
          <div className="absolute inset-0 opacity-30">
            {/* 同心圆 */}
            {[0.15, 0.3, 0.45].map((r, i) => (
              <div
                key={i}
                className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full border border-outline-variant/40"
                style={{
                  width: `${r * 2 * 100}%`,
                  height: `${r * 2 * 100}%`,
                }}
              />
            ))}
            {/* 十字线 */}
            <div className="absolute left-1/2 top-0 h-full w-px -translate-x-1/2 bg-gradient-to-b from-transparent via-outline-variant/40 to-transparent" />
            <div className="absolute left-0 top-1/2 h-px w-full -translate-y-1/2 bg-gradient-to-r from-transparent via-outline-variant/40 to-transparent" />
          </div>

          {/* 中心标记 */}
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
            <div className="h-1.5 w-1.5 rounded-full bg-primary/40" />
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
                <Send className="mx-auto h-8 w-8 text-outline/30" />
                <p className="mt-2 text-[10px] text-outline/50">
                  Waiting for telemetry events...
                </p>
                <p className="mt-1 text-[9px] text-outline/40">
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
