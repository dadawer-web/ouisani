import {
  Activity,
  BookOpen,
  Brain,
  FolderOpen,
  Monitor,
  Radar,
  ShieldCheck,
  Sparkles,
  Workflow,
} from "lucide-react";

export type Surface =
  | "missions"
  | "runs"
  | "workflow"
  | "kernel"
  | "telemetry"
  | "memory"
  | "wiki"
  | "vfs"
  | "capabilities";

export interface ToolDef {
  id: Surface;
  label: string;
  sub: string;
  icon: typeof Workflow;
}

export const TOOL_DEFS: ToolDef[] = [
  { id: "missions", label: "连续任务", sub: "Missions", icon: Sparkles },
  { id: "runs", label: "Run 控制台", sub: "Runs & Trace", icon: Activity },
  { id: "workflow", label: "工作流", sub: "Agent Topology", icon: Workflow },
  { id: "kernel", label: "内核监控", sub: "God's Eye", icon: Monitor },
  { id: "telemetry", label: "遥测雷达", sub: "Telemetry", icon: Radar },
  { id: "memory", label: "记忆", sub: "Memory Viewer", icon: Brain },
  { id: "wiki", label: "Wiki", sub: "Compiled Knowledge", icon: BookOpen },
  { id: "vfs", label: "VFS Bridge", sub: "Workspace", icon: FolderOpen },
  { id: "capabilities", label: "IDE & 能力", sub: "Skills · Diff · Browser · Channels", icon: ShieldCheck },
];
