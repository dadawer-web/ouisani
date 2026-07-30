import { useEffect, useState } from "react";
import { AIOS_WS_URL, AIOS_API_URL } from "./config";
import { CheckCircle2, AlertCircle, X, AlertTriangle, Code, Play, Loader2, Workflow } from "lucide-react";

import SessionSidebar from "@/components/SessionSidebar";
import ChatSurface from "@/components/ChatSurface";
import ToolsOverlay, { type Surface } from "@/components/ToolsOverlay";
import KernelStatusBar from "@/components/KernelStatusBar";
import ThemeToggle from "@/components/ThemeToggle";
import { useWorkflowStore } from "@/store/workflowStore";
import { useSystemStore } from "@/store/systemStore";
import { useTelemetryStore } from "@/store/telemetryStore";
import { useAlertsStore } from "@/store/alertsStore";
import { usePermissionStore } from "@/store/permissionStore";
import { useSessionStore } from "@/store/sessionStore";

const PLACEHOLDER_HTML =
  "<html><body style='display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#FAF9F5;color:#87736D;font-family:Inter,system-ui,sans-serif'><p style='opacity:0.5'>Awaiting agent render signal…</p></body></html>";

export default function App() {
  const toast = useWorkflowStore((s) => s.toast);
  const hideToast = useWorkflowStore((s) => s.hideToast);
  const systemAlert = useWorkflowStore((s) => s.systemAlert);
  const triggerSystemAlert = useWorkflowStore((s) => s.triggerSystemAlert);
  const dismissSystemAlert = useWorkflowStore((s) => s.dismissSystemAlert);
  const setControlWs = useWorkflowStore((s) => s.setControlWs);

  const kernelConnected = useSystemStore((s) => s.connected);
  const activeWorkflowNode = useSystemStore((s) => s.activeWorkflowNode);

  // 会话标题（顶栏展示）
  const sessions = useSessionStore((s) => s.sessions);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const setActive = useSessionStore((s) => s.setActive);
  const activeTitle =
    sessions.find((s) => s.id === activeSessionId)?.title ?? "New Chat";

  // overlay 工具面（null = 不显示 overlay，回到对话）
  const [activeTool, setActiveTool] = useState<Surface | null>(null);

  const [debugCode, setDebugCode] = useState<string | null>(null);
  // ── 人机审批门：修复指令输入 + 恢复中状态 ──
  const [recoveryGuidance, setRecoveryGuidance] = useState("");
  const [isResuming, setIsResuming] = useState(false);
  // ── 全息视界：刷新按钮 loading ──
  const [isRefreshingVfs, setIsRefreshingVfs] = useState(false);

  // ── 全息视界：UI Sandbox 渲染状态（单一归属：仅 App 拥有 WS→setHtmlPayload） ──
  const [htmlPayload, setHtmlPayload] = useState<string>(PLACEHOLDER_HTML);

  // ── mount：拉取资产 + 激活自愈/AST 遥测流 + 恢复最近会话 ──
  useEffect(() => {
    useWorkflowStore.getState().fetchCatalogs();
    useTelemetryStore.getState().connect();
    useAlertsStore.getState().connect();
    usePermissionStore.getState().connect();
    // 恢复最近一次会话（若无 active）
    const { sessions: loaded, activeSessionId: cur } = useSessionStore.getState();
    if (!cur && loaded.length > 0) setActive(loaded[0].id);
  }, [setActive]);

  // ── WebSocket: 监听内核自愈告警 + UI_RENDER 渲染信号（含心跳+重连） ──
  useEffect(() => {
    let ws: WebSocket | null = null;
    let pingTimer: ReturnType<typeof setInterval> | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let isManualClose = false;

    const connect = () => {
      ws = new WebSocket(`${AIOS_WS_URL}/api/dashboard/alerts?token=AIOS-SUPER-SECRET-KEY`);

      ws.onopen = () => {
        console.log("[AIOS] Dashboard alert WebSocket connected");
        // 30 秒心跳
        pingTimer = setInterval(() => {
          if (ws?.readyState === WebSocket.OPEN) ws.send("PING");
        }, 30000);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === "HUMAN_INTERVENTION") {
            triggerSystemAlert(data.nodeId || "unknown", data.dump || "No stacktrace available");
          } else if (data.type === "UI_RENDER") {
            // ── 全息视界：拦截 UI_RENDER 信号，注入 iframe ──
            const htmlContent = data.html_content || data.payload?.html_content || "";
            if (htmlContent) {
              setHtmlPayload(htmlContent);
              console.log("[AIOS] UI_RENDER received, sandbox updated");
            }
          }
        } catch {
          console.warn("[AIOS] Failed to parse alert message:", event.data);
        }
      };

      ws.onerror = () => {
        console.warn("[AIOS] Dashboard alert WebSocket error");
      };

      ws.onclose = () => {
        console.log("[AIOS] Dashboard alert WebSocket closed");
        if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
        // 3 秒后自动重连
        if (!isManualClose) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      };
    };

    connect();

    return () => {
      isManualClose = true;
      if (pingTimer) clearInterval(pingTimer);
      if (reconnectTimer) clearTimeout(reconnectTimer);
      ws?.close();
    };
  }, [triggerSystemAlert]);

  // ── WebSocket: God Hand 控制通道（热补丁参数，含心跳+重连） ──
  useEffect(() => {
    let ws: WebSocket | null = null;
    let pingTimer: ReturnType<typeof setInterval> | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let isManualClose = false;

    const connect = () => {
      ws = new WebSocket(`${AIOS_WS_URL}/api/workflow/control?token=AIOS-SUPER-SECRET-KEY`);

      ws.onopen = () => {
        console.log("[AIOS] God Hand control WebSocket connected");
        setControlWs(ws);
        pingTimer = setInterval(() => {
          if (ws?.readyState === WebSocket.OPEN) ws.send("PING");
        }, 30000);
      };

      ws.onclose = () => {
        console.log("[AIOS] God Hand control WebSocket closed");
        setControlWs(null);
        if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
        if (!isManualClose) {
          reconnectTimer = setTimeout(connect, 3000);
        }
      };

      ws.onerror = () => {
        console.warn("[AIOS] God Hand control WebSocket error");
      };
    };

    connect();

    return () => {
      isManualClose = true;
      if (pingTimer) clearInterval(pingTimer);
      if (reconnectTimer) clearTimeout(reconnectTimer);
      ws?.close();
      setControlWs(null);
    };
  }, [setControlWs]);

  // ── 连接内核监控流 ──
  useEffect(() => {
    useSystemStore.getState().connectKernel();
  }, []);

  // ── 查看现场：从 VFS 读取节点代码 ──
  const handleOpenDebugView = async () => {
    const nodeId = systemAlert.nodeId;
    try {
      const response = await fetch(
        `${AIOS_API_URL}/api/vfs/read?path=/factory/${nodeId}.py&token=AIOS-SUPER-SECRET-KEY`,
      );
      if (response.ok) {
        const text = await response.text();
        setDebugCode(text);
      } else {
        setDebugCode(`// Failed to load code for node: ${nodeId}\n// HTTP ${response.status}`);
      }
    } catch {
      setDebugCode(`// Failed to load code for node: ${nodeId}\n// Backend unreachable`);
    }
  };

  // ── 人机审批门：下发 SIGCONT 恢复指令 ──
  const handleResumeNode = async () => {
    const nodeId = systemAlert.nodeId;
    setIsResuming(true);
    try {
      const response = await fetch(
        `${AIOS_API_URL}/api/recovery/${nodeId}/resume`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            guidance: recoveryGuidance,
            token: "AIOS-SUPER-SECRET-KEY",
          }),
        },
      );
      if (response.ok) {
        console.log("[AIOS] SIGCONT sent, node resuming:", nodeId);
        dismissSystemAlert();
        setRecoveryGuidance("");
        setDebugCode(null);
      } else {
        console.error("[AIOS] Resume failed:", response.status);
      }
    } catch (e) {
      console.error("[AIOS] Resume request failed:", e);
    } finally {
      setIsResuming(false);
    }
  };

  // ── 全息视界：从 VFS 主动拉取最新 HTML 产物 ──
  const handleRefreshVfs = async () => {
    setIsRefreshingVfs(true);
    try {
      // 尝试从 VFS 读取 index.html（常见产物路径）
      const response = await fetch(
        `${AIOS_API_URL}/api/vfs/read?path=/factory/index.html&token=AIOS-SUPER-SECRET-KEY`,
      );
      if (response.ok) {
        const html = await response.text();
        if (html && html.trim().length > 0) {
          setHtmlPayload(html);
          console.log("[AIOS] VFS refresh: index.html loaded");
        }
      }
    } catch (e) {
      console.warn("[AIOS] VFS refresh failed:", e);
    } finally {
      setIsRefreshingVfs(false);
    }
  };

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-background">
      {/* ═══ 顶栏 (h-12) ═══ */}
      <header className="flex h-12 flex-shrink-0 items-center gap-4 border-b border-outline-variant/15 bg-surface px-4">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary text-on-primary">
            <Workflow className="h-4 w-4" />
          </div>
          <span className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface">
            AIOS Atelier
          </span>
        </div>
        <div className="h-4 w-px bg-outline-variant/30" />
        <span className="truncate font-headline text-sm font-semibold text-outline">
          {activeTitle}
        </span>

        <div className="ml-auto flex items-center gap-3">
          <KernelStatusBar />
          <div className="h-4 w-px bg-outline-variant/30" />
          <ThemeToggle />
        </div>
      </header>

      {/* ═══ 主体：侧栏 + 对话画布（overlay 覆盖其上） ═══ */}
      <div className="relative flex flex-1 overflow-hidden">
        <SessionSidebar onOpenTool={(s) => setActiveTool(s)} />

        <main className="flex-1 overflow-hidden bg-surface">
          <ChatSurface
            htmlPayload={htmlPayload}
            isRefreshingVfs={isRefreshingVfs}
            onRefreshVfs={handleRefreshVfs}
            onOpenTool={(s) => setActiveTool(s)}
          />
        </main>

        {/* 工具 overlay — 全画面覆盖主区，返回键退回对话 */}
        <ToolsOverlay
          active={activeTool}
          onChange={setActiveTool}
          htmlPayload={htmlPayload}
          isRefreshingVfs={isRefreshingVfs}
          onRefreshVfs={handleRefreshVfs}
        />
      </div>

      {/* ═══ 底部状态页脚 (h-8) ═══ */}
      <footer className="flex h-8 flex-shrink-0 items-center justify-between border-t border-outline-variant/15 bg-surface px-4 text-[10px] text-outline">
        <div className="flex items-center gap-2">
          <span
            className={`h-1.5 w-1.5 rounded-full ${kernelConnected ? "bg-tertiary animate-soft-pulse" : "bg-error"}`}
          />
          <span className="uppercase tracking-tight">
            {kernelConnected ? "Kernel Online" : "Kernel Offline"}
          </span>
          {activeWorkflowNode && (
            <>
              <span className="text-outline/40">·</span>
              <span className="font-mono text-primary">active: {activeWorkflowNode}</span>
            </>
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className="font-mono uppercase tracking-tight">AIOS v1.0</span>
          <span className="text-outline/40">·</span>
          <span className="font-bold text-tertiary">Ready</span>
        </div>
      </footer>

      {/* ═══ Toast 通知 ═══ */}
      {toast.visible && (
        <div
          className={`fixed bottom-12 left-1/2 z-50 flex -translate-x-1/2 items-center gap-3 rounded-xl px-4 py-3 ambient-shadow backdrop-blur transition-all ${
            toast.type === "success"
              ? "bg-tertiary-container/20 text-on-surface"
              : toast.type === "error"
                ? "bg-error-container/30 text-on-surface"
                : "bg-surface-container-lowest text-on-surface"
          }`}
        >
          {toast.type === "success" ? (
            <CheckCircle2 className="h-5 w-5 text-tertiary" />
          ) : toast.type === "error" ? (
            <AlertCircle className="h-5 w-5 text-error" />
          ) : (
            <AlertCircle className="h-5 w-5 text-primary" />
          )}
          <span className="text-sm font-medium">{toast.message}</span>
          <button
            onClick={hideToast}
            className="ml-2 rounded p-0.5 text-outline transition-colors hover:text-on-surface"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* ═══ 系统告警弹窗 — AutoMedic 熔断时弹出，z-index 最高层 ═══ */}
      {systemAlert.visible && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-on-surface/40 backdrop-blur-sm">
          <div className="relative w-full max-w-2xl rounded-xl bg-surface-container-lowest ambient-shadow">
            {/* 标题栏 */}
            <div className="flex items-center gap-3 border-b border-outline-variant/20 px-6 py-4">
              <AlertTriangle className="h-6 w-6 animate-soft-pulse text-error" />
              <h2 className="font-headline text-lg font-bold text-error">
                HUMAN INTERVENTION REQUIRED
              </h2>
              <button
                onClick={dismissSystemAlert}
                className="ml-auto rounded-lg p-1 text-outline transition-colors hover:bg-surface-container-high hover:text-on-surface"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* 告警内容 */}
            <div className="space-y-4 px-6 py-5">
              <div className="flex items-center gap-2">
                <span className="text-sm font-semibold text-on-surface-variant">Node ID:</span>
                <code className="rounded-lg bg-error-container/30 px-2 py-0.5 font-mono text-sm text-error">
                  {systemAlert.nodeId}
                </code>
              </div>

              <div>
                <span className="text-sm font-semibold text-on-surface-variant">Error Stack:</span>
                <pre className="mt-2 max-h-40 overflow-auto rounded-lg bg-surface-dim p-3 font-mono text-xs leading-relaxed text-on-surface-variant">
                  {systemAlert.dump}
                </pre>
              </div>

              {/* 调试代码视图 */}
              {debugCode !== null && (
                <div>
                  <div className="flex items-center gap-2">
                    <Code className="h-4 w-4 text-primary" />
                    <span className="text-sm font-semibold text-on-surface-variant">VFS Source Code:</span>
                  </div>
                  <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-surface-dim p-3 font-mono text-xs leading-relaxed text-on-surface-variant">
                    {debugCode}
                  </pre>
                </div>
              )}

              {/* ── 人机审批门：修复指令输入区 ── */}
              <div>
                <label className="flex items-center gap-2 text-sm font-semibold text-tertiary">
                  <Play className="h-4 w-4" />
                  修复指令 (Guidance Prompt):
                </label>
                <textarea
                  value={recoveryGuidance}
                  onChange={(e) => setRecoveryGuidance(e.target.value)}
                  placeholder="输入修复指令，例如：请检查 Spring Boot 的端口配置，确保 8080 端口未被占用…"
                  rows={3}
                  className="mt-2 w-full resize-none rounded-lg bg-surface-container-low p-3 font-mono text-xs text-on-surface placeholder:text-outline/50 focus:outline-none ghost-border"
                />
              </div>
            </div>

            {/* 操作按钮 */}
            <div className="flex items-center justify-end gap-3 border-t border-outline-variant/20 px-6 py-4">
              <button
                onClick={dismissSystemAlert}
                className="rounded-lg bg-surface-container-high px-4 py-2 text-sm font-medium text-on-surface transition-colors hover:bg-surface-container-highest"
              >
                Dismiss
              </button>
              <button
                onClick={handleOpenDebugView}
                className="flex items-center gap-2 rounded-lg bg-surface-container-low px-4 py-2 text-sm font-bold text-primary transition-colors hover:bg-surface-container-high"
              >
                <Code className="h-4 w-4" />
                Open Debug View
              </button>
              {/* ── Resume 按钮：下发 SIGCONT 恢复执行 ── */}
              <button
                onClick={handleResumeNode}
                disabled={isResuming}
                className="flex items-center gap-2 rounded-lg btn-primary-ink px-4 py-2 text-sm font-bold text-on-primary transition-opacity hover:opacity-90 disabled:opacity-50"
              >
                {isResuming ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Play className="h-4 w-4" />
                )}
                Resume (SIGCONT)
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
