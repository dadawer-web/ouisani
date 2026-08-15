import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  Activity,
  Archive,
  ArrowRight,
  CalendarClock,
  Check,
  CheckCircle2,
  CircleDot,
  FileOutput,
  Lightbulb,
  Loader2,
  Plus,
  ShieldCheck,
  Sparkles,
  Target,
  X,
} from "lucide-react";
import { useMissionStore, type Mission, type MissionStatus } from "@/store/missionStore";
import { usePermissionStore } from "@/store/permissionStore";
import { cn } from "@/lib/utils";

interface MissionHomeProps {
  onOpenRuns: () => void;
}

const statusLabel: Record<MissionStatus, string> = {
  ACTIVE: "正在运行",
  WAITING_APPROVAL: "等待我审批",
  BACKGROUND: "后台任务",
  PLANNED: "计划执行",
  COMPLETED: "最近完成",
  BLOCKED: "需要处理",
  FAILED: "执行失败",
};

const statusColor: Record<MissionStatus, string> = {
  ACTIVE: "text-secondary",
  WAITING_APPROVAL: "text-primary",
  BACKGROUND: "text-outline",
  PLANNED: "text-tertiary",
  COMPLETED: "text-tertiary",
  BLOCKED: "text-error",
  FAILED: "text-error",
};

function formatTime(value: number) {
  if (!value) return "未安排";
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function MissionCard({ mission, selected, onSelect }: { mission: Mission; selected: boolean; onSelect: () => void }) {
  const Icon = mission.status === "COMPLETED" ? CheckCircle2 : mission.status === "WAITING_APPROVAL" ? ShieldCheck : mission.status === "PLANNED" ? CalendarClock : Activity;
  return (
    <button onClick={onSelect} className={cn("w-full rounded-xl border p-3 text-left transition-colors", selected ? "border-primary/45 bg-surface-container-lowest" : "border-outline-variant/15 bg-surface-container-lowest/60 hover:bg-surface-container-lowest")}>
      <div className="flex items-start gap-2.5">
        <Icon className={cn("mt-0.5 h-4 w-4 flex-shrink-0", statusColor[mission.status])} />
        <div className="min-w-0 flex-1">
          <div className="line-clamp-2 text-xs font-semibold text-on-surface">{mission.goal}</div>
          <div className="mt-1 flex items-center gap-2 text-[10px] text-outline">
            <span className={cn("font-semibold", statusColor[mission.status])}>{statusLabel[mission.status]}</span>
            <span>·</span>
            <span>{formatTime(mission.updatedAt)}</span>
          </div>
          <div className="mt-2 truncate text-[10px] text-outline/80">{mission.currentState || "等待状态更新"}</div>
        </div>
        <ArrowRight className="h-3.5 w-3.5 flex-shrink-0 text-outline/50" />
      </div>
    </button>
  );
}

function Section({ title, subtitle, icon: Icon, missions, selectedId, onSelect, empty }: {
  title: string;
  subtitle: string;
  icon: typeof Activity;
  missions: Mission[];
  selectedId: string | null;
  onSelect: (mission: Mission) => void;
  empty: string;
}) {
  return (
    <section className="rounded-2xl border border-outline-variant/15 bg-surface-container-low p-3">
      <div className="mb-2.5 flex items-center gap-2 px-1">
        <Icon className="h-4 w-4 text-primary" />
        <div className="min-w-0">
          <div className="text-xs font-bold text-on-surface">{title}</div>
          <div className="text-[10px] text-outline">{subtitle}</div>
        </div>
        <span className="ml-auto rounded-full bg-surface-container-high px-2 py-0.5 font-mono text-[10px] text-outline">{missions.length}</span>
      </div>
      {missions.length === 0 ? (
        <div className="rounded-xl border border-dashed border-outline-variant/20 px-3 py-4 text-center text-[10px] text-outline/75">{empty}</div>
      ) : (
        <div className="space-y-1.5">{missions.slice(0, 4).map((mission) => <MissionCard key={mission.missionId} mission={mission} selected={mission.missionId === selectedId} onSelect={() => onSelect(mission)} />)}</div>
      )}
    </section>
  );
}

export default function MissionHome({ onOpenRuns }: MissionHomeProps) {
  const missions = useMissionStore((state) => state.missions);
  const loading = useMissionStore((state) => state.loading);
  const error = useMissionStore((state) => state.error);
  const fetchMissions = useMissionStore((state) => state.fetchMissions);
  const createMission = useMissionStore((state) => state.createMission);
  const addApproval = useMissionStore((state) => state.addApproval);
  const pendingApprovals = usePermissionStore((state) => state.pending);
  const respond = usePermissionStore((state) => state.respond);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [goal, setGoal] = useState("");
  const [creating, setCreating] = useState(false);
  const selected = missions.find((mission) => mission.missionId === selectedId) ?? missions[0] ?? null;

  useEffect(() => {
    void fetchMissions();
    const timer = window.setInterval(() => void fetchMissions(), 3500);
    return () => window.clearInterval(timer);
  }, [fetchMissions]);

  // Bridge the permission stream into the mission read-model. The popup still
  // owns the actual decision; Mission only records the pending human action.
  useEffect(() => {
    for (const ask of pendingApprovals) {
      const mission = missions.find((item) => ask.workflowId && item.runIds.includes(ask.workflowId));
      if (!mission || mission.pendingApprovals.some((approval) => approval.requestId === ask.requestId)) continue;
      void addApproval(mission.missionId, {
        requestId: ask.requestId,
        action: ask.description,
        toolName: ask.toolName,
        target: ask.target,
        workflowId: ask.workflowId,
        traceId: ask.traceId,
        createdAt: ask.timestamp,
      });
    }
  }, [pendingApprovals, missions, addApproval]);

  const running = useMemo(() => missions.filter((mission) => mission.status === "ACTIVE"), [missions]);
  const waiting = useMemo(() => missions.filter((mission) => mission.status === "WAITING_APPROVAL"), [missions]);
  const background = useMemo(() => missions.filter((mission) => mission.status === "BACKGROUND" || mission.status === "BLOCKED" || mission.status === "FAILED"), [missions]);
  const completed = useMemo(() => missions.filter((mission) => mission.status === "COMPLETED").slice(0, 6), [missions]);
  const planned = useMemo(() => missions.filter((mission) => mission.status === "PLANNED" || mission.nextTriggerAt > Date.now()), [missions]);
  const discoveries = useMemo(() => missions.flatMap((mission) => mission.confirmedKnowledge.map((item) => ({ ...item, mission }))).sort((a, b) => b.createdAt - a.createdAt).slice(0, 8), [missions]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!goal.trim()) return;
    setCreating(true);
    const mission = await createMission(goal);
    if (mission) {
      setGoal("");
      setSelectedId(mission.missionId);
    }
    setCreating(false);
  };

  return (
    <div className="custom-scrollbar h-full overflow-y-auto bg-surface p-4 text-on-surface xl:p-6">
      <div className="mx-auto max-w-[1500px]">
        <div className="mb-5 flex flex-wrap items-start gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 font-headline text-xl font-bold"><Sparkles className="h-5 w-5 text-primary" />连续任务</div>
            <p className="mt-1 max-w-2xl text-xs leading-relaxed text-outline">Mission 把用户目标、运行中的 workflow、审批和已确认产物串成一条可恢复的工作线。</p>
          </div>
          <button onClick={onOpenRuns} className="console-action"><Activity className="h-3.5 w-3.5" />打开 Run 控制台</button>
        </div>

        <form onSubmit={submit} className="mb-5 flex items-center gap-2 rounded-2xl border border-primary/25 bg-primary/5 p-2">
          <Target className="ml-2 h-4 w-4 flex-shrink-0 text-primary" />
          <input value={goal} onChange={(event) => setGoal(event.target.value)} placeholder="告诉 AIOS 你要持续完成什么…" className="min-w-0 flex-1 bg-transparent px-2 py-2 text-sm text-on-surface outline-none placeholder:text-outline/60" />
          <button type="submit" disabled={creating || !goal.trim()} className="flex items-center gap-1.5 rounded-xl btn-primary-ink px-3 py-2 text-xs font-bold text-on-primary disabled:opacity-50">
            {creating ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Plus className="h-3.5 w-3.5" />}新建 Mission
          </button>
        </form>

        {error && <div className="mb-4 rounded-xl bg-error-container/30 px-3 py-2 text-xs text-error">{error}</div>}
        {loading && missions.length === 0 ? <div className="flex h-48 items-center justify-center gap-2 text-sm text-outline"><Loader2 className="h-4 w-4 animate-spin" />正在载入 Mission…</div> : (
          <div className="grid gap-3 lg:grid-cols-2 2xl:grid-cols-3">
            <Section title="正在运行" subtitle="Active now" icon={Activity} missions={running} selectedId={selected?.missionId ?? null} onSelect={(mission) => setSelectedId(mission.missionId)} empty="没有正在运行的任务" />
            <Section title="等待我审批" subtitle="Human-in-the-loop" icon={ShieldCheck} missions={waiting} selectedId={selected?.missionId ?? null} onSelect={(mission) => setSelectedId(mission.missionId)} empty={pendingApprovals.length ? `${pendingApprovals.length} 个审批将在弹窗中处理` : "当前没有待审批动作"} />
            <Section title="后台任务" subtitle="Background / needs attention" icon={Archive} missions={background} selectedId={selected?.missionId ?? null} onSelect={(mission) => setSelectedId(mission.missionId)} empty="后台没有暂停或阻塞任务" />
            <Section title="最近完成" subtitle="Completed recently" icon={CheckCircle2} missions={completed} selectedId={selected?.missionId ?? null} onSelect={(mission) => setSelectedId(mission.missionId)} empty="完成的 Mission 会出现在这里" />
            <Section title="计划执行" subtitle="Next trigger" icon={CalendarClock} missions={planned} selectedId={selected?.missionId ?? null} onSelect={(mission) => setSelectedId(mission.missionId)} empty="尚无计划中的任务" />
            <section className="rounded-2xl border border-outline-variant/15 bg-surface-container-low p-3">
              <div className="mb-2.5 flex items-center gap-2 px-1"><Lightbulb className="h-4 w-4 text-primary" /><div><div className="text-xs font-bold">新发现 / 新产物</div><div className="text-[10px] text-outline">Confirmed knowledge & artifacts</div></div><span className="ml-auto rounded-full bg-surface-container-high px-2 py-0.5 font-mono text-[10px] text-outline">{discoveries.length}</span></div>
              {discoveries.length === 0 ? <div className="rounded-xl border border-dashed border-outline-variant/20 px-3 py-4 text-center text-[10px] text-outline/75">Mission 确认知识或产物后会显示在这里</div> : <div className="space-y-1.5">{discoveries.map((item) => <button key={`${item.mission.missionId}-${item.id}`} onClick={() => setSelectedId(item.mission.missionId)} className="flex w-full items-start gap-2 rounded-xl bg-surface-container-lowest p-2.5 text-left hover:bg-surface-container-high"><FileOutput className="mt-0.5 h-4 w-4 flex-shrink-0 text-tertiary" /><div className="min-w-0 flex-1"><div className="truncate text-xs font-semibold">{item.title}</div><div className="mt-0.5 line-clamp-2 text-[10px] text-outline">{item.summary || item.source}</div></div><span className="text-[9px] text-outline">{item.kind}</span></button>)}</div>}
            </section>
          </div>
        )}

        {selected && <section className="mt-4 rounded-2xl border border-primary/25 bg-surface-container-lowest p-4 ambient-shadow-sm">
          <div className="flex flex-wrap items-start gap-3 border-b border-outline-variant/15 pb-3"><div className="min-w-0 flex-1"><div className="flex items-center gap-2"><CircleDot className={cn("h-4 w-4", statusColor[selected.status])} /><h2 className="font-headline text-base font-bold">{selected.goal}</h2><span className={cn("rounded-full bg-surface-container-high px-2 py-0.5 text-[10px] font-semibold", statusColor[selected.status])}>{statusLabel[selected.status]}</span></div><div className="mt-1 font-mono text-[10px] text-outline">{selected.missionId} · {selected.runIds.length} linked run(s)</div></div><button onClick={() => setSelectedId(null)} className="rounded-lg p-1.5 text-outline hover:bg-surface-container-high hover:text-on-surface"><X className="h-4 w-4" /></button></div>
          <div className="grid gap-3 py-3 sm:grid-cols-2 lg:grid-cols-4"><div><div className="text-[10px] uppercase tracking-widest text-outline">当前状态</div><div className="mt-1 text-sm font-semibold">{selected.currentState || "—"}</div></div><div><div className="text-[10px] uppercase tracking-widest text-outline">下一步</div><div className="mt-1 text-sm font-semibold">{selected.nextStep || "—"}</div></div><div><div className="text-[10px] uppercase tracking-widest text-outline">下次触发</div><div className="mt-1 text-sm font-semibold">{selected.nextTriggerAt ? formatTime(selected.nextTriggerAt) : selected.nextTriggerEvent || "手动"}</div></div><div><div className="text-[10px] uppercase tracking-widest text-outline">完成汇报</div><div className="mt-1 line-clamp-2 text-sm">{selected.completionReport || "尚未完成"}</div></div></div>
          <div className="flex flex-wrap items-center gap-2 border-t border-outline-variant/15 pt-3 text-[10px] text-outline"><span className="inline-flex items-center gap-1"><Activity className="h-3.5 w-3.5" />Runs: {selected.runIds.length || "—"}</span><span>·</span><span className="inline-flex items-center gap-1"><ShieldCheck className="h-3.5 w-3.5" />Approvals: {selected.pendingApprovals.length || "—"}</span><span>·</span><span className="inline-flex items-center gap-1"><Lightbulb className="h-3.5 w-3.5" />Knowledge: {selected.confirmedKnowledge.length || "—"}</span><button onClick={onOpenRuns} className="ml-auto inline-flex items-center gap-1 text-primary hover:underline">查看关联运行 <ArrowRight className="h-3 w-3" /></button></div>
        </section>}

        {pendingApprovals.length > 0 && <div className="mt-4 rounded-2xl border border-primary/30 bg-primary/5 p-3"><div className="mb-2 flex items-center gap-2 text-xs font-bold text-primary"><ShieldCheck className="h-4 w-4" />待审批动作 <span className="font-mono">{pendingApprovals.length}</span></div><div className="space-y-1.5">{pendingApprovals.slice(0, 3).map((ask) => <div key={ask.requestId} className="flex flex-wrap items-center gap-2 rounded-xl bg-surface-container-lowest px-3 py-2"><span className="min-w-0 flex-1 truncate text-xs">{ask.description || `允许 ${ask.toolName}`}</span><button onClick={() => respond(ask.requestId, "ALLOW_ONCE")} className="inline-flex items-center gap-1 rounded-lg bg-tertiary/10 px-2 py-1 text-[10px] font-bold text-tertiary"><Check className="h-3 w-3" />允许</button><button onClick={() => respond(ask.requestId, "DENY")} className="inline-flex items-center gap-1 rounded-lg bg-error/10 px-2 py-1 text-[10px] font-bold text-error"><X className="h-3 w-3" />拒绝</button></div>)}</div></div>}
      </div>
    </div>
  );
}
