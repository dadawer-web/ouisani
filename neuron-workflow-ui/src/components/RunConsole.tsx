import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Download,
  FileClock,
  Pause,
  Play,
  RotateCcw,
  Square,
  Workflow,
  XCircle,
} from "lucide-react";
import { AIOS_API_URL } from "@/config";
import { cn } from "@/lib/utils";

type RunStatus = "QUEUED" | "RUNNING" | "PAUSED" | "CANCEL_REQUESTED" | "SUCCEEDED" | "FAILED" | "CANCELLED";

interface RunNode {
  nodeId: string;
  role: string;
  executor: string;
  status: string;
  startedAt: number;
  endedAt: number;
  durationMs: number;
  error?: string;
}

interface RunSnapshot {
  runId: string;
  workflowId: string;
  traceId: string;
  status: RunStatus;
  startedAt: number;
  endedAt: number;
  totalNodes: number;
  succeeded: number;
  failed: number;
  running: number;
  skipped: number;
  suspended: number;
  nodes: RunNode[];
}

interface TimelineEntry {
  ts: number;
  decision: string;
  eventType?: string;
  target?: string;
  reason?: string;
  agentId?: string;
  nodeId?: string;
  traceId?: string;
}

interface ContinuationStep {
  stepId: string;
  label: string;
  status: string;
  reason?: string;
}

interface ContinuationTool {
  checkpointId: string;
  toolName: string;
  result?: string;
  readOnly: boolean;
}

interface ContinuationPlan {
  checkpointId: string;
  runId: string;
  instruction?: string;
  state: string;
  retainedTools: ContinuationTool[];
  reusableResults: ContinuationTool[];
  retainedSteps: ContinuationStep[];
  invalidatedSteps: ContinuationStep[];
  requiresApproval: ContinuationStep[];
}

const TOKEN = "AIOS-SUPER-SECRET-KEY";

const statusMeta: Record<RunStatus, { label: string; className: string }> = {
  QUEUED: { label: "Queued", className: "text-outline" },
  RUNNING: { label: "Running", className: "text-secondary" },
  PAUSED: { label: "Paused", className: "text-tertiary" },
  CANCEL_REQUESTED: { label: "Cancelling", className: "text-error" },
  SUCCEEDED: { label: "Succeeded", className: "text-tertiary" },
  FAILED: { label: "Failed", className: "text-error" },
  CANCELLED: { label: "Cancelled", className: "text-outline" },
};

function formatTime(value: number) {
  if (!value) return "—";
  return new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function duration(run: RunSnapshot) {
  const end = run.endedAt || Date.now();
  const ms = Math.max(0, end - run.startedAt);
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function StatusIcon({ status }: { status: string }) {
  if (status === "SUCCESS" || status === "SUCCEEDED") return <CheckCircle2 className="h-4 w-4 text-tertiary" />;
  if (status === "FAILED") return <XCircle className="h-4 w-4 text-error" />;
  if (status === "RUNNING") return <Activity className="h-4 w-4 animate-pulse text-secondary" />;
  if (status === "SKIPPED") return <ChevronRight className="h-4 w-4 text-outline" />;
  return <Clock3 className="h-4 w-4 text-outline" />;
}

export default function RunConsole() {
  const [runs, setRuns] = useState<RunSnapshot[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [continuation, setContinuation] = useState<ContinuationPlan | null>(null);
  const [continueInstruction, setContinueInstruction] = useState("");
  const [continuing, setContinuing] = useState(false);

  const selected = useMemo(
    () => runs.find((run) => run.runId === selectedId) ?? runs[0] ?? null,
    [runs, selectedId],
  );

  useEffect(() => {
    if (selected?.runId) setSelectedId(selected.runId);
  }, [selected?.runId]);

  const fetchRuns = useCallback(async () => {
    try {
      const response = await fetch(`${AIOS_API_URL}/api/runs?token=${TOKEN}`);
      if (!response.ok) throw new Error(`Run API HTTP ${response.status}`);
      const data = (await response.json()) as RunSnapshot[];
      setRuns(Array.isArray(data) ? data : []);
      setSelectedId((current) => current && data.some((run) => run.runId === current) ? current : data[0]?.runId ?? null);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Run service unavailable");
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchTimeline = useCallback(async (runId: string) => {
    try {
      const response = await fetch(`${AIOS_API_URL}/api/runs/${encodeURIComponent(runId)}/timeline?token=${TOKEN}`);
      if (!response.ok) return;
      const data = (await response.json()) as TimelineEntry[];
      setTimeline(Array.isArray(data) ? data : []);
    } catch {
      // Timeline is additive; keep the run snapshot usable when it is unavailable.
    }
  }, []);

  const fetchContinuation = useCallback(async (runId: string) => {
    try {
      const response = await fetch(`${AIOS_API_URL}/api/runs/${encodeURIComponent(runId)}/continuation?token=${TOKEN}`);
      if (!response.ok) { setContinuation(null); return; }
      const data = (await response.json()) as ContinuationPlan;
      setContinuation(data);
    } catch { setContinuation(null); }
  }, []);

  useEffect(() => {
    void fetchRuns();
    const timer = window.setInterval(() => void fetchRuns(), 2500);
    return () => window.clearInterval(timer);
  }, [fetchRuns]);

  useEffect(() => {
    if (selected?.runId) {
      void fetchTimeline(selected.runId);
      void fetchContinuation(selected.runId);
    } else {
      setTimeline([]);
      setContinuation(null);
    }
  }, [fetchContinuation, fetchTimeline, selected?.runId]);

  const control = async (action: "PAUSE" | "RESUME" | "CANCEL") => {
    if (!selected) return;
    setBusyAction(action);
    try {
      const response = await fetch(`${AIOS_API_URL}/api/runs/${encodeURIComponent(selected.runId)}/control`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
        body: JSON.stringify({ action }),
      });
      if (!response.ok) throw new Error(`Control request rejected (${response.status})`);
      await fetchRuns();
      await fetchTimeline(selected.runId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Control request failed");
    } finally {
      setBusyAction(null);
    }
  };

  const exportTimeline = () => {
    if (!selected || timeline.length === 0) return;
    const blob = new Blob([timeline.map((entry) => JSON.stringify(entry)).join("\n")], { type: "application/x-ndjson" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${selected.runId}-timeline.jsonl`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const continueRun = async () => {
    if (!selected || !continueInstruction.trim()) return;
    setContinuing(true);
    try {
      const response = await fetch(`${AIOS_API_URL}/api/runs/${encodeURIComponent(selected.runId)}/continue`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
        body: JSON.stringify({ instruction: continueInstruction.trim() }),
      });
      if (!response.ok) throw new Error(`Continuation request rejected (${response.status})`);
      const data = (await response.json()) as { plan: ContinuationPlan };
      setContinuation(data.plan);
      setContinueInstruction("");
      await fetchRuns();
      await fetchTimeline(selected.runId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Continuation request failed");
    } finally {
      setContinuing(false);
    }
  };

  const activeCount = runs.filter((run) => run.status === "RUNNING" || run.status === "PAUSED" || run.status === "CANCEL_REQUESTED").length;
  const riskCount = runs.filter((run) => run.status === "FAILED" || run.suspended > 0).length;

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface p-4 text-on-surface">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <div>
          <div className="flex items-center gap-2 font-headline text-lg font-bold">
            <Activity className="h-5 w-5 text-primary" /> Run Console
          </div>
          <p className="mt-0.5 text-xs text-outline">Runs, agent state, intervention and trace evidence</p>
        </div>
        <div className="ml-auto flex items-center gap-2 text-[11px]">
          <span className="rounded-full bg-secondary/10 px-2 py-1 text-secondary">{activeCount} active</span>
          <span className={cn("rounded-full px-2 py-1", riskCount ? "bg-error/10 text-error" : "bg-tertiary/10 text-tertiary")}>{riskCount} risk</span>
          <button onClick={() => void fetchRuns()} className="rounded-lg border border-outline-variant/30 p-2 text-outline hover:bg-surface-container-high hover:text-on-surface" title="Refresh runs">
            <RotateCcw className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {error && <div className="mb-3 flex items-center gap-2 rounded-lg bg-error-container/30 px-3 py-2 text-xs text-error"><AlertTriangle className="h-4 w-4" />{error}</div>}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 xl:grid-cols-[280px_minmax(0,1fr)]">
        <section className="custom-scrollbar min-h-0 overflow-y-auto rounded-xl border border-outline-variant/20 bg-surface-container-low p-2">
          <div className="mb-2 px-2 text-[10px] font-bold uppercase tracking-widest text-outline/70">Recent runs</div>
          {loading && <div className="px-2 py-8 text-center text-xs text-outline">Loading run registry…</div>}
          {!loading && runs.length === 0 && <div className="px-2 py-8 text-center text-xs leading-relaxed text-outline">No runs yet.<br />Deploy a workflow to see it here.</div>}
          <div className="space-y-1">
            {runs.map((run) => {
              const meta = statusMeta[run.status] ?? statusMeta.QUEUED;
              return (
                <button key={run.runId} onClick={() => setSelectedId(run.runId)} className={cn("w-full rounded-lg border px-3 py-2.5 text-left transition-colors", selected?.runId === run.runId ? "border-primary/40 bg-surface-container-lowest" : "border-transparent hover:bg-surface-container-high")}>
                  <div className="flex items-center gap-2"><Workflow className="h-3.5 w-3.5 text-primary" /><span className="min-w-0 flex-1 truncate font-mono text-xs">{run.workflowId}</span><span className={cn("text-[10px] font-semibold", meta.className)}>{meta.label}</span></div>
                  <div className="mt-1 flex items-center justify-between text-[10px] text-outline"><span>{formatTime(run.startedAt)}</span><span>{run.succeeded}/{run.totalNodes} nodes</span></div>
                </button>
              );
            })}
          </div>
        </section>

        <section className="custom-scrollbar min-h-0 overflow-y-auto rounded-xl border border-outline-variant/20 bg-surface-container-lowest p-4">
          {!selected ? <div className="flex h-full items-center justify-center text-sm text-outline">Select a run to inspect its execution.</div> : (
            <>
              <div className="flex flex-wrap items-start gap-3 border-b border-outline-variant/15 pb-3">
                <div className="min-w-0 flex-1"><div className="flex items-center gap-2"><span className="font-headline text-base font-bold">{selected.workflowId}</span><span className={cn("rounded-full bg-surface-container-high px-2 py-0.5 text-[10px] font-semibold", statusMeta[selected.status]?.className)}>{statusMeta[selected.status]?.label ?? selected.status}</span></div><div className="mt-1 truncate font-mono text-[10px] text-outline">run {selected.runId} · trace {selected.traceId || "—"}</div></div>
                <div className="flex items-center gap-1.5">
                  {(selected.status === "RUNNING" || selected.status === "QUEUED") && <button onClick={() => void control("PAUSE")} disabled={!!busyAction} className="console-action"><Pause className="h-3.5 w-3.5" />Pause</button>}
                  {selected.status === "PAUSED" && <button onClick={() => void control("RESUME")} disabled={!!busyAction} className="console-action"><Play className="h-3.5 w-3.5" />Resume</button>}
                  {!["SUCCEEDED", "FAILED", "CANCELLED"].includes(selected.status) && <button onClick={() => void control("CANCEL")} disabled={!!busyAction} className="console-action console-action-danger"><Square className="h-3.5 w-3.5" />Cancel</button>}
                  <button onClick={exportTimeline} className="rounded-lg border border-outline-variant/25 p-2 text-outline hover:bg-surface-container-high hover:text-on-surface" title="Export timeline"><Download className="h-3.5 w-3.5" /></button>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-2 py-3 sm:grid-cols-5">
                {[ ["Duration", duration(selected)], ["Nodes", `${selected.succeeded}/${selected.totalNodes}`], ["Running", selected.running], ["Skipped", selected.skipped], ["Suspended", selected.suspended] ].map(([label, value]) => <div key={label} className="rounded-lg bg-surface-container-low px-3 py-2"><div className="text-[10px] uppercase tracking-wider text-outline">{label}</div><div className="mt-1 font-mono text-sm font-semibold">{value}</div></div>)}
              </div>

              <div className="mb-4 rounded-xl border border-primary/20 bg-primary/5 p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <div className="text-xs font-bold uppercase tracking-widest text-primary">打断后继续</div>
                  {continuation && <span className="rounded-full bg-surface-container-high px-2 py-0.5 text-[10px] text-outline">{continuation.state}</span>}
                </div>
                <div className="mt-2 flex gap-2">
                  <input value={continueInstruction} onChange={(event) => setContinueInstruction(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void continueRun(); }} placeholder="输入新的指令，例如：改为只检查配置，不要写文件" className="min-w-0 flex-1 rounded-lg border border-outline-variant/25 bg-surface-container-lowest px-3 py-2 text-xs outline-none focus:border-primary/60" />
                  <button onClick={() => void continueRun()} disabled={continuing || !continueInstruction.trim()} className="console-action"><Play className="h-3.5 w-3.5" />{continuing ? "处理中" : "继续"}</button>
                </div>
                {continuation && <div className="mt-3 grid gap-2 text-[11px] sm:grid-cols-3">
                  <div className="rounded-lg bg-tertiary/10 p-2"><div className="font-semibold text-tertiary">保留 / 可复用</div><div className="mt-1 text-outline">{continuation.retainedTools.length + continuation.retainedSteps.length} 项保留，{continuation.reusableResults.length} 个只读结果可复用</div></div>
                  <div className="rounded-lg bg-error/10 p-2"><div className="font-semibold text-error">放弃</div><div className="mt-1 text-outline">{continuation.invalidatedSteps.length} 个旧步骤失效</div></div>
                  <div className="rounded-lg bg-secondary/10 p-2"><div className="font-semibold text-secondary">需重新审批</div><div className="mt-1 text-outline">{continuation.requiresApproval.length} 个危险动作</div></div>
                </div>}
                {continuation && (continuation.invalidatedSteps.length > 0 || continuation.requiresApproval.length > 0) && <div className="mt-2 space-y-1 text-[10px] text-outline">
                  {continuation.retainedTools.slice(0, 8).map((tool) => <div key={`retained-tool-${tool.checkpointId}`} className="text-tertiary">保留工具：{tool.toolName}{tool.readOnly ? "（只读，可复用）" : "（结果保留，不重放）"}</div>)}
                  {continuation.retainedSteps.slice(0, 8).map((step) => <div key={`retained-step-${step.stepId}`} className="text-tertiary">保留步骤：{step.label || step.stepId}</div>)}
                  {continuation.invalidatedSteps.map((step) => <div key={`invalid-${step.stepId}`}>放弃：{step.label || step.stepId}</div>)}
                  {continuation.requiresApproval.map((step) => <div key={`approval-${step.stepId}`} className="text-secondary">重新审批：{step.label || step.stepId}</div>)}
                </div>}
              </div>

              <div className="grid gap-4 2xl:grid-cols-[minmax(0,1fr)_minmax(280px,0.75fr)]">
                <div><div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-outline"><Workflow className="h-3.5 w-3.5" /> Agent nodes</div><div className="overflow-hidden rounded-lg border border-outline-variant/15"><table className="w-full text-left text-xs"><thead className="bg-surface-container-low text-[10px] uppercase tracking-wider text-outline"><tr><th className="px-3 py-2">Node</th><th className="px-3 py-2">State</th><th className="px-3 py-2">Time</th></tr></thead><tbody>{selected.nodes.map((node) => <tr key={node.nodeId} className="border-t border-outline-variant/10"><td className="px-3 py-2"><div className="font-mono">{node.nodeId}</div><div className="text-[10px] text-outline">{node.role || node.executor}</div></td><td className="px-3 py-2"><span className="inline-flex items-center gap-1.5"><StatusIcon status={node.status} />{node.status}</span></td><td className="px-3 py-2 font-mono text-[10px] text-outline">{node.durationMs ? `${node.durationMs}ms` : formatTime(node.startedAt)}</td></tr>)}</tbody></table></div></div>
                <div><div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-outline"><FileClock className="h-3.5 w-3.5" /> Timeline</div><div className="custom-scrollbar max-h-[360px] space-y-1 overflow-y-auto rounded-lg border border-outline-variant/15 bg-surface-container-low p-2">{timeline.length === 0 ? <div className="px-2 py-8 text-center text-xs text-outline">No timeline events yet.</div> : timeline.slice().reverse().map((entry, index) => <div key={`${entry.ts}-${index}`} className="rounded-md bg-surface-container-lowest px-2.5 py-2"><div className="flex items-center gap-2"><span className="font-mono text-[10px] text-outline">{formatTime(entry.ts)}</span><span className="text-[11px] font-semibold text-primary">{entry.decision}</span></div><div className="mt-0.5 truncate text-[10px] text-outline">{entry.target || entry.nodeId || entry.eventType || "runtime"}{entry.reason ? ` · ${entry.reason}` : ""}</div></div>)}</div></div>
              </div>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
