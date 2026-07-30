import { useState, useRef, useEffect } from "react";
import { ArrowUp, Loader2, Sparkles, MessageSquare, Workflow } from "lucide-react";
import { useSessionStore } from "@/store/sessionStore";
import { useWorkflowStore } from "@/store/workflowStore";
import { streamChat, type ChatTurnMessage } from "@/lib/chatStream";
import SkillRolePopover from "@/components/SkillRolePopover";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  ChatComposer —— 常驻底部输入条，顶部 Chat/Workflow 模式开关
//   · workflow：prompt → autoCompile → plan → deploy → activity(流式)
//   · chat：prompt → fetch /api/chat SSE → 逐 token 追加到 chat 消息
//  streamingMessageId 期间禁用发送（并发 turn 防护，两模式共用）。
// ════════════════════════════════════════════════════════════════

export type ComposerMode = "chat" | "workflow";

interface ChatComposerProps {
  mode: ComposerMode;
  onModeChange: (m: ComposerMode) => void;
}

const CHAT_AGENT_ID = "chat";

export default function ChatComposer({ mode, onModeChange }: ChatComposerProps) {
  const [idea, setIdea] = useState("");
  const [busy, setBusy] = useState(false);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const streamingMessageId = useSessionStore((s) => s.streamingMessageId);
  const createSession = useSessionStore((s) => s.createSession);
  const renameSession = useSessionStore((s) => s.renameSession);
  const addMessage = useSessionStore((s) => s.addMessage);
  const updateMessage = useSessionStore((s) => s.updateMessage);
  const setStreamingMessageId = useSessionStore((s) => s.setStreamingMessageId);
  const enabledRoles = useWorkflowStore((s) => s.enabledRoles);
  const enabledSkills = useWorkflowStore((s) => s.enabledSkills);

  const autoCompile = useWorkflowStore((s) => s.autoCompile);

  const disabled = !!streamingMessageId || busy;
  const canSend = idea.trim().length > 0 && !disabled;

  // textarea 自适应高度
  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`;
  }, [idea]);

  // 切会话/切模式/卸载时中止进行中的 chat 流
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);
  useEffect(() => {
    abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId, mode]);

  /** 确保有 active session，返回 sid；首条消息同步标题 */
  const ensureSession = (text: string): string => {
    let sid = activeSessionId;
    if (!sid) {
      const s = createSession(text.substring(0, 20) || "New Chat");
      sid = s.id;
    }
    const session = useSessionStore.getState().sessions.find((s) => s.id === sid);
    const isFirst = !session || session.messages.length === 0;
    if (isFirst) renameSession(sid, text.substring(0, 20));
    return sid;
  };

  // ── Chat 模式 send ──
  const handleSendChat = async (text: string, sid: string) => {
    // 1. user prompt
    addMessage(sid, { role: "user", kind: "prompt", text });

    // 2. 构造历史（含刚加入的 prompt）— prompt→user, chat→assistant，时间序
    const session = useSessionStore.getState().sessions.find((s) => s.id === sid);
    const history: ChatTurnMessage[] = (session?.messages ?? [])
      .filter((m) => m.kind === "prompt" || m.kind === "chat")
      .reverse() // store 是 newest-first → 时间序
      .map((m) => ({
        role: m.kind === "chat" ? "assistant" : "user",
        content: m.text,
      }));

    // 3. 占位 agent 回复 + 锁定 composer
    const msg = addMessage(sid, { role: "agent", kind: "chat", text: "" });
    setStreamingMessageId(msg.id);

    // 4. SSE 流式
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    await streamChat({
      agentId: CHAT_AGENT_ID,
      messages: history,
      onDelta: (d) =>
        updateMessage(sid, msg.id, (p) => ({ text: p.text + d })),
      onDone: () => {
        updateMessage(sid, msg.id, (p) => ({
          meta: { ...p.meta, status: "succeeded", endedAt: Date.now() },
        }));
        setStreamingMessageId(null);
        abortRef.current = null;
      },
      onError: (errMsg) => {
        updateMessage(sid, msg.id, (p) => ({
          text: p.text + (p.text ? "\n\n" : "") + `✗ ${errMsg}`,
          meta: { ...p.meta, status: "error", endedAt: Date.now() },
        }));
        setStreamingMessageId(null);
        abortRef.current = null;
      },
      signal: ctrl.signal,
    });
  };

  // ── Workflow 模式 send：只编译拓扑 + 出 plan，停住等用户「部署到内核」 ──
  //  部署 + 运行由 ChatSurface.deployAndRun 在 PlanCard 按钮点击时触发，
  //  避免一气呵成打满 LLM RPM，也让用户在部署前确认拓扑。
  const handleSendWorkflow = async (text: string, sid: string) => {
    addMessage(sid, { role: "user", kind: "prompt", text });

    const ok = await autoCompile(text);
    if (!ok) {
      addMessage(sid, {
        role: "agent",
        kind: "error",
        text: "架构师规划失败：后端未响应或返回空拓扑。请检查后端服务后重试。",
      });
      return;
    }

    const { nodes, edges } = useWorkflowStore.getState();
    const labels = nodes
      .map((n) => {
        const d = n.data as any;
        return `${n.id}(${d?.role || "?"})`;
      })
      .join(" · ");
    addMessage(sid, {
      role: "agent",
      kind: "plan",
      text: `已规划 ${nodes.length} 个节点：${labels}。确认无误后点击「部署到内核」运行。`,
      meta: {
        topology: {
          nodes: nodes.map((n) => ({ id: n.id, role: (n.data as any)?.role })),
          edges: edges.map((e) => ({ source: e.source, target: e.target })),
        },
        status: "pending_deploy",
      },
    });
  };

  const handleSend = async () => {
    if (!canSend) return;
    const text = idea.trim();
    setIdea("");
    setBusy(true);
    const sid = ensureSession(text);
    try {
      if (mode === "chat") await handleSendChat(text, sid);
      else await handleSendWorkflow(text, sid);
    } finally {
      setBusy(false);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const tag = enabledRoles.length + enabledSkills.length;
  const isChat = mode === "chat";

  return (
    <div className="flex-shrink-0 border-t border-outline-variant/15 bg-surface px-4 py-3">
      <div className="mx-auto w-full max-w-3xl">
        {/* 模式开关 */}
        <div className="mb-2 flex items-center justify-center">
          <div className="flex items-center gap-0.5 rounded-lg bg-surface-container-low p-0.5 ghost-border">
            <ModeBtn
              active={isChat}
              onClick={() => onModeChange("chat")}
              icon={MessageSquare}
              label="对话"
            />
            <ModeBtn
              active={!isChat}
              onClick={() => onModeChange("workflow")}
              icon={Workflow}
              label="工作流"
            />
          </div>
        </div>

        <div
          className={cn(
            "flex flex-col rounded-2xl bg-surface-container-lowest p-2.5 transition-colors ghost-border focus-within:ghost-border-strong",
            disabled && "opacity-70",
          )}
        >
          <textarea
            ref={taRef}
            value={idea}
            onChange={(e) => setIdea(e.target.value)}
            onKeyDown={onKeyDown}
            rows={1}
            disabled={busy}
            placeholder={
              streamingMessageId
                ? "Agent 正在工作…"
                : isChat
                  ? "和 AI 自由对话…（多轮上下文 + 记忆）"
                  : "描述你的想法，AIOS 架构师将编排工作流并执行…"
            }
            className="max-h-40 w-full resize-none bg-transparent px-2 py-1.5 text-sm leading-relaxed text-on-surface placeholder:text-outline/50 focus:outline-none disabled:cursor-not-allowed"
          />

          <div className="flex items-center justify-between px-1 pt-1">
            <div className="flex items-center gap-1.5">
              {/* 工作流模式才显示 skill/role 选择 */}
              {!isChat && (
                <>
                  <SkillRolePopover />
                  {tag > 0 && (
                    <span className="text-[10px] font-medium uppercase tracking-wider text-outline">
                      {enabledRoles.length} roles · {enabledSkills.length} skills
                    </span>
                  )}
                </>
              )}
              {isChat && (
                <span className="text-[10px] font-medium uppercase tracking-wider text-outline">
                  直接对话 LLM · 注入记忆
                </span>
              )}
            </div>

            <button
              onClick={handleSend}
              disabled={!canSend}
              className={cn(
                "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-bold transition-all",
                canSend
                  ? "btn-primary-ink text-on-primary hover:opacity-90"
                  : "cursor-not-allowed bg-surface-container-high text-outline",
              )}
            >
              {busy ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : streamingMessageId ? (
                <Sparkles className="h-3.5 w-3.5 animate-soft-pulse" />
              ) : (
                <ArrowUp className="h-3.5 w-3.5" />
              )}
              {busy ? "处理中" : streamingMessageId ? "运行中" : "发送"}
            </button>
          </div>
        </div>
        <p className="mt-1.5 px-2 text-center text-[10px] text-outline/60">
          {isChat
            ? "Enter 发送 · Shift+Enter 换行 · 多轮对话 + 记忆注入"
            : "Enter 发送 · Shift+Enter 换行 · 回车即触发 auto-compile + 部署"}
        </p>
      </div>
    </div>
  );
}

function ModeBtn({
  active,
  onClick,
  icon: Icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: typeof MessageSquare;
  label: string;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex items-center gap-1.5 rounded-md px-3 py-1 text-xs font-semibold transition-colors",
        active
          ? "bg-surface-container-lowest text-primary ghost-border-strong"
          : "text-outline hover:text-on-surface",
      )}
    >
      <Icon className="h-3.5 w-3.5" />
      {label}
    </button>
  );
}
