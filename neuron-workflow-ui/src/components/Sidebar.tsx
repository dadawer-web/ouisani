import { useState, useEffect } from "react";
import { Plus, Rocket, Workflow, Sparkles, Loader2, Cpu, Users, FolderOpen } from "lucide-react";
import { useWorkflowStore } from "@/store/workflowStore";
import VfsWorkspaceBridge from "@/components/VfsWorkspaceBridge";

/** 左侧边栏 — 工作流操作面板（专家 + 傻瓜双模式） */
export default function Sidebar() {
  const workflowName = useWorkflowStore((s) => s.workflowName);
  const setWorkflowName = useWorkflowStore((s) => s.setWorkflowName);
  const nodeCount = useWorkflowStore((s) => s.nodes.length);
  const edgeCount = useWorkflowStore((s) => s.edges.length);
  const deploy = useWorkflowStore((s) => s.deploy);
  const deploying = useWorkflowStore((s) => s.deploying);
  const addNode = useWorkflowStore((s) => s.addNode);
  const autoCompile = useWorkflowStore((s) => s.autoCompile);
  const enabledSkills = useWorkflowStore((s) => s.enabledSkills);
  const toggleSkill = useWorkflowStore((s) => s.toggleSkill);
  const enabledRoles = useWorkflowStore((s) => s.enabledRoles);
  const toggleRole = useWorkflowStore((s) => s.toggleRole);
  const availableSkills = useWorkflowStore((s) => s.availableSkills);
  const availableRoles = useWorkflowStore((s) => s.availableRoles);
  const fetchCatalogs = useWorkflowStore((s) => s.fetchCatalogs);

  useEffect(() => {
    fetchCatalogs();
  }, []);

  const [userIdea, setUserIdea] = useState("");
  const [autoCompiling, setAutoCompiling] = useState(false);
  // 侧边栏视图切换：workflow | vfs
  const [sidebarTab, setSidebarTab] = useState<"workflow" | "vfs">("workflow");

  const handleAddNode = () => {
    addNode({
      x: 250 + Math.random() * 200,
      y: 150 + Math.random() * 150,
    });
  };

  const handleAutoCompile = async () => {
    if (!userIdea.trim()) return;
    setAutoCompiling(true);
    try {
      await autoCompile(userIdea);
    } finally {
      setAutoCompiling(false);
    }
  };

  return (
    <div className="flex h-full w-72 flex-col border-r border-zinc-800/80 bg-[#0a0a0f]/95 backdrop-blur-sm">
      {/* ═══ 顶部区域（固定不滚动） ═══ */}
      <div className="flex-shrink-0">
        {/* 标题 */}
        <div className="flex items-center gap-2 border-b border-zinc-800/80 px-4 py-4">
          <Workflow className="h-5 w-5 text-cyan-400" />
          <div>
            <h1 className="text-sm font-bold tracking-wider text-zinc-100 uppercase">
              AIOS Workflow
            </h1>
            <p className="text-[10px] text-zinc-500">Agent Topology Builder</p>
          </div>
        </div>

        {/* Tab 切换：Workflow | VFS Bridge */}
        <div className="flex border-b border-zinc-800/50">
          <button
            onClick={() => setSidebarTab("workflow")}
            className={`flex flex-1 items-center justify-center gap-1.5 py-2 text-[10px] font-bold uppercase tracking-wider transition-all ${
              sidebarTab === "workflow"
                ? "border-b-2 border-cyan-400 text-cyan-400 bg-cyan-500/[0.04]"
                : "text-zinc-500 hover:text-zinc-300"
            }`}
          >
            <Workflow className="h-3 w-3" />
            Workflow
          </button>
          <button
            onClick={() => setSidebarTab("vfs")}
            className={`flex flex-1 items-center justify-center gap-1.5 py-2 text-[10px] font-bold uppercase tracking-wider transition-all ${
              sidebarTab === "vfs"
                ? "border-b-2 border-emerald-400 text-emerald-400 bg-emerald-500/[0.04]"
                : "text-zinc-500 hover:text-zinc-300"
            }`}
          >
            <FolderOpen className="h-3 w-3" />
            VFS Bridge
          </button>
        </div>

        {/* VFS Bridge 视图 */}
        {sidebarTab === "vfs" && (
          <div className="flex-1 overflow-y-auto min-h-0 p-4 custom-scrollbar">
            <VfsWorkspaceBridge />
          </div>
        )}

        {/* 傻瓜模式 — 一句话生成拓扑 */}
        {sidebarTab === "workflow" && (
        <>
        <div className="border-b border-zinc-800/50 px-4 py-3">
          <label className="mb-1.5 flex items-center gap-1.5 text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
            <Sparkles className="h-3 w-3" /> Auto-Compile
          </label>
          <textarea
            value={userIdea}
            onChange={(e) => setUserIdea(e.target.value)}
            placeholder="输入您的想法，AIOS 将自动为您构建工作流..."
            rows={3}
            className="w-full resize-none rounded-lg border border-cyan-500/20 bg-cyan-500/[0.03] px-3 py-2 text-xs text-zinc-200 placeholder-zinc-600 outline-none transition-colors focus:border-cyan-400/50 focus:ring-1 focus:ring-cyan-500/20"
          />
          <button
            onClick={handleAutoCompile}
            disabled={!userIdea.trim() || autoCompiling}
            className="mt-2 flex w-full items-center justify-center gap-2 rounded-lg border border-cyan-500/40 bg-cyan-500/15 px-3 py-2.5 text-xs font-bold text-cyan-200 shadow-[0_0_15px_rgba(0,240,255,0.1)] transition-all hover:border-cyan-400/70 hover:bg-cyan-500/25 hover:shadow-[0_0_25px_rgba(0,240,255,0.2)] active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40"
          >
            {autoCompiling ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Sparkles className="h-4 w-4" />
            )}
            {autoCompiling ? "生成中..." : "一键生成拓扑 (Auto-Compile)"}
          </button>
        </div>

        {/* 工作流名称 */}
        <div className="border-b border-zinc-800/50 px-4 py-3">
          <label className="mb-1 block text-[10px] font-medium tracking-wider text-zinc-500 uppercase">
            Workflow Name
          </label>
          <input
            type="text"
            value={workflowName}
            onChange={(e) => setWorkflowName(e.target.value)}
            className="w-full rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 outline-none transition-colors focus:border-cyan-500/50"
          />
        </div>

        {/* 统计 */}
        <div className="flex gap-3 border-b border-zinc-800/50 px-4 py-3">
          <div className="flex-1 rounded border border-zinc-800/50 bg-zinc-900/30 px-2 py-1.5 text-center">
            <div className="text-lg font-bold text-cyan-400">{nodeCount}</div>
            <div className="text-[9px] text-zinc-500 uppercase">Nodes</div>
          </div>
          <div className="flex-1 rounded border border-zinc-800/50 bg-zinc-900/30 px-2 py-1.5 text-center">
            <div className="text-lg font-bold text-violet-400">{edgeCount}</div>
            <div className="text-[9px] text-zinc-500 uppercase">Edges</div>
          </div>
        </div>
        </>
        )}

      </div>

      {/* ═══ 中间滚动区域（技能 + 角色列表） ═══ */}
      <div className="flex-1 overflow-y-auto min-h-0 p-4 space-y-6 custom-scrollbar">
        {/* 技能插拔选择器 (Skill Selector) — 按需装载技能模块 */}
        <div>
          <label className="mb-2 flex items-center gap-1.5 text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
            <Cpu className="h-3 w-3" /> Skill Selector
          </label>
          {availableSkills.length === 0 ? (
            <div className="flex items-center gap-2 rounded-lg border border-zinc-800/40 bg-zinc-900/20 px-3 py-3">
              <Loader2 className="h-3.5 w-3.5 animate-spin text-cyan-500/50" />
              <span className="text-[10px] text-zinc-600">正在从 AIOS 内核扫描物理资产...</span>
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {availableSkills.map((skill) => {
                const active = enabledSkills.includes(skill.id);
                return (
                  <div
                    key={skill.id}
                    onClick={() => toggleSkill(skill.id)}
                    className={`flex items-start space-x-3 p-3 rounded-lg border transition-all duration-300 cursor-pointer group ${
                      active
                        ? 'bg-cyan-950/30 border-cyan-500/50 shadow-[0_0_15px_rgba(6,182,212,0.15)]'
                        : 'bg-gray-800/30 border-gray-700/50 hover:bg-gray-700/50 hover:border-gray-600'
                    }`}
                  >
                    <div className={`text-2xl transition-transform duration-300 group-hover:scale-110 ${active ? 'grayscale-0' : 'grayscale-[50%]'}`}>
                      {skill.icon}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className={`font-medium text-sm truncate ${active ? 'text-cyan-400' : 'text-gray-300'}`}>
                        {skill.name}
                      </div>
                      <div className="text-xs text-gray-500 mt-1 line-clamp-2 leading-snug">
                        {skill.desc}
                      </div>
                    </div>
                    <div className="flex-shrink-0 mt-1">
                      <div className={`w-4 h-4 rounded border flex items-center justify-center transition-colors ${
                        active ? 'bg-cyan-500 border-cyan-500' : 'border-gray-600 bg-transparent'
                      }`}>
                        {active && <svg className="w-3 h-3 text-gray-900" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          <div className="mt-2 text-[9px] text-zinc-600">
            已选 {enabledSkills.length}/{availableSkills.length} 个技能
            {enabledSkills.length === 0 && availableSkills.length > 0 && (
              <span className="ml-1 text-cyan-500/80">（未挂载任何技能）</span>
            )}
          </div>
        </div>

        {/* 角色插拔选择器 (Role Selector) — 按需装载认知角色 */}
        <div>
          <label className="mb-2 flex items-center gap-1.5 text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
            <Users className="h-3 w-3" /> Role Selector
          </label>
          {availableRoles.length === 0 ? (
            <div className="flex items-center gap-2 rounded-lg border border-zinc-800/40 bg-zinc-900/20 px-3 py-3">
              <Loader2 className="h-3.5 w-3.5 animate-spin text-cyan-500/50" />
              <span className="text-[10px] text-zinc-600">正在从 AIOS 内核扫描物理资产...</span>
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {availableRoles.map((role) => {
                const active = enabledRoles.includes(role.id);
                return (
                  <div
                    key={role.id}
                    onClick={() => toggleRole(role.id)}
                    className={`flex items-start space-x-3 p-3 rounded-lg border transition-all duration-300 cursor-pointer group ${
                      active
                        ? 'bg-cyan-950/30 border-cyan-500/50 shadow-[0_0_15px_rgba(6,182,212,0.15)]'
                        : 'bg-gray-800/30 border-gray-700/50 hover:bg-gray-700/50 hover:border-gray-600'
                    }`}
                  >
                    <div className={`text-2xl transition-transform duration-300 group-hover:scale-110 ${active ? 'grayscale-0' : 'grayscale-[50%]'}`}>
                      {role.icon}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className={`font-medium text-sm truncate ${active ? 'text-cyan-400' : 'text-gray-300'}`}>
                        {role.name}
                      </div>
                      <div className="text-xs text-gray-500 mt-1 line-clamp-2 leading-snug">
                        {role.desc}
                      </div>
                    </div>
                    <div className="flex-shrink-0 mt-1">
                      <div className={`w-4 h-4 rounded border flex items-center justify-center transition-colors ${
                        active ? 'bg-cyan-500 border-cyan-500' : 'border-gray-600 bg-transparent'
                      }`}>
                        {active && <svg className="w-3 h-3 text-gray-900" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          <div className="mt-2 text-[9px] text-zinc-600">
            已选 {enabledRoles.length}/{availableRoles.length} 个角色
            {enabledRoles.length === 0 && availableRoles.length > 0 && (
              <span className="ml-1 text-cyan-500/80">（未挂载任何角色）</span>
            )}
          </div>
        </div>
      </div>

      {/* ═══ 底部按钮区域（钉死在底部，永不滚走） ═══ */}
      <div className="flex-shrink-0 border-t border-zinc-700 bg-gray-900 p-4">
        <div className="mb-2 text-[9px] font-medium tracking-widest text-zinc-600 uppercase">
          Expert Mode
        </div>
        <div className="flex flex-col gap-2">
          <button
            onClick={handleAddNode}
            className="flex items-center justify-center gap-2 rounded-lg border border-zinc-700/50 bg-zinc-800/30 px-3 py-2.5 text-xs font-semibold text-zinc-300 transition-all hover:border-zinc-600 hover:bg-zinc-800/50 active:scale-[0.98]"
          >
            <Plus className="h-4 w-4" />
            + 添加节点
          </button>

          <button
            onClick={deploy}
            disabled={deploying || nodeCount === 0}
            className="flex items-center justify-center gap-2 rounded-lg border border-emerald-500/40 bg-emerald-500/15 px-3 py-2.5 text-xs font-bold text-emerald-200 shadow-[0_0_15px_rgba(16,185,129,0.1)] transition-all hover:border-emerald-400/70 hover:bg-emerald-500/25 hover:shadow-[0_0_25px_rgba(16,185,129,0.2)] active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40"
          >
            {deploying ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Rocket className="h-4 w-4" />
            )}
            {deploying ? "部署中..." : "部署到内核 (Deploy)"}
          </button>
        </div>

        {/* 提示 */}
        <p className="mt-3 text-[10px] leading-relaxed text-zinc-600">
          傻瓜模式：输入想法 → 一键生成。专家模式：手动添加节点 → 拖线连线 → 部署。
        </p>
      </div>
    </div>
  );
}
