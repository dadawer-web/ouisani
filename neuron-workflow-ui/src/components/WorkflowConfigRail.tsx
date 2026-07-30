import { useEffect, useState } from "react";
import { Plus, Cpu, Users, Workflow, ChevronLeft, ChevronRight } from "lucide-react";
import { useWorkflowStore } from "@/store/workflowStore";
import { cn } from "@/lib/utils";

/** 工作流配置轨 —— 工作流名/统计/Skill/Role 选择器/添加节点（auto-compile 与 Deploy 移至浮动 composer） */
export default function WorkflowConfigRail({
  collapsed,
  onToggleCollapsed,
}: {
  collapsed: boolean;
  onToggleCollapsed: () => void;
}) {
  const workflowName = useWorkflowStore((s) => s.workflowName);
  const setWorkflowName = useWorkflowStore((s) => s.setWorkflowName);
  const nodeCount = useWorkflowStore((s) => s.nodes.length);
  const edgeCount = useWorkflowStore((s) => s.edges.length);
  const addNode = useWorkflowStore((s) => s.addNode);
  const enabledSkills = useWorkflowStore((s) => s.enabledSkills);
  const toggleSkill = useWorkflowStore((s) => s.toggleSkill);
  const enabledRoles = useWorkflowStore((s) => s.enabledRoles);
  const toggleRole = useWorkflowStore((s) => s.toggleRole);
  const availableSkills = useWorkflowStore((s) => s.availableSkills);
  const availableRoles = useWorkflowStore((s) => s.availableRoles);
  const fetchCatalogs = useWorkflowStore((s) => s.fetchCatalogs);

  useEffect(() => {
    fetchCatalogs();
  }, [fetchCatalogs]);

  const [query, setQuery] = useState("");

  const filteredSkills = availableSkills.filter(
    (s) => s.name.toLowerCase().includes(query.toLowerCase()) || query === "",
  );
  const filteredRoles = availableRoles.filter(
    (r) => r.name.toLowerCase().includes(query.toLowerCase()) || query === "",
  );

  if (collapsed) {
    return (
      <div className="flex w-10 flex-shrink-0 flex-col items-center bg-surface-container-low py-3">
        <button
          onClick={onToggleCollapsed}
          className="flex h-8 w-8 items-center justify-center rounded-lg text-outline hover:bg-surface-container-high hover:text-primary"
          title="展开配置轨"
        >
          <ChevronRight className="h-4 w-4" />
        </button>
        <Workflow className="mt-3 h-4 w-4 text-outline/50" />
      </div>
    );
  }

  return (
    <div className="flex w-72 flex-shrink-0 flex-col bg-surface-container-low">
      {/* 标题 + 折叠 */}
      <div className="flex items-center gap-2 px-4 py-3">
        <Workflow className="h-4 w-4 text-primary" />
        <div className="flex-1">
          <div className="font-headline text-xs font-bold uppercase tracking-wider text-on-surface">
            Workflow Config
          </div>
          <div className="text-[10px] text-outline">Agent Topology Builder</div>
        </div>
        <button
          onClick={onToggleCollapsed}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-outline hover:bg-surface-container-high hover:text-primary"
          title="折叠配置轨"
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
      </div>

      {/* 工作流名 */}
      <div className="px-4 pb-3">
        <label className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-outline">
          Workflow Name
        </label>
        <input
          type="text"
          value={workflowName}
          onChange={(e) => setWorkflowName(e.target.value)}
          className="w-full rounded-lg bg-surface-container-lowest px-2.5 py-1.5 font-mono text-xs text-on-surface ghost-border focus:outline-none focus:ghost-border-strong"
        />
      </div>

      {/* 统计 */}
      <div className="flex gap-2 px-4 pb-3">
        <div className="flex-1 rounded-lg bg-surface-container-lowest px-2 py-2 text-center ghost-border">
          <div className="font-mono text-lg font-bold text-primary">{nodeCount}</div>
          <div className="text-[9px] uppercase tracking-wider text-outline">Nodes</div>
        </div>
        <div className="flex-1 rounded-lg bg-surface-container-lowest px-2 py-2 text-center ghost-border">
          <div className="font-mono text-lg font-bold text-secondary">{edgeCount}</div>
          <div className="text-[9px] uppercase tracking-wider text-outline">Edges</div>
        </div>
      </div>

      {/* 搜索过滤 */}
      <div className="px-4 pb-2">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="过滤技能/角色…"
          className="w-full rounded-lg bg-surface-container-lowest px-2.5 py-1.5 text-xs text-on-surface placeholder:text-outline/50 ghost-border focus:outline-none"
        />
      </div>

      {/* 滚动区：技能 + 角色 */}
      <div className="custom-scrollbar flex-1 space-y-5 overflow-y-auto px-4 pb-4">
        {/* Skill Selector */}
        <div>
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
              {filteredSkills.map((skill) => {
                const active = enabledSkills.includes(skill.id);
                return (
                  <button
                    key={skill.id}
                    onClick={() => toggleSkill(skill.id)}
                    className={cn(
                      "flex items-start gap-2.5 rounded-lg p-2.5 text-left transition-colors",
                      active
                        ? "bg-surface-container-lowest ghost-border-strong"
                        : "bg-surface-container-lowest/50 hover:bg-surface-container-high",
                    )}
                  >
                    <span className="text-xl">{skill.icon}</span>
                    <span className="min-w-0 flex-1">
                      <span
                        className={cn(
                          "block truncate text-xs font-medium",
                          active ? "text-primary" : "text-on-surface",
                        )}
                      >
                        {skill.name}
                      </span>
                      <span className="mt-0.5 block line-clamp-2 text-[10px] leading-snug text-outline">
                        {skill.desc}
                      </span>
                    </span>
                    <span
                      className={cn(
                        "mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border transition-colors",
                        active
                          ? "border-primary bg-primary text-on-primary"
                          : "border-outline/40 bg-transparent",
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
              })}
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
              {filteredRoles.map((role) => {
                const active = enabledRoles.includes(role.id);
                return (
                  <button
                    key={role.id}
                    onClick={() => toggleRole(role.id)}
                    className={cn(
                      "flex items-start gap-2.5 rounded-lg p-2.5 text-left transition-colors",
                      active
                        ? "bg-surface-container-lowest ghost-border-strong"
                        : "bg-surface-container-lowest/50 hover:bg-surface-container-high",
                    )}
                  >
                    <span className="text-xl">{role.icon}</span>
                    <span className="min-w-0 flex-1">
                      <span
                        className={cn(
                          "block truncate text-xs font-medium",
                          active ? "text-primary" : "text-on-surface",
                        )}
                      >
                        {role.name}
                      </span>
                      <span className="mt-0.5 block line-clamp-2 text-[10px] leading-snug text-outline">
                        {role.desc}
                      </span>
                    </span>
                    <span
                      className={cn(
                        "mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border transition-colors",
                        active
                          ? "border-primary bg-primary text-on-primary"
                          : "border-outline/40 bg-transparent",
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
              })}
            </div>
          )}
        </div>
      </div>

      {/* 底部：添加节点 */}
      <div className="border-t border-outline-variant/15 p-4">
        <button
          onClick={() => addNode({ x: 250 + Math.random() * 200, y: 150 + Math.random() * 150 })}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-surface-container-high px-3 py-2.5 text-xs font-semibold text-on-surface transition-colors hover:bg-surface-container-highest"
        >
          <Plus className="h-4 w-4" />
          添加节点
        </button>
        <p className="mt-2 text-[10px] leading-relaxed text-outline">
          专家模式：手动添加节点 → 拖线连线 → 部署。
        </p>
      </div>
    </div>
  );
}
