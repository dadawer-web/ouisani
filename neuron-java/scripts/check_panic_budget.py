#!/usr/bin/env python3
"""Enforce a ratcheting production panic-prone usage budget for Java.

借鉴 jcode/scripts/check_panic_budget.py，适配 Java。

Counts production Java occurrences of:
- `throw new RuntimeException|IllegalStateException|IllegalArgumentException|
   NullPointerException|UnsupportedOperationException|Error`
- `System.exit(`
- `Objects.requireNonNull(`  (panic on null)
- `.orElseThrow()`  (Optional no-arg form, throws NoSuchElementException)
- `assert `  (Java assert, often disabled at runtime)

Policy:
- 已追踪文件不得增加 panic-prone 用法计数
- 新生产文件不得引入 panic-prone 用法
- 总数不得增加
- `--update` 仅在主动清理后刷新基线
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
BASELINE_FILE = REPO_ROOT / "scripts" / "budgets" / "panic_budget.json"
SCAN_ROOTS = (REPO_ROOT / "src" / "main" / "java",)

# Java panic-prone patterns
PATTERN = re.compile(
    r"throw\s+new\s+"
    r"(?:RuntimeException|IllegalStateException|IllegalArgumentException|"
    r"NullPointerException|UnsupportedOperationException|"
    r"IllegalStateException|AssertionError|Error\b)"
    r"|System\.exit\s*\("
    r"|Objects\.requireNonNull\s*\("
    r"|\.orElseThrow\s*\(\s*\)"  # only no-arg form is panic-prone
    r"|\bassert\s+"  # Java assert keyword
)

SKIP_FILENAMES = {"module-info.java", "package-info.java"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", action="store_true", help="refresh the baseline")
    return parser.parse_args()


def is_production_java_file(path: Path) -> bool:
    if path.suffix != ".java":
        return False
    if path.name in SKIP_FILENAMES:
        return False
    rel = path.relative_to(REPO_ROOT).as_posix()
    if "/test/" in rel or rel.startswith("test/"):
        return False
    stem = path.stem
    if stem.endswith("Test") or stem.endswith("Tests") or stem.endswith("IT"):
        return False
    return True


def strip_java_comments_and_strings(text: str) -> str:
    """粗略去除 Java 注释和字符串字面量，避免误匹配。

    这不是完整 Java 词法分析器，但对预算棘轮用途足够——
    我们要的是"代码中真实出现的 panic-prone 用法"，
    不计较注释里的示例或字符串里的字面文本。
    """
    # 块注释 /* ... */（跨行）
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    # 行注释 //
    text = re.sub(r"//[^\n]*", "", text)
    # 字符串字面量 "..."（含转义）
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    # 字符字面量 '...'
    text = re.sub(r"'(?:\\.|[^'\\])'", "''", text)
    return text


def production_lines(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8", errors="ignore")
    cleaned = strip_java_comments_and_strings(raw)
    return cleaned.splitlines()


def production_java_files() -> list[Path]:
    files: list[Path] = []
    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            if is_production_java_file(path):
                files.append(path)
    return files


def current_counts() -> dict[str, int]:
    counts: dict[str, int] = {}
    for path in production_java_files():
        count = sum(1 for line in production_lines(path) if PATTERN.search(line))
        if count:
            counts[path.relative_to(REPO_ROOT).as_posix()] = count
    return counts


def load_baseline() -> dict[str, Any]:
    if not BASELINE_FILE.exists():
        return {"version": 1, "total": 0, "tracked_files": {}}
    data = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"error: invalid baseline file format: {BASELINE_FILE}")
    total = data.get("total")
    tracked = data.get("tracked_files")
    if not isinstance(total, int) or total < 0:
        raise SystemExit(f"error: invalid total in {BASELINE_FILE}")
    if not isinstance(tracked, dict) or any(
        not isinstance(k, str) or not isinstance(v, int) or v <= 0 for k, v in tracked.items()
    ):
        raise SystemExit(f"error: invalid tracked_files in {BASELINE_FILE}")
    return data


def write_baseline(counts: dict[str, int]) -> None:
    BASELINE_FILE.parent.mkdir(parents=True, exist_ok=True)
    BASELINE_FILE.write_text(
        json.dumps(
            {"version": 1, "total": sum(counts.values()), "tracked_files": counts},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    baseline = load_baseline()
    current = current_counts()
    current_total = sum(current.values())

    if args.update:
        write_baseline(current)
        print(
            "Updated panic-prone baseline: "
            f"total={baseline['total']} -> {current_total}, "
            f"files={len(baseline['tracked_files'])} -> {len(current)}"
        )
        return 0

    tracked: dict[str, int] = baseline["tracked_files"]
    regressions: list[str] = []
    improvements: list[str] = []

    if current_total > baseline["total"]:
        regressions.append(f"total panic-prone count grew: {baseline['total']} -> {current_total}")
    elif current_total < baseline["total"]:
        improvements.append(f"total panic-prone count shrank: {baseline['total']} -> {current_total}")

    for path, count in sorted(current.items()):
        old_count = tracked.get(path)
        if old_count is None:
            regressions.append(f"new production panic-prone usage: {path} ({count})")
        elif count > old_count:
            regressions.append(f"production panic-prone usage grew: {path} ({old_count} -> {count})")
        elif count < old_count:
            improvements.append(f"production panic-prone usage shrank: {path} ({old_count} -> {count})")

    for path, old_count in sorted(tracked.items()):
        if path not in current:
            improvements.append(f"production panic-prone usage removed: {path} ({old_count} -> 0)")

    if regressions:
        print("Panic-prone usage budget exceeded:", file=sys.stderr)
        for entry in regressions:
            print(f"  - {entry}", file=sys.stderr)
        print("Run scripts/check_panic_budget.py --update only after intentional cleanup.", file=sys.stderr)
        return 1

    if improvements:
        print("Panic-prone usage budget improved:")
        for entry in improvements:
            print(f"  - {entry}")
        print("Consider running: scripts/check_panic_budget.py --update")
    else:
        print(f"Panic-prone budget OK: total={current_total} files={len(current)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
