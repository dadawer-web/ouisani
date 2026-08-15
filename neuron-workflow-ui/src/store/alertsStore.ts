import { create } from "zustand";
import { AIOS_WS_URL } from "../config";

// ════════════════════════════════════════════════════════════════
//  alertsStore —— 后端系统告警流
//   连 /api/system/alerts WS，接收包装格式 {channel, message}，
//   按 channel 映射 severity，提取 title/detail，主界面常驻展示。
//   频道：sys.kernel.panic / emergency_halt / sys.dlq.* / sys.cost.warning
//        / sys.workflow.suspended / sys.workflow.node_resumed
//        / sys.security.* / agent.heartbeat
// ════════════════════════════════════════════════════════════════

export type Severity = "critical" | "warning" | "info";

export interface AlertEntry {
  id: string;
  channel: string;
  severity: Severity;
  timestamp: number;
  title: string;
  detail: string;
}

type AlertPayload = Record<string, unknown>;

interface AlertsState {
  alerts: AlertEntry[];
  connected: boolean;
  unread: number;
  connect: () => void;
  disconnect: () => void;
  markAllRead: () => void;
  dismiss: (id: string) => void;
  clearAll: () => void;
}

const MAX_ALERTS = 200;
const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_DELAY_MS = 3_000;

// ── 频道 → severity 映射 ──
const CRITICAL = new Set(["sys.kernel.panic", "emergency_halt"]);
const WARNING = new Set([
  "sys.dlq.entry_added",
  "sys.cost.warning",
  "sys.workflow.suspended",
  "sys.security.vulnerability_found",
]);

function severityOf(channel: string): Severity {
  if (CRITICAL.has(channel)) return "critical";
  if (WARNING.has(channel)) return "warning";
  return "info";
}

let _idCounter = 0;
const uid = () => `alt_${++_idCounter}_${Date.now().toString(36)}`;

/** 取对象的字符串字段，容错 */
const str = (v: unknown, d = ""): string =>
  v == null ? d : typeof v === "string" ? v : String(v);

/** 按频道从 message payload 提取 title / detail */
function extract(channel: string, m: AlertPayload): { title: string; detail: string } {
  switch (channel) {
    case "sys.kernel.panic":
      return {
        title: `Kernel Panic · ${m.agentId || "?"}`,
        detail: [m.message, m.stackTrace].filter(Boolean).join("\n"),
      };
    case "emergency_halt":
      return {
        title: `Emergency Halt · ${m.agentId || "?"}`,
        detail: [m.reason, m.stackTrace].filter(Boolean).join("\n"),
      };
    case "sys.dlq.entry_added":
      return {
        title: `DLQ Entry · ${m.agentId || "?"}`,
        detail: `${str(m.message)} [${str(m.errorType)}] retry=${m.retryCount ?? 0}`,
      };
    case "sys.dlq.retry_requested":
      return { title: `DLQ Retry · ${str(m.entryId)}`, detail: `retry #${m.retryCount ?? 0}` };
    case "sys.dlq.dismissed":
      return { title: `DLQ Dismissed · ${str(m.entryId)}`, detail: str(m.reason) };
    case "sys.dlq.resolved":
      return {
        title: `DLQ Resolved · ${str(m.entryId)}`,
        detail: `success=${m.success} · resolvedAt=${m.resolvedAt ?? ""}`,
      };
    case "sys.cost.warning":
      return {
        title: `Cost Warning · ${m.agentId || "?"}`,
        detail: `${m.cost} ≥ ${m.threshold} · ${str(m.message)}`,
      };
    case "sys.workflow.suspended":
      return {
        title: `Workflow Suspended · ${m.nodeId || m.workflowId || "?"}`,
        detail: str(m.reason),
      };
    case "sys.workflow.node_resumed":
      return {
        title: `Node Resumed · ${m.nodeId || "?"}`,
        detail: `workflow=${str(m.workflowId)} · agent=${str(m.agentId)}`,
      };
    case "sys.security.vulnerability_found":
      return {
        title: `Vulnerability · ${str(m.severity).toUpperCase()}`,
        detail: `${str(m.vulnerabilityId)}: ${str(m.description)}`,
      };
    case "sys.security.audit_complete":
      return {
        title: `Audit ${str(m.status)}`,
        detail: `${str(m.auditId)} ${str(m.reportUrl)}`.trim(),
      };
    case "agent.heartbeat":
      return {
        title: `Heartbeat · ${m.agentId || "?"}`,
        detail: `${str(m.status)} · cpu ${m.cpuUsage ?? "?"}% mem ${m.memoryUsage ?? "?"}`,
      };
    default:
      return { title: channel, detail: JSON.stringify(m) ?? "" };
  }
}

export const useAlertsStore = create<AlertsState>((set, get) => ({
  alerts: [],
  connected: false,
  unread: 0,

  connect: () => {
    if (get().connected) return;

    const ws = new WebSocket(
      `${AIOS_WS_URL}/api/system/alerts?token=AIOS-SUPER-SECRET-KEY`,
    );

    let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

    ws.onopen = () => {
      console.log("[Alerts] WebSocket connected");
      set({ connected: true });
      heartbeatTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "PING" }));
      }, HEARTBEAT_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data);
        if (raw.type === "PONG") return;

        // 后端包装格式：{ channel, message }
        const channel: string = raw.channel;
        if (!channel) return; // 非告警消息，忽略

        // message 可能是对象或字符串
        let m = raw.message;
        if (typeof m === "string") {
          try { m = JSON.parse(m); } catch { /* 纯字符串，保留 */ }
        }
        const obj: AlertPayload = m && typeof m === "object" ? m as AlertPayload : { payload: m };

        const { title, detail } = extract(channel, obj);
        const entry: AlertEntry = {
          id: uid(),
          channel,
          severity: severityOf(channel),
          timestamp: typeof obj.timestamp === "number" ? obj.timestamp : Date.now(),
          title,
          detail,
        };

        set((state) => ({
          alerts: [entry, ...state.alerts].slice(0, MAX_ALERTS),
          unread: state.unread + 1,
        }));
      } catch {
        console.warn("[Alerts] Failed to parse message:", event.data);
      }
    };

    ws.onclose = () => {
      console.log("[Alerts] WebSocket disconnected");
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      set({ connected: false });
      setTimeout(() => {
        if (!get().connected) {
          console.log("[Alerts] Reconnecting...");
          get().connect();
        }
      }, RECONNECT_DELAY_MS);
    };

    ws.onerror = () => {
      console.warn("[Alerts] WebSocket error");
    };
  },

  disconnect: () => {
    set({ connected: false });
  },

  markAllRead: () => set({ unread: 0 }),

  dismiss: (id) =>
    set((state) => ({ alerts: state.alerts.filter((a) => a.id !== id) })),

  clearAll: () => set({ alerts: [], unread: 0 }),
}));
