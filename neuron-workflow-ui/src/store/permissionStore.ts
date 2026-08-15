import { create } from "zustand";
import { AIOS_API_URL, AIOS_WS_URL } from "../config";

// ════════════════════════════════════════════════════════════════
//  permissionStore —— 工具权限审批流（Standing Scoped Approvals）
//   连 /api/permission/stream WS：
//   - 入向：后端 ToolPermissionChannel 广播的 ASK 请求
//     { requestId, agentId, toolName, target, description, timestamp }
//   - 出向：用户决策 { type:"permission_response", requestId, decision }
//     decision ∈ "ALLOW_ONCE" | "ALWAYS_TARGET" | "DENY"
//   pending 队列驱动 PermissionApprovalPopup 弹窗；ALWAYS_TARGET 时后端
//   会调 PermissionChecker.grantTargetApproval 记账，后续同 target 不再弹。
//   与 alertsStore 同构：心跳 PING + 断线重连。
// ════════════════════════════════════════════════════════════════

export type ApprovalDecision = "ALLOW_ONCE" | "ALWAYS_TARGET" | "DENY";

export interface PermissionAsk {
  requestId: string;
  agentId: string;
  toolName: string;
  target: string | null;
  description: string;
  actionDigest: string | null;
  workflowId: string | null;
  traceId: string | null;
  timestamp: number;
}

interface PermissionState {
  pending: PermissionAsk[];
  connected: boolean;
  connect: () => void;
  disconnect: () => void;
  respond: (requestId: string, decision: ApprovalDecision) => void;
}

const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_DELAY_MS = 3_000;

// 单例 WS 引用（zustand store 外持有，便于 respond 时发送）
let _ws: WebSocket | null = null;

export const usePermissionStore = create<PermissionState>((set, get) => ({
  pending: [],
  connected: false,

  connect: () => {
    if (get().connected) return;

    const ws = new WebSocket(
      `${AIOS_WS_URL}/api/permission/stream?token=AIOS-SUPER-SECRET-KEY`,
    );
    _ws = ws;

    let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

    ws.onopen = () => {
      console.log("[Permission] WebSocket connected");
      set({ connected: true });
      heartbeatTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "PING" }));
      }, HEARTBEAT_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data);
        if (raw.type === "PONG") return;
        // 后端直接转发 ToolPermissionChannel 的 payload（非 {channel,message} 包装）
        if (!raw.requestId) return;
        const ask: PermissionAsk = {
          requestId: String(raw.requestId),
          agentId: String(raw.agentId ?? ""),
          toolName: String(raw.toolName ?? ""),
          target: raw.target ? String(raw.target) : null,
          description: String(raw.description ?? ""),
          actionDigest: raw.actionDigest ? String(raw.actionDigest) : null,
          workflowId: raw.workflowId ? String(raw.workflowId) : null,
          traceId: raw.traceId ? String(raw.traceId) : null,
          timestamp: Number(raw.timestamp ?? Date.now()),
        };
        set((state) => ({
          // 同一 requestId 去重，避免补播导致重复弹窗
          pending: state.pending.some((p) => p.requestId === ask.requestId)
            ? state.pending
            : [...state.pending, ask],
        }));
      } catch {
        console.warn("[Permission] Failed to parse message:", event.data);
      }
    };

    ws.onclose = () => {
      console.log("[Permission] WebSocket disconnected");
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      _ws = null;
      set({ connected: false });
      setTimeout(() => {
        if (!get().connected) {
          console.log("[Permission] Reconnecting...");
          get().connect();
        }
      }, RECONNECT_DELAY_MS);
    };

    ws.onerror = () => {
      console.warn("[Permission] WebSocket error");
    };
  },

  disconnect: () => {
    if (_ws) { _ws.close(); _ws = null; }
    set({ connected: false, pending: [] });
  },

  respond: (requestId, decision) => {
    if (_ws && _ws.readyState === WebSocket.OPEN) {
      const ask = get().pending.find((p) => p.requestId === requestId);
      _ws.send(JSON.stringify({
        type: "permission_response",
        requestId,
        decision,
        actionDigest: ask?.actionDigest ?? undefined,
      }));
    }
    // Keep the Mission read-model in sync with the popup's optimistic decision.
    // This is intentionally best-effort: the permission WebSocket remains the
    // source of truth for waking the blocked agent loop.
    void fetch(`${AIOS_API_URL}/api/missions/approvals/${encodeURIComponent(requestId)}/resolve?token=AIOS-SUPER-SECRET-KEY`, {
      method: "POST",
      headers: { Authorization: "Bearer AIOS-SUPER-SECRET-KEY" },
    }).catch(() => undefined);
    // 乐观移除：后端收到后唤醒阻塞的 agent loop；无需等 ack
    set((state) => ({ pending: state.pending.filter((p) => p.requestId !== requestId) }));
  },
}));
