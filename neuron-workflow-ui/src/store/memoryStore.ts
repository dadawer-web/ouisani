import { create } from "zustand";
import { AIOS_API_URL } from "../config";

// ════════════════════════════════════════════════════════════════
//  类型定义 — 镜像后端 com.ouisani.aios.core.memory.providers.MemoryRecord
//  端点契约见 MemoryViewerRoutes.java：GET/PATCH/DELETE /api/memory/{agentId}[/{key}]
// ════════════════════════════════════════════════════════════════

export type MemoryDomain = "USER" | "AGENT";
export type MemoryLayer = "L0" | "L1" | "L2" | "L3";

export interface MemoryRecord {
  key: string;
  content: string;
  source: string;
  timestamp: number;
  confidence: number;
  domain: MemoryDomain;
  layer: MemoryLayer;
  version: number;
}

export type MemoryFilter = "ALL" | "USER" | "AGENT";

interface MemoryListResponse {
  agentId: string;
  count: number;
  memories: MemoryRecord[];
}

interface MemoryPatchResponse {
  ok: boolean;
  record?: MemoryRecord;
}

// ════════════════════════════════════════════════════════════════
//  Store 状态与操作
// ════════════════════════════════════════════════════════════════

const TOKEN = "AIOS-SUPER-SECRET-KEY";
const LS_KEY = "aios.memoryViewer.agentId";

/** 后端未注入 primary store 时返回 503 — 组件据此渲染特殊提示 */
export const ERR_PRIMARY_STORE_NOT_CONFIGURED = "PRIMARY_STORE_NOT_CONFIGURED";

interface MemoryState {
  agentId: string;
  memories: MemoryRecord[];
  filter: MemoryFilter;
  loading: boolean;
  /** null 表示无错误；ERR_PRIMARY_STORE_NOT_CONFIGURED 为特殊 503 标记；其余为后端 error 字段或网络错误 */
  error: string | null;
  lastUpdated: number | null;
  /** 当前正在 PATCH/DELETE 的 key — 用于行级 loading 指示 */
  pendingKey: string | null;

  setAgentId: (id: string) => void;
  setFilter: (f: MemoryFilter) => void;
  fetchMemories: () => Promise<void>;
  updateConfidence: (key: string, confidence: number) => Promise<void>;
  updateDomain: (key: string, domain: MemoryDomain) => Promise<void>;
  updateLayer: (key: string, layer: MemoryLayer) => Promise<void>;
  deleteMemory: (key: string) => Promise<void>;
}

/** 从 localStorage 恢复上次使用的 agentId */
function loadInitialAgentId(): string {
  try {
    return localStorage.getItem(LS_KEY) || "";
  } catch {
    return "";
  }
}

/** 通用 PATCH/DELETE 错误解析 — 区分 503 与其他错误 */
async function parseError(resp: Response): Promise<string> {
  if (resp.status === 503) return ERR_PRIMARY_STORE_NOT_CONFIGURED;
  try {
    const body = await resp.json();
    return body.error || `HTTP ${resp.status}`;
  } catch {
    return `HTTP ${resp.status}`;
  }
}

export const useMemoryStore = create<MemoryState>((set, get) => ({
  agentId: loadInitialAgentId(),
  memories: [],
  filter: "ALL",
  loading: false,
  error: null,
  lastUpdated: null,
  pendingKey: null,

  setAgentId: (id) => {
    set({ agentId: id });
    try {
      localStorage.setItem(LS_KEY, id);
    } catch {
      /* localStorage 不可用时静默降级 */
    }
  },

  setFilter: (f) => set({ filter: f }),

  fetchMemories: async () => {
    const { agentId } = get();
    if (!agentId.trim()) {
      set({ error: "agentId required", memories: [] });
      return;
    }
    set({ loading: true, error: null });
    try {
      const url = `${AIOS_API_URL}/api/memory/${encodeURIComponent(agentId)}?token=${TOKEN}`;
      const resp = await fetch(url);
      if (!resp.ok) {
        set({
          loading: false,
          error: await parseError(resp),
          memories: [],
          lastUpdated: Date.now(),
        });
        return;
      }
      const data: MemoryListResponse = await resp.json();
      // 按 timestamp 倒序（最新在前）
      const sorted = [...(data.memories || [])].sort(
        (a, b) => b.timestamp - a.timestamp
      );
      set({
        memories: sorted,
        loading: false,
        error: null,
        lastUpdated: Date.now(),
      });
    } catch (e) {
      set({
        loading: false,
        error: e instanceof Error ? e.message : "network error",
        lastUpdated: Date.now(),
      });
    }
  },

  updateConfidence: async (key, confidence) => {
    const state = get();
    // 乐观更新 + 保存回滚快照
    const snapshot = state.memories;
    const next = state.memories.map((m) =>
      m.key === key ? { ...m, confidence } : m
    );
    set({ memories: next, pendingKey: key, error: null });
    try {
      const url = `${AIOS_API_URL}/api/memory/${encodeURIComponent(state.agentId)}/${encodeURIComponent(key)}?token=${TOKEN}`;
      const resp = await fetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ confidence }),
      });
      if (!resp.ok) {
        // 回滚
        set({ memories: snapshot, pendingKey: null, error: await parseError(resp) });
        return;
      }
      const data: MemoryPatchResponse = await resp.json();
      // 用后端返回的最新 record 覆盖（拿到新 version 等）
      if (data.record) {
        set({
          memories: get().memories.map((m) =>
            m.key === key ? { ...data.record! } : m
          ),
          pendingKey: null,
          lastUpdated: Date.now(),
        });
      } else {
        set({ pendingKey: null, lastUpdated: Date.now() });
      }
    } catch (e) {
      set({
        memories: snapshot,
        pendingKey: null,
        error: e instanceof Error ? e.message : "network error",
      });
    }
  },

  updateDomain: async (key, domain) => {
    const state = get();
    const snapshot = state.memories;
    const next = state.memories.map((m) =>
      m.key === key ? { ...m, domain } : m
    );
    set({ memories: next, pendingKey: key, error: null });
    try {
      const url = `${AIOS_API_URL}/api/memory/${encodeURIComponent(state.agentId)}/${encodeURIComponent(key)}?token=${TOKEN}`;
      const resp = await fetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ domain }),
      });
      if (!resp.ok) {
        set({ memories: snapshot, pendingKey: null, error: await parseError(resp) });
        return;
      }
      const data: MemoryPatchResponse = await resp.json();
      if (data.record) {
        set({
          memories: get().memories.map((m) =>
            m.key === key ? { ...data.record! } : m
          ),
          pendingKey: null,
          lastUpdated: Date.now(),
        });
      } else {
        set({ pendingKey: null, lastUpdated: Date.now() });
      }
    } catch (e) {
      set({
        memories: snapshot,
        pendingKey: null,
        error: e instanceof Error ? e.message : "network error",
      });
    }
  },

  updateLayer: async (key, layer) => {
    const state = get();
    const snapshot = state.memories;
    const next = state.memories.map((m) =>
      m.key === key ? { ...m, layer } : m
    );
    set({ memories: next, pendingKey: key, error: null });
    try {
      const url = `${AIOS_API_URL}/api/memory/${encodeURIComponent(state.agentId)}/${encodeURIComponent(key)}?token=${TOKEN}`;
      const resp = await fetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ layer }),
      });
      if (!resp.ok) {
        set({ memories: snapshot, pendingKey: null, error: await parseError(resp) });
        return;
      }
      const data: MemoryPatchResponse = await resp.json();
      if (data.record) {
        set({
          memories: get().memories.map((m) =>
            m.key === key ? { ...data.record! } : m
          ),
          pendingKey: null,
          lastUpdated: Date.now(),
        });
      } else {
        set({ pendingKey: null, lastUpdated: Date.now() });
      }
    } catch (e) {
      set({
        memories: snapshot,
        pendingKey: null,
        error: e instanceof Error ? e.message : "network error",
      });
    }
  },

  deleteMemory: async (key) => {
    const state = get();
    const snapshot = state.memories;
    const next = state.memories.filter((m) => m.key !== key);
    set({ memories: next, pendingKey: key, error: null });
    try {
      const url = `${AIOS_API_URL}/api/memory/${encodeURIComponent(state.agentId)}/${encodeURIComponent(key)}?token=${TOKEN}`;
      const resp = await fetch(url, { method: "DELETE" });
      if (!resp.ok) {
        set({ memories: snapshot, pendingKey: null, error: await parseError(resp) });
        return;
      }
      set({ pendingKey: null, lastUpdated: Date.now() });
    } catch (e) {
      set({
        memories: snapshot,
        pendingKey: null,
        error: e instanceof Error ? e.message : "network error",
      });
    }
  },
}));
