import { useState } from "react";
import { Plus, Rocket, Workflow, Sparkles, Loader2 } from "lucide-react";
import { useWorkflowStore } from "@/store/workflowStore";

/** 左侧边栏 — 工作流操作面板（专家 + 傻瓜双模式） */
export default function Sidebar() {
  const workflowName = useWorkflowStore((s) => s.workflowName);
  const setWorkflowName = useWorkflowStore((s) => s.setWorkflowName);
  const nodeCount = useWorkflowStore((s) => s.nodes.length);
  const edgeCount = useWorkflowStore((s) => s.edges.length);
  const deploy = useWorkflowStore((s) => s.deploy);
  const deploying = useWorkflowStore((s) => s.deploying);
  const addNode = useWorkflowStore((s) => s.addNode);
  const autoCompile = useWorkflowStore((s) => s.autoCompile);

  const [userIdea, setUserIdea] = useState("");
  const [autoCompiling, setAutoCompiling] = useState(false);

  const handleAddNode = () => {
    addNode({
      x: 250 + Math.random() * 200,
      y: 150 + Math.random() * 150,
    });
  };

  const handleAutoCompile = () => {
    if (!userIdea.trim()) return;
    setAutoCompiling(true);
    // 模拟后端延迟
    setTimeout(() => {
      autoCompile(userIdea);
      setAutoCompiling(false);
    }, 800);
  };

  return (
    <div className="flex h-full w-72 flex-col border-r border-zinc-800/80 bg-[#0a0a0f]/95 backdrop-blur-sm">
      {/* 标题 */}
      <div className="flex items-center gap-2 border-b border-zinc-800/80 px-4 py-4">
        <Workflow className="h-5 w-5 text-cyan-400" />
        <div>
          <h1 className="text-sm font-bold tracking-wider text-zinc-100 uppercase">
            AIOS Workflow
          </h1>
          <p className="text-[10px] text-zinc-500">Agent Topology Builder</p>
        </div>
      </div>

      {/* ════════════════════════════════════════════════════════════════
          傻瓜模式 — 一句话生成拓扑
         ════════════════════════════════════════════════════════════════ */}
      <div className="border-b border-zinc-800/50 px-4 py-3">
        <label className="mb-1.5 flex items-center gap-1.5 text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
          <Sparkles className="h-3 w-3" /> Auto-Compile
        </label>
        <textarea
          value={userIdea}
          onChange={(e) => setUserIdea(e.target.value)}
          placeholder="输入您的想法，AIOS 将自动为您构建工作流..."
          rows={3}
          className="w-full resize-none rounded-lg border border-cyan-500/20 bg-cyan-500/[0.03] px-3 py-2 text-xs text-zinc-200 placeholder-zinc-600 outline-none transition-colors focus:border-cyan-400/50 focus:ring-1 focus:ring-cyan-500/20"
        />
        <button
          onClick={handleAutoCompile}
          disabled={!userIdea.trim() || autoCompiling}
          className="mt-2 flex w-full items-center justify-center gap-2 rounded-lg border border-cyan-500/40 bg-cyan-500/15 px-3 py-2.5 text-xs font-bold text-cyan-200 shadow-[0_0_15px_rgba(0,240,255,0.1)] transition-all hover:border-cyan-400/70 hover:bg-cyan-500/25 hover:shadow-[0_0_25px_rgba(0,240,255,0.2)] active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40"
        >
          {autoCompiling ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Sparkles className="h-4 w-4" />
          )}
          {autoCompiling ? "生成中..." : "一键生成拓扑 (Auto-Compile)"}
        </button>
      </div>

      {/* 工作流名称 */}
      <div className="border-b border-zinc-800/50 px-4 py-3">
        <label className="mb-1 block text-[10px] font-medium tracking-wider text-zinc-500 uppercase">
          Workflow Name
        </label>
        <input
          type="text"
          value={workflowName}
          onChange={(e) => setWorkflowName(e.target.value)}
          className="w-full rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 outline-none transition-colors focus:border-cyan-500/50"
        />
      </div>

      {/* 统计 */}
      <div className="flex gap-3 border-b border-zinc-800/50 px-4 py-3">
        <div className="flex-1 rounded border border-zinc-800/50 bg-zinc-900/30 px-2 py-1.5 text-center">
          <div className="text-lg font-bold text-cyan-400">{nodeCount}</div>
          <div className="text-[9px] text-zinc-500 uppercase">Nodes</div>
        </div>
        <div className="flex-1 rounded border border-zinc-800/50 bg-zinc-900/30 px-2 py-1.5 text-center">
          <div className="text-lg font-bold text-violet-400">{edgeCount}</div>
          <div className="text-[9px] text-zinc-500 uppercase">Edges</div>
        </div>
      </div>

      {/* 专家模式操作按钮 */}
      <div className="flex flex-col gap-2 px-4 py-4">
        <div className="mb-1 text-[9px] font-medium tracking-widest text-zinc-600 uppercase">
          Expert Mode
        </div>
        <button
          onClick={handleAddNode}
          className="flex items-center justify-center gap-2 rounded-lg border border-zinc-700/50 bg-zinc-800/30 px-3 py-2.5 text-xs font-semibold text-zinc-300 transition-all hover:border-zinc-600 hover:bg-zinc-800/50 active:scale-[0.98]"
        >
          <Plus className="h-4 w-4" />
          + 添加节点
        </button>

        <button
          onClick={deploy}
          disabled={deploying || nodeCount === 0}
          className="flex items-center justify-center gap-2 rounded-lg border border-emerald-500/40 bg-emerald-500/15 px-3 py-2.5 text-xs font-bold text-emerald-200 shadow-[0_0_15px_rgba(16,185,129,0.1)] transition-all hover:border-emerald-400/70 hover:bg-emerald-500/25 hover:shadow-[0_0_25px_rgba(16,185,129,0.2)] active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40"
        >
          {deploying ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Rocket className="h-4 w-4" />
          )}
          {deploying ? "部署中..." : "部署到内核 (Deploy)"}
        </button>
      </div>

      {/* 提示 */}
      <div className="mt-auto border-t border-zinc-800/50 px-4 py-3">
        <p className="text-[10px] leading-relaxed text-zinc-600">
          傻瓜模式：输入想法 → 一键生成。专家模式：手动添加节点 → 拖线连线 → 部署。
        </p>
      </div>
    </div>
  );
}
