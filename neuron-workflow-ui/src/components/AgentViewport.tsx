import { Monitor, RefreshCw, Loader2, ChevronLeft, ChevronRight } from "lucide-react";

/**
 * 全息视界 —— Agent UI 沙箱 iframe。
 * htmlPayload 由 App 顶层 dashboard/alerts WebSocket 的 UI_RENDER 信号注入；
 * Refresh 按钮主动从 VFS 拉取 /factory/index.html。
 */
export default function AgentViewport({
  htmlPayload,
  isRefreshing,
  onRefresh,
  collapsed,
  onToggleCollapsed,
}: {
  htmlPayload: string;
  isRefreshing: boolean;
  onRefresh: () => void;
  collapsed: boolean;
  onToggleCollapsed: () => void;
}) {
  if (collapsed) {
    return (
      <div className="flex w-10 flex-shrink-0 flex-col items-center bg-surface-container-low py-3">
        <button
          onClick={onToggleCollapsed}
          className="flex h-8 w-8 items-center justify-center rounded-lg text-outline hover:bg-surface-container-high hover:text-primary"
          title="展开视界"
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
        <Monitor className="mt-3 h-4 w-4 text-outline/50" />
      </div>
    );
  }

  return (
    <div className="flex w-96 flex-shrink-0 flex-col bg-surface-container-low">
      {/* 标题栏 */}
      <div className="flex items-center gap-2 px-4 py-3">
        <Monitor className="h-4 w-4 text-primary" />
        <span className="font-headline text-[11px] font-bold uppercase tracking-widest text-on-surface">
          Agent Viewport
        </span>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          title="从 VFS 拉取最新 HTML 产物"
          className="ml-auto flex items-center gap-1 rounded-lg bg-surface-container-high px-2 py-1 text-[9px] font-bold uppercase tracking-wider text-outline transition-colors hover:bg-surface-container-highest hover:text-primary disabled:opacity-40"
        >
          {isRefreshing ? (
            <Loader2 className="h-3 w-3 animate-spin" />
          ) : (
            <RefreshCw className="h-3 w-3" />
          )}
          Refresh
        </button>
        <span className="rounded bg-surface-container-high px-1.5 py-0.5 font-mono text-[9px] text-outline">
          SANDBOX
        </span>
        <button
          onClick={onToggleCollapsed}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-outline hover:bg-surface-container-high hover:text-primary"
          title="折叠视界"
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>

      {/* 沙箱 iframe */}
      <div className="flex-1 p-2">
        <iframe
          sandbox="allow-scripts allow-same-origin"
          srcDoc={htmlPayload}
          className="h-full w-full rounded-xl border-2 border-outline-variant/30 bg-white"
          title="Agent UI Sandbox"
        />
      </div>

      {/* 底部状态 */}
      <div className="flex items-center gap-2 px-4 py-2">
        <div className="h-1.5 w-1.5 animate-soft-pulse rounded-full bg-primary" />
        <span className="text-[9px] font-medium uppercase tracking-wider text-outline">
          Listening for UI_RENDER signals
        </span>
      </div>
    </div>
  );
}
