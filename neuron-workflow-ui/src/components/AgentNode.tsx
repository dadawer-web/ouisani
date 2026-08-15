import { memo, useState, useCallback, useEffect, useRef, type FC } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Bot, Trash2, Radio, ArrowRightFromLine, Sliders, ChevronUp, CheckCircle2, AlertCircle, Loader2 } from "lucide-react";
import { useWorkflowStore, type AgentNodeData } from "@/store/workflowStore";
import { cn } from "@/lib/utils";

/** 状态对应的样式映射 —— 对齐 cc-haha 语义色（古铜=运行 / 绿=成功 / 红=失败 / 暖灰=空闲） */
const STATUS_STYLES: Record<string, { ring: string; icon: FC<{ className?: string }> }> = {
  running: {
    ring: "ring-primary/50",
    icon: Loader2,
  },
  succeeded: {
    ring: "ring-tertiary/50",
    icon: CheckCircle2,
  },
  failed: {
    ring: "ring-error/50",
    icon: AlertCircle,
  },
  idle: {
    ring: "ring-outline-variant/50",
    icon: Bot,
  },
};

/** 自定义智能体节点 —— 暖纸卡片 + God Hand 控制面板 */
const AgentNode: FC<NodeProps> = memo(({ id, data }) => {
  const d = data as AgentNodeData;
  const updateNodeData = useWorkflowStore((s) => s.updateNodeData);
  const removeNode = useWorkflowStore((s) => s.removeNode);
  const hotPatchParam = useWorkflowStore((s) => s.hotPatchParam);

  const [panelOpen, setPanelOpen] = useState(false);
  const [threshold, setThreshold] = useState(
    parseFloat(String(d.userParams?.threshold ?? "0.05")),
  );
  const [keywords, setKeywords] = useState(
    String(d.userParams?.keywords ?? ""),
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
    [id, hotPatchParam],
  );

  const handleThresholdChange = useCallback(
    (value: number) => {
      setThreshold(value);
      debouncedHotPatch({ threshold: value });
    },
    [debouncedHotPatch],
  );

  const handleKeywordsChange = useCallback(
    (value: string) => {
      setKeywords(value);
      updateNodeData(id, { userParams: { ...d.userParams, keywords: value } });
    },
    [id, d.userParams, updateNodeData],
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
    <div className={cn(
      "group relative min-w-[220px] rounded-lg bg-surface-container-lowest p-0 ring-1 transition-all duration-300 hover:ring-primary/40",
      style.ring,
    )}>
      {/* 左侧输入 Handle */}
      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 !rounded-full !border-2 !border-primary !bg-surface-container-lowest"
      />

      {/* 顶部标题栏 */}
      <div className="flex items-center gap-2 rounded-t-lg bg-surface-container-low px-3 py-2">
        <StatusIcon className={cn("h-4 w-4 text-primary", status === "running" && "animate-spin")} />
        <span className="flex-1 truncate font-headline text-xs font-semibold uppercase tracking-wider text-on-surface">
          {d.label || "Agent"}
        </span>
        {status !== "idle" && (
          <span className={cn(
            "pill",
            status === "running" && "bg-primary-fixed/40 text-primary",
            status === "succeeded" && "bg-tertiary-container/30 text-tertiary",
            status === "failed" && "bg-error-container/40 text-error",
          )}>
            {status}
          </span>
        )}
        <button
          onClick={() => setPanelOpen(!panelOpen)}
          className="rounded p-0.5 text-outline opacity-0 transition-all hover:bg-surface-container-high hover:text-primary group-hover:opacity-100"
          title="Control Panel"
        >
          {panelOpen ? <ChevronUp className="h-3.5 w-3.5" /> : <Sliders className="h-3.5 w-3.5" />}
        </button>
        <button
          onClick={() => removeNode(id)}
          className="rounded p-0.5 text-outline opacity-0 transition-all hover:bg-error-container/30 hover:text-error group-hover:opacity-100"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* 内容区 */}
      <div className="flex flex-col gap-2 px-3 py-3">
        {/* Role / Intent */}
        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wider text-outline">
            Role / Intent
          </span>
          <input
            type="text"
            value={d.role || ""}
            onChange={(e) => updateNodeData(id, { role: e.target.value })}
            placeholder="爬取天气数据…"
            className="rounded-md bg-surface-container-low px-2 py-1.5 text-xs text-on-surface placeholder:text-outline/50 focus:outline-none ghost-border"
          />
        </label>

        {/* Subscribe Topic */}
        <label className="flex flex-col gap-1">
          <span className="flex items-center gap-1 text-[10px] font-medium uppercase tracking-wider text-outline">
            <ArrowRightFromLine className="h-3 w-3" /> Sub Topic
          </span>
          <input
            type="text"
            value={d.subscribeTopic || ""}
            onChange={(e) => updateNodeData(id, { subscribeTopic: e.target.value })}
            placeholder="(source = empty)"
            className="rounded-md bg-surface-container-low px-2 py-1.5 text-xs text-on-surface placeholder:text-outline/50 focus:outline-none ghost-border"
          />
        </label>

        {/* Publish Topic */}
        <label className="flex flex-col gap-1">
          <span className="flex items-center gap-1 text-[10px] font-medium uppercase tracking-wider text-outline">
            <Radio className="h-3 w-3" /> Pub Topic
          </span>
          <input
            type="text"
            value={d.publishTopic || ""}
            onChange={(e) => updateNodeData(id, { publishTopic: e.target.value })}
            placeholder="raw_data"
            className="rounded-md bg-surface-container-low px-2 py-1.5 text-xs text-on-surface placeholder:text-outline/50 focus:outline-none ghost-border"
          />
        </label>

        {/* 运行状态输出/错误 */}
        {status === "succeeded" && d.output && (
          <div className="rounded-md bg-tertiary-container/15 px-2 py-1.5 text-[10px] text-tertiary">
            {d.output}
          </div>
        )}
        {status === "failed" && d.error && (
          <div className="break-all rounded-md bg-error-container/20 px-2 py-1.5 text-[10px] text-error">
            {d.error}
          </div>
        )}
      </div>

      {/* ═══ God Hand 控制面板 — 可展开的参数拨盘 + 热补丁 ═══ */}
      {panelOpen && (
        <div className="rounded-b-lg bg-surface-dim px-3 py-3">
          <div className="mb-2 flex items-center gap-1.5">
            <Sliders className="h-3.5 w-3.5 text-primary" />
            <span className="text-[10px] font-bold uppercase tracking-wider text-primary">
              God Hand — Live Control
            </span>
          </div>

          {/* Threshold 滑动条 */}
          <label className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-medium uppercase text-on-surface-variant">
                Threshold
              </span>
              <span className="rounded bg-surface-container-high px-1.5 py-0.5 font-mono text-[10px] text-on-surface">
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
              className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-surface-container-high accent-primary"
            />
            <div className="flex justify-between text-[9px] text-outline">
              <span>0.1%</span>
              <span>20%</span>
            </div>
          </label>

          {/* Keywords 输入框 */}
          <label className="mt-2 flex flex-col gap-1">
            <span className="text-[10px] font-medium uppercase text-on-surface-variant">
              Keywords
            </span>
            <input
              type="text"
              value={keywords}
              onChange={(e) => handleKeywordsChange(e.target.value)}
              placeholder="AI, 科技, 金融…"
              className="rounded-md bg-surface-container-lowest px-2 py-1.5 text-xs text-on-surface placeholder:text-outline/50 focus:outline-none ghost-border"
            />
          </label>
        </div>
      )}

      {/* 右侧输出 Handle */}
      <Handle
        type="source"
        position={Position.Right}
        className="!h-3 !w-3 !rounded-full !border-2 !border-secondary !bg-surface-container-lowest"
      />
    </div>
  );
});

AgentNode.displayName = "AgentNode";

export default AgentNode;
