import { useEffect, useRef, useState } from "react";
import {
  Workflow,
  Monitor,
  Loader2,
  PauseCircle,
  CheckCircle2,
  XCircle,
  Clock,
  AlertCircle,
  Sparkles,
  Rocket,
  Folder,
  FileText,
} from "lucide-react";
import { useSessionStore, type ChatMessage } from "@/store/sessionStore";
import { useWorkflowStore } from "@/store/workflowStore";
import { useTurnStream } from "@/hooks/useTurnStream";
import AgentViewport from "@/components/AgentViewport";
import ChatComposer, { type ComposerMode } from "@/components/ChatComposer";
import SystemLogRail from "@/components/SystemLogRail";
import PermissionApprovalPopup from "@/components/PermissionApprovalPopup";
import { AIOS_API_URL } from "@/config";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  ChatSurface —— 对话主画布
//  左：消息流（按 kind 着色/排版，auto-scroll）；右：AgentViewport 产物预览
//  消费 useTurnStream；watch htmlPayload → 推 artifact 消息；顶栏「查看拓扑」开 overlay
// ════════════════════════════════════════════════════════════════

interface ChatSurfaceProps {
  htmlPayload: string;
  isRefreshingVfs: boolean;
  onRefreshVfs: () => void;
  onOpenTool: (surface: "workflow") => void;
}

export default function ChatSurface({
  htmlPayload,
  isRefreshingVfs,
  onRefreshVfs,
  onOpenTool,
}: ChatSurfaceProps) {
  const { startTurn } = useTurnStream();

  const sessions = useSessionStore((s) => s.sessions);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const addMessage = useSessionStore((s) => s.addMessage);
  const updateMessage = useSessionStore((s) => s.updateMessage);
  const setStreamingMessageId = useSessionStore((s) => s.setStreamingMessageId);
  const streamingMessageId = useSessionStore((s) => s.streamingMessageId);
  const deploy = useWorkflowStore((s) => s.deploy);

  // 对话优先：默认 chat 模式直连 LLM；切到 workflow 走 autoCompile+deploy+事件流
  const [mode, setMode] = useState<ComposerMode>("chat");
  const isChat = mode === "chat";

  // ── 部署到内核 + 运行拓扑（由 PlanCard「部署到内核」按钮触发） ──
  //  用户确认拓扑后手动点击，避免一气呵成打满 LLM RPM，也给了部署前 review 的机会。
  const deployAndRun = async (planMessage: ChatMessage) => {
    const sid = activeSessionId;
    if (!sid) return;
    // 标记 plan 为部署中，禁用按钮
    updateMessage(sid, planMessage.id, (p) => ({
      meta: { ...p.meta, status: "deploying" },
    }));

    const deployed = await deploy();
    if (!deployed) {
      // 回滚为待部署，便于重试
      updateMessage(sid, planMessage.id, (p) => ({
        meta: { ...p.meta, status: "pending_deploy" },
      }));
      addMessage(sid, {
        role: "agent",
        kind: "error",
        text: "部署失败：无法将工作流发送至 AIOS 内核。请检查后端连接。",
      });
      return;
    }

    // 标记已部署（按钮转成「已部署」徽章）
    updateMessage(sid, planMessage.id, (p) => ({
      meta: { ...p.meta, status: "deployed" },
    }));
    // 创建 activity 消息并订阅内核事件流
    const msg = addMessage(sid, { role: "agent", kind: "activity", text: "" });
    setStreamingMessageId(msg.id);
    startTurn(msg.id, sid);
  };

  const activeSession = sessions.find((s) => s.id === activeSessionId);
  // messages 是 newest-first，渲染时反转成时间序（oldest-first）
  const messages = activeSession
    ? [...activeSession.messages].reverse()
    : [];

  const [viewportCollapsed, setViewportCollapsed] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);
  const lastPayloadRef = useRef<string>(htmlPayload);

  // auto-scroll 到底部
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [activeSession?.messages.length, streamingMessageId, activeSessionId]);

  // watch htmlPayload → 推 artifact 消息（跳过初始占位值；UI_RENDER 时自动展开右栏）
  useEffect(() => {
    if (htmlPayload !== lastPayloadRef.current) {
      lastPayloadRef.current = htmlPayload;
      if (activeSessionId) {
        addMessage(activeSessionId, {
          role: "agent",
          kind: "artifact",
          text: "Agent 渲染了新产物，已在右侧视界预览。",
          meta: { htmlPreview: htmlPayload.slice(0, 120) },
        });
        setViewportCollapsed(false); // UI_RENDER 时自动展开
      }
    }
  }, [htmlPayload, activeSessionId, addMessage]);

  const hasMessages = messages.length > 0;

  return (
    <div className="flex h-full min-h-0 flex-1 overflow-hidden">
      {/* ═══ 消息流 ═══ */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 顶栏：会话标题 + 查看拓扑 */}
        <div className="flex h-10 flex-shrink-0 items-center gap-2 border-b border-outline-variant/15 px-4">
          <span className="truncate font-headline text-xs font-semibold text-on-surface-variant">
            {activeSession?.title ?? "AIOS Atelier"}
          </span>
          <button
            onClick={() => onOpenTool("workflow")}
            className="ml-auto flex items-center gap-1.5 rounded-lg bg-surface-container-high px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider text-outline transition-colors hover:bg-surface-container-highest hover:text-primary"
          >
            <Workflow className="h-3 w-3" />
            查看拓扑
          </button>
        </div>

        {/* 消息滚动区 */}
        <div
          ref={scrollRef}
          className="custom-scrollbar min-h-0 flex-1 overflow-y-auto"
        >
          {!hasMessages ? (
            <EmptyState />
          ) : (
            <div className="mx-auto w-full max-w-3xl space-y-5 px-4 py-6">
              {messages.map((m) => (
                <MessageBubble
                  key={m.id}
                  message={m}
                  streaming={m.id === streamingMessageId}
                  onDeploy={() => deployAndRun(m)}
                />
              ))}
            </div>
          )}
        </div>

        {/* 常驻系统告警条（后端日志/告警/状态实时流） */}
        <SystemLogRail />

        {/* 工具权限审批弹窗（pending 空时自隐） */}
        <PermissionApprovalPopup />

        {/* 常驻 composer */}
        <ChatComposer mode={mode} onModeChange={setMode} />
      </div>

      {/* ═══ 右栏：AgentViewport 产物预览（chat 模式隐藏，消息流占满） ═══ */}
      {!isChat && (
        <AgentViewport
          htmlPayload={htmlPayload}
          isRefreshing={isRefreshingVfs}
          onRefresh={onRefreshVfs}
          collapsed={viewportCollapsed}
          onToggleCollapsed={() => setViewportCollapsed((v) => !v)}
        />
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────
//  空态
// ────────────────────────────────────────────────────────────────
function EmptyState() {
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 text-center">
      <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-primary text-on-primary">
        <Sparkles className="h-6 w-6" />
      </div>
      <h2 className="font-headline text-lg font-bold text-on-surface">
        AIOS Atelier
      </h2>
      <p className="mt-1.5 max-w-md text-sm leading-relaxed text-outline">
        对话即编排。描述你的意图，架构师将规划并发工作流、部署到内核，
        并把执行流与产物实时渲染在这里。
      </p>
      <div className="mt-6 grid w-full max-w-md grid-cols-1 gap-2 text-left">
        {[
          "写一个并发抓取三个数据源并汇总报告的工作流",
          "编排一个自我修复的代码生成与校验流水线",
          "生成一个落地页并实时预览",
        ].map((s) => (
          <div
            key={s}
            className="rounded-lg bg-surface-container-lowest px-3 py-2 text-xs text-on-surface-variant ghost-border"
          >
            {s}
          </div>
        ))}
      </div>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────
//  单条消息 — 按 kind 着色/排版
// ────────────────────────────────────────────────────────────────
function MessageBubble({
  message,
  streaming,
  onDeploy,
}: {
  message: ChatMessage;
  streaming: boolean;
  onDeploy?: () => void;
}) {
  const { role, kind, text, meta } = message;

  // user prompt：右对齐气泡
  if (role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] rounded-2xl rounded-br-md bg-primary-container/40 px-4 py-2.5 text-sm text-on-surface ghost-border">
          {text}
        </div>
      </div>
    );
  }

  // agent 消息按 kind 分流
  switch (kind) {
    case "plan":
      return <PlanCard text={text} meta={meta} onDeploy={onDeploy} />;
    case "chat":
      return <ChatBlock text={text} streaming={streaming} status={meta?.status} />;
    case "activity":
      return <ActivityBlock text={text} streaming={streaming} paused={meta?.paused} status={meta?.status} meta={meta} />;
    case "artifact":
      return (
        <div className="flex items-start gap-2.5">
          <Monitor className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" />
          <div className="rounded-xl bg-surface-container-lowest px-4 py-2.5 text-sm text-on-surface-variant ghost-border">
            {text}
          </div>
        </div>
      );
    case "error":
      return (
        <div className="flex items-start gap-2.5">
          <XCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-error" />
          <div className="rounded-xl bg-error-container/25 px-4 py-2.5 text-sm text-on-surface ghost-border">
            {text}
          </div>
        </div>
      );
    case "system":
      return (
        <div className="flex justify-center">
          <span className="rounded-full bg-surface-container-lowest px-3 py-1 text-[10px] uppercase tracking-wider text-outline ghost-border">
            {text}
          </span>
        </div>
      );
    default:
      return (
        <div className="flex items-start gap-2.5">
          <div className="max-w-[80%] rounded-2xl rounded-bl-md bg-surface-container-lowest px-4 py-2.5 text-sm text-on-surface ghost-border">
            {text}
          </div>
        </div>
      );
  }
}

function PlanCard({
  text,
  meta,
  onDeploy,
}: {
  text: string;
  meta?: Record<string, any>;
  onDeploy?: () => void;
}) {
  const deploying = useWorkflowStore((s) => s.deploying);
  const streamingMessageId = useSessionStore((s) => s.streamingMessageId);
  const nodes: { id: string; role: string }[] = meta?.topology?.nodes ?? [];
  const status = meta?.status as "pending_deploy" | "deploying" | "deployed" | undefined;

  // 正在运行某 turn 时禁用部署（避免并发）
  const busy = deploying || !!streamingMessageId;

  return (
    <div className="rounded-xl bg-surface-container-lowest p-3 ghost-border">
      <div className="mb-2 flex items-center gap-1.5">
        <Workflow className="h-3.5 w-3.5 text-primary" />
        <span className="text-[10px] font-bold uppercase tracking-wider text-primary">Plan</span>
        {status === "deployed" && (
          <span className="ml-auto flex items-center gap-1 text-[10px] font-semibold text-tertiary">
            <CheckCircle2 className="h-3 w-3" />
            已部署
          </span>
        )}
      </div>
      <p className="text-sm text-on-surface">{text}</p>
      {nodes.length > 0 && (
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {nodes.map((n) => (
            <span
              key={n.id}
              className="rounded-md bg-surface-container-high px-2 py-0.5 font-mono text-[10px] text-on-surface-variant"
            >
              {n.id}
              <span className="text-outline"> · {n.role}</span>
            </span>
          ))}
        </div>
      )}

      {/* 部署到内核按钮 —— 仅在待部署状态显示 */}
      {status === "pending_deploy" && onDeploy && (
        <button
          onClick={onDeploy}
          disabled={busy}
          className={cn(
            "mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-xs font-bold transition-all",
            busy
              ? "cursor-not-allowed bg-surface-container-high text-outline"
              : "btn-primary-ink text-on-primary hover:opacity-90",
          )}
        >
          {deploying ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Rocket className="h-3.5 w-3.5" />
          )}
          {deploying ? "部署中…" : "部署到内核并运行"}
        </button>
      )}
      {status === "deploying" && !deploying && (
        <div className="mt-3 flex items-center justify-center gap-1.5 rounded-lg bg-surface-container-high px-3 py-2 text-xs text-outline">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          正在部署到内核…
        </div>
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────
//  Chat 消息 — prose 气泡，流式时尾部加闪烁光标
// ────────────────────────────────────────────────────────────────
function ChatBlock({
  text,
  streaming,
  status,
}: {
  text: string;
  streaming: boolean;
  status?: string;
}) {
  const ended = !!status;
  const isErr = status === "error";
  const showCursor = streaming && !ended && text.length > 0;
  return (
    <div className="flex items-start gap-2.5">
      <div
        className={cn(
          "max-w-[80%] rounded-2xl rounded-bl-md px-4 py-2.5 text-sm leading-relaxed ghost-border",
          isErr
            ? "bg-error-container/25 text-on-surface"
            : "bg-surface-container-lowest text-on-surface",
        )}
      >
        {text ? (
          <div className="whitespace-pre-wrap break-words">
            {text}
            {showCursor && (
              <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-soft-pulse bg-primary align-text-bottom" />
            )}
          </div>
        ) : (
          streaming &&
          !ended && (
            <div className="flex items-center gap-2 text-xs text-outline">
              <Loader2 className="h-3 w-3 animate-spin" />
              AI 思考中…
            </div>
          )
        )}
      </div>
    </div>
  );
}

function ActivityBlock({
  text,
  streaming,
  paused,
  status,
  meta,
}: {
  text: string;
  streaming: boolean;
  paused?: boolean;
  status?: string;
  meta?: Record<string, any>;
}) {
  const ended = !!status;
  return (
    <div className="rounded-xl bg-surface-dim/60 p-3 ghost-border">
      <div className="mb-2 flex items-center gap-2">
        {streaming && !ended ? (
          paused ? (
            <PauseCircle className="h-3.5 w-3.5 animate-soft-pulse text-tertiary" />
          ) : (
            <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />
          )
        ) : status === "succeeded" ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-tertiary" />
        ) : status === "failed" || status === "timeout" ? (
          <XCircle className="h-3.5 w-3.5 text-error" />
        ) : (
          <Clock className="h-3.5 w-3.5 text-outline" />
        )}
        <span
          className={cn(
            "text-[10px] font-bold uppercase tracking-wider",
            status === "succeeded"
              ? "text-tertiary"
              : status === "failed" || status === "timeout"
                ? "text-error"
                : "text-outline",
          )}
        >
          {ended ? `Activity · ${status}` : paused ? "Paused · 等待人机审批" : "Activity · 流式中"}
        </span>
      </div>
      {text ? (
        <pre className="custom-scrollbar max-h-72 overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-relaxed text-on-surface-variant">
          {text}
        </pre>
      ) : (
        !ended && (
          <div className="flex items-center gap-2 text-[11px] text-outline">
            <AlertCircle className="h-3 w-3" />
            等待内核事件流…
          </div>
        )
      )}
      {status === "succeeded" && meta?.workflowId && (
        <ArtifactsCard workflowId={String(meta.workflowId)} />
      )}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════
//  ArtifactsCard — 工作流成功后拉取 factory/ 产物文件，可点开预览文本内容
// ════════════════════════════════════════════════════════════════
interface ArtifactFile {
  name: string;
  size: number;
  modified: number;
  text: boolean;
}

function formatSize(n: number): string {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}K`;
  return `${(n / 1024 / 1024).toFixed(1)}M`;
}

function ArtifactsCard({ workflowId }: { workflowId: string }) {
  const [files, setFiles] = useState<ArtifactFile[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [content, setContent] = useState<string | null>(null);
  const [contentLoading, setContentLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetch(
      `${AIOS_API_URL}/api/artifacts/${encodeURIComponent(workflowId)}?token=AIOS-SUPER-SECRET-KEY`,
    )
      .then((r) => (r.ok ? r.json() : Promise.reject(`HTTP ${r.status}`)))
      .then((d) => {
        if (!cancelled) {
          setFiles(d.files ?? []);
          setLoading(false);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(String(e?.message ?? e));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [workflowId]);

  const openFile = async (name: string) => {
    if (active === name) {
      setActive(null);
      setContent(null);
      return;
    }
    setActive(name);
    setContent(null);
    setContentLoading(true);
    try {
      const r = await fetch(
        `${AIOS_API_URL}/api/artifacts/${encodeURIComponent(workflowId)}/file?name=${encodeURIComponent(name)}&token=AIOS-SUPER-SECRET-KEY`,
      );
      const d = await r.json();
      if (!r.ok) throw new Error(d.error || `HTTP ${r.status}`);
      setContent(d.content ?? "");
    } catch (e: any) {
      setContent(`✗ ${e?.message ?? e}`);
    } finally {
      setContentLoading(false);
    }
  };

  return (
    <div className="mt-3 rounded-lg bg-surface-container-lowest p-2.5 ghost-border">
      <div className="mb-1.5 flex items-center gap-1.5">
        <Folder className="h-3.5 w-3.5 text-primary" />
        <span className="text-[10px] font-bold uppercase tracking-wider text-primary">
          产物 · factory/
        </span>
        {files && (
          <span className="ml-auto text-[10px] text-outline">{files.length} 个文件</span>
        )}
      </div>
      {loading && <div className="text-[11px] text-outline">加载产物…</div>}
      {error && <div className="text-[11px] text-error">✗ {error}</div>}
      {!loading && files && files.length === 0 && (
        <div className="text-[11px] text-outline">无产物文件</div>
      )}
      {files && files.length > 0 && (
        <div className="space-y-0.5">
          {files.map((f) => (
            <div key={f.name}>
              <button
                onClick={() => f.text && openFile(f.name)}
                disabled={!f.text}
                className={cn(
                  "flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left text-[11px] transition-colors",
                  f.text
                    ? "hover:bg-surface-container-high text-on-surface"
                    : "cursor-not-allowed text-outline",
                )}
              >
                <FileText className="h-3 w-3 flex-shrink-0" />
                <span className="flex-1 truncate font-mono">{f.name}</span>
                <span className="text-[9px] text-outline">
                  {f.text ? formatSize(f.size) : "binary"}
                </span>
              </button>
              {active === f.name && (
                <div className="mt-1 rounded-md bg-surface-container-high p-2">
                  {contentLoading ? (
                    <span className="text-[10px] text-outline">读取中…</span>
                  ) : (
                    <pre className="custom-scrollbar max-h-64 overflow-auto whitespace-pre-wrap font-mono text-[10px] leading-relaxed text-on-surface-variant">
                      {content}
                    </pre>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
