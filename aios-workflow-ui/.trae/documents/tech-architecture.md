## 1. 架构设计
```mermaid
graph TB
    "React Frontend" --> "AIOS Backend API"
    subgraph "Frontend"
        "React Flow Canvas"
        "AgentNode Component"
        "Sidebar Component"
        "Zustand Store"
    end
    subgraph "Backend (AIOS Java)"
        "TopologyCompiler API"
        "WorkflowEngine API"
    end
    "Zustand Store" --> "React Flow Canvas"
    "Sidebar Component" --> "Zustand Store"
```

## 2. 技术说明
- 前端：React@18 + TypeScript + TailwindCSS@3 + Vite
- 初始化工具：vite-init (react-ts 模板)
- 状态管理：Zustand
- 画布引擎：@xyflow/react (React Flow v12)
- 图标库：lucide-react
- HTTP 客户端：axios
- 后端：AIOS Java 内核 (已存在)

## 3. 路由定义
| 路由 | 用途 |
|------|------|
| / | 工作流画布主页面 |

## 4. API 定义
```typescript
// 部署工作流到 AIOS 后端
interface DeployWorkflowRequest {
  workflowName: string;
  nodes: WorkflowNodeConfig[];
  edges: WorkflowEdgeConfig[];
}

interface WorkflowNodeConfig {
  instanceId: string;
  blueprintId: string;
  userParams: Record<string, string>;
  subscribeTopic: string;
  publishTopic: string;
}

interface WorkflowEdgeConfig {
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
}

// POST /api/workflow/deploy
```

## 5. 数据模型
### 5.1 前端状态模型 (Zustand)
```typescript
interface WorkflowStore {
  nodes: Node[];
  edges: Edge[];
  workflowName: string;
  addNode: (position: XYPosition) => void;
  updateNodeData: (id: string, data: Partial<AgentNodeData>) => void;
  removeNode: (id: string) => void;
  onConnect: (connection: Connection) => void;
  deploy: () => Promise<void>;
}

interface AgentNodeData {
  label: string;
  role: string;
  blueprintId: string;
  subscribeTopic: string;
  publishTopic: string;
  userParams: Record<string, string>;
}
```
