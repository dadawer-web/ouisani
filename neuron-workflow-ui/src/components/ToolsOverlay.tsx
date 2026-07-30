import { useEffect } from "react";
import { ArrowLeft, Workflow, Monitor, Radar, Brain, FolderOpen } from "lucide-react";
import WorkflowSurface from "@/components/WorkflowSurface";
import KernelMonitor from "@/components/KernelMonitor";
import TelemetryRadar from "@/components/TelemetryRadar";
import MemoryViewer from "@/components/MemoryViewer";
import VfsWorkspaceBridge from "@/components/VfsWorkspaceBridge";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  ToolsOverlay —— 全画面 overlay，把可观测工具降为次级抽屉
//  点击侧栏 Tools 区进入；返回键退出；复用现有 5 个面板组件
// ════════════════════════════════════════════════════════════════

/** 主画布切换的"面"——侧栏导航项 + overlay 渲染目标 */
export type Surface = "workflow" | "kernel" | "telemetry" | "memory" | "vfs";

interface ToolDef {
  id: Surface;
  label: string;
  sub: string;
  icon: typeof Workflow;
}

export const TOOL_DEFS: ToolDef[] = [
  { id: "workflow", label: "工作流", sub: "Agent Topology", icon: Workflow },
  { id: "kernel", label: "内核监控", sub: "God's Eye", icon: Monitor },
  { id: "telemetry", label: "遥测雷达", sub: "Telemetry", icon: Radar },
  { id: "memory", label: "记忆", sub: "Memory Viewer", icon: Brain },
  { id: "vfs", label: "VFS Bridge", sub: "Workspace", icon: FolderOpen },
];

const TITLES: Record<Surface, string> = {
  workflow: "工作流拓扑",
  kernel: "内核监控",
  telemetry: "遥测雷达",
  memory: "记忆",
  vfs: "VFS Bridge",
};

interface ToolsOverlayProps {
  active: Surface | null;
  onChange: (s: Surface | null) => void;
  htmlPayload: string;
  isRefreshingVfs: boolean;
  onRefreshVfs: () => void;
}

export default function ToolsOverlay({
  active,
  onChange,
  htmlPayload,
  isRefreshingVfs,
  onRefreshVfs,
}: ToolsOverlayProps) {
  // ESC 关闭
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onChange(null);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [active, onChange]);

  if (!active) return null;
  const current = TOOL_DEFS.find((t) => t.id === active);

  return (
    <div className="absolute inset-0 z-30 flex flex-col bg-surface">
      {/* overlay 顶栏：返回 + 标题 + 工具 tab */}
      <div className="flex h-12 flex-shrink-0 items-center gap-2 border-b border-outline-variant/15 bg-surface-container-low px-3">
        <button
          onClick={() => onChange(null)}
          className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-medium text-outline transition-colors hover:bg-surface-container-high hover:text-on-surface"
        >
          <ArrowLeft className="h-4 w-4" />
          返回对话
        </button>
        <div className="h-4 w-px bg-outline-variant/30" />
        <span className="font-headline text-sm font-bold text-on-surface">
          {TITLES[active]}
        </span>

        {/* 工具 tab */}
        <div className="ml-auto flex items-center gap-1">
          {TOOL_DEFS.map((t) => {
            const Icon = t.icon;
            const isActive = t.id === active;
            return (
              <button
                key={t.id}
                onClick={() => onChange(t.id)}
                className={cn(
                  "flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors",
                  isActive
                    ? "bg-surface-container-lowest text-primary ghost-border-strong"
                    : "text-outline hover:bg-surface-container-high hover:text-on-surface",
                )}
                title={t.label}
              >
                <Icon className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">{t.label}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 工具内容 */}
      <div className="min-h-0 flex-1 overflow-hidden">
        {active === "workflow" && (
          <WorkflowSurface
            htmlPayload={htmlPayload}
            isRefreshingVfs={isRefreshingVfs}
            onRefreshVfs={onRefreshVfs}
          />
        )}
        {active === "kernel" && (
          <div className="h-full p-3">
            <KernelMonitor />
          </div>
        )}
        {active === "telemetry" && (
          <div className="h-full p-3">
            <TelemetryRadar />
          </div>
        )}
        {active === "memory" && (
          <div className="h-full p-3">
            <MemoryViewer />
          </div>
        )}
        {active === "vfs" && (
          <div className="custom-scrollbar h-full overflow-auto p-6">
            <VfsWorkspaceBridge />
          </div>
        )}
      </div>
      {current && <span className="sr-only">{current.sub}</span>}
    </div>
  );
}
