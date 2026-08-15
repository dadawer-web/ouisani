import { useState } from "react";
import {
  Workflow,
  Plus,
  Trash2,
  Monitor,
  Radar,
  Brain,
  FolderOpen,
  MessageSquare,
  Activity,
  Sparkles,
  BookOpen,
  ShieldCheck,
} from "lucide-react";
import { useSessionStore } from "@/store/sessionStore";
import { TOOL_DEFS, type Surface } from "@/components/toolDefs";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  SessionSidebar —— cc-haha 式左侧栏：品牌 + New chat + 会话历史 + Tools 区
//  会话历史仅存 localStorage；激活项古铜左竖条；Tools 点击 → onOpenTool
// ════════════════════════════════════════════════════════════════

interface SessionSidebarProps {
  onOpenTool: (s: Surface) => void;
}

const TOOL_ICONS: Record<Surface, typeof Workflow> = {
  missions: Sparkles,
  runs: Activity,
  workflow: Workflow,
  kernel: Monitor,
  telemetry: Radar,
  memory: Brain,
  wiki: BookOpen,
  vfs: FolderOpen,
  capabilities: ShieldCheck,
};

export default function SessionSidebar({ onOpenTool }: SessionSidebarProps) {
  const sessions = useSessionStore((s) => s.sessions);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const createSession = useSessionStore((s) => s.createSession);
  const setActive = useSessionStore((s) => s.setActive);
  const deleteSession = useSessionStore((s) => s.deleteSession);
  const [confirmId, setConfirmId] = useState<string | null>(null);

  const handleNewChat = () => {
    createSession("New Chat");
  };

  return (
    <aside className="flex h-full w-[264px] flex-shrink-0 flex-col bg-surface-container-low">
      {/* 品牌标记 */}
      <div className="flex items-center gap-2.5 px-4 py-3.5">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-on-primary">
          <Workflow className="h-4 w-4" />
        </div>
        <div className="leading-tight">
          <div className="font-headline text-sm font-bold text-on-surface">AIOS Atelier</div>
          <div className="text-[10px] text-outline">Chat-First Workbench</div>
        </div>
      </div>

      {/* New chat */}
      <div className="px-3 pb-2">
        <button
          onClick={handleNewChat}
          className="flex w-full items-center justify-center gap-2 rounded-lg btn-primary-ink px-3 py-2 text-xs font-bold text-on-primary transition-opacity hover:opacity-90"
        >
          <Plus className="h-3.5 w-3.5" />
          New Chat
        </button>
      </div>

      {/* 会话历史 */}
      <div className="mb-1 px-4 pt-2 text-[10px] font-bold uppercase tracking-widest text-outline/60">
        Sessions
      </div>
      <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto px-2">
        {sessions.length === 0 ? (
          <div className="px-2 py-6 text-center text-[11px] leading-relaxed text-outline">
            尚无会话。
            <br />
            点击 New Chat 或直接在底部输入开始。
          </div>
        ) : (
          <div className="space-y-0.5">
            {sessions.map((s) => {
              const isActive = s.id === activeSessionId;
              return (
                <div
                  key={s.id}
                  className={cn(
                    "group relative flex items-center gap-2 rounded-lg px-2.5 py-2 text-left transition-colors",
                    isActive
                      ? "bg-surface-container-lowest text-on-surface"
                      : "text-outline hover:bg-surface-container-high hover:text-on-surface",
                  )}
                >
                  {isActive && (
                    <span className="absolute left-0 top-1/2 h-4 w-1 -translate-y-1/2 rounded-full bg-primary" />
                  )}
                  <button
                    onClick={() => setActive(s.id)}
                    className="flex min-w-0 flex-1 items-center gap-2 text-left"
                  >
                    <MessageSquare
                      className={cn("h-3.5 w-3.5 flex-shrink-0", isActive ? "text-primary" : "text-outline")}
                    />
                    <span className="min-w-0 flex-1 truncate text-xs font-medium">{s.title}</span>
                  </button>
                  {confirmId === s.id ? (
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => {
                          deleteSession(s.id);
                          setConfirmId(null);
                        }}
                        className="rounded p-0.5 text-error hover:bg-error-container/30"
                        title="确认删除"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => setConfirmId(s.id)}
                      className="rounded p-0.5 text-outline opacity-0 transition-opacity hover:text-error group-hover:opacity-100"
                      title="删除会话"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Tools 区 */}
      <div className="border-t border-outline-variant/15 px-2 py-2">
        <div className="mb-1 px-2 text-[10px] font-bold uppercase tracking-widest text-outline/60">
          Tools
        </div>
        <div className="space-y-0.5">
          {TOOL_DEFS.map((t) => {
            const Icon = TOOL_ICONS[t.id];
            return (
              <button
                key={t.id}
                onClick={() => onOpenTool(t.id)}
                className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-outline transition-colors hover:bg-surface-container-high hover:text-on-surface"
              >
                <Icon className="h-3.5 w-3.5" />
                <span className="flex-1">{t.label}</span>
                <span className="text-[10px] text-outline/50">{t.sub}</span>
              </button>
            );
          })}
        </div>
        <div className="mt-2 px-2 text-[10px] text-outline/40">AIOS v1.0 · Atelier</div>
      </div>
    </aside>
  );
}
