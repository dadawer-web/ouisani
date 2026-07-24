import { useEffect, useState, useMemo } from "react";
import {
  Brain,
  RefreshCw,
  Trash2,
  Loader2,
  AlertCircle,
  ChevronDown,
  ChevronUp,
  ServerCrash,
  Search,
} from "lucide-react";
import {
  useMemoryStore,
  ERR_PRIMARY_STORE_NOT_CONFIGURED,
  type MemoryDomain,
  type MemoryFilter,
} from "@/store/memoryStore";

/**
 * 记忆查看器面板 — P3「看得见改得了」前端入口。
 *
 * 接入后端 `/api/memory/{agentId}[/{key}]` 三个端点（GET/PATCH/DELETE）。
 * - 列出指定 agent 的全部当前记忆
 * - 行内编辑 confidence（slider，松手 PATCH）
 * - 行内切换 domain（USER/AGENT 按钮，PATCH）
 * - 行内删除（带 confirm，DELETE）
 * - 按 domain 过滤 + timestamp 倒序
 * - 503 兜底：后端未注入 primary store 时显示配置提示
 *
 * OS 类比：相当于 Linux 的 `/proc/<pid>/maps` 浏览器 —— 用户态可查看并修改
 * 内核 VersionedMemoryStore 的运行时状态。
 */
export default function MemoryViewer() {
  const {
    agentId,
    memories,
    filter,
    loading,
    error,
    lastUpdated,
    pendingKey,
    setAgentId,
    setFilter,
    fetchMemories,
    updateConfidence,
    updateDomain,
    deleteMemory,
  } = useMemoryStore();

  const [inputId, setInputId] = useState(agentId);
  const [confirmDeleteKey, setConfirmDeleteKey] = useState<string | null>(null);

  // 挂载时若已有持久化 agentId，自动加载
  useEffect(() => {
    if (agentId.trim()) {
      fetchMemories();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleLoad = () => {
    const trimmed = inputId.trim();
    if (!trimmed) return;
    setAgentId(trimmed);
    fetchMemories();
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") handleLoad();
  };

  // 过滤后的记忆列表
  const filteredMemories = useMemo(() => {
    if (filter === "ALL") return memories;
    return memories.filter((m) => m.domain === filter);
  }, [memories, filter]);

  const userCount = memories.filter((m) => m.domain === "USER").length;
  const agentCount = memories.filter((m) => m.domain === "AGENT").length;

  return (
    <div className="flex h-full flex-col gap-2 p-3 font-mono">
      {/* ═══ 顶栏：agentId 输入 + Load + Refresh + lastUpdated ═══ */}
      <div className="flex flex-shrink-0 items-center gap-2">
        <div className="flex flex-1 items-center gap-2 rounded-md border border-violet-500/30 bg-black/60 px-3 py-1.5">
          <Brain className="h-3.5 w-3.5 text-violet-400" />
          <input
            type="text"
            value={inputId}
            onChange={(e) => setInputId(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="agentId (e.g. agent_1)"
            className="flex-1 bg-transparent text-xs text-zinc-100 placeholder-zinc-600 outline-none"
          />
        </div>
        <button
          onClick={handleLoad}
          disabled={loading || !inputId.trim()}
          className="flex items-center gap-1.5 rounded-md border border-violet-500/40 bg-violet-900/30 px-3 py-1.5 text-[10px] font-bold uppercase tracking-wider text-violet-300 transition-all hover:border-violet-400/70 hover:bg-violet-900/50 disabled:opacity-40"
        >
          {loading ? (
            <Loader2 className="h-3 w-3 animate-spin" />
          ) : (
            <Search className="h-3 w-3" />
          )}
          Load
        </button>
        <button
          onClick={() => fetchMemories()}
          disabled={loading || !agentId.trim()}
          title="刷新当前 agentId 的记忆列表"
          className="flex items-center gap-1.5 rounded-md border border-zinc-700/50 bg-zinc-900/40 px-2 py-1.5 text-[10px] font-bold uppercase tracking-wider text-zinc-400 transition-all hover:border-zinc-600 hover:text-zinc-200 disabled:opacity-40"
        >
          <RefreshCw className={`h-3 w-3 ${loading ? "animate-spin" : ""}`} />
        </button>
        {lastUpdated && (
          <span className="text-[9px] text-zinc-600">
            {formatTs(lastUpdated)}
          </span>
        )}
      </div>

      {/* ═══ Filter 切换 + 计数 ═══ */}
      <div className="flex flex-shrink-0 items-center gap-2">
        <div className="flex items-center gap-0.5 rounded-md border border-zinc-800/50 bg-black/40 p-0.5">
          {(["ALL", "USER", "AGENT"] as MemoryFilter[]).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`rounded px-2.5 py-0.5 text-[9px] font-bold uppercase tracking-wider transition-all ${
                filter === f
                  ? f === "USER"
                    ? "bg-violet-900/60 text-violet-300"
                    : f === "AGENT"
                      ? "bg-cyan-900/60 text-cyan-300"
                      : "bg-zinc-700/60 text-zinc-200"
                  : "text-zinc-500 hover:text-zinc-300"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
        <span className="text-[9px] text-zinc-600">
          {filteredMemories.length}/{memories.length} shown
          {memories.length > 0 && (
            <span className="ml-1 text-zinc-700">
              (U:{userCount} A:{agentCount})
            </span>
          )}
        </span>
      </div>

      {/* ═══ 内容区 ═══ */}
      <div className="flex-1 overflow-y-auto min-h-0 custom-scrollbar">
        {/* 503 特殊提示 */}
        {error === ERR_PRIMARY_STORE_NOT_CONFIGURED && (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-amber-500/40 bg-amber-950/20 p-6 text-center">
            <ServerCrash className="h-8 w-8 text-amber-400" />
            <div className="text-xs font-bold text-amber-300">
              Primary Memory Store Not Configured
            </div>
            <div className="text-[10px] leading-relaxed text-amber-200/70">
              后端 <code className="text-amber-300">VersionedMemoryStore</code> 未注入。
              <br />
              请确认启动时已调用
              <br />
              <code className="rounded bg-amber-900/40 px-1.5 py-0.5 text-amber-200">
                AiosAppManager.configure(scheduler)
              </code>
            </div>
          </div>
        )}

        {/* 通用错误 */}
        {error && error !== ERR_PRIMARY_STORE_NOT_CONFIGURED && (
          <div className="flex items-start gap-2 rounded-lg border border-red-500/40 bg-red-950/20 p-3">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-400" />
            <div className="text-[11px] text-red-300/90">{error}</div>
          </div>
        )}

        {/* 加载中 */}
        {loading && memories.length === 0 && (
          <div className="flex items-center justify-center gap-2 py-12 text-zinc-500">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span className="text-[11px]">Loading memories...</span>
          </div>
        )}

        {/* 空状态 */}
        {!loading && !error && memories.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-12 text-zinc-600">
            <Brain className="h-8 w-8 opacity-30" />
            <span className="text-[11px]">
              {agentId.trim()
                ? `No memories for "${agentId}"`
                : "Enter an agentId to load memories"}
            </span>
          </div>
        )}

        {/* 记忆卡片列表 */}
        {filteredMemories.map((m) => (
          <MemoryCard
            key={m.key}
            record={m}
            pending={pendingKey === m.key}
            confirmDelete={confirmDeleteKey === m.key}
            onConfidenceChange={(c) => updateConfidence(m.key, c)}
            onDomainToggle={() =>
              updateDomain(m.key, m.domain === "USER" ? "AGENT" : "USER")
            }
            onDeleteClick={() => setConfirmDeleteKey(m.key)}
            onDeleteConfirm={() => {
              deleteMemory(m.key);
              setConfirmDeleteKey(null);
            }}
            onDeleteCancel={() => setConfirmDeleteKey(null)}
          />
        ))}
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════════
//  单条记忆卡片
// ════════════════════════════════════════════════════════════════

interface MemoryCardProps {
  record: {
    key: string;
    content: string;
    source: string;
    timestamp: number;
    confidence: number;
    domain: MemoryDomain;
    version: number;
  };
  pending: boolean;
  confirmDelete: boolean;
  onConfidenceChange: (c: number) => void;
  onDomainToggle: () => void;
  onDeleteClick: () => void;
  onDeleteConfirm: () => void;
  onDeleteCancel: () => void;
}

function MemoryCard({
  record,
  pending,
  confirmDelete,
  onConfidenceChange,
  onDomainToggle,
  onDeleteClick,
  onDeleteConfirm,
  onDeleteCancel,
}: MemoryCardProps) {
  const [expanded, setExpanded] = useState(false);
  const [draftConfidence, setDraftConfidence] = useState(record.confidence);

  // 后端返回新值时同步本地 draft
  useEffect(() => {
    setDraftConfidence(record.confidence);
  }, [record.confidence]);

  const isUser = record.domain === "USER";
  const truncated = record.content.length > 200 && !expanded
    ? record.content.slice(0, 200) + "..."
    : record.content;

  return (
    <div
      className={`mb-2 rounded-md border bg-black/60 p-2.5 transition-all ${
        isUser
          ? "border-violet-500/30 hover:border-violet-500/50"
          : "border-cyan-500/30 hover:border-cyan-500/50"
      } ${pending ? "opacity-70" : ""}`}
    >
      {/* ── 头部：key + source + timestamp + version ── */}
      <div className="flex items-start gap-2">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span
              className={`truncate font-mono text-[11px] font-bold ${
                isUser ? "text-violet-300" : "text-cyan-300"
              }`}
              title={record.key}
            >
              {record.key}
            </span>
            {pending && (
              <Loader2 className="h-3 w-3 flex-shrink-0 animate-spin text-zinc-500" />
            )}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-[9px] text-zinc-600">
            {record.source && (
              <span className="rounded bg-zinc-800/60 px-1.5 py-0.5">
                src: {record.source}
              </span>
            )}
            <span>{formatTs(record.timestamp)}</span>
            <span className="text-zinc-700">v{record.version}</span>
          </div>
        </div>

        {/* Domain 切换 */}
        <button
          onClick={onDomainToggle}
          disabled={pending}
          title={`切换 domain (当前 ${record.domain})`}
          className={`flex-shrink-0 rounded px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider transition-all disabled:opacity-50 ${
            isUser
              ? "bg-violet-900/60 text-violet-300 hover:bg-violet-800/70"
              : "bg-cyan-900/60 text-cyan-300 hover:bg-cyan-800/70"
          }`}
        >
          {record.domain}
        </button>

        {/* 删除按钮 / 确认框 */}
        {!confirmDelete ? (
          <button
            onClick={onDeleteClick}
            disabled={pending}
            title="删除此记忆"
            className="flex-shrink-0 rounded p-1 text-zinc-600 transition-all hover:bg-red-900/40 hover:text-red-400 disabled:opacity-30"
          >
            <Trash2 className="h-3 w-3" />
          </button>
        ) : (
          <div className="flex flex-shrink-0 items-center gap-1">
            <button
              onClick={onDeleteConfirm}
              className="rounded bg-red-900/60 px-1.5 py-0.5 text-[9px] font-bold uppercase text-red-300 hover:bg-red-800/80"
            >
              Del
            </button>
            <button
              onClick={onDeleteCancel}
              className="rounded bg-zinc-800/60 px-1.5 py-0.5 text-[9px] font-bold uppercase text-zinc-400 hover:bg-zinc-700/60"
            >
              No
            </button>
          </div>
        )}
      </div>

      {/* ── Content ── */}
      {record.content && (
        <div className="mt-1.5">
          <pre
            className={`whitespace-pre-wrap break-all rounded bg-zinc-900/40 p-1.5 text-[10px] leading-relaxed text-zinc-400 ${
              expanded ? "max-h-48 overflow-y-auto custom-scrollbar" : ""
            }`}
          >
            {truncated}
          </pre>
          {record.content.length > 200 && (
            <button
              onClick={() => setExpanded((v) => !v)}
              className="mt-0.5 flex items-center gap-0.5 text-[9px] text-zinc-600 hover:text-zinc-400"
            >
              {expanded ? (
                <>
                  <ChevronUp className="h-2.5 w-2.5" /> Collapse
                </>
              ) : (
                <>
                  <ChevronDown className="h-2.5 w-2.5" /> Expand ({record.content.length} chars)
                </>
              )}
            </button>
          )}
        </div>
      )}

      {/* ── Confidence slider ── */}
      <div className="mt-2 flex items-center gap-2">
        <span className="text-[9px] uppercase tracking-wider text-zinc-600">
          conf
        </span>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={draftConfidence}
          onChange={(e) => setDraftConfidence(parseFloat(e.target.value))}
          onMouseUp={(e) => {
            const val = parseFloat((e.target as HTMLInputElement).value);
            if (val !== record.confidence) onConfidenceChange(val);
          }}
          onTouchEnd={(e) => {
            const val = parseFloat((e.target as HTMLInputElement).value);
            if (val !== record.confidence) onConfidenceChange(val);
          }}
          className="flex-1 accent-cyan-400"
          style={{
            background: `linear-gradient(to right, rgba(34,211,238,0.4) 0%, rgba(34,211,238,0.4) ${draftConfidence * 100}%, rgba(39,39,42,0.6) ${draftConfidence * 100}%, rgba(39,39,42,0.6) 100%)`,
            height: "3px",
            borderRadius: "2px",
          }}
        />
        <span
          className={`w-8 text-right text-[10px] font-bold ${
            draftConfidence >= 0.7
              ? "text-emerald-400"
              : draftConfidence >= 0.4
                ? "text-amber-400"
                : "text-red-400"
          }`}
          style={{ textShadow: "0 0 6px currentColor" }}
        >
          {draftConfidence.toFixed(2)}
        </span>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════════
//  工具函数
// ════════════════════════════════════════════════════════════════

/** 格式化时间戳 — HH:mm:ss.SSS */
function formatTs(ts: number): string {
  const d = new Date(ts);
  return (
    d.toLocaleTimeString("en-US", { hour12: false }) +
    "." +
    String(d.getMilliseconds()).padStart(3, "0")
  );
}
