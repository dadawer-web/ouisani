import { ShieldCheck, Check, Infinity as InfinityIcon, X } from "lucide-react";
import { usePermissionStore, type ApprovalDecision } from "@/store/permissionStore";
import { cn } from "@/lib/utils";

// ════════════════════════════════════════════════════════════════
//  PermissionApprovalPopup —— 工具权限审批弹窗（Standing Scoped Approvals）
//   由 permissionStore.pending 队列驱动；pending 空时自隐。
//   队首 ASK 展示三选项：
//     本次允许 (ALLOW_ONCE) / 永久允许此目标 (ALWAYS_TARGET) / 拒绝 (DENY)
//   ALWAYS_TARGET 时后端调 PermissionChecker.grantTargetApproval，后续同 target 不再弹。
//   模态化呈现（agent loop 阻塞等待回填），样式复用现有 token。
// ════════════════════════════════════════════════════════════════

export default function PermissionApprovalPopup() {
  const pending = usePermissionStore((s) => s.pending);
  const respond = usePermissionStore((s) => s.respond);

  if (pending.length === 0) return null;
  const ask = pending[0];
  const hasTarget = !!ask.target;

  const decide = (decision: ApprovalDecision) => respond(ask.requestId, decision);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-2xl bg-surface-container-lowest p-4 shadow-2xl ghost-border">
        {/* 标题 */}
        <div className="mb-3 flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-primary" />
          <span className="font-headline text-xs font-bold uppercase tracking-wider text-primary">
            工具权限审批
          </span>
          {pending.length > 1 && (
            <span className="ml-auto text-[10px] text-outline">
              +{pending.length - 1} 待审批
            </span>
          )}
        </div>

        {/* 主体 */}
        <div className="mb-3 rounded-lg bg-surface-container-high/50 px-3 py-2.5">
          <div className="text-sm font-semibold text-on-surface">
            允许{" "}
            <code className="rounded bg-surface-container-highest px-1.5 py-0.5 font-mono text-xs text-primary">
              {ask.toolName}
            </code>
            {hasTarget && (
              <>
                {" → "}
                <code className="rounded bg-surface-container-highest px-1.5 py-0.5 font-mono text-xs text-primary break-all">
                  {ask.target}
                </code>
              </>
            )}
            {" ?"}
          </div>
          {ask.description && (
            <p className="mt-1.5 text-[11px] leading-relaxed text-outline">
              {ask.description}
            </p>
          )}
          {ask.actionDigest && (
            <p className="mt-2 break-all font-mono text-[9px] text-outline/70">
              digest: {ask.actionDigest}
            </p>
          )}
        </div>

        {/* 三按钮 */}
        <div className="grid grid-cols-1 gap-1.5">
          <button
            onClick={() => decide("ALWAYS_TARGET")}
            disabled={!hasTarget}
            className={cn(
              "flex items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-xs font-bold transition-all",
              hasTarget
                ? "btn-primary-ink text-on-primary hover:opacity-90"
                : "cursor-not-allowed bg-surface-container-high text-outline",
            )}
            title={hasTarget ? "永久放行此 target，后续不再询问" : "该工具无 target 参数，无法永久授权"}
          >
            <InfinityIcon className="h-3.5 w-3.5" />
            永久允许此目标
          </button>
          <button
            onClick={() => decide("ALLOW_ONCE")}
            className="flex items-center justify-center gap-1.5 rounded-lg bg-surface-container-high px-3 py-2 text-xs font-bold text-on-surface transition-colors hover:bg-surface-container-highest"
          >
            <Check className="h-3.5 w-3.5" />
            本次允许
          </button>
          <button
            onClick={() => decide("DENY")}
            className="flex items-center justify-center gap-1.5 rounded-lg bg-error-container/25 px-3 py-2 text-xs font-bold text-error transition-colors hover:bg-error-container/40"
          >
            <X className="h-3.5 w-3.5" />
            拒绝
          </button>
        </div>
      </div>
    </div>
  );
}
