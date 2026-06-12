import { create } from "zustand";
import { AIOS_API_URL } from "../config";
import {
  type Node,
  type Edge,
  type Connection,
  type XYPosition,
  addEdge,
} from "@xyflow/react";
import axios from "axios";

/** 智能体节点数据 */
export interface AgentNodeData {
  label: string;
  role: string;
  blueprintId: string;
  subscribeTopic: string;
  publishTopic: string;
  userParams: Record<string, string>;
  [key: string]: unknown;
}

/** 编译后的节点格式 (WorkflowManifest.nodes[]) */
export interface CompiledWorkflowNode {
  instanceId: string;
  blueprintId: string;
  role: string;
  subscribeTopic: string;
  publishTopic: string;
  userParams: Record<string, string>;
}

/** 编译后的工作流清单 (WorkflowManifest) */
export interface CompiledWorkflowManifest {
  workflowName: string;
  nodes: CompiledWorkflowNode[];
  enabledSkills: string[];
  enabledRoles: string[];
}

/** 武器库/角色库目录项 — 从后端 /api/registry/catalogs 动态获取 */
export interface CatalogItem {
  id: string;
  name: string;
  desc: string;
  icon: string;
}

/** Toast 状态 */
interface ToastState {
  visible: boolean;
  message: string;
  type: "success" | "error" | "info";
}

/** 系统告警状态 — AutoMedic 熔断时触发 */
export interface SystemAlert {
  visible: boolean;
  nodeId: string;
  dump: string;
}

/** 工作流状态管理 */
interface WorkflowStore {
  nodes: Node[];
  edges: Edge[];
  workflowName: string;
  nodeCounter: number;
  toast: ToastState;
  systemAlert: SystemAlert;
  deploying: boolean;
  controlWs: WebSocket | null;
  enabledSkills: string[];
  setEnabledSkills: (skills: string[]) => void;
  toggleSkill: (skillId: string) => void;
  enabledRoles: string[];
  setEnabledRoles: (roles: string[]) => void;
  toggleRole: (roleId: string) => void;
  availableRoles: CatalogItem[];
  availableSkills: CatalogItem[];
  fetchCatalogs: () => Promise<void>;
  setNodes: (nodes: Node[]) => void;
  setEdges: (edges: Edge[]) => void;
  addNode: (position: XYPosition) => void;
  updateNodeData: (id: string, data: Partial<AgentNodeData>) => void;
  removeNode: (id: string) => void;
  onConnect: (connection: Connection) => void;
  setWorkflowName: (name: string) => void;
  showToast: (message: string, type?: ToastState["type"]) => void;
  hideToast: () => void;
  triggerSystemAlert: (nodeId: string, dump: string) => void;
  dismissSystemAlert: () => void;
  setControlWs: (ws: WebSocket | null) => void;
  hotPatchParam: (targetNode: string, params: Record<string, number>) => void;
  autoCompile: (userIdea: string) => Promise<void>;
  compileToWorkflow: () => CompiledWorkflowManifest;
  deploy: () => Promise<void>;
}

let idCounter = 1;
const getId = () => `agent_${idCounter++}`;

export const useWorkflowStore = create<WorkflowStore>((set, get) => ({
  nodes: [],
  edges: [],
  workflowName: "untitled_workflow",
  nodeCounter: 0,
  toast: { visible: false, message: "", type: "info" },
  systemAlert: { visible: false, nodeId: "", dump: "" },
  deploying: false,
  controlWs: null,
  enabledSkills: ["skills.web_scraper"],
  setEnabledSkills: (skills) => set({ enabledSkills: skills }),
  toggleSkill: (skillId) =>
    set((state) => ({
      enabledSkills: state.enabledSkills.includes(skillId)
        ? state.enabledSkills.filter((s) => s !== skillId)
        : [...state.enabledSkills, skillId],
    })),
  enabledRoles: ["System_Architect", "Python_Coder", "Code_Reviewer"],
  setEnabledRoles: (roles) => set({ enabledRoles: roles }),
  toggleRole: (roleId) =>
    set((state) => ({
      enabledRoles: state.enabledRoles.includes(roleId)
        ? state.enabledRoles.filter((r) => r !== roleId)
        : [...state.enabledRoles, roleId],
    })),
  availableRoles: [],
  availableSkills: [],
  fetchCatalogs: async () => {
    try {
      const res = await axios.get(`${AIOS_API_URL}/api/registry/catalogs`);
      set({
        availableRoles: res.data.roles || [],
        availableSkills: res.data.skills || [],
      });
    } catch (err) {
      console.error("[AIOS] Failed to fetch catalogs from backend", err);
    }
  },

  setNodes: (nodes) => set({ nodes }),
  setEdges: (edges) => set({ edges }),

  addNode: (position) => {
    const id = getId();
    const newNode: Node = {
      id,
      type: "agentNode",
      position,
      data: {
        label: `Agent ${get().nodeCounter + 1}`,
        role: "",
        blueprintId: "",
        subscribeTopic: "",
        publishTopic: "",
        userParams: {},
      },
    };
    set((state) => ({
      nodes: [...state.nodes, newNode],
      nodeCounter: state.nodeCounter + 1,
    }));
  },

  updateNodeData: (id, data) => {
    set((state) => ({
      nodes: state.nodes.map((node) =>
        node.id === id ? { ...node, data: { ...node.data, ...data } } : node
      ),
    }));
  },

  removeNode: (id) => {
    set((state) => ({
      nodes: state.nodes.filter((node) => node.id !== id),
      edges: state.edges.filter(
        (edge) => edge.source !== id && edge.target !== id
      ),
    }));
  },

  onConnect: (connection) => {
    set((state) => ({
      edges: addEdge(
        {
          ...connection,
          animated: true,
          style: { stroke: "#00f0ff", strokeWidth: 2 },
        },
        state.edges
      ),
    }));
  },

  setWorkflowName: (name) => set({ workflowName: name }),

  showToast: (message, type = "info") => {
    set({ toast: { visible: true, message, type } });
    setTimeout(() => get().hideToast(), 4000);
  },

  hideToast: () => set({ toast: { visible: false, message: "", type: "info" } }),

  triggerSystemAlert: (nodeId, dump) =>
    set({ systemAlert: { visible: true, nodeId, dump } }),

  dismissSystemAlert: () =>
    set({ systemAlert: { visible: false, nodeId: "", dump: "" } }),

  setControlWs: (ws) => set({ controlWs: ws }),

  /**
   * God Hand Protocol — 热补丁参数到运行中的沙箱智能体。
   * 通过控制 WebSocket 发送 HOT_PATCH_PARAM 指令，
   * 后端将参数写入 VFS 配置文件并广播 EventBus 通知。
   */
  hotPatchParam: (targetNode, params) => {
    const ws = get().controlWs;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn("[AIOS] Control WebSocket not connected. Cannot hot-patch.");
      get().showToast("控制通道未连接，无法热补丁", "error");
      return;
    }
    const message = JSON.stringify({
      action: "HOT_PATCH_PARAM",
      targetNode,
      params,
    });
    ws.send(message);
    console.log(`[AIOS] Hot-patch sent: ${targetNode}`, params);
  },

  /**
   * 一键生成拓扑 — 调用后端 LLM 动态编译 DAG。
   * 发送用户需求 + enabledSkills + enabledRoles 到 /api/workflow/compile，
   * 后端 TopologyCompiler 调用 LLM 返回 {nodes, edges} JSON，
   * 前端映射为 React Flow 节点/边并自动布局。
   * 失败时降级为本地演示拓扑，确保界面不白屏。
   */
  autoCompile: async (userIdea: string) => {
    const { showToast } = get();
    const idea = userIdea.trim() || "通用任务";
    set({ workflowName: idea.substring(0, 20).replace(/\s+/g, "_") });

    try {
      showToast("正在请示架构师，请稍候...", "info");

      // 调用后端拓扑编译 API，设置较长的超时时间
      const res = await axios.post(
        `${AIOS_API_URL}/api/workflow/compile`,
        {
          prompt: idea,
          enabledSkills: get().enabledSkills,
          enabledRoles: get().enabledRoles,
        },
        { timeout: 120000 }
      );

      let rawData = res.data;
      // 兼容后端返回字符串包裹 ```json 的情况
      if (typeof rawData === "string") {
        const cleaned = rawData
          .replace(/```json/g, "")
          .replace(/```/g, "")
          .trim();
        rawData = JSON.parse(cleaned);
      }

      const { nodes = [], edges = [] } = rawData;

      if (nodes.length === 0) throw new Error("后端返回了空节点");

      // 动态分配坐标
      const flowNodes: Node[] = nodes.map((n: any, i: number) => ({
        id: n.id || `agent_${i + 1}`,
        type: "agentNode",
        position: {
          x: 100 + (i % 3) * 350,
          y: 150 + Math.floor(i / 3) * 200 + (i % 2 === 0 ? 50 : 0),
        },
        data: {
          label: n.id || `Node ${i + 1}`,
          role: n.role || "未分配职责",
          blueprintId: n.blueprintId || "agentNode",
          subscribeTopic: "",
          publishTopic: "",
          userParams: n.userParams || {},
        },
      }));

      const flowEdges: Edge[] = edges.map((e: any, i: number) => ({
        id: `e_${e.source}_${e.target}_${i}`,
        source: e.source,
        target: e.target,
        animated: true,
        style: { stroke: "#00f0ff", strokeWidth: 2 },
      }));

      set({ nodes: flowNodes, edges: flowEdges, nodeCounter: flowNodes.length });
      showToast(
        `架构师规划完毕！生成 ${flowNodes.length} 个并发节点`,
        "success"
      );
    } catch (err) {
      console.error("[AIOS] Auto-compile failed, using fallback mock:", err);
      showToast("后端响应超时或失败，已降级为本地演示拓扑", "error");

      // 兜底逻辑：即使失败也不让界面白屏
      const fallbackNodes: Node[] = [
        {
          id: "agent_1",
          type: "agentNode",
          position: { x: 100, y: 200 },
          data: {
            label: "Fallback 1",
            role: "采集",
            blueprintId: "agentNode",
            subscribeTopic: "",
            publishTopic: "",
            userParams: {},
          },
        },
        {
          id: "agent_2",
          type: "agentNode",
          position: { x: 450, y: 200 },
          data: {
            label: "Fallback 2",
            role: "处理",
            blueprintId: "agentNode",
            subscribeTopic: "",
            publishTopic: "",
            userParams: {},
          },
        },
      ];
      const fallbackEdges: Edge[] = [
        {
          id: "e_1_2",
          source: "agent_1",
          target: "agent_2",
          animated: true,
          style: { stroke: "#00f0ff", strokeWidth: 2 },
        },
      ];
      set({
        nodes: fallbackNodes,
        edges: fallbackEdges,
        nodeCounter: 2,
      });
    }
  },

  /**
   * 拓扑编译核心逻辑 — 将 React Flow 图编译为 WorkflowManifest。
   *
   * 遍历 edges，为每条边自动生成 topic：
   *   - source 节点获得 publishTopic = "topic_{sourceId}_{targetId}"
   *   - target 节点获得 subscribeTopic = "topic_{sourceId}_{targetId}"
   *
   * 如果一个节点有多条出边，publishTopic 用逗号拼接；
   * 如果一个节点有多条入边，subscribeTopic 用逗号拼接。
   */
  compileToWorkflow: () => {
    const { nodes, edges, workflowName } = get();

    // 构建每个节点的 publish/subscribe topic 映射
    const publishMap = new Map<string, string[]>();
    const subscribeMap = new Map<string, string[]>();

    for (const edge of edges) {
      const topicName = `topic_${edge.source}_${edge.target}`;

      if (!publishMap.has(edge.source)) publishMap.set(edge.source, []);
      publishMap.get(edge.source)!.push(topicName);

      if (!subscribeMap.has(edge.target)) subscribeMap.set(edge.target, []);
      subscribeMap.get(edge.target)!.push(topicName);
    }

    // 编译节点
    const compiledNodes: CompiledWorkflowNode[] = nodes.map((node) => {
      const d = node.data as AgentNodeData;
      const publishTopics = publishMap.get(node.id) ?? [];
      const subscribeTopics = subscribeMap.get(node.id) ?? [];

      return {
        instanceId: node.id,
        blueprintId: d.blueprintId || node.id,
        role: d.role || "",
        subscribeTopic: subscribeTopics.join(","),
        publishTopic: publishTopics.join(","),
        userParams: d.userParams || {},
      };
    });

    const manifest: CompiledWorkflowManifest = {
      workflowName,
      nodes: compiledNodes,
      enabledSkills: get().enabledSkills,
      enabledRoles: get().enabledRoles,
    };

    return manifest;
  },

  deploy: async () => {
    const { compileToWorkflow, showToast } = get();
    set({ deploying: true });

    try {
      const manifest = compileToWorkflow();

      // 打印编译后的 JSON 到控制台
      console.log(
        "[AIOS] Compiled WorkflowManifest:\n",
        JSON.stringify(manifest, null, 2)
      );

      // 发送到 AIOS 后端
      const response = await axios.post(
        `${AIOS_API_URL}/api/workflow/deploy`,
        manifest,
        {
          timeout: 30000,
          headers: { Authorization: "Bearer AIOS-SUPER-SECRET-KEY" },
        }
      );

      console.log("[AIOS] Deploy response:", response.data);
      showToast("已将工作流拓扑发送至 AIOS 内核！", "success");
    } catch (err) {
      // 即使后端不可达，也打印编译结果供调试
      const manifest = compileToWorkflow();
      console.warn(
        "[AIOS] Backend unreachable. Compiled manifest for reference:\n",
        JSON.stringify(manifest, null, 2)
      );

      if (axios.isAxiosError(err) && err.code === "ERR_NETWORK") {
        showToast("后端未连接，拓扑已编译（见控制台）", "error");
      } else {
        showToast("部署失败，请检查后端服务", "error");
      }
    } finally {
      set({ deploying: false });
    }
  },
}));
