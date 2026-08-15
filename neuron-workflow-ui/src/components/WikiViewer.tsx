import { useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  BookOpen,
  Check,
  CircleHelp,
  FileText,
  Loader2,
  RefreshCw,
  Search,
  ShieldCheck,
} from "lucide-react";
import {
  useWikiStore,
  type WikiCategory,
  type WikiEntry,
} from "@/store/wikiStore";

const CATEGORIES: Array<{ id: WikiCategory; label: string }> = [
  { id: "ALL", label: "全部" },
  { id: "PROJECTS", label: "Projects" },
  { id: "TOPICS", label: "Topics" },
  { id: "DECISIONS", label: "Decisions" },
  { id: "SOURCES", label: "Sources" },
  { id: "ARTIFACTS", label: "Artifacts" },
];

export default function WikiViewer() {
  const {
    agentId,
    tenantId,
    workflowId,
    teamId,
    entries,
    category,
    search,
    onlyConfirmed,
    loading,
    error,
    lastUpdated,
    pendingId,
    setAgentId,
    setTenantId,
    setWorkflowId,
    setTeamId,
    setCategory,
    setSearch,
    setOnlyConfirmed,
    fetchWiki,
    confirmEntry,
  } = useWikiStore();
  const [inputId, setInputId] = useState(agentId);

  useEffect(() => {
    if (agentId.trim()) fetchWiki();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filteredEntries = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return entries.filter((entry) => {
      if (category !== "ALL" && entry.category !== category) return false;
      if (onlyConfirmed && !entry.userConfirmed) return false;
      if (!needle) return true;
      return [entry.title, entry.content, entry.source, entry.namespace]
        .filter(Boolean)
        .some((value) => value.toLowerCase().includes(needle));
    });
  }, [entries, category, onlyConfirmed, search]);

  const handleLoad = () => {
    const id = inputId.trim();
    if (!id) return;
    setAgentId(id);
    fetchWiki();
  };

  return (
    <div className="flex h-full flex-col gap-2 p-3">
      <div className="flex flex-shrink-0 items-center gap-2">
        <div className="flex flex-1 items-center gap-2 rounded-lg bg-surface-container-low px-3 py-1.5 ghost-border">
          <BookOpen className="h-3.5 w-3.5 text-primary" />
          <input
            value={inputId}
            onChange={(event) => setInputId(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && handleLoad()}
            placeholder="agentId (scope-aware Wiki)"
            className="flex-1 bg-transparent text-xs text-on-surface placeholder:text-outline/50 focus:outline-none"
          />
        </div>
        <button
          onClick={handleLoad}
          disabled={loading || !inputId.trim()}
          className="btn-primary-ink flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[10px] font-bold uppercase tracking-wider text-on-primary disabled:opacity-40"
        >
          <Search className="h-3 w-3" /> Load
        </button>
        <button
          onClick={() => fetchWiki()}
          disabled={loading || !agentId.trim()}
          title="刷新 Wiki 投影"
          className="flex items-center gap-1.5 rounded-lg bg-surface-container-high px-2 py-1.5 text-[10px] font-bold uppercase tracking-wider text-outline hover:text-primary disabled:opacity-40"
        >
          <RefreshCw className={`h-3 w-3 ${loading ? "animate-spin" : ""}`} />
        </button>
        {lastUpdated && <span className="font-mono text-[9px] text-outline">{formatTs(lastUpdated)}</span>}
      </div>

      <div className="flex flex-shrink-0 flex-wrap items-center gap-2 text-[10px]">
        <span className="text-outline">可见范围上下文</span>
        <input value={tenantId} onChange={(event) => setTenantId(event.target.value)} placeholder="tenantId" className="w-28 rounded-md bg-surface-container-low px-2 py-1 text-on-surface placeholder:text-outline/45 ghost-border focus:outline-none" />
        <input value={workflowId} onChange={(event) => setWorkflowId(event.target.value)} placeholder="workflowId（TASK）" className="w-36 rounded-md bg-surface-container-low px-2 py-1 text-on-surface placeholder:text-outline/45 ghost-border focus:outline-none" />
        <input value={teamId} onChange={(event) => setTeamId(event.target.value)} placeholder="teamId（TEAM）" className="w-32 rounded-md bg-surface-container-low px-2 py-1 text-on-surface placeholder:text-outline/45 ghost-border focus:outline-none" />
        <span className="text-outline/60">不填则只显示当前身份可见的 PRIVATE/legacy 内容</span>
        <button onClick={() => fetchWiki()} disabled={loading || !agentId.trim()} className="rounded-md bg-surface-container-high px-2 py-1 font-bold text-outline hover:text-primary disabled:opacity-40">应用范围</button>
      </div>

      <div className="flex flex-shrink-0 flex-wrap items-center gap-2">
        <div className="flex items-center gap-0.5 rounded-lg bg-surface-container-low p-0.5 ghost-border">
          {CATEGORIES.map((item) => (
            <button
              key={item.id}
              onClick={() => setCategory(item.id)}
              className={`rounded-md px-2 py-0.5 text-[9px] font-bold transition-colors ${
                category === item.id
                  ? "bg-surface-container-highest text-on-surface"
                  : "text-outline hover:text-on-surface"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
        <label className="flex items-center gap-1.5 text-[10px] text-outline">
          <input
            type="checkbox"
            checked={onlyConfirmed}
            onChange={(event) => setOnlyConfirmed(event.target.checked)}
            className="accent-primary"
          />
          只看已确认
        </label>
        <div className="ml-auto flex items-center gap-1 rounded-lg bg-surface-container-low px-2 py-1 ghost-border">
          <Search className="h-3 w-3 text-outline" />
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && fetchWiki()}
            placeholder="搜索 Wiki"
            className="w-36 bg-transparent text-[10px] text-on-surface placeholder:text-outline/50 focus:outline-none"
          />
        </div>
        <span className="font-mono text-[9px] text-outline">{filteredEntries.length}/{entries.length}</span>
      </div>

      <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto">
        {error && (
          <div className="mb-2 flex items-start gap-2 rounded-lg bg-error-container/40 p-3 ghost-border">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-error" />
            <div className="text-[11px] text-on-error-container">{error}</div>
          </div>
        )}
        {loading && entries.length === 0 && (
          <div className="flex items-center justify-center gap-2 py-12 text-outline">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span className="text-[11px]">Compiling Wiki...</span>
          </div>
        )}
        {!loading && !error && filteredEntries.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-12 text-outline">
            <BookOpen className="h-8 w-8 opacity-30" />
            <span className="text-[11px]">
              {agentId.trim() ? "No visible Wiki entries" : "Enter an agentId to compile Wiki"}
            </span>
          </div>
        )}
        {filteredEntries.map((entry) => (
          <WikiCard
            key={entry.wikiId}
            entry={entry}
            pending={pendingId === entry.wikiId}
            onConfirm={() => confirmEntry(entry.wikiId, !entry.userConfirmed)}
          />
        ))}
      </div>
    </div>
  );
}

function WikiCard({ entry, pending, onConfirm }: { entry: WikiEntry; pending: boolean; onConfirm: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const shortContent = !expanded && entry.content.length > 300
    ? `${entry.content.slice(0, 300)}...`
    : entry.content;
  const categoryTone: Record<string, string> = {
    PROJECTS: "bg-primary-fixed/60 text-primary",
    TOPICS: "bg-secondary-container/60 text-on-secondary-container",
    DECISIONS: "bg-tertiary-container/60 text-on-tertiary-container",
    SOURCES: "bg-surface-container-high text-outline",
    ARTIFACTS: "bg-error-container/50 text-on-error-container",
  };

  return (
    <article className={`mb-2 rounded-lg bg-surface-container-lowest p-3 ghost-border ${pending ? "opacity-70" : ""}`}>
      <div className="flex items-start gap-2">
        <FileText className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <h3 className="text-xs font-bold text-on-surface">{entry.title}</h3>
            <span className={`pill ${categoryTone[entry.category] || "bg-surface-container-high text-outline"}`}>{entry.category}</span>
            {entry.superseded && <span className="pill bg-error-container/40 text-on-error-container">已被取代</span>}
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[9px] text-outline">
            <span className="font-mono">{entry.namespace}</span>
            <span>scope:{entry.visibilityScope}</span>
            <span>v{entry.version}</span>
            <span>{Math.round(entry.confidence * 100)}% confidence</span>
          </div>
        </div>
        <button
          onClick={onConfirm}
          disabled={pending}
          title={entry.userConfirmed ? "取消用户确认" : "标记为用户已确认"}
          className={`flex flex-shrink-0 items-center gap-1 rounded-md px-2 py-1 text-[9px] font-bold transition-colors disabled:opacity-50 ${
            entry.userConfirmed
              ? "bg-primary-fixed/70 text-primary"
              : "bg-surface-container-high text-outline hover:text-primary"
          }`}
        >
          {pending ? <Loader2 className="h-3 w-3 animate-spin" /> : entry.userConfirmed ? <Check className="h-3 w-3" /> : <ShieldCheck className="h-3 w-3" />}
          {entry.userConfirmed ? "已确认" : "确认"}
        </button>
      </div>

      <button onClick={() => setExpanded(!expanded)} className="mt-2 block w-full text-left text-[11px] leading-relaxed text-on-surface-variant">
        {shortContent || <span className="italic text-outline">(empty content)</span>}
      </button>

      <div className="mt-2 grid grid-cols-1 gap-1 text-[9px] text-outline sm:grid-cols-2">
        <span><strong className="text-outline/70">来源</strong> {entry.source}{entry.sourceRef ? ` · ${entry.sourceRef}` : ""}</span>
        <span><strong className="text-outline/70">Agent / trace</strong> {entry.sourceAgentId || entry.ownerAgentId}{entry.traceId ? ` · ${entry.traceId}` : " · —"}</span>
        {entry.basis && <span><strong className="text-outline/70">依据</strong> {entry.basis}</span>}
        {entry.supersedesWikiId && <span><strong className="text-outline/70">取代</strong> {entry.supersedesWikiId}</span>}
      </div>
      <div className="mt-1 flex items-center gap-1 text-[9px] text-outline/70">
        {entry.userConfirmed ? <ShieldCheck className="h-3 w-3 text-primary" /> : <CircleHelp className="h-3 w-3" />}
        {entry.userConfirmed ? "用户已确认此 Wiki 投影" : "尚未经过用户确认"}
        <span className="ml-auto font-mono">{entry.wikiId}</span>
      </div>
    </article>
  );
}

function formatTs(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
