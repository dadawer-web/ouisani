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
  type MemoryLayer,
  type MemoryFilter,
} from "@/store/memoryStore";
import { AIOS_API_URL } from "../config";

const AIOS_TOKEN = "AIOS-SUPER-SECRET-KEY";

interface RetrievalTraceView {
  query?: string;
  seeds?: Array<{
    node_id?: string;
    node_type?: string;
    channels?: string[];
    combined_score?: number;
    reason?: string;
  }>;
  expanded_edges?: Array<{
    source_id?: string;
    target_id?: string;
    type?: string;
    confidence?: number;
  }>;
  ranking?: Array<{ node_id?: string; rank?: number; score?: number; reasons?: string[] }>;
  evidence_bundle?: Array<{
    evidence_id?: string;
    role?: string;
    summary?: string;
    source_ref?: string;
    score?: number;
  }>;
  conflicts?: Array<{
    claim_node_id?: string;
    supporting_evidence_ids?: string[];
    contradicting_evidence_ids?: string[];
    reason?: string;
  }>;
  answer_support?: Array<{ evidence_id?: string; covered?: boolean; reason?: string }>;
  sufficient?: boolean;
  insufficiency_reason?: string;
  should_observe?: boolean;
}

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
 * 视觉语言对齐 cc-haha「Technical Atelier」：暖纸卡片 + 古铜/苔绿 domain 胶囊。
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
    updateLayer,
    deleteMemory,
  } = useMemoryStore();

  const [inputId, setInputId] = useState(agentId);
  const [confirmDeleteKey, setConfirmDeleteKey] = useState<string | null>(null);
  const [traceQuery, setTraceQuery] = useState("");
  const [trace, setTrace] = useState<RetrievalTraceView | null>(null);
  const [traceLoading, setTraceLoading] = useState(false);
  const [traceError, setTraceError] = useState<string | null>(null);

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

  const loadTrace = async () => {
    const scope = agentId.trim();
    const query = traceQuery.trim();
    if (!scope || !query) return;
    setTraceLoading(true);
    setTraceError(null);
    try {
      const params = new URLSearchParams({ query, onInsufficient: "OBSERVE" });
      params.set("token", AIOS_TOKEN);
      const response = await fetch(
        `${AIOS_API_URL}/api/memory/graph/${encodeURIComponent(scope)}/retrieve?${params.toString()}`
      );
      if (!response.ok) {
        let message = `HTTP ${response.status}`;
        try {
          const body = await response.json();
          message = body.error || message;
        } catch {
          // Keep the HTTP fallback.
        }
        setTraceError(message);
        setTrace(null);
        return;
      }
      setTrace((await response.json()) as RetrievalTraceView);
    } catch (error) {
      setTraceError(error instanceof Error ? error.message : "network error");
      setTrace(null);
    } finally {
      setTraceLoading(false);
    }
  };

  // 过滤后的记忆列表
  const filteredMemories = useMemo(() => {
    if (filter === "ALL") return memories;
    return memories.filter((m) => m.domain === filter);
  }, [memories, filter]);

  const userCount = memories.filter((m) => m.domain === "USER").length;
  const agentCount = memories.filter((m) => m.domain === "AGENT").length;

  return (
    <div className="flex h-full flex-col gap-2 p-3">
      {/* ═══ 顶栏：agentId 输入 + Load + Refresh + lastUpdated ═══ */}
      <div className="flex flex-shrink-0 items-center gap-2">
        <div className="flex flex-1 items-center gap-2 rounded-lg bg-surface-container-low px-3 py-1.5 ghost-border">
          <Brain className="h-3.5 w-3.5 text-primary" />
          <input
            type="text"
            value={inputId}
            onChange={(e) => setInputId(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="agentId (e.g. agent_1)"
            className="flex-1 bg-transparent text-xs text-on-surface placeholder:text-outline/50 focus:outline-none"
          />
        </div>
        <button
          onClick={handleLoad}
          disabled={loading || !inputId.trim()}
          className="btn-primary-ink flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[10px] font-bold uppercase tracking-wider text-on-primary transition-opacity hover:opacity-90 disabled:opacity-40"
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
          className="flex items-center gap-1.5 rounded-lg bg-surface-container-high px-2 py-1.5 text-[10px] font-bold uppercase tracking-wider text-outline transition-colors hover:bg-surface-container-highest hover:text-primary disabled:opacity-40"
        >
          <RefreshCw className={`h-3 w-3 ${loading ? "animate-spin" : ""}`} />
        </button>
        {lastUpdated && (
          <span className="font-mono text-[9px] text-outline">
            {formatTs(lastUpdated)}
          </span>
        )}
      </div>

      {/* ═══ Filter 切换 + 计数 ═══ */}
      <div className="flex flex-shrink-0 items-center gap-2">
        <div className="flex items-center gap-0.5 rounded-lg bg-surface-container-low p-0.5 ghost-border">
          {(["ALL", "USER", "AGENT"] as MemoryFilter[]).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-2.5 py-0.5 text-[9px] font-bold uppercase tracking-wider transition-colors ${
                filter === f
                  ? f === "USER"
                    ? "bg-primary-fixed/60 text-primary"
                    : f === "AGENT"
                      ? "bg-tertiary-container/50 text-on-tertiary-container"
                      : "bg-surface-container-highest text-on-surface"
                  : "text-outline hover:text-on-surface"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
        <span className="font-mono text-[9px] text-outline">
          {filteredMemories.length}/{memories.length} shown
          {memories.length > 0 && (
            <span className="ml-1 text-outline/60">
              (U:{userCount} A:{agentCount})
            </span>
          )}
        </span>
      </div>

      <div className="flex flex-shrink-0 flex-col gap-2 rounded-lg bg-surface-container-low p-2 ghost-border">
        <div className="flex items-center gap-2">
          <Search className="h-3.5 w-3.5 text-primary" />
          <input
            value={traceQuery}
            onChange={(event) => setTraceQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") void loadTrace();
            }}
            placeholder="Trace retrieval: why was this memory recalled?"
            className="min-w-0 flex-1 bg-transparent text-[10px] text-on-surface placeholder:text-outline/50 focus:outline-none"
          />
          <button
            onClick={() => void loadTrace()}
            disabled={traceLoading || !agentId.trim() || !traceQuery.trim()}
            className="btn-primary-ink rounded-md px-2 py-1 text-[9px] font-bold uppercase tracking-wider text-on-primary disabled:opacity-40"
          >
            {traceLoading ? "Tracing..." : "Trace"}
          </button>
        </div>
        {traceError && <div className="text-[10px] text-error">{traceError}</div>}
        {trace && <RetrievalTracePanel trace={trace} />}
      </div>

      {/* ═══ 内容区 ═══ */}
      <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto">
        {/* 503 特殊提示 —— primary-fixed 配置提示卡 */}
        {error === ERR_PRIMARY_STORE_NOT_CONFIGURED && (
          <div className="flex flex-col items-center gap-3 rounded-xl bg-primary-fixed/30 p-6 text-center ghost-border">
            <ServerCrash className="h-8 w-8 text-primary" />
            <div className="font-headline text-xs font-bold text-primary">
              Primary Memory Store Not Configured
            </div>
            <div className="text-[10px] leading-relaxed text-on-surface-variant">
              后端 <code className="font-mono text-primary">VersionedMemoryStore</code> 未注入。
              <br />
              请确认启动时已调用
              <br />
              <code className="mt-1 inline-block rounded bg-surface-container-lowest px-1.5 py-0.5 font-mono text-on-surface">
                AiosAppManager.configure(scheduler)
              </code>
            </div>
          </div>
        )}

        {/* 通用错误 */}
        {error && error !== ERR_PRIMARY_STORE_NOT_CONFIGURED && (
          <div className="flex items-start gap-2 rounded-lg bg-error-container/40 p-3 ghost-border">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-error" />
            <div className="text-[11px] text-on-error-container">{error}</div>
          </div>
        )}

        {/* 加载中 */}
        {loading && memories.length === 0 && (
          <div className="flex items-center justify-center gap-2 py-12 text-outline">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span className="text-[11px]">Loading memories...</span>
          </div>
        )}

        {/* 空状态 */}
        {!loading && !error && memories.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-12 text-outline">
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
            onLayerChange={(layer) => updateLayer(m.key, layer)}
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

function RetrievalTracePanel({ trace }: { trace: RetrievalTraceView }) {
  const seeds = trace.seeds || [];
  const evidence = trace.evidence_bundle || [];
  const conflicts = trace.conflicts || [];
  const edges = trace.expanded_edges || [];
  return (
    <div className="rounded-md bg-surface-container-lowest p-2 text-[9px] ghost-border">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`pill ${trace.sufficient ? "bg-primary-fixed/60 text-primary" : "bg-error-container/50 text-error"}`}>
          {trace.sufficient ? "GROUNDED" : trace.should_observe ? "OBSERVE" : "INSUFFICIENT"}
        </span>
        <span className="font-mono text-outline">{seeds.length} seeds</span>
        <span className="font-mono text-outline">{edges.length} typed edges</span>
        <span className="font-mono text-outline">{evidence.length} evidence</span>
        {conflicts.length > 0 && <span className="font-mono text-error">{conflicts.length} conflict(s)</span>}
      </div>
      {!trace.sufficient && trace.insufficiency_reason && (
        <div className="mt-1 text-error">{trace.insufficiency_reason}</div>
      )}
      {seeds.length > 0 && (
        <div className="mt-2 space-y-1">
          <div className="font-bold uppercase tracking-wider text-outline">Why recalled</div>
          {seeds.slice(0, 4).map((seed) => (
            <div key={seed.node_id} className="rounded bg-surface-container-low px-1.5 py-1">
              <span className="font-mono text-on-surface">{seed.node_id}</span>
              <span className="ml-1 text-outline">{seed.node_type}</span>
              <span className="ml-1 text-primary">{(seed.combined_score ?? 0).toFixed(3)}</span>
              {seed.channels && <span className="ml-1 text-outline">[{seed.channels.join(" + ")}]</span>}
              {seed.reason && <div className="mt-0.5 text-outline/80">{seed.reason}</div>}
            </div>
          ))}
        </div>
      )}
      {evidence.length > 0 && (
        <div className="mt-2 space-y-1">
          <div className="font-bold uppercase tracking-wider text-outline">Answer evidence</div>
          {evidence.slice(0, 6).map((item) => (
            <div key={item.evidence_id} className="flex gap-1 rounded bg-surface-container-low px-1.5 py-1">
              <span className={item.role === "CONFLICT" ? "text-error" : "text-primary"}>{item.role}</span>
              <span className="min-w-0 flex-1 truncate text-on-surface">{item.summary || item.evidence_id}</span>
              {item.source_ref && <span className="max-w-[35%] truncate font-mono text-outline">{item.source_ref}</span>}
            </div>
          ))}
        </div>
      )}
      {conflicts.length > 0 && (
        <div className="mt-2 space-y-1 text-error">
          <div className="font-bold uppercase tracking-wider">Conflicts</div>
          {conflicts.slice(0, 4).map((conflict) => (
            <div key={conflict.claim_node_id} className="rounded bg-error-container/20 px-1.5 py-1">
              {conflict.claim_node_id}: {(conflict.contradicting_evidence_ids || []).join(", ")}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

interface MemoryCardProps {
  record: {
    key: string;
    content: string;
    source: string;
    timestamp: number;
    confidence: number;
    domain: MemoryDomain;
    layer: MemoryLayer;
    version: number;
  };
  pending: boolean;
  confirmDelete: boolean;
  onConfidenceChange: (c: number) => void;
  onDomainToggle: () => void;
  onLayerChange: (layer: MemoryLayer) => void;
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
  onLayerChange,
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
      className={`mb-2 rounded-lg bg-surface-container-lowest p-2.5 ghost-border transition-all hover:ring-1 hover:ring-primary/30 ${
        pending ? "opacity-70" : ""
      }`}
    >
      {/* ── 头部：key + source + timestamp + version ── */}
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span
              className="truncate font-mono text-[11px] font-bold text-on-surface"
              title={record.key}
            >
              {record.key}
            </span>
            {pending && (
              <Loader2 className="h-3 w-3 flex-shrink-0 animate-spin text-outline" />
            )}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-[9px] text-outline">
            {record.source && (
              <span className="rounded bg-surface-container-high px-1.5 py-0.5 font-mono">
                src: {record.source}
              </span>
            )}
            <span className="font-mono">{formatTs(record.timestamp)}</span>
            <span className="font-mono text-outline/70">v{record.version}</span>
            <span className="rounded bg-primary-fixed/30 px-1.5 py-0.5 font-mono text-primary">
              <select
                value={record.layer}
                disabled={pending}
                onChange={(event) => onLayerChange(event.target.value as MemoryLayer)}
                className="bg-transparent font-mono text-[9px] text-primary outline-none"
                title="调整记忆生命周期层"
              >
                {(["L0", "L1", "L2", "L3"] as MemoryLayer[]).map((layer) => (
                  <option key={layer} value={layer}>{layer}</option>
                ))}
              </select>
            </span>
          </div>
        </div>

        {/* Domain 胶囊 —— USER 用 primary-fixed，AGENT 用 tertiary-container */}
        <button
          onClick={onDomainToggle}
          disabled={pending}
          title={`切换 domain (当前 ${record.domain})`}
          className={`pill flex-shrink-0 transition-opacity disabled:opacity-50 ${
            isUser
              ? "bg-primary-fixed/60 text-primary"
              : "bg-tertiary-container/50 text-on-tertiary-container"
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
            className="flex-shrink-0 rounded p-1 text-outline transition-colors hover:bg-error-container/40 hover:text-error disabled:opacity-30"
          >
            <Trash2 className="h-3 w-3" />
          </button>
        ) : (
          <div className="flex flex-shrink-0 items-center gap-1">
            <button
              onClick={onDeleteConfirm}
              className="rounded bg-error-container/60 px-1.5 py-0.5 text-[9px] font-bold uppercase text-error hover:bg-error-container/80"
            >
              Del
            </button>
            <button
              onClick={onDeleteCancel}
              className="rounded bg-surface-container-high px-1.5 py-0.5 text-[9px] font-bold uppercase text-outline hover:bg-surface-container-highest"
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
            className={`whitespace-pre-wrap break-all rounded bg-surface-container-low p-1.5 font-mono text-[10px] leading-relaxed text-on-surface-variant ${
              expanded ? "custom-scrollbar max-h-48 overflow-y-auto" : ""
            }`}
          >
            {truncated}
          </pre>
          {record.content.length > 200 && (
            <button
              onClick={() => setExpanded((v) => !v)}
              className="mt-0.5 flex items-center gap-0.5 text-[9px] text-outline hover:text-primary"
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

      {/* ── Confidence slider —— 古铜主题色，CSS 变量自适应明暗 ── */}
      <div className="mt-2 flex items-center gap-2">
        <span className="text-[9px] font-bold uppercase tracking-wider text-outline">
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
          className="h-1 flex-1 cursor-pointer appearance-none rounded-full accent-primary"
          style={{
            background: `linear-gradient(to right, rgb(var(--primary) / 0.7) 0%, rgb(var(--primary) / 0.7) ${draftConfidence * 100}%, rgb(var(--outline-variant) / 0.4) ${draftConfidence * 100}%, rgb(var(--outline-variant) / 0.4) 100%)`,
          }}
        />
        <span
          className={`w-8 text-right font-mono text-[10px] font-bold ${
            draftConfidence >= 0.7
              ? "text-tertiary"
              : draftConfidence >= 0.4
                ? "text-primary"
                : "text-error"
          }`}
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
