import { useCallback, useEffect, useRef } from "react";
import { useSessionStore, type ChatMessage } from "@/store/sessionStore";
import { useSystemStore, type EventBusLog } from "@/store/systemStore";
import { useTelemetryStore } from "@/store/telemetryStore";
import { useWorkflowStore } from "@/store/workflowStore";

// ════════════════════════════════════════════════════════════════
//  useTurnStream — 把后端 WS 推送的 DAG 事件 / 事件总线日志 / app stdout /
//  自愈告警渲染为当前 activity 消息的「流式回复」。
//
//  - eventBusLogs: newest-first, 无 id → 签名集合去重，从 head 扫到首个旧 sig 即 break
//  - telemetryStore.logs: oldest-first, 有 id → 用 lastSeenId 跟踪
//  - 终态: 只认 dag.WORKFLOW_SUCCEEDED / dag.WORKFLOW_FAILED；NODE_FAILED 仅作活动行
//  - HUMAN_INTERVENTION: 走 systemAlert（不在 eventBusLogs）→ 标 paused，不 finalize
//  - 超时策略: 无活动超时（5 分钟内无任何事件才 finalize）+ 30 分钟硬上限兜底。
//    工作流持续产出事件时 idle timer 不断重置，不会因跑得久就误判 timeout；
//    后端 LLM 慢 / 429 退避导致的长耗时不再触发前端假超时。
//  - updateMessage 全程函数式避免竞态；finalize 幂等
//  - StrictMode 下用 useRef 持 unsubscribe 防双订阅
// ════════════════════════════════════════════════════════════════

const IDLE_TIMEOUT_MS = 5 * 60_000; // 无活动 5 分钟才判 timeout
const HARD_TIMEOUT_MS = 30 * 60_000; // 硬上限 30 分钟，无论如何兜底
const MAX_LINES = 80;

const capLines = (s: string, n: number) => {
  const a = s.split("\n");
  return a.length > n ? a.slice(-n).join("\n") : s;
};

/** 单条总线日志着色为一行 */
function formatLogLine(e: EventBusLog): string {
  const topic = e.topic || "sys";
  if (topic.startsWith("dag.")) {
    const evt = topic.slice(4);
    if (evt === "WORKFLOW_SUCCEEDED") return `✓ workflow succeeded · ${e.payload.slice(0, 80)}`;
    if (evt === "WORKFLOW_FAILED") return `✗ workflow failed · ${e.payload.slice(0, 80)}`;
    return `· ${evt} · ${e.payload.slice(0, 80)}`;
  }
  if (topic.startsWith("app.")) return `  │ ${e.payload.slice(0, 100)}`;
  return `· [${topic}] ${e.payload.slice(0, 80)}`;
}

export interface TurnStreamApi {
  startTurn: (streamingMsgId: string, sessionId: string) => void;
  /** 是否有 turn 正在流式 */
  active: boolean;
}

export function useTurnStream(): TurnStreamApi {
  const stateRef = useRef<{
    streamingMsgId: string | null;
    sessionId: string | null;
    seenSig: Set<string>;
    lastTelLogId: string | null;
    finalized: boolean;
    idleTimer: ReturnType<typeof setTimeout> | null;
    hardTimer: ReturnType<typeof setTimeout> | null;
    unsubSys: (() => void) | null;
    unsubTel: (() => void) | null;
    unsubAlert: (() => void) | null;
  }>({
    streamingMsgId: null,
    sessionId: null,
    seenSig: new Set(),
    lastTelLogId: null,
    finalized: false,
    idleTimer: null,
    hardTimer: null,
    unsubSys: null,
    unsubTel: null,
    unsubAlert: null,
  });

  // 重置无活动计时器 —— 每收到一个事件就续命，工作流持续产出时不会误判 timeout
  const resetIdle = useCallback(() => {
    const st = stateRef.current;
    if (st.finalized) return;
    if (st.idleTimer) clearTimeout(st.idleTimer);
    st.idleTimer = setTimeout(() => finalize("timeout", "无活动 5 分钟"), IDLE_TIMEOUT_MS);
  }, []);

  const finalize = useCallback((reason: string, detail?: string) => {
    const st = stateRef.current;
    if (st.finalized) return;
    st.finalized = true;
    if (st.idleTimer) clearTimeout(st.idleTimer);
    if (st.hardTimer) clearTimeout(st.hardTimer);
    st.idleTimer = st.hardTimer = null;
    st.unsubSys?.();
    st.unsubTel?.();
    st.unsubAlert?.();
    st.unsubSys = st.unsubTel = st.unsubAlert = null;

    const { streamingMsgId, sessionId } = st;
    if (!streamingMsgId || !sessionId) {
      useSessionStore.getState().setStreamingMessageId(null);
      return;
    }
    useSessionStore.getState().updateMessage(sessionId, streamingMsgId, (p: ChatMessage) => ({
      text: p.text + `\n\n— ${reason}${detail ? ": " + detail : ""}`,
      meta: { ...p.meta, status: reason, endedAt: Date.now() },
    }));
    useSessionStore.getState().setStreamingMessageId(null);
  }, []);

  const startTurn = useCallback(
    (streamingMsgId: string, sessionId: string) => {
      const st = stateRef.current;
      // 若上一 turn 未收尾（理论不该发生，并发防护），先清掉
      st.unsubSys?.();
      st.unsubTel?.();
      st.unsubAlert?.();
      if (st.idleTimer) clearTimeout(st.idleTimer);
      if (st.hardTimer) clearTimeout(st.hardTimer);

      st.streamingMsgId = streamingMsgId;
      st.sessionId = sessionId;
      st.seenSig = new Set();
      st.lastTelLogId = null;
      st.finalized = false;
      // 无活动超时 + 硬上限兜底（工作流持续产出事件时 idle 不断续命）
      st.idleTimer = setTimeout(() => finalize("timeout", "无活动 5 分钟"), IDLE_TIMEOUT_MS);
      st.hardTimer = setTimeout(() => finalize("timeout", "硬超时 30 分钟"), HARD_TIMEOUT_MS);

      // ── systemStore.eventBusLogs delta（newest-first） ──
      st.unsubSys = useSystemStore.subscribe((s, prev) => {
        if (s.eventBusLogs === prev.eventBusLogs) return;
        if (stateRef.current.streamingMsgId !== streamingMsgId) return;
        const logs = s.eventBusLogs;
        const fresh: EventBusLog[] = [];
        for (let i = 0; i < logs.length; i++) {
          const e = logs[i];
          // 过滤心跳/系统节拍噪声 —— sig_tick 每 60s 一条，会占满 80 行缓冲区，
          // 把 NODE_SUCCEEDED 等真正的完成事件挤掉，导致用户看不到工作流完成日志。
          if (e.topic === "sig_tick") {
            // 仍记入 seenSig 防止补播，但不入 fresh
            st.seenSig.add(`${e.timestamp}|${e.topic}|${e.payload.slice(0, 64)}`);
            continue;
          }
          const sig = `${e.timestamp}|${e.topic}|${e.payload.slice(0, 64)}`;
          if (st.seenSig.has(sig)) break; // 命中旧项，其后皆旧
          st.seenSig.add(sig);
          fresh.push(e);
        }
        fresh.reverse(); // 新到旧 → 时间序
        let terminal: string | null = null;
        let detail = "";
        const lines = fresh.map((e) => {
          if (e.topic === "dag.WORKFLOW_SUCCEEDED") {
            terminal = "succeeded";
            detail = e.payload.slice(0, 80);
            // 解析 workflowId 存入 activity meta，供产物面板拉取 factory 文件
            try {
              const evt = JSON.parse(e.payload);
              if (evt.workflowId) {
                useSessionStore.getState().updateMessage(sessionId, streamingMsgId, (p) => ({
                  meta: { ...p.meta, workflowId: evt.workflowId },
                }));
              }
            } catch { /* payload 非 JSON，忽略 */ }
          } else if (e.topic === "dag.WORKFLOW_FAILED") {
            terminal = "failed";
            detail = e.payload.slice(0, 80);
          }
          return formatLogLine(e);
        });
        if (lines.length) {
          useSessionStore.getState().updateMessage(sessionId, streamingMsgId, (p) => ({
            text: capLines(p.text + lines.join("\n") + "\n", MAX_LINES),
          }));
          // 收到事件 → 续命无活动计时器
          resetIdle();
        }
        if (terminal) finalize(terminal, detail);
      });

      // ── telemetryStore.logs delta（oldest-first, 有 id） ──
      st.unsubTel = useTelemetryStore.subscribe((s, prev) => {
        if (s.logs === prev.logs) return;
        if (stateRef.current.streamingMsgId !== streamingMsgId) return;
        const fresh = st.lastTelLogId
          ? s.logs.filter((l) => l.id > st.lastTelLogId!)
          : s.logs.slice();
        if (fresh.length) {
          st.lastTelLogId = fresh[fresh.length - 1].id;
          useSessionStore.getState().updateMessage(sessionId, streamingMsgId, (p) => ({
            text: capLines(p.text + fresh.map((l) => l.text).join("\n") + "\n", MAX_LINES),
          }));
          // 收到事件 → 续命无活动计时器
          resetIdle();
        }
      });

      // ── systemAlert 暂停标注（HUMAN_INTERVENTION 不 finalize，resume 后继续流式） ──
      st.unsubAlert = useWorkflowStore.subscribe((s, prev) => {
        if (s.systemAlert.visible === prev.systemAlert.visible) return;
        if (stateRef.current.streamingMsgId !== streamingMsgId) return;
        useSessionStore.getState().updateMessage(sessionId, streamingMsgId, (p) => ({
          meta: { ...p.meta, paused: s.systemAlert.visible },
        }));
      });
    },
    [finalize, resetIdle],
  );

  // 组件卸载时清理订阅与定时器
  useEffect(() => {
    return () => {
      const st = stateRef.current;
      if (st.idleTimer) clearTimeout(st.idleTimer);
      if (st.hardTimer) clearTimeout(st.hardTimer);
      st.unsubSys?.();
      st.unsubTel?.();
      st.unsubAlert?.();
    };
  }, []);

  return { startTurn, active: false };
}
