#!/usr/bin/env python3
"""Enforce a ratcheting Java file-size budget.

借鉴 jcode/scripts/check_code_size_budget.py，适配 Java。

Policy:
- 生产 Java 文件（src/main/java/）超过 LOC 阈值即被基线追踪
- 已追踪的超标文件不得增长
- 不得引入新的超标生产文件
- 文件缩减或下穿阈值时报告改进
- `--update` 仅在主动清理后刷新基线
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
BASELINE_FILE = REPO_ROOT / "scripts" / "budgets" / "code_size_budget.json"
DEFAULT_THRESHOLD = 1000
SCAN_ROOTS = (REPO_ROOT / "src" / "main" / "java",)

# 这些文件不是真正的类实现，应排除
SKIP_FILENAMES = {"module-info.java", "package-info.java"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--update",
        action="store_true",
        help="refresh the baseline to the current oversized-file set",
    )
    parser.add_argument(
        "--threshold",
        type=int,
        default=None,
        help=f"override threshold (default: {DEFAULT_THRESHOLD})",
    )
    return parser.parse_args()


def is_production_java_file(path: Path) -> bool:
    if path.suffix != ".java":
        return False
    if path.name in SKIP_FILENAMES:
        return False
    rel = path.relative_to(REPO_ROOT).as_posix()
    # 生产代码必须在 src/main/java 下，且不在 test 子路径里
    if "/test/" in rel or rel.startswith("test/"):
        return False
    # 文件名以 Test/Tests/IT 结尾的视为测试，即使误放在 main 下也排除
    stem = path.stem
    if stem.endswith("Test") or stem.endswith("Tests") or stem.endswith("IT"):
        return False
    return True


def java_file_line_count(path: Path) -> int:
    with path.open("r", encoding="utf-8", errors="ignore") as handle:
        return sum(1 for _ in handle)


def current_oversized_files(threshold: int) -> dict[str, int]:
    files: dict[str, int] = {}
    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            if not is_production_java_file(path):
                continue
            line_count = java_file_line_count(path)
            if line_count > threshold:
                files[path.relative_to(REPO_ROOT).as_posix()] = line_count
    return files


def load_baseline() -> dict[str, Any]:
    if not BASELINE_FILE.exists():
        return {"version": 1, "threshold_loc": DEFAULT_THRESHOLD, "tracked_files": {}}
    data = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"error: invalid baseline file format: {BASELINE_FILE}")
    threshold = data.get("threshold_loc")
    tracked = data.get("tracked_files")
    if not isinstance(threshold, int) or threshold <= 0:
        raise SystemExit(f"error: invalid threshold_loc in {BASELINE_FILE}")
    if not isinstance(tracked, dict) or any(
        not isinstance(k, str) or not isinstance(v, int) or v <= 0
        for k, v in tracked.items()
    ):
        raise SystemExit(f"error: invalid tracked_files in {BASELINE_FILE}")
    return data


def write_baseline(threshold: int, tracked_files: dict[str, int]) -> None:
    BASELINE_FILE.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 1,
        "threshold_loc": threshold,
        "tracked_files": tracked_files,
    }
    BASELINE_FILE.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    baseline = load_baseline()
    threshold = args.threshold if args.threshold is not None else baseline["threshold_loc"]
    current = current_oversized_files(threshold)

    if args.update:
        write_baseline(threshold, current)
        print(
            "Updated code-size baseline: "
            f"tracked={len(baseline['tracked_files'])} -> {len(current)} oversized files "
            f"(threshold={threshold})"
        )
        return 0

    tracked: dict[str, int] = baseline["tracked_files"]
    regressions: list[str] = []
    improvements: list[str] = []

    for path, lines in sorted(current.items()):
        old_lines = tracked.get(path)
        if old_lines is None:
            regressions.append(
                f"new oversized file exceeds {threshold} LOC: {path} ({lines} LOC)"
            )
        elif lines > old_lines:
            regressions.append(
                f"oversized file grew: {path} ({old_lines} -> {lines} LOC)"
            )
        elif lines < old_lines:
            improvements.append(f"oversized file shrank: {path} ({old_lines} -> {lines} LOC)")

    for path, old_lines in sorted(tracked.items()):
        if path not in current:
            improvements.append(
                f"oversized file no longer exceeds {threshold} LOC: {path} ({old_lines} -> OK)"
            )

    if regressions:
        print(
            "Code-size budget exceeded. Existing oversized Java files must shrink or stay flat, "
            "and new oversized production files are not allowed:",
            file=sys.stderr,
        )
        for entry in regressions:
            print(f"  - {entry}", file=sys.stderr)
        print(
            "Run scripts/check_code_size_budget.py --update only after intentional cleanup.",
            file=sys.stderr,
        )
        return 1

    if improvements:
        print("Code-size budget improved:")
        for entry in improvements:
            print(f"  - {entry}")
        print("Consider running: scripts/check_code_size_budget.py --update")
    else:
        print(
            "Code-size budget OK: "
            f"tracked={len(tracked)} threshold={threshold}LOC no oversized-file regressions"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
