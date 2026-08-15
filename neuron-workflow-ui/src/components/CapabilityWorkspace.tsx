import { useCallback, useEffect, useMemo, useState } from "react";
import { Check, ChevronRight, Code2, Globe2, PackagePlus, PlugZap, RefreshCw, RotateCcw, ShieldCheck, X } from "lucide-react";
import { AIOS_API_URL } from "@/config";
import { cn } from "@/lib/utils";

const TOKEN = "AIOS-SUPER-SECRET-KEY";
type Tab = "skills" | "diffs" | "browser" | "channels";

interface SkillEntry { id: string; name: string; description: string; source: string; installed: boolean; enabled: boolean; controlled: boolean; version: string; risk: string; allowedTools: string[]; category: string; tags: string[]; }
interface DiffEntry { diffId: string; requestId: string; agentId: string; action: string; target: string; risk: string; snapshotId: string; deltaCount: number; meetsExpectation: boolean; createdAt: number; review: string; reverted: boolean; revertedAt: number; }
interface BrowserWorkspace { workspaceId: string; runId: string; missionId: string; sessionId: string; url: string; title: string; status: string; connected: boolean; createdAt: number; updatedAt: number; }
interface Channel { id: string; name: string; kind: string; connected: boolean; selected: boolean; capabilities: string[]; owner: string; }

async function request(path: string, init?: RequestInit) {
  const response = await fetch(`${AIOS_API_URL}${path}${path.includes("?") ? "&" : "?"}token=${TOKEN}`, init);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

function time(value: number) { return value ? new Date(value).toLocaleString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "—"; }

export default function CapabilityWorkspace({ onOpenVfs }: { onOpenVfs?: () => void }) {
  const [tab, setTab] = useState<Tab>("skills");
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [diffs, setDiffs] = useState<DiffEntry[]>([]);
  const [browsers, setBrowsers] = useState<BrowserWorkspace[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [skillName, setSkillName] = useState("");
  const [skillSource, setSkillSource] = useState("");
  const [browserUrl, setBrowserUrl] = useState("https://");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [s, d, b, c] = await Promise.all([
        request("/api/skills/catalog"), request("/api/diffs"), request("/api/browser/workspaces"), request("/api/channels"),
      ]);
      setSkills(Array.isArray(s) ? s : []); setDiffs(Array.isArray(d) ? d : []); setBrowsers(Array.isArray(b) ? b : []); setChannels(Array.isArray(c) ? c : []); setError(null);
    } catch (e) { setError(e instanceof Error ? e.message : "Capability service unavailable"); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const mutate = async (key: string, path: string, init?: RequestInit) => {
    setBusy(key); setError(null);
    try { await request(path, init); await load(); }
    catch (e) { setError(e instanceof Error ? e.message : "Request failed"); }
    finally { setBusy(null); }
  };

  const installSkill = async () => {
    if (!skillName.trim()) return;
    await mutate(`install-${skillName}`, "/api/skills/install", { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` }, body: JSON.stringify({ name: skillName.trim(), source: skillSource.trim() || undefined }) });
    setSkillName(""); setSkillSource("");
  };
  const openBrowser = async () => {
    await mutate("browser-open", "/api/browser/workspaces", { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` }, body: JSON.stringify({ url: browserUrl }) });
  };

  const tabs = useMemo(() => [
    ["skills", "Skill 能力目录", ShieldCheck], ["diffs", "Diff Review / 撤销", Code2], ["browser", "浏览器工作区", Globe2], ["channels", "多通道", PlugZap],
  ] as const, []);

  return <div className="flex h-full min-h-0 flex-col bg-surface p-4 text-on-surface">
    <div className="mb-3 flex flex-wrap items-center gap-2">
      <div><div className="flex items-center gap-2 font-headline text-lg font-bold"><Code2 className="h-5 w-5 text-primary" />IDE & Capability Workspace</div><p className="mt-0.5 text-xs text-outline">受控能力、变更审查、浏览器会话与多通道入口</p></div>
      <div className="ml-auto flex items-center gap-2"><button onClick={() => void load()} className="rounded-lg border border-outline-variant/25 p-2 text-outline hover:bg-surface-container-high" title="Refresh"><RefreshCw className="h-3.5 w-3.5" /></button>{onOpenVfs && <button onClick={onOpenVfs} className="console-action"><ChevronRight className="h-3.5 w-3.5" />打开 VFS IDE</button>}</div>
    </div>
    {error && <div className="mb-3 flex items-center gap-2 rounded-lg bg-error-container/30 px-3 py-2 text-xs text-error"><X className="h-4 w-4" />{error}</div>}
    <div className="mb-3 flex flex-wrap gap-1 rounded-xl border border-outline-variant/15 bg-surface-container-low p-1">{tabs.map(([id, label, Icon]) => <button key={id} onClick={() => setTab(id)} className={cn("flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-medium", tab === id ? "bg-surface-container-lowest text-primary ghost-border-strong" : "text-outline hover:bg-surface-container-high hover:text-on-surface")}><Icon className="h-3.5 w-3.5" />{label}</button>)}</div>
    <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto rounded-xl border border-outline-variant/15 bg-surface-container-lowest p-3">
      {tab === "skills" && <section><div className="mb-3 flex items-center justify-between"><div><h2 className="text-sm font-bold">Skill 能力目录</h2><p className="text-[11px] text-outline">安装仅接受批准本地根目录；安装后才可启用。</p></div><span className="rounded-full bg-primary/10 px-2 py-1 text-[10px] text-primary">{skills.filter(s => s.enabled).length} enabled / {skills.length}</span></div><div className="mb-3 grid gap-2 sm:grid-cols-[1fr_1.5fr_auto]"><input value={skillName} onChange={e => setSkillName(e.target.value)} placeholder="skill id" className="field" /><input value={skillSource} onChange={e => setSkillSource(e.target.value)} placeholder="可选：approved skill root 下的路径" className="field" /><button onClick={() => void installSkill()} disabled={!skillName.trim() || !!busy} className="console-action"><PackagePlus className="h-3.5 w-3.5" />受控安装</button></div><div className="grid gap-2 lg:grid-cols-2">{skills.map(skill => <div key={skill.id} className="rounded-lg border border-outline-variant/15 bg-surface-container-low p-3"><div className="flex items-start gap-2"><div className="min-w-0 flex-1"><div className="flex items-center gap-2"><span className="truncate text-xs font-semibold">{skill.name}</span><span className="rounded-full bg-surface-container-high px-1.5 py-0.5 text-[9px] text-outline">{skill.source}</span><span className="rounded-full bg-tertiary/10 px-1.5 py-0.5 text-[9px] text-tertiary">{skill.risk || "LOW"}</span></div><p className="mt-1 line-clamp-2 text-[10px] text-outline">{skill.description || "No description"}</p><div className="mt-1 text-[9px] text-outline/70">{skill.allowedTools?.join(", ") || "behavioral / read-only"}</div></div><button onClick={() => void mutate(`skill-${skill.id}`, `/api/skills/${encodeURIComponent(skill.id)}/${skill.enabled ? "disable" : "enable"}`, { method: "POST", headers: { Authorization: `Bearer ${TOKEN}` } })} className={cn("rounded-lg px-2 py-1 text-[10px]", skill.enabled ? "bg-tertiary/15 text-tertiary" : "bg-surface-container-high text-outline")}>{skill.enabled ? <Check className="h-3.5 w-3.5" /> : "启用"}</button></div></div>)}{skills.length === 0 && <div className="py-8 text-center text-xs text-outline">暂无可见 Skill；请从 approved skill root 发起受控安装。</div>}</div></section>}
      {tab === "diffs" && <section><div className="mb-3 flex items-center justify-between"><div><h2 className="text-sm font-bold">Diff Review / 撤销时间线</h2><p className="text-[11px] text-outline">撤销必须先由用户批准，并复用 ActionGovernor 的原始快照。</p></div><span className="text-[10px] text-outline">{diffs.length} changes</span></div><div className="space-y-2">{diffs.map(diff => <div key={diff.diffId} className="rounded-lg border border-outline-variant/15 bg-surface-container-low p-3"><div className="flex flex-wrap items-center gap-2"><span className="font-mono text-[10px] text-outline">{time(diff.createdAt)}</span><span className="text-xs font-semibold text-primary">{diff.action || "change"}</span><span className="truncate text-[10px] text-outline">{diff.target || diff.agentId}</span><span className={cn("rounded-full px-1.5 py-0.5 text-[9px]", diff.review === "APPROVED" ? "bg-tertiary/15 text-tertiary" : diff.review === "REJECTED" ? "bg-error/15 text-error" : "bg-secondary/15 text-secondary")}>{diff.review}</span><span className="ml-auto text-[10px] text-outline">{diff.deltaCount} deltas</span></div><div className="mt-2 flex gap-2"><button onClick={() => void mutate(`approve-${diff.diffId}`, `/api/diffs/${diff.diffId}/review`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` }, body: JSON.stringify({ decision: "APPROVED" }) })} disabled={diff.reverted} className="rounded-lg bg-tertiary/10 px-2 py-1 text-[10px] text-tertiary disabled:opacity-40">Approve</button><button onClick={() => void mutate(`reject-${diff.diffId}`, `/api/diffs/${diff.diffId}/review`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` }, body: JSON.stringify({ decision: "REJECTED" }) })} disabled={diff.reverted} className="rounded-lg bg-error/10 px-2 py-1 text-[10px] text-error disabled:opacity-40">Reject</button><button onClick={() => void mutate(`revert-${diff.diffId}`, `/api/diffs/${diff.diffId}/revert`, { method: "POST", headers: { Authorization: `Bearer ${TOKEN}` } })} disabled={diff.reverted || diff.review !== "APPROVED" || !!busy} className="console-action"><RotateCcw className="h-3.5 w-3.5" />{diff.reverted ? "已撤销" : "撤销"}</button></div></div>)}{diffs.length === 0 && <div className="py-8 text-center text-xs text-outline">还没有可审查的变更；受治理写入完成后会自动出现在这里。</div>}</div></section>}
      {tab === "browser" && <section><div className="mb-3 flex items-center justify-between"><div><h2 className="text-sm font-bold">浏览器工作区</h2><p className="text-[11px] text-outline">会话元数据与导航通过 Chrome extension bridge；未连接时只保留工作区状态。</p></div><span className="rounded-full bg-secondary/10 px-2 py-1 text-[10px] text-secondary">{browsers.filter(b => b.connected).length} connected</span></div><div className="mb-3 flex gap-2"><input value={browserUrl} onChange={e => setBrowserUrl(e.target.value)} placeholder="https://example.com" className="field flex-1" /><button onClick={() => void openBrowser()} disabled={!!busy || !browserUrl.trim()} className="console-action"><Globe2 className="h-3.5 w-3.5" />打开工作区</button></div><div className="space-y-2">{browsers.map(browser => <div key={browser.workspaceId} className="rounded-lg border border-outline-variant/15 bg-surface-container-low p-3"><div className="flex items-center gap-2"><Globe2 className="h-3.5 w-3.5 text-primary" /><span className="font-mono text-[10px]">{browser.workspaceId.slice(-12)}</span><span className={cn("rounded-full px-1.5 py-0.5 text-[9px]", browser.connected ? "bg-tertiary/15 text-tertiary" : "bg-secondary/15 text-secondary")}>{browser.status}</span><button onClick={() => void mutate(`close-${browser.workspaceId}`, `/api/browser/workspaces/${browser.workspaceId}`, { method: "DELETE", headers: { Authorization: `Bearer ${TOKEN}` } })} className="ml-auto rounded p-1 text-outline hover:text-error"><X className="h-3.5 w-3.5" /></button></div><div className="mt-2 truncate text-xs">{browser.title || browser.url || "等待浏览器扩展"}</div><div className="mt-1 truncate font-mono text-[10px] text-outline">{browser.url || "—"}</div><div className="mt-2 flex gap-2"><input defaultValue={browser.url} id={`browser-url-${browser.workspaceId}`} className="field flex-1" /><button onClick={() => { const el = document.getElementById(`browser-url-${browser.workspaceId}`) as HTMLInputElement | null; void mutate(`nav-${browser.workspaceId}`, `/api/browser/workspaces/${browser.workspaceId}/navigate`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` }, body: JSON.stringify({ url: el?.value }) }); }} className="rounded-lg bg-primary/10 px-2 py-1 text-[10px] text-primary">Navigate</button></div></div>)}{browsers.length === 0 && <div className="py-8 text-center text-xs text-outline">暂无浏览器工作区。</div>}</div></section>}
      {tab === "channels" && <section><div className="mb-3"><h2 className="text-sm font-bold">多通道目录</h2><p className="text-[11px] text-outline">通道只声明能力与连接状态；消息仍经过统一会话、权限和审计边界。</p></div><div className="grid gap-2 lg:grid-cols-2">{channels.map(channel => <div key={channel.id} className={cn("rounded-lg border p-3", channel.selected ? "border-primary/40 bg-primary/5" : "border-outline-variant/15 bg-surface-container-low")}><div className="flex items-center gap-2"><PlugZap className="h-3.5 w-3.5 text-primary" /><span className="text-xs font-semibold">{channel.name}</span><span className="text-[9px] text-outline">{channel.kind}</span><span className={cn("ml-auto rounded-full px-1.5 py-0.5 text-[9px]", channel.connected ? "bg-tertiary/15 text-tertiary" : "bg-surface-container-high text-outline")}>{channel.connected ? "connected" : "offline"}</span></div><div className="mt-2 flex flex-wrap gap-1">{channel.capabilities.map(cap => <span key={cap} className="rounded bg-surface-container-high px-1.5 py-0.5 text-[9px] text-outline">{cap}</span>)}</div><div className="mt-2 flex gap-2"><button onClick={() => void mutate(`select-${channel.id}`, `/api/channels/${channel.id}/select`, { method: "POST", headers: { Authorization: `Bearer ${TOKEN}` } })} className="rounded-lg bg-primary/10 px-2 py-1 text-[10px] text-primary">{channel.selected ? "当前通道" : "选择"}</button><button onClick={() => void mutate(`connect-${channel.id}`, `/api/channels/${channel.id}/${channel.connected ? "disconnect" : "connect"}`, { method: "POST", headers: { Authorization: `Bearer ${TOKEN}` } })} className="rounded-lg bg-surface-container-high px-2 py-1 text-[10px] text-outline">{channel.connected ? "断开" : "连接"}</button></div></div>)}</div></section>}
    </div>
  </div>;
}
