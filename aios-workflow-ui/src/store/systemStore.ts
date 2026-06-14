import { create } from "zustand";
import { AIOS_WS_URL } from "../config";

/** 沙箱智能体进程 */
export interface ProcessInfo {
  pid: number;
  agentName: string;
  sandboxType: "Docker" | "Wasm";
  status: "Running" | "Sleeping" | "Blocked" | "Crashed" | "Killed";
  cpu: number; // Token 消耗占比 %
  ram: number; // Token 使用量
}

/** 内核通信日志 */
export interface EventBusLog {
  timestamp: number;
  topic: string;
  payload: string;
}

/** 系统状态 */
export interface SystemState {
  cpuUsage: number;
  ramUsage: number;
  activeProcesses: number;
  processes: ProcessInfo[];
  eventBusLogs: EventBusLog[];
  appOutputs: Record<string, string>;
  activeWorkflowNode: string | null;
  connected: boolean;
  connectKernel: () => void;
  disconnectKernel: () => void;
}

const MAX_LOG_ENTRIES = 50;
const HEARTBEAT_INTERVAL_MS = 30_000; // 30 秒心跳，防止 Idle Timeout
const RECONNECT_DELAY_MS = 3_000; // 3 秒后断线重连

export const useSystemStore = create<SystemState>((set, get) => ({
  cpuUsage: 0,
  ramUsage: 0,
  activeProcesses: 0,
  processes: [],
  eventBusLogs: [],
  appOutputs: {},
  activeWorkflowNode: null,
  connected: false,

  connectKernel: () => {
    // 防止重复连接
    if (get().connected) return;

    const ws = new WebSocket(
      `${AIOS_WS_URL}/api/system/stream?token=AIOS-SUPER-SECRET-KEY`
    );

    let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

    ws.onopen = () => {
      console.log("[SystemStore] Kernel stream connected");
      set({ connected: true });

      // ── 心跳机制：每 30 秒发送 PING，防止服务器因空闲掐断连接 ──
      heartbeatTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "PING" }));
        }
      }, HEARTBEAT_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data);

        // 忽略 PONG 心跳响应
        if (raw.type === "PONG") return;

        // 智能解包：兼容 EventBus 的 { channel, message } 包装格式
        let data = raw;
        let msgType = raw.type;

        if (raw.channel && !raw.type) {
          // EventBus 包装格式：{ channel: "sys.telemetry.metrics", message: "..." }
          try {
            data =
              typeof raw.message === "string"
                ? JSON.parse(raw.message)
                : raw.message || raw;
          } catch {
            data = { payload: raw.message };
          }
          // 映射通道名到消息类型
          if (raw.channel === "sys.telemetry.metrics") msgType = "SYS_METRICS";
          else if (raw.channel === "sys.eventbus.logs") msgType = "EVENT_BUS_LOG";
          else if (raw.channel === "sys.app.stdout") msgType = "APP_OUTPUT";
        }

        // 如果解包后的 data 自带 type，优先使用
        msgType = data.type || msgType;

        // 接收大盘数据
        if (msgType === "SYS_METRICS") {
          set({
            cpuUsage: data.cpuUsage ?? data.cpu_usage ?? 0,
            ramUsage: data.ramUsage ?? data.ram_usage ?? 0,
            activeProcesses:
              data.activeProcesses ?? data.active_processes ?? 0,
            processes: data.processes ?? [],
          });
        }
        // 接收总线日志
        else if (msgType === "EVENT_BUS_LOG") {
          set((state) => {
            const newLog: EventBusLog = {
              timestamp: data.timestamp ?? Date.now(),
              topic: data.topic || raw.channel || "sys",
              payload:
                typeof data.payload === "string"
                  ? data.payload
                  : JSON.stringify(data.payload ?? ""),
            };
            return {
              eventBusLogs: [newLog, ...state.eventBusLogs].slice(
                0,
                MAX_LOG_ENTRIES
              ),
            };
          });
        }
        // 接收应用 stdout — 后端通过 sys.eventbus.logs 频道推送 APP_OUTPUT 类型消息
        else if (msgType === "APP_OUTPUT") {
          const logLine = data.payload ?? "";
          const agentId = data.agentId ?? "unknown";
          set((state) => ({
            appOutputs: {
              ...state.appOutputs,
              [agentId]: (state.appOutputs[agentId] ?? "") + logLine + "\n",
            },
          }));

          // ── DAG Trace 实时解析：提取当前激活的工作流节点 ──
          if (logLine.includes("[DAG_TRACE]")) {
            if (logLine.includes(">>> NODE_START:")) {
              const activeNodeId = logLine.split("NODE_START:")[1]?.trim();
              if (activeNodeId) {
                set({ activeWorkflowNode: activeNodeId });
              }
            } else if (logLine.includes("<<< NODE_SUCCESS:") || logLine.includes("!!! NODE_FAILED:")) {
              set({ activeWorkflowNode: null });
            }
          }

          // APP_OUTPUT 同时写入事件总线日志，方便大屏滚动显示
          set((state) => {
            const newLog: EventBusLog = {
              timestamp: data.timestamp ?? Date.now(),
              topic: `app.${agentId}`,
              payload: logLine,
            };
            return {
              eventBusLogs: [newLog, ...state.eventBusLogs].slice(
                0,
                MAX_LOG_ENTRIES
              ),
            };
          });
        }
      } catch {
        console.warn("[SystemStore] Failed to parse kernel message:", event.data);
      }
    };

    ws.onclose = () => {
      console.log("[SystemStore] Kernel stream disconnected");
      // 清理心跳定时器
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
      set({ connected: false });
      // 3 秒后自动重连
      setTimeout(() => {
        if (!get().connected) {
          console.log("[SystemStore] Reconnecting...");
          get().connectKernel();
        }
      }, RECONNECT_DELAY_MS);
    };

    ws.onerror = () => {
      console.warn("[SystemStore] Kernel stream error");
      // onclose 会紧跟 onerror 触发，重连逻辑在 onclose 中处理
    };
  },

  disconnectKernel: () => {
    set({ connected: false });
  },
}));
