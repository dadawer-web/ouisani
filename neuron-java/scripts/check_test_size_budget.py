#!/usr/bin/env python3
"""Enforce a ratcheting Java test-file size budget.

借鉴 jcode/scripts/check_test_size_budget.py，适配 Java。

Policy:
- src/test/java/ 下超过 LOC 阈值的测试文件被基线追踪
- 已追踪的超标测试文件不得增长
- 不得引入新的超标测试文件
- `--update` 仅在主动清理后刷新基线

注：neuron-java 当前 src/test/ 目录尚未建立，基线初始为空。
此 checker 作为前置门禁存在——一旦开始写测试，立刻受预算约束。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
BASELINE_FILE = REPO_ROOT / "scripts" / "budgets" / "test_size_budget.json"
DEFAULT_THRESHOLD = 1200
SCAN_ROOTS = (REPO_ROOT / "src" / "test" / "java",)

SKIP_FILENAMES = {"module-info.java", "package-info.java"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", action="store_true", help="refresh the baseline")
    parser.add_argument(
        "--threshold",
        type=int,
        default=None,
        help=f"override threshold (default: {DEFAULT_THRESHOLD})",
    )
    return parser.parse_args()


def is_test_java_file(path: Path) -> bool:
    if path.suffix != ".java":
        return False
    if path.name in SKIP_FILENAMES:
        return False
    rel = path.relative_to(REPO_ROOT).as_posix()
    # 必须在 src/test/java 下
    return "/src/test/java/" in rel or rel.startswith("src/test/java/")


def java_file_line_count(path: Path) -> int:
    with path.open("r", encoding="utf-8", errors="ignore") as handle:
        return sum(1 for _ in handle)


def current_oversized_files(threshold: int) -> dict[str, int]:
    files: dict[str, int] = {}
    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            if not is_test_java_file(path):
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
        not isinstance(k, str) or not isinstance(v, int) or v <= 0 for k, v in tracked.items()
    ):
        raise SystemExit(f"error: invalid tracked_files in {BASELINE_FILE}")
    return data


def write_baseline(threshold: int, tracked_files: dict[str, int]) -> None:
    BASELINE_FILE.parent.mkdir(parents=True, exist_ok=True)
    BASELINE_FILE.write_text(
        json.dumps(
            {"version": 1, "threshold_loc": threshold, "tracked_files": tracked_files},
            indent=2,
            sort_keys=True,
        )
        + "\n",
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
            "Updated test-size baseline: "
            f"tracked={len(baseline['tracked_files'])} -> {len(current)} oversized test files"
        )
        return 0

    tracked: dict[str, int] = baseline["tracked_files"]
    regressions: list[str] = []
    improvements: list[str] = []

    for path, lines in sorted(current.items()):
        old_lines = tracked.get(path)
        if old_lines is None:
            regressions.append(f"new oversized test file exceeds {threshold} LOC: {path} ({lines} LOC)")
        elif lines > old_lines:
            regressions.append(f"oversized test file grew: {path} ({old_lines} -> {lines} LOC)")
        elif lines < old_lines:
            improvements.append(f"oversized test file shrank: {path} ({old_lines} -> {lines} LOC)")

    for path, old_lines in sorted(tracked.items()):
        if path not in current:
            improvements.append(
                f"oversized test file no longer exceeds {threshold} LOC: {path} ({old_lines} -> OK)"
            )

    if regressions:
        print("Test-size budget exceeded:", file=sys.stderr)
        for entry in regressions:
            print(f"  - {entry}", file=sys.stderr)
        print("Run scripts/check_test_size_budget.py --update only after intentional cleanup.", file=sys.stderr)
        return 1

    if improvements:
        print("Test-size budget improved:")
        for entry in improvements:
            print(f"  - {entry}")
        print("Consider running: scripts/check_test_size_budget.py --update")
    else:
        print(f"Test-size budget OK: tracked={len(tracked)} threshold={threshold}LOC")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
