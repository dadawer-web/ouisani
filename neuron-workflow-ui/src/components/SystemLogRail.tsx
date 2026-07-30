import { useState } from "react";
import {
  ChevronDown,
  ChevronUp,
  Trash2,
  Activity,
  AlertTriangle,
  AlertOctagon,
  Circle,
} from "lucide-react";
import { useAlertsStore, type AlertEntry, type Severity } from "@/store/alertsStore";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  SystemLogRail —— 主界面常驻系统告警条
//   收起态：薄条显示最近一条告警 + 未读计数；展开态：滚动列表 + 清空。
//   挂在 ChatSurface 的 composer 上方，chat/workflow 模式都显示。
// ════════════════════════════════════════════════════════════════

const SEV_STYLE: Record<Severity, { dot: string; text: string; icon: typeof Circle }> = {
  critical: { dot: "bg-error animate-soft-pulse", text: "text-error", icon: AlertOctagon },
  warning: { dot: "bg-primary", text: "text-primary", icon: AlertTriangle },
  info: { dot: "bg-outline/50", text: "text-outline", icon: Circle },
};

function fmtTime(ts: number) {
  const d = new Date(ts);
  return (
    d.toLocaleTimeString("en-US", { hour12: false }) +
    "." + String(d.getMilliseconds()).padStart(3, "0")
  );
}

/** 频道名简化：sys.kernel.panic → kernel.panic，agent.heartbeat → agent.heartbeat */
function shortChannel(ch: string) {
  return ch.startsWith("sys.") ? ch.slice(4) : ch;
}

export default function SystemLogRail() {
  const alerts = useAlertsStore((s) => s.alerts);
  const unread = useAlertsStore((s) => s.unread);
  const connected = useAlertsStore((s) => s.connected);
  const markAllRead = useAlertsStore((s) => s.markAllRead);
  const clearAll = useAlertsStore((s) => s.clearAll);
  const dismiss = useAlertsStore((s) => s.dismiss);

  const [expanded, setExpanded] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);

  // 收起态优先显示最高 severity 的一条
  const headline: AlertEntry | undefined =
    alerts.find((a) => a.severity === "critical") ??
    alerts.find((a) => a.severity === "warning") ??
    alerts[0];

  const toggle = () => {
    const next = !expanded;
    setExpanded(next);
    if (next) markAllRead();
  };

  // ── 收起态：薄条 ──
  if (!expanded) {
    const hasAlert = !!headline;
    const Icon = hasAlert ? SEV_STYLE[headline.severity].icon : Activity;
    return (
      <div className="flex h-8 flex-shrink-0 items-center gap-2 border-t border-outline-variant/15 bg-surface-container-low px-4">
        <span
          className={cn(
            "h-1.5 w-1.5 rounded-full",
            hasAlert ? SEV_STYLE[headline.severity].dot : "bg-tertiary animate-soft-pulse",
          )}
        />
        <Icon
          className={cn(
            "h-3 w-3 flex-shrink-0",
            hasAlert ? SEV_STYLE[headline.severity].text : "text-tertiary",
          )}
        />
        <span className="flex min-w-0 flex-1 items-center gap-2 text-[11px]">
          {hasAlert ? (
            <>
              <span className="shrink-0 font-mono text-outline/60">{fmtTime(headline.timestamp)}</span>
              <span className={cn("truncate font-medium", SEV_STYLE[headline.severity].text)}>
                {headline.title}
              </span>
            </>
          ) : (
            <span className="text-outline">
              系统正常 · {connected ? "告警流在线" : "告警流离线"}
            </span>
          )}
        </span>

        {unread > 0 && (
          <span className="rounded-full bg-error/15 px-1.5 py-0.5 text-[9px] font-bold text-error">
            {unread} 新
          </span>
        )}
        {alerts.length > 0 && (
          <span className="font-mono text-[9px] text-outline/50">{alerts.length}</span>
        )}
        <button
          onClick={toggle}
          className="flex items-center gap-0.5 rounded px-1 py-0.5 text-outline transition-colors hover:bg-surface-container-high hover:text-on-surface"
          title="展开告警列表"
        >
          <ChevronUp className="h-3.5 w-3.5" />
        </button>
      </div>
    );
  }

  // ── 展开态：列表 ──
  return (
    <div className="flex h-44 flex-shrink-0 flex-col border-t border-outline-variant/15 bg-surface-container-low">
      {/* 顶栏 */}
      <div className="flex h-8 flex-shrink-0 items-center gap-2 border-b border-outline-variant/10 px-4">
        <Activity className="h-3.5 w-3.5 text-tertiary" />
        <span className="text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">
          系统告警
        </span>
        <span
          className={cn(
            "flex items-center gap-1 text-[9px]",
            connected ? "text-tertiary" : "text-error",
          )}
        >
          <span className={cn("h-1.5 w-1.5 rounded-full", connected ? "bg-tertiary animate-soft-pulse" : "bg-error")} />
          {connected ? "LIVE" : "OFFLINE"}
        </span>
        <span className="font-mono text-[9px] text-outline/50">{alerts.length}/{200}</span>
        <div className="ml-auto flex items-center gap-1">
          {alerts.length > 0 && (
            <button
              onClick={clearAll}
              className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[9px] text-outline transition-colors hover:bg-surface-container-high hover:text-error"
              title="清空"
            >
              <Trash2 className="h-3 w-3" />
              清空
            </button>
          )}
          <button
            onClick={toggle}
            className="flex items-center rounded px-1 py-0.5 text-outline transition-colors hover:bg-surface-container-high hover:text-on-surface"
            title="收起"
          >
            <ChevronDown className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {/* 列表 */}
      <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto px-2 py-1">
        {alerts.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <span className="animate-soft-pulse text-[10px] text-outline/50">
              等待系统事件…
            </span>
          </div>
        ) : (
          alerts.map((a, i) => {
            const st = SEV_STYLE[a.severity];
            const Icon = st.icon;
            const isOpen = openId === a.id;
            return (
              <div
                key={a.id}
                className="rounded-md transition-colors hover:bg-surface-container-high/50"
                style={{ opacity: Math.max(0.4, 1 - i * 0.008) }}
              >
                <button
                  onClick={() => setOpenId(isOpen ? null : a.id)}
                  className="flex w-full items-center gap-2 px-2 py-1 text-left"
                >
                  <Icon className={cn("h-3 w-3 flex-shrink-0", st.text)} />
                  <span className="shrink-0 font-mono text-[9px] text-outline/60">
                    {fmtTime(a.timestamp)}
                  </span>
                  <span className="shrink-0 rounded bg-surface-container-high px-1 py-0.5 font-mono text-[8px] text-outline">
                    {shortChannel(a.channel)}
                  </span>
                  <span className={cn("truncate text-[10px] font-medium", st.text)}>
                    {a.title}
                  </span>
                </button>
                {isOpen && a.detail && (
                  <pre className="mx-2 mb-1 max-h-24 overflow-auto whitespace-pre-wrap rounded bg-surface-dim p-2 font-mono text-[9px] leading-relaxed text-on-surface-variant">
                    {a.detail}
                  </pre>
                )}
                {isOpen && (
                  <button
                    onClick={() => {
                      dismiss(a.id);
                      setOpenId(null);
                    }}
                    className="mb-1 ml-7 text-[8px] text-outline/50 underline hover:text-error"
                  >
                    忽略此条
                  </button>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
