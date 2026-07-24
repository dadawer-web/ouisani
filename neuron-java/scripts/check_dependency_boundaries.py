#!/usr/bin/env python3
"""Enforce Java package-level dependency boundaries.

借鉴 jcode/scripts/check_dependency_boundaries.py，适配 Java 包结构。

jcode 守卫"type crate 不得依赖 runtime/provider/tui crate"。
Java 没有独立 crate，但有等价的"契约包不得依赖实现包"原则：

规则（CONTRACT_RULES）：
- 契约/DTO 包（syscall/schema, llm 接口, permission 规则）必须保持纯数据/接口
- 这些包不得 import 任何实现包：drivers.*, operator.*, user.*

棘轮机制：
- KNOWN_VIOLATIONS 列出既有违规（报告但不阻塞，应逐步消除）
- 新增违规立即阻塞
- 消除已知违规后会报告改进
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCAN_ROOT = REPO_ROOT / "src" / "main" / "java"

# 契约包前缀（相对 src/main/java 的 POSIX 路径）→ 禁止 import 的包前缀
# 路径前缀匹配，末尾 / 确保不误匹配（如 core/llm 不会匹配 core/llmdecode）
CONTRACT_RULES: dict[str, list[str]] = {
    # Syscall DTO payloads — 纯数据契约
    "com/ouisani/aios/core/syscall/schema/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # LLM 抽象接口层 — 内核契约
    "com/ouisani/aios/core/llm/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # Permission 契约层
    "com/ouisani/aios/core/permission/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # 工具定义契约
    "com/ouisani/aios/core/tool/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # A2A 协议契约
    "com/ouisani/aios/core/a2a/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # VersionedPlan 契约层 — 内核任务图，不得依赖用户态实现
    "com/ouisani/aios/core/plan/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # Ranking 纯函数契约层 — 镜像 core/plan 布局，不得依赖用户态实现
    "com/ouisani/aios/core/ranking/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
    # 统一快照契约层 — EnvironmentSnapshot 抽象，不得依赖用户态实现
    # (镜像 ToolSdk DIP 模式：core/snapshot 定义 SnapshotCapturer 接口，user 态实现并注册)
    "com/ouisani/aios/core/snapshot/": [
        "com.ouisani.aios.drivers.",
        "com.ouisani.aios.operator.",
        "com.ouisani.aios.user.",
    ],
}

# 这些文件即使位于契约包内也豁免（如 LlmRouter.java 在 core/llm/ 下但本身就是路由实现）
# 用相对 REPO_ROOT 的 POSIX 路径
EXEMPT_FILES: set[str] = {
    "src/main/java/com/ouisani/aios/core/llm/LlmRouter.java",  # 路由实现，需要感知 Provider
    "src/main/java/com/ouisani/aios/core/llm/LlmRouterHolder.java",  # 全局持有器
    "src/main/java/com/ouisani/aios/core/llm/SpeculativePredictor.java",  # 推测执行器
    "src/main/java/com/ouisani/aios/core/llm/InstructionDecoder.java",  # 指令解码实现
    "src/main/java/com/ouisani/aios/core/llm/NumaOomException.java",  # 异常类但需引用 Provider
}

# 棘轮式已知违规：报告但不阻塞 CI；消除后会提示改进。
# 格式："文件相对路径 -> import 的包前缀"
# 新增违规不在本表中 → 立即阻塞。
#
# 2026-06-28: core/tool → user.sdk.AiosSdk 的 6 个违规已通过 DIP 修复
# （提取 ToolSdk 接口，AiosSdk implements ToolSdk）。清空此表锁定改进。
#
# 2026-07-10: core/snapshot 加入契约守卫。SnapshotManager 既有 2 处 user.container
# import 属历史遗留（CRIU 恢复需 ContainerRuntime 重新分配沙箱），棘轮锁定，
# 后续应由 ContainerRuntime 抽象接口（core 定义）消除。新增 EnvironmentSnapshot
# 抽象层严格走 DIP，不得新增 user 态 import。
KNOWN_VIOLATIONS: set[str] = {
    "src/main/java/com/ouisani/aios/core/snapshot/SnapshotManager.java -> com.ouisani.aios.user.container.AgentImageConfig",
    "src/main/java/com/ouisani/aios/core/snapshot/SnapshotManager.java -> com.ouisani.aios.user.container.ContainerRuntime",
}

IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;", re.MULTILINE)
SKIP_FILENAMES = {"module-info.java", "package-info.java"}


def find_contract_files() -> list[tuple[Path, str, list[str]]]:
    """返回 [(file_path, contract_prefix, forbidden_imports)]"""
    result: list[tuple[Path, str, list[str]]] = []
    if not SCAN_ROOT.exists():
        return result
    for path in sorted(SCAN_ROOT.rglob("*.java")):
        if path.name in SKIP_FILENAMES:
            continue
        rel = path.relative_to(REPO_ROOT).as_posix()
        if rel in EXEMPT_FILES:
            continue
        # 转换为包路径前缀（src/main/java/com/ouisani/... -> com/ouisani/...）
        if not rel.startswith("src/main/java/"):
            continue
        pkg_path = rel[len("src/main/java/"):]
        for contract_prefix, forbidden in CONTRACT_RULES.items():
            if pkg_path.startswith(contract_prefix):
                result.append((path, contract_prefix, forbidden))
                break
    return result


def extract_imports(text: str) -> list[str]:
    return [m.group(2) for m in IMPORT_RE.finditer(text)]


def check_boundaries() -> tuple[list[str], list[str], list[str]]:
    """返回 (new_violations, known_violations_still_present, removed_known)。

    每个 violation 字符串格式："{rel}: contract file under '{prefix}' "
    "imports implementation package '{imp}' (forbidden: {bad}*)"
    """
    current_pairs: list[tuple[str, str]] = []  # (violation_str, key_str)
    current_keys: set[str] = set()
    for path, contract_prefix, forbidden in find_contract_files():
        text = path.read_text(encoding="utf-8", errors="ignore")
        imports = extract_imports(text)
        rel = path.relative_to(REPO_ROOT).as_posix()
        for imp in imports:
            for bad_prefix in forbidden:
                if imp.startswith(bad_prefix):
                    violation = (
                        f"{rel}: contract file under '{contract_prefix}' "
                        f"imports implementation package '{imp}' "
                        f"(forbidden: {bad_prefix}*)"
                    )
                    key = f"{rel} -> {imp}"
                    current_pairs.append((violation, key))
                    current_keys.add(key)
                    break  # 同一 import 只算一次

    new_violations: list[str] = []
    known_present: list[str] = []
    for violation, key in current_pairs:
        if key in KNOWN_VIOLATIONS:
            known_present.append(violation)
        else:
            new_violations.append(violation)

    removed = [k for k in KNOWN_VIOLATIONS if k not in current_keys]
    return new_violations, known_present, removed


def main() -> int:
    new_violations, known_present, removed = check_boundaries()

    if new_violations:
        print("NEW dependency boundary violations (block CI):", file=sys.stderr)
        for v in new_violations:
            print(f"  - {v}", file=sys.stderr)
        print(
            "\nContract packages (DTO/interface) must not import implementation packages "
            "(drivers/operator/user). Move the dependency the other way, or extract a new "
            "contract in the contract package.",
            file=sys.stderr,
        )
        print(f"\nNew violations: {len(new_violations)}", file=sys.stderr)
        if known_present:
            print(f"Known violations (pre-existing, not blocking): {len(known_present)}", file=sys.stderr)
        return 1

    if known_present:
        print(f"Dependency boundaries: {len(known_present)} KNOWN violations (tracked, not blocking):")
        for v in known_present:
            print(f"  - {v}")
        if removed:
            print(f"\nIMPROVEMENT: {len(removed)} known violations eliminated:")
            for k in sorted(removed):
                print(f"  - {k}")
            print("Consider removing them from KNOWN_VIOLATIONS in scripts/check_dependency_boundaries.py")
        else:
            print(f"\nNo new violations. {len(known_present)} known violations remain to be cleaned up.")
        return 0

    if removed:
        print(f"Dependency boundaries OK. IMPROVEMENT: {len(removed)} known violations eliminated:")
        for k in sorted(removed):
            print(f"  - {k}")
        print("Consider removing them from KNOWN_VIOLATIONS in scripts/check_dependency_boundaries.py")
        return 0

    print("Dependency boundaries OK: no contract package imports implementation packages.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
