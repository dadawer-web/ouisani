import { memo, useState, useCallback, useEffect, useRef, type FC } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Bot, Trash2, Radio, ArrowRightFromLine, Sliders, ChevronDown, ChevronUp, CheckCircle2, AlertCircle, Loader2 } from "lucide-react";
import { useWorkflowStore, type AgentNodeData } from "@/store/workflowStore";

/** 状态对应的样式映射 */
const STATUS_STYLES: Record<string, { border: string; shadow: string; icon: FC<{ className?: string }> }> = {
  running: {
    border: "border-amber-500/60",
    shadow: "shadow-[0_0_25px_rgba(245,158,11,0.2)]",
    icon: Loader2,
  },
  succeeded: {
    border: "border-emerald-500/60",
    shadow: "shadow-[0_0_20px_rgba(52,211,153,0.15)]",
    icon: CheckCircle2,
  },
  failed: {
    border: "border-red-500/60",
    shadow: "shadow-[0_0_25px_rgba(239,68,68,0.2)]",
    icon: AlertCircle,
  },
  idle: {
    border: "border-cyan-500/30",
    shadow: "shadow-[0_0_20px_rgba(0,240,255,0.08)]",
    icon: Bot,
  },
};

/** 自定义智能体节点 — 深色极客风格卡片 + God Hand 控制面板 */
const AgentNode: FC<NodeProps> = memo(({ id, data }) => {
  const d = data as AgentNodeData;
  const updateNodeData = useWorkflowStore((s) => s.updateNodeData);
  const removeNode = useWorkflowStore((s) => s.removeNode);
  const hotPatchParam = useWorkflowStore((s) => s.hotPatchParam);

  const [panelOpen, setPanelOpen] = useState(false);
  const [threshold, setThreshold] = useState(
    parseFloat(String(d.userParams?.threshold ?? "0.05"))
  );
  const [keywords, setKeywords] = useState(
    String(d.userParams?.keywords ?? "")
  );
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 防抖热补丁：输入后 300ms 才发射
  const debouncedHotPatch = useCallback(
    (params: Record<string, number | string>) => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        // 只发送数字类型的 params（WebSocket HOT_PATCH_PARAM）
        const numericParams: Record<string, number> = {};
        for (const [k, v] of Object.entries(params)) {
          if (typeof v === "number") numericParams[k] = v;
        }
        if (Object.keys(numericParams).length > 0) {
          hotPatchParam(id, numericParams);
        }
      }, 300);
    },
    [id, hotPatchParam]
  );

  const handleThresholdChange = useCallback(
    (value: number) => {
      setThreshold(value);
      debouncedHotPatch({ threshold: value });
    },
    [debouncedHotPatch]
  );

  const handleKeywordsChange = useCallback(
    (value: string) => {
      setKeywords(value);
      updateNodeData(id, { userParams: { ...d.userParams, keywords: value } });
    },
    [id, d.userParams, updateNodeData]
  );

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  const status = d.status ?? "idle";
  const style = STATUS_STYLES[status] ?? STATUS_STYLES.idle;
  const StatusIcon = style.icon;

  return (
    <div className={`group relative min-w-[220px] rounded-lg border ${style.border} bg-[#0d1117]/95 ${style.shadow} backdrop-blur-sm transition-all duration-300 hover:border-cyan-400/60 hover:shadow-[0_0_30px_rgba(0,240,255,0.15)]`}>
      {/* 左侧输入 Handle */}
      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 !rounded-full !border-2 !border-cyan-400 !bg-[#0d1117] transition-colors hover:!bg-cyan-400"
      />

      {/* 顶部标题栏 */}
      <div className="flex items-center gap-2 rounded-t-lg border-b border-cyan-500/20 bg-cyan-500/5 px-3 py-2">
        <StatusIcon className={`h-4 w-4 text-cyan-400 ${status === "running" ? "animate-spin" : ""}`} />
        <span className="flex-1 text-xs font-semibold tracking-wider text-cyan-300 uppercase">
          {d.label || "Agent"}
        </span>
        {status !== "idle" && (
          <span className={`rounded px-1.5 py-0.5 font-mono text-[9px] font-bold uppercase ${
            status === "running" ? "bg-amber-900/40 text-amber-300" :
            status === "succeeded" ? "bg-emerald-900/40 text-emerald-300" :
            status === "failed" ? "bg-red-900/40 text-red-300" : ""
          }`}>
            {status}
          </span>
        )}
        <button
          onClick={() => setPanelOpen(!panelOpen)}
          className="rounded p-0.5 text-amber-400/60 opacity-0 transition-all hover:bg-amber-500/20 hover:text-amber-300 group-hover:opacity-100"
          title="Control Panel"
        >
          {panelOpen ? <ChevronUp className="h-3.5 w-3.5" /> : <Sliders className="h-3.5 w-3.5" />}
        </button>
        <button
          onClick={() => removeNode(id)}
          className="rounded p-0.5 text-zinc-500 opacity-0 transition-all hover:bg-red-500/20 hover:text-red-400 group-hover:opacity-100"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* 内容区 */}
      <div className="flex flex-col gap-2 px-3 py-3">
        {/* Role / Intent */}
        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium tracking-wider text-zinc-500 uppercase">
            Role / Intent
          </span>
          <input
            type="text"
            value={d.role || ""}
            onChange={(e) => updateNodeData(id, { role: e.target.value })}
            placeholder="爬取天气数据..."
            className="rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 placeholder-zinc-600 outline-none transition-colors focus:border-cyan-500/50 focus:ring-1 focus:ring-cyan-500/20"
          />
        </label>

        {/* Subscribe Topic */}
        <label className="flex flex-col gap-1">
          <span className="flex items-center gap-1 text-[10px] font-medium tracking-wider text-zinc-500 uppercase">
            <ArrowRightFromLine className="h-3 w-3" /> Sub Topic
          </span>
          <input
            type="text"
            value={d.subscribeTopic || ""}
            onChange={(e) =>
              updateNodeData(id, { subscribeTopic: e.target.value })
            }
            placeholder="(source = empty)"
            className="rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 placeholder-zinc-600 outline-none transition-colors focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20"
          />
        </label>

        {/* Publish Topic */}
        <label className="flex flex-col gap-1">
          <span className="flex items-center gap-1 text-[10px] font-medium tracking-wider text-zinc-500 uppercase">
            <Radio className="h-3 w-3" /> Pub Topic
          </span>
          <input
            type="text"
            value={d.publishTopic || ""}
            onChange={(e) =>
              updateNodeData(id, { publishTopic: e.target.value })
            }
            placeholder="raw_data"
            className="rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 placeholder-zinc-600 outline-none transition-colors focus:border-cyan-500/50 focus:ring-1 focus:ring-cyan-500/20"
          />
        </label>

        {/* 运行状态输出/错误 */}
        {status === "succeeded" && d.output && (
          <div className="rounded border border-emerald-800/30 bg-emerald-900/10 px-2 py-1.5 text-[10px] text-emerald-300">
            {d.output}
          </div>
        )}
        {status === "failed" && d.error && (
          <div className="rounded border border-red-800/30 bg-red-900/10 px-2 py-1.5 text-[10px] text-red-300 break-all">
            {d.error}
          </div>
        )}
      </div>

      {/* ════════════════════════════════════════════════════════════════
          God Hand 控制面板 — 可展开的参数拨盘 + 热补丁
         ════════════════════════════════════════════════════════════════ */}
      {panelOpen && (
        <div className="border-t border-amber-500/20 bg-amber-500/[0.03] px-3 py-3">
          <div className="mb-2 flex items-center gap-1.5">
            <Sliders className="h-3.5 w-3.5 text-amber-400" />
            <span className="text-[10px] font-bold tracking-wider text-amber-400 uppercase">
              God Hand — Live Control
            </span>
          </div>

          {/* Threshold 滑动条 */}
          <label className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-medium text-amber-300/70 uppercase">
                Threshold
              </span>
              <span className="rounded bg-amber-900/30 px-1.5 py-0.5 font-mono text-[10px] text-amber-300">
                {(threshold * 100).toFixed(1)}%
              </span>
            </div>
            <input
              type="range"
              min={0.001}
              max={0.2}
              step={0.001}
              value={threshold}
              onChange={(e) => handleThresholdChange(parseFloat(e.target.value))}
              className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-zinc-800 accent-amber-500 [&::-webkit-slider-thumb]:h-3.5 [&::-webkit-slider-thumb]:w-3.5 [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-amber-400 [&::-webkit-slider-thumb]:shadow-[0_0_8px_rgba(245,158,11,0.4)]"
            />
            <div className="flex justify-between text-[9px] text-zinc-600">
              <span>0.1%</span>
              <span>20%</span>
            </div>
          </label>

          {/* Keywords 输入框 */}
          <label className="mt-2 flex flex-col gap-1">
            <span className="text-[10px] font-medium text-amber-300/70 uppercase">
              Keywords
            </span>
            <input
              type="text"
              value={keywords}
              onChange={(e) => handleKeywordsChange(e.target.value)}
              placeholder="AI, 科技, 金融..."
              className="rounded border border-amber-800/30 bg-amber-900/10 px-2 py-1.5 text-xs text-amber-200 placeholder-amber-700/40 outline-none transition-colors focus:border-amber-500/50 focus:ring-1 focus:ring-amber-500/20"
            />
          </label>
        </div>
      )}

      {/* 右侧输出 Handle */}
      <Handle
        type="source"
        position={Position.Right}
        className="!h-3 !w-3 !rounded-full !border-2 !border-violet-400 !bg-[#0d1117] transition-colors hover:!bg-violet-400"
      />
    </div>
  );
});

AgentNode.displayName = "AgentNode";

export default AgentNode;
