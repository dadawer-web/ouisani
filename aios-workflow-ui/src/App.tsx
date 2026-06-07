import { useCallback, useEffect, useState } from "react";
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
import { CheckCircle2, AlertCircle, X, AlertTriangle, Code, Monitor } from "lucide-react";
import "@xyflow/react/dist/style.css";

import AgentNode from "@/components/AgentNode";
import Sidebar from "@/components/Sidebar";
import { useWorkflowStore } from "@/store/workflowStore";

const nodeTypes = { agentNode: AgentNode };

export default function App() {
  const nodes = useWorkflowStore((s) => s.nodes);
  const edges = useWorkflowStore((s) => s.edges);
  const onConnect = useWorkflowStore((s) => s.onConnect);
  const setNodes = useWorkflowStore((s) => s.setNodes);
  const setEdges = useWorkflowStore((s) => s.setEdges);
  const toast = useWorkflowStore((s) => s.toast);
  const hideToast = useWorkflowStore((s) => s.hideToast);
  const systemAlert = useWorkflowStore((s) => s.systemAlert);
  const triggerSystemAlert = useWorkflowStore((s) => s.triggerSystemAlert);
  const dismissSystemAlert = useWorkflowStore((s) => s.dismissSystemAlert);
  const setControlWs = useWorkflowStore((s) => s.setControlWs);

  const [debugCode, setDebugCode] = useState<string | null>(null);

  // ── 全息视界：UI Sandbox 渲染状态 ──
  const [htmlPayload, setHtmlPayload] = useState<string>(
    "<html><body style='display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#0a0a0f;color:#333;font-family:monospace'><p style='opacity:0.3'>Awaiting agent render signal...</p></body></html>"
  );

  const onNodesChange: OnNodesChange = useCallback(
    (changes) => {
      setNodes(applyNodeChanges(changes, nodes));
    },
    [nodes, setNodes]
  );

  const onEdgesChange: OnEdgesChange = useCallback(
    (changes) => {
      setEdges(applyEdgeChanges(changes, edges));
    },
    [edges, setEdges]
  );

  // ── WebSocket: 监听内核自愈告警 + UI_RENDER 渲染信号 ──
  useEffect(() => {
    const ws = new WebSocket("ws://localhost:8080/api/dashboard/alerts?token=AIOS-SUPER-SECRET-KEY");

    ws.onopen = () => {
      console.log("[AIOS] Dashboard alert WebSocket connected");
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "HUMAN_INTERVENTION") {
          triggerSystemAlert(data.nodeId || "unknown", data.dump || "No stacktrace available");
        } else if (data.type === "UI_RENDER") {
          // ── 全息视界：拦截 UI_RENDER 信号，注入 iframe ──
          const htmlContent = data.html_content || data.payload?.html_content || "";
          if (htmlContent) {
            setHtmlPayload(htmlContent);
            console.log("[AIOS] UI_RENDER received, sandbox updated");
          }
        }
      } catch {
        console.warn("[AIOS] Failed to parse alert message:", event.data);
      }
    };

    ws.onerror = () => {
      console.warn("[AIOS] Dashboard alert WebSocket error");
    };

    ws.onclose = () => {
      console.log("[AIOS] Dashboard alert WebSocket closed");
    };

    return () => {
      ws.close();
    };
  }, [triggerSystemAlert]);

  // ── WebSocket: God Hand 控制通道（热补丁参数） ──
  useEffect(() => {
    const ws = new WebSocket("ws://localhost:8080/api/workflow/control?token=AIOS-SUPER-SECRET-KEY");

    ws.onopen = () => {
      console.log("[AIOS] God Hand control WebSocket connected");
      setControlWs(ws);
    };

    ws.onclose = () => {
      console.log("[AIOS] God Hand control WebSocket closed");
      setControlWs(null);
    };

    ws.onerror = () => {
      console.warn("[AIOS] God Hand control WebSocket error");
    };

    return () => {
      ws.close();
      setControlWs(null);
    };
  }, [setControlWs]);

  // ── 查看现场：从 VFS 读取节点代码 ──
  const handleOpenDebugView = async () => {
    const nodeId = systemAlert.nodeId;
    try {
      const response = await fetch(
        `http://localhost:8080/api/vfs/read?path=/factory/${nodeId}.py&token=AIOS-SUPER-SECRET-KEY`
      );
      if (response.ok) {
        const text = await response.text();
        setDebugCode(text);
      } else {
        setDebugCode(`// Failed to load code for node: ${nodeId}\n// HTTP ${response.status}`);
      }
    } catch {
      setDebugCode(`// Failed to load code for node: ${nodeId}\n// Backend unreachable`);
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#0a0a0f]">
      {/* 左侧边栏 */}
      <Sidebar />

      {/* 中间：React Flow 画布 */}
      <div className="relative flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          fitView
          className="bg-[#0a0a0f]"
          defaultEdgeOptions={{
            animated: true,
            style: { stroke: "#00f0ff", strokeWidth: 2 },
          }}
        >
          <Background
            variant={BackgroundVariant.Dots}
            gap={20}
            size={1}
            color="#1a1a2e"
          />
          <Controls
            className="!border-zinc-800 !bg-[#0d1117]/90 !shadow-lg [&>button]:!border-zinc-800 [&>button]:!bg-[#0d1117] [&>button]:!fill-zinc-400 [&>button]:hover:!bg-zinc-800"
          />
          <MiniMap
            className="!border-zinc-800 !bg-[#0d1117]/90"
            nodeColor="#1e293b"
            maskColor="rgba(0,0,0,0.7)"
          />
        </ReactFlow>
      </div>

      {/* ════════════════════════════════════════════════════════════════
          右侧：全息视界 (UI Sandbox) — 实时渲染智能体前端代码
         ════════════════════════════════════════════════════════════════ */}
      <div className="flex w-96 flex-col border-l border-cyan-500/20 bg-[#080810]/95">
        {/* 标题栏 */}
        <div className="flex items-center gap-2 border-b border-cyan-500/20 bg-cyan-500/[0.04] px-4 py-3">
          <Monitor className="h-4 w-4 text-cyan-400" />
          <span className="text-[11px] font-bold tracking-widest text-cyan-300 uppercase">
            Agent UI Viewport
          </span>
          <span className="ml-auto rounded bg-cyan-900/30 px-1.5 py-0.5 font-mono text-[9px] text-cyan-500">
            SANDBOX
          </span>
        </div>

        {/* 沙箱 iframe */}
        <div className="flex-1 p-2">
          <iframe
            sandbox="allow-scripts allow-same-origin"
            srcDoc={htmlPayload}
            className="h-full w-full rounded-lg border-2 border-zinc-700/50 bg-white"
            title="Agent UI Sandbox"
          />
        </div>

        {/* 底部状态栏 */}
        <div className="flex items-center gap-2 border-t border-zinc-800/50 px-4 py-2">
          <div className="h-1.5 w-1.5 animate-pulse rounded-full bg-cyan-400" />
          <span className="text-[9px] font-medium tracking-wider text-zinc-500 uppercase">
            Listening for UI_RENDER signals
          </span>
        </div>
      </div>

      {/* Toast 通知 */}
      {toast.visible && (
        <div
          className={`fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 items-center gap-3 rounded-lg border px-4 py-3 shadow-2xl backdrop-blur-sm transition-all ${
            toast.type === "success"
              ? "border-emerald-500/40 bg-emerald-900/30 text-emerald-200"
              : toast.type === "error"
                ? "border-red-500/40 bg-red-900/30 text-red-200"
                : "border-cyan-500/40 bg-cyan-900/30 text-cyan-200"
          }`}
        >
          {toast.type === "success" ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          ) : toast.type === "error" ? (
            <AlertCircle className="h-5 w-5 text-red-400" />
          ) : (
            <AlertCircle className="h-5 w-5 text-cyan-400" />
          )}
          <span className="text-sm font-medium">{toast.message}</span>
          <button
            onClick={hideToast}
            className="ml-2 rounded p-0.5 opacity-60 transition-opacity hover:opacity-100"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* ════════════════════════════════════════════════════════════════
          系统告警弹窗 — AutoMedic 熔断时弹出，z-index 最高层
         ════════════════════════════════════════════════════════════════ */}
      {systemAlert.visible && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/70 backdrop-blur-sm">
          <div className="relative w-full max-w-2xl rounded-xl border-2 border-red-500/60 bg-[#1a0a0a] shadow-[0_0_60px_rgba(239,68,68,0.3)]">
            {/* 标题栏 */}
            <div className="flex items-center gap-3 border-b border-red-500/30 px-6 py-4">
              <AlertTriangle className="h-6 w-6 animate-pulse text-red-500" />
              <h2 className="text-lg font-bold text-red-300">
                HUMAN INTERVENTION REQUIRED
              </h2>
              <button
                onClick={dismissSystemAlert}
                className="ml-auto rounded-lg p-1 text-red-400/60 transition-colors hover:bg-red-900/30 hover:text-red-300"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* 告警内容 */}
            <div className="space-y-4 px-6 py-5">
              <div className="flex items-center gap-2">
                <span className="text-sm font-semibold text-red-400">Node ID:</span>
                <code className="rounded bg-red-900/30 px-2 py-0.5 font-mono text-sm text-red-200">
                  {systemAlert.nodeId}
                </code>
              </div>

              <div>
                <span className="text-sm font-semibold text-red-400">Error Stack:</span>
                <pre className="mt-2 max-h-40 overflow-auto rounded-lg border border-red-800/40 bg-black/50 p-3 font-mono text-xs leading-relaxed text-red-300/80">
                  {systemAlert.dump}
                </pre>
              </div>

              {/* 调试代码视图 */}
              {debugCode !== null && (
                <div>
                  <div className="flex items-center gap-2">
                    <Code className="h-4 w-4 text-amber-400" />
                    <span className="text-sm font-semibold text-amber-400">VFS Source Code:</span>
                  </div>
                  <pre className="mt-2 max-h-48 overflow-auto rounded-lg border border-amber-800/40 bg-black/50 p-3 font-mono text-xs leading-relaxed text-amber-200/80">
                    {debugCode}
                  </pre>
                </div>
              )}
            </div>

            {/* 操作按钮 */}
            <div className="flex items-center justify-end gap-3 border-t border-red-500/20 px-6 py-4">
              <button
                onClick={dismissSystemAlert}
                className="rounded-lg border border-zinc-700 bg-zinc-800 px-4 py-2 text-sm font-medium text-zinc-300 transition-colors hover:bg-zinc-700"
              >
                Dismiss
              </button>
              <button
                onClick={handleOpenDebugView}
                className="flex items-center gap-2 rounded-lg border border-amber-500/50 bg-amber-600/20 px-4 py-2 text-sm font-bold text-amber-200 shadow-[0_0_15px_rgba(245,158,11,0.15)] transition-all hover:border-amber-400/70 hover:bg-amber-600/30"
              >
                <Code className="h-4 w-4" />
                Open Debug View
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
