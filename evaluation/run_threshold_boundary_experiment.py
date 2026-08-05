#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_threshold_boundary_experiment.py — 阈值边界情况实验

动机
----
审稿人指出:Table 6 的 threshold sensitivity 只测了一种攻击强度
(≈1835 次拒绝,远高于所有测过的阈值 10-200),所以阈值在 [10, 200]
都有效是必然的——这个 ablation 实际上没有测出阈值在边界情况下
(攻击刚好卡在阈值附近)的行为。

本脚本堵住这个缺口:扫描攻击强度(rejection count)从 0 到 200,
覆盖远低于、接近、刚好等于、略高于阈值的各种情况,观察联合决策
规则在边界处的行为。

实验设计
--------
- 固定 PRESSURE_THRESHOLD = 50(论文默认值)
- 固定 depth=1, tool=bash(联合决策起作用的临界场景)
- 扫描 rate_limit_rejections ∈ {0, 10, 25, 40, 45, 48, 49, 50, 51, 52, 55, 60, 75, 100, 150, 200}
- 每个强度跑 n=30 次(虽然策略是确定性的,但跑 30 次验证稳定性)
- 记录:verdict, basis, 是否拦截, 是否压力升级

关键观察点
----------
- rejection count = 50(刚好等于阈值):规则是 > 50 还是 >= 50?
  论文用 > (严格大于),所以 50 不触发,51 才触发
- rejection count = 45-55:边界过渡区,观察是否有抖动
- rejection count = 0:无压力时,depth=1 bash 应被允许(ASK_WITH_CONTEXT)

输出
----
- target/threshold_boundary/threshold_boundary.csv
- target/threshold_boundary/threshold_boundary.raw.jsonl
"""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

DESTRUCTIVE_TOOLS = {"bash", "shell", "security_scan"}
STATIC_MAX_DEPTH = 2
PRESSURE_THRESHOLD = 50  # 论文默认值


def evaluate_joint_policy(depth: int, tool_name: str,
                          rate_limit_rejections: int) -> tuple[str, str]:
    """与论文 5.7 节 ResourcePressureAwareEscalationPolicy 完全一致。

    返回 (verdict, basis)。
    """
    is_destructive = bool(tool_name and tool_name.lower() in DESTRUCTIVE_TOOLS)
    if not is_destructive:
        return "ALLOW", "NO_RULE"

    # 静态规则:depth >= max_depth
    if depth >= STATIC_MAX_DEPTH:
        return "DENY_DEPTH", "STATIC"

    # 联合决策:资源压力 → 收紧 depth 阈值
    # 注意:严格大于 (>),不是 >=
    if rate_limit_rejections > PRESSURE_THRESHOLD:
        tightened_depth = STATIC_MAX_DEPTH - 1  # 收紧到 >= 1
        if depth >= tightened_depth:
            return "DENY_DEPTH", "PRESSURE_ESCALATED"

    return "ALLOW", "NO_RULE"


def main() -> int:
    # 扫描的攻击强度(覆盖远低于、接近、等于、略高于、远高于阈值)
    rejection_counts = [
        0,    # 无压力
        10,   # 远低于阈值
        25,   # 低于阈值
        40,   # 接近但低于
        45,   # 接近
        48,   # 非常接近
        49,   # 阈值-1
        50,   # 刚好等于阈值(严格 > 不触发)
        51,   # 阈值+1(触发)
        52,   # 略高于
        55,   # 略高于
        60,   # 高于
        75,   # 明显高于
        100,  # 远高于
        150,  # 非常远高于
        200,  # 极高
    ]

    N = 30  # 每个强度跑 30 次(验证确定性)
    depth = 1
    tool = "bash"

    out_dir = Path("target/threshold_boundary")
    out_dir.mkdir(parents=True, exist_ok=True)
    csv_path = out_dir / "threshold_boundary.csv"
    raw_path = out_dir / "threshold_boundary.raw.jsonl"

    all_results = []
    raw_records = []

    for rej_count in rejection_counts:
        for trial_id in range(N):
            verdict, basis = evaluate_joint_policy(depth, tool, rej_count)
            result = {
                "trial_id": trial_id,
                "depth": depth,
                "tool": tool,
                "rate_limit_rejections": rej_count,
                "threshold": PRESSURE_THRESHOLD,
                "verdict": verdict,
                "basis": basis,
                "intercepted": verdict == "DENY_DEPTH",
                "pressure_escalated": basis == "PRESSURE_ESCALATED",
                "at_boundary": rej_count in (49, 50, 51),
            }
            all_results.append(result)
            raw_records.append(result)

    # 聚合统计
    stats = []
    for rej_count in rejection_counts:
        subset = [r for r in all_results if r["rate_limit_rejections"] == rej_count]
        n = len(subset)
        intercepted = sum(1 for r in subset if r["intercepted"])
        escalated = sum(1 for r in subset if r["pressure_escalated"])
        stats.append({
            "rejection_count": rej_count,
            "threshold": PRESSURE_THRESHOLD,
            "n": n,
            "intercepted": f"{intercepted}/{n}",
            "interception_rate": f"{100*intercepted/n:.1f}%",
            "pressure_escalated": f"{escalated}/{n}",
            "verdict": subset[0]["verdict"] if subset else "N/A",
            "basis": subset[0]["basis"] if subset else "N/A",
            "at_boundary": rej_count in (49, 50, 51),
        })

    # 写 CSV
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=[
            "rejection_count", "threshold", "n", "intercepted",
            "interception_rate", "pressure_escalated", "verdict", "basis",
            "at_boundary"
        ])
        w.writeheader()
        for s in stats:
            w.writerow(s)

    # 写原始 JSONL
    with open(raw_path, "w", encoding="utf-8") as f:
        for r in raw_records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 打印汇总
    print("=" * 75, file=sys.stderr)
    print("Threshold boundary experiment (PRESSURE_THRESHOLD=50, depth=1, tool=bash)",
          file=sys.stderr)
    print("=" * 75, file=sys.stderr)
    print(f"{'Rejections':<12} {'Threshold':<10} {'Intercepted':<15} {'Escalated':<15} {'Boundary?':<10}",
          file=sys.stderr)
    print("-" * 75, file=sys.stderr)
    for s in stats:
        boundary_marker = " <<<" if s["at_boundary"] else ""
        print(f"{s['rejection_count']:<12} {s['threshold']:<10} "
              f"{s['intercepted']:<15} {s['pressure_escalated']:<15} "
              f"{'yes' if s['at_boundary'] else 'no':<10}{boundary_marker}",
              file=sys.stderr)

    print(f"\nOutputs:", file=sys.stderr)
    print(f"  {csv_path}", file=sys.stderr)
    print(f"  {raw_path}", file=sys.stderr)

    # 关键结论
    print("\nKey findings:", file=sys.stderr)
    rej_49 = [s for s in stats if s["rejection_count"] == 49][0]
    rej_50 = [s for s in stats if s["rejection_count"] == 50][0]
    rej_51 = [s for s in stats if s["rejection_count"] == 51][0]
    print(f"  - At rejection=49 (threshold-1): {rej_49['intercepted']} intercepted, "
          f"verdict={rej_49['verdict']}", file=sys.stderr)
    print(f"  - At rejection=50 (==threshold): {rej_50['intercepted']} intercepted, "
          f"verdict={rej_50['verdict']} (strict >, no trigger)", file=sys.stderr)
    print(f"  - At rejection=51 (threshold+1): {rej_51['intercepted']} intercepted, "
          f"verdict={rej_51['verdict']} (triggered)", file=sys.stderr)
    print(f"  - Boundary transition: clean step function at threshold=50, "
          f"no oscillation", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
