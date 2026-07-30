import { useCallback, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  BackgroundVariant,
  applyNodeChanges,
  applyEdgeChanges,
  type OnNodesChange,
  type OnEdgesChange,
} from "@xyflow/react";
import { Sparkles, Loader2, Rocket, ShieldAlert } from "lucide-react";
import "@xyflow/react/dist/style.css";

import AgentNode from "@/components/AgentNode";
import WorkflowConfigRail from "@/components/WorkflowConfigRail";
import AgentViewport from "@/components/AgentViewport";
import { useWorkflowStore } from "@/store/workflowStore";
import { useSystemStore } from "@/store/systemStore";

const nodeTypes = { agentNode: AgentNode };

/** 工作流面 —— 配置轨 + ReactFlow 画布 + Agent Viewport + 浮动 composer */
export default function WorkflowSurface({
  htmlPayload,
  isRefreshingVfs,
  onRefreshVfs,
}: {
  htmlPayload: string;
  isRefreshingVfs: boolean;
  onRefreshVfs: () => void;
}) {
  const nodes = useWorkflowStore((s) => s.nodes);
  const edges = useWorkflowStore((s) => s.edges);
  const onConnect = useWorkflowStore((s) => s.onConnect);
  const setNodes = useWorkflowStore((s) => s.setNodes);
  const setEdges = useWorkflowStore((s) => s.setEdges);
  const deploy = useWorkflowStore((s) => s.deploy);
  const deploying = useWorkflowStore((s) => s.deploying);
  const nodeCount = useWorkflowStore((s) => s.nodes.length);
  const edgeCount = useWorkflowStore((s) => s.edges.length);
  const autoCompile = useWorkflowStore((s) => s.autoCompile);
  const connected = useSystemStore((s) => s.connected);

  const [userIdea, setUserIdea] = useState("");
  const [autoCompiling, setAutoCompiling] = useState(false);
  const [railCollapsed, setRailCollapsed] = useState(false);
  const [viewportCollapsed, setViewportCollapsed] = useState(false);

  const onNodesChange: OnNodesChange = useCallback(
    (changes) => setNodes(applyNodeChanges(changes, nodes)),
    [nodes, setNodes],
  );
  const onEdgesChange: OnEdgesChange = useCallback(
    (changes) => setEdges(applyEdgeChanges(changes, edges)),
    [edges, setEdges],
  );

  const handleAutoCompile = async () => {
    if (!userIdea.trim()) return;
    setAutoCompiling(true);
    try {
      await autoCompile(userIdea);
    } finally {
      setAutoCompiling(false);
    }
  };

  return (
    <div className="flex h-full w-full overflow-hidden bg-surface">
      <WorkflowConfigRail
        collapsed={railCollapsed}
        onToggleCollapsed={() => setRailCollapsed((v) => !v)}
      />

      {/* 画布 + 浮动 composer */}
      <div className="relative flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          fitView
          className="bg-surface"
          defaultEdgeOptions={{
            animated: true,
            style: { stroke: "rgb(var(--primary))", strokeWidth: 2 },
          }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="rgb(var(--outline-variant) / 0.4)" />
          <Controls showInteractive={false} />
          <MiniMap nodeColor={() => "rgb(var(--primary-container))"} maskColor="rgb(var(--surface-dim) / 0.6)" />
        </ReactFlow>

        {/* 画布左上角拓扑摘要 */}
        <div className="pointer-events-none absolute left-4 top-4 flex items-center gap-2">
          <span className="pill bg-surface-container-lowest/85 text-on-surface-variant backdrop-blur">
            {nodeCount} nodes · {edgeCount} edges
          </span>
          {!connected && (
            <span className="pill bg-error-container/30 text-error backdrop-blur">
              <ShieldAlert className="h-3 w-3" /> kernel offline
            </span>
          )}
        </div>

        {/* 浮动 composer —— auto-compile + Deploy，仿 cc-haha 底部 composer */}
        <div className="absolute bottom-6 left-1/2 z-20 w-full max-w-2xl -translate-x-1/2 px-6">
          <div className="glass-panel ambient-shadow rounded-2xl p-3">
            <textarea
              value={userIdea}
              onChange={(e) => setUserIdea(e.target.value)}
              placeholder="用一句话描述你的想法，AIOS 架构师将自动编排工作流拓扑…"
              rows={2}
              className="w-full resize-none bg-transparent px-2 py-1.5 text-sm text-on-surface placeholder:text-outline/50 focus:outline-none"
            />
            <div className="flex items-center justify-between px-2 pt-1">
              <span className="text-[10px] font-medium uppercase tracking-wider text-outline">
                Auto-Compile · 傻瓜模式
              </span>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleAutoCompile}
                  disabled={!userIdea.trim() || autoCompiling}
                  className="flex items-center gap-2 rounded-lg bg-surface-container-high px-3 py-1.5 text-xs font-semibold text-on-surface transition-colors hover:bg-surface-container-highest disabled:opacity-40"
                >
                  {autoCompiling ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Sparkles className="h-3.5 w-3.5" />
                  )}
                  生成拓扑
                </button>
                <button
                  onClick={deploy}
                  disabled={deploying || nodeCount === 0}
                  className="flex items-center gap-2 rounded-lg btn-primary-ink px-3 py-1.5 text-xs font-bold text-on-primary transition-opacity hover:opacity-90 disabled:opacity-40"
                >
                  {deploying ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Rocket className="h-3.5 w-3.5" />
                  )}
                  部署到内核
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <AgentViewport
        htmlPayload={htmlPayload}
        isRefreshing={isRefreshingVfs}
        onRefresh={onRefreshVfs}
        collapsed={viewportCollapsed}
        onToggleCollapsed={() => setViewportCollapsed((v) => !v)}
      />
    </div>
  );
}
