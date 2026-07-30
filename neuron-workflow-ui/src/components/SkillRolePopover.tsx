import { useState, useRef, useEffect } from "react";
import { Cpu, Users, Search, X } from "lucide-react";
import { useWorkflowStore } from "@/store/workflowStore";
import { cn } from "@/lib/utils";

/**
 * Skill/Role 选择器 Popover —— 从 WorkflowConfigRail 抽出，供 ChatComposer 触发。
 * 复用 workflowStore 的 toggleSkill / toggleRole / available* / enabled*，
 * 不含 fetchCatalogs（已移至 App）。
 */
export default function SkillRolePopover() {
  const enabledSkills = useWorkflowStore((s) => s.enabledSkills);
  const toggleSkill = useWorkflowStore((s) => s.toggleSkill);
  const enabledRoles = useWorkflowStore((s) => s.enabledRoles);
  const toggleRole = useWorkflowStore((s) => s.toggleRole);
  const availableSkills = useWorkflowStore((s) => s.availableSkills);
  const availableRoles = useWorkflowStore((s) => s.availableRoles);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const q = query.toLowerCase();
  const filteredSkills = availableSkills.filter((s) => s.name.toLowerCase().includes(q) || q === "");
  const filteredRoles = availableRoles.filter((r) => r.name.toLowerCase().includes(q) || q === "");

  const totalSelected = enabledSkills.length + enabledRoles.length;

  return (
    <div className="relative" ref={panelRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors",
          open
            ? "bg-surface-container-high text-primary"
            : "text-outline hover:bg-surface-container-high hover:text-on-surface",
        )}
        title="技能 / 角色"
      >
        <Cpu className="h-3.5 w-3.5" />
        <span className="tabular-nums">{totalSelected}</span>
      </button>

      {open && (
        <div className="absolute bottom-full left-0 z-40 mb-2 w-80 rounded-xl bg-surface-container-lowest ambient-shadow ghost-border">
          {/* 头部 + 搜索 */}
          <div className="flex items-center gap-2 border-b border-outline-variant/15 px-3 py-2.5">
            <Search className="h-3.5 w-3.5 text-outline" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="过滤技能/角色…"
              className="flex-1 bg-transparent text-xs text-on-surface placeholder:text-outline/50 focus:outline-none"
            />
            <button
              onClick={() => setOpen(false)}
              className="rounded p-0.5 text-outline hover:text-on-surface"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>

          <div className="custom-scrollbar max-h-80 overflow-y-auto p-3">
            {/* Skill Selector */}
            <div className="mb-4">
              <label className="mb-2 flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wider text-outline">
                <Cpu className="h-3 w-3" /> Skill Selector
                <span className="ml-auto text-outline/60">
                  {enabledSkills.length}/{availableSkills.length}
                </span>
              </label>
              {availableSkills.length === 0 ? (
                <div className="rounded-lg bg-surface-container-lowest px-3 py-3 text-[10px] text-outline ghost-border">
                  正在从内核扫描物理资产…
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {filteredSkills.map((skill) => (
                    <SelectorRow
                      key={skill.id}
                      icon={skill.icon}
                      name={skill.name}
                      desc={skill.desc}
                      active={enabledSkills.includes(skill.id)}
                      onClick={() => toggleSkill(skill.id)}
                    />
                  ))}
                </div>
              )}
            </div>

            {/* Role Selector */}
            <div>
              <label className="mb-2 flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wider text-outline">
                <Users className="h-3 w-3" /> Role Selector
                <span className="ml-auto text-outline/60">
                  {enabledRoles.length}/{availableRoles.length}
                </span>
              </label>
              {availableRoles.length === 0 ? (
                <div className="rounded-lg bg-surface-container-lowest px-3 py-3 text-[10px] text-outline ghost-border">
                  正在从内核扫描物理资产…
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {filteredRoles.map((role) => (
                    <SelectorRow
                      key={role.id}
                      icon={role.icon}
                      name={role.name}
                      desc={role.desc}
                      active={enabledRoles.includes(role.id)}
                      onClick={() => toggleRole(role.id)}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SelectorRow({
  icon,
  name,
  desc,
  active,
  onClick,
}: {
  icon: string;
  name: string;
  desc: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex items-start gap-2.5 rounded-lg p-2 text-left transition-colors",
        active
          ? "bg-surface-container-lowest ghost-border-strong"
          : "bg-surface-container-lowest/50 hover:bg-surface-container-high",
      )}
    >
      <span className="text-lg">{icon}</span>
      <span className="min-w-0 flex-1">
        <span className={cn("block truncate text-xs font-medium", active ? "text-primary" : "text-on-surface")}>
          {name}
        </span>
        <span className="mt-0.5 block line-clamp-2 text-[10px] leading-snug text-outline">{desc}</span>
      </span>
      <span
        className={cn(
          "mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border transition-colors",
          active ? "border-primary bg-primary text-on-primary" : "border-outline/40 bg-transparent",
        )}
      >
        {active && (
          <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
          </svg>
        )}
      </span>
    </button>
  );
}
