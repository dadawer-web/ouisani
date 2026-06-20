import { create } from "zustand";
import { AIOS_WS_URL } from "../config";

// ════════════════════════════════════════════════════════════════
//  类型定义 — 可观测性事件数据结构
// ════════════════════════════════════════════════════════════════

/** Agent 节点状态 */
export type AgentStatus = "IDLE" | "RUNNING" | "SUCCESS" | "HEALING" | "FAILED";

/** 雷达上的 Agent 节点 */
export interface RadarAgent {
  id: string;
  label: string;
  status: AgentStatus;
  /** 节点在画布上的坐标 (0~1 归一化) */
  x: number;
  y: number;
  /** 自愈气泡信息 */
  healingBubble: HealingBubble | null;
}

/** 自愈气泡 */
export interface HealingBubble {
  attempt: number;
  maxAttempts: number;
  error: string;
  visible: boolean;
  dismissAt: number; // 自动消失时间戳
}

/** 飞梭动画 — 邮件在两个节点之间飞行 */
export interface MailShuttle {
  id: string;
  senderId: string;
  receiverId: string;
  mailType: string;
  startedAt: number;   // 动画开始时间
  duration: number;     // 动画持续时间 (ms)
}

/** 操作流瀑布日志条目 */
export interface LogEntry {
  id: string;
  timestamp: number;
  text: string;
  /** 是否正在打字机效果中 */
  typing: boolean;
}

// ════════════════════════════════════════════════════════════════
//  Store 状态与操作
// ════════════════════════════════════════════════════════════════

const MAX_LOG_ENTRIES = 100;
const MAX_SHUTTLES = 20;
const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_DELAY_MS = 3_000;
const BUBBLE_DURATION_MS = 2_000;

interface TelemetryState {
  agents: Map<string, RadarAgent>;
  shuttles: MailShuttle[];
  logs: LogEntry[];
  connected: boolean;
  connect: () => void;
  disconnect: () => void;
}

/** 生成唯一 ID */
let _idCounter = 0;
const uid = () => `tlm_${++_idCounter}_${Date.now().toString(36)}`;

/** 确保节点存在，不存在则自动创建 */
function ensureAgent(
  agents: Map<string, RadarAgent>,
  id: string,
  updates?: Partial<RadarAgent>
): Map<string, RadarAgent> {
  const next = new Map(agents);
  if (!next.has(id)) {
    // 自动布局：环形分布
    const count = next.size;
    const angle = (count / 8) * Math.PI * 2 - Math.PI / 2;
    const radius = 0.32;
    next.set(id, {
      id,
      label: id,
      status: "IDLE",
      x: 0.5 + radius * Math.cos(angle),
      y: 0.5 + radius * Math.sin(angle),
      healingBubble: null,
      ...updates,
    });
  } else if (updates) {
    next.set(id, { ...next.get(id)!, ...updates });
  }
  return next;
}

export const useTelemetryStore = create<TelemetryState>((set, get) => ({
  agents: new Map(),
  shuttles: [],
  logs: [],
  connected: false,

  connect: () => {
    if (get().connected) return;

    const ws = new WebSocket(
      `${AIOS_WS_URL}/api/dashboard/alerts?token=AIOS-SUPER-SECRET-KEY`
    );

    let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

    ws.onopen = () => {
      console.log("[TelemetryRadar] WebSocket connected");
      set({ connected: true });

      heartbeatTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "PING" }));
        }
      }, HEARTBEAT_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data);

        // 智能解包：兼容 EventBus 的 { channel, message } 包装格式
        let data = raw;
        if (raw.channel && !raw.type) {
          try {
            data =
              typeof raw.message === "string"
                ? JSON.parse(raw.message)
                : raw.message || raw;
          } catch {
            data = { payload: raw.message };
          }
        }

        const eventType = data.eventType || data.type;

        // ── 邮件飞梭事件 ──
        if (eventType === "MAIL_DELIVERED") {
          const senderId = data.sender || "unknown";
          const receiverId = data.receiver || "unknown";
          const mailType = data.mailType || "TASK";

          set((state) => {
            let agents = new Map(state.agents);
            agents = ensureAgent(agents, senderId, { status: "RUNNING" });
            agents = ensureAgent(agents, receiverId, { status: "RUNNING" });

            const shuttle: MailShuttle = {
              id: uid(),
              senderId,
              receiverId,
              mailType,
              startedAt: Date.now(),
              duration: 500,
            };

            return {
              agents,
              shuttles: [...state.shuttles, shuttle].slice(-MAX_SHUTTLES),
            };
          });
        }

        // ── 自愈重试事件 ──
        else if (eventType === "SELF_HEALING_TRIGGERED") {
          const agentId = data.agentId || "unknown";
          const attempt = data.attempt || 1;
          const error = data.error || "Unknown error";

          set((state) => {
            let agents = new Map(state.agents);
            agents = ensureAgent(agents, agentId, {
              status: "HEALING",
              healingBubble: {
                attempt,
                maxAttempts: 3,
                error,
                visible: true,
                dismissAt: Date.now() + BUBBLE_DURATION_MS,
              },
            });

            // 追加日志
            const logEntry: LogEntry = {
              id: uid(),
              timestamp: Date.now(),
              text: `[SELF-HEAL] ${agentId} attempt ${attempt}/3: ${error}`,
              typing: true,
            };

            return {
              agents,
              logs: [...state.logs, logEntry].slice(-MAX_LOG_ENTRIES),
            };
          });

          // 2 秒后恢复为 RUNNING 状态
          setTimeout(() => {
            set((state) => {
              const agents = new Map(state.agents);
              const agent = agents.get(agentId);
              if (agent && agent.status === "HEALING") {
                agents.set(agentId, {
                  ...agent,
                  status: "RUNNING",
                  healingBubble: null,
                });
              }
              return { agents };
            });
          }, BUBBLE_DURATION_MS);
        }

        // ── AST 操作事件 (未来扩展) ──
        else if (eventType === "AST_REWRITE" || eventType === "FILE_WRITE" || eventType === "BOULDER_SAVE") {
          const logEntry: LogEntry = {
            id: uid(),
            timestamp: Date.now(),
            text: `[AST] ${data.detail || data.message || eventType}`,
            typing: true,
          };
          set((state) => ({
            logs: [...state.logs, logEntry].slice(-MAX_LOG_ENTRIES),
          }));
        }

        // ── HUMAN_INTERVENTION 告警 (已有逻辑兼容) ──
        else if (eventType === "HUMAN_INTERVENTION") {
          const nodeId = data.nodeId || "unknown";
          set((state) => {
            let agents = new Map(state.agents);
            agents = ensureAgent(agents, nodeId, { status: "FAILED" });
            return { agents };
          });
        }
      } catch {
        console.warn("[TelemetryRadar] Failed to parse message:", event.data);
      }
    };

    ws.onclose = () => {
      console.log("[TelemetryRadar] WebSocket disconnected");
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
      set({ connected: false });
      setTimeout(() => {
        if (!get().connected) {
          console.log("[TelemetryRadar] Reconnecting...");
          get().connect();
        }
      }, RECONNECT_DELAY_MS);
    };

    ws.onerror = () => {
      console.warn("[TelemetryRadar] WebSocket error");
    };
  },

  disconnect: () => {
    set({ connected: false });
  },
}));
