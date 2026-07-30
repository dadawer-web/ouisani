import { create } from "zustand";

// ════════════════════════════════════════════════════════════════
//  本地会话存储 — chat-facade 的会话历史，仅存 localStorage
//  后端无对话/会话端点，活动流(activity)是临时流式，重开不回放
// ════════════════════════════════════════════════════════════════

export interface ChatMessage {
  id: string;
  role: "user" | "agent";
  kind: "prompt" | "plan" | "activity" | "artifact" | "error" | "system" | "chat";
  text: string;
  ts: number;
  meta?: Record<string, any>;
}

export interface Session {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  workflowName: string;
  messages: ChatMessage[];
}

interface SessionState {
  sessions: Session[];
  activeSessionId: string | null;
  streamingMessageId: string | null;
  createSession: (title?: string) => Session;
  deleteSession: (id: string) => void;
  renameSession: (id: string, title: string) => void;
  setActive: (id: string | null) => void;
  addMessage: (sessionId: string, message: Omit<ChatMessage, "id" | "ts">) => ChatMessage;
  updateMessage: (
    sessionId: string,
    messageId: string,
    updater: (prev: ChatMessage) => Partial<ChatMessage>,
  ) => void;
  setStreamingMessageId: (id: string | null) => void;
  clearActive: () => void;
}

const LS_KEY = "aios.sessions";
const MAX_SESSIONS = 20;
const MAX_MESSAGES_PER_SESSION = 100;

/** 生成唯一 ID */
let _idCounter = 0;
const uid = (prefix: string) =>
  `${prefix}_${(++_idCounter).toString(36)}_${Date.now().toString(36)}`;

/** 手动持久化 — 持久化时剔除 activity（活动流是临时流式，重开不回放） */
function persist(sessions: Session[]) {
  try {
    const slim = sessions.map((s) => ({
      ...s,
      messages: s.messages.filter((m) => m.kind !== "activity"),
    }));
    localStorage.setItem(LS_KEY, JSON.stringify(slim));
  } catch (e) {
    console.warn("[SessionStore] persist failed:", e);
  }
}

function hydrate(): Session[] {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.slice(0, MAX_SESSIONS);
  } catch (e) {
    console.warn("[SessionStore] hydrate failed:", e);
    return [];
  }
}

export const useSessionStore = create<SessionState>((set, get) => ({
  sessions: hydrate(),
  activeSessionId: null,
  streamingMessageId: null,

  createSession: (title = "New Chat") => {
    const id = uid("session");
    const now = Date.now();
    const newSession: Session = {
      id,
      title: title.substring(0, 20),
      createdAt: now,
      updatedAt: now,
      workflowName: "untitled_workflow",
      messages: [],
    };
    const next = [newSession, ...get().sessions].slice(0, MAX_SESSIONS);
    persist(next);
    set({ sessions: next, activeSessionId: id });
    return newSession;
  },

  deleteSession: (id) => {
    const next = get().sessions.filter((s) => s.id !== id);
    persist(next);
    set({
      sessions: next,
      activeSessionId:
        get().activeSessionId === id
          ? next[0]?.id ?? null
          : get().activeSessionId,
    });
  },

  renameSession: (id, title) => {
    const next = get().sessions.map((s) =>
      s.id === id ? { ...s, title: title.substring(0, 20), updatedAt: Date.now() } : s,
    );
    persist(next);
    set({ sessions: next });
  },

  setActive: (id) => set({ activeSessionId: id }),

  addMessage: (sessionId, message) => {
    const newMessage: ChatMessage = {
      id: uid("msg"),
      ts: Date.now(),
      ...message,
    };
    const next = get().sessions.map((s) =>
      s.id === sessionId
        ? {
            ...s,
            messages: [newMessage, ...s.messages].slice(-MAX_MESSAGES_PER_SESSION),
            updatedAt: Date.now(),
          }
        : s,
    );
    persist(next);
    set({ sessions: next });
    return newMessage;
  },

  /** 函数式更新 — 在 set 回调内基于 prev 追加，防多订阅同 tick 竞态；返回值与 prev 合并 */
  updateMessage: (sessionId, messageId, updater) => {
    let next: Session[] = [];
    set((state) => {
      next = state.sessions.map((s) =>
        s.id === sessionId
          ? {
              ...s,
              messages: s.messages.map((m) =>
                m.id === messageId ? { ...m, ...updater(m) } : m,
              ),
              updatedAt: Date.now(),
            }
          : s,
      );
      return { sessions: next };
    });
    // activity 消息不持久化，但 plan/artifact/error/prompt/system 需要
    persist(next);
  },

  setStreamingMessageId: (id) => set({ streamingMessageId: id }),

  clearActive: () => set({ activeSessionId: null }),
}));
