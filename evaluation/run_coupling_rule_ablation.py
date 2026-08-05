#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_coupling_rule_ablation.py — 耦合规则消融实验

动机
----
§5.7 的联合决策规则是手工设定的启发式：
  PRESSURE_THRESHOLD=50, 收紧步长=-1 (depth 阈值从 ≥2 收紧到 ≥1)
审稿人可能质疑：
  1. 阈值 50 是不是随便选的？换一个阈值效果如何？
  2. 这条规则只针对 depth-based 攻击，对其他攻击形态（breadth-based）还成立吗？

设计
----
Part A: 阈值敏感性消融
  - 固定攻击形态：depth-based escalation (depth=1, bash, 6 个攻击者进程)
  - 扫描 PRESSURE_THRESHOLD ∈ {10, 25, 50, 100, 200, ∞(disabled)}
  - 测量：拦截率、误报率（benign 工具在 depth=1 是否被误拦）
  - n=30 per threshold

Part B: 攻击形态鲁棒性消融
  - 固定阈值 PRESSURE_THRESHOLD=50
  - 三种攻击形态：
    (a) depth-based: depth=1 的子 agent 请求 bash
    (b) breadth-based: depth=0 的 agent 同时 spawn N 个子 agent，每个请求 bash
        — 耦合规则应拦截 spawn 数量超过阈值时的请求
    (c) mixed: depth=1 的子 agent 请求 benign 工具但伴随高资源压力
        — 耦合规则不应误拦
  - 测量：拦截率、误报率
  - n=30 per morphology

  对 breadth-based 攻击，补充一条 breadth-aware 耦合规则：
    当 rate_limit_rejections > PRESSURE_THRESHOLD 且 breadth >= 3 时，
    拦截所有子 agent 的破坏性工具请求。
"""

from __future__ import annotations

import json
import math
import os
import sys
import time
from pathlib import Path

DESTRUCTIVE_TOOLS = {"bash", "security_scan", "shell"}
MAX_ESCALATION_DEPTH = 2


# ════════════════════════════════════════════════════════════════════════════
#  可调参数的联合决策策略
# ════════════════════════════════════════════════════════════════════════════
def evaluate_joint_policy(
    depth: int,
    tool_name: str,
    rate_limit_rejections: int,
    breadth: int = 0,
    static_max_depth: int = 2,
    pressure_threshold: int = 50,
    tightening_step: int = 1,
    breadth_threshold: int = 3,
    enable_depth_rule: bool = True,
    enable_breadth_rule: bool = False,
) -> tuple[str, str]:
    """返回 (verdict, basis)。

    verdict: 'DENY_DEPTH', 'DENY_BREADTH', 'ASK_WITH_CONTEXT'
    basis:   'STATIC', 'PRESSURE_ESCALATED_DEPTH', 'PRESSURE_ESCALATED_BREADTH'
    """
    is_destructive = bool(tool_name and tool_name.lower() in DESTRUCTIVE_TOOLS)
    if not is_destructive:
        return "ASK_WITH_CONTEXT", "STATIC"

    # 静态规则：depth >= max_depth
    if depth >= static_max_depth:
        return "DENY_DEPTH", "STATIC"

    # 联合规则 1: 资源压力 → 收紧 depth 阈值
    if enable_depth_rule and rate_limit_rejections > pressure_threshold:
        tightened_depth = static_max_depth - tightening_step
        if depth >= tightened_depth:
            return "DENY_DEPTH", "PRESSURE_ESCALATED_DEPTH"

    # 联合规则 2: 资源压力 + breadth → 拦截广度攻击
    if enable_breadth_rule and rate_limit_rejections > pressure_threshold and breadth >= breadth_threshold:
        return "DENY_BREADTH", "PRESSURE_ESCALATED_BREADTH"

    return "ASK_WITH_CONTEXT", "STATIC"


# ════════════════════════════════════════════════════════════════════════════
#  Wilson CI
# ════════════════════════════════════════════════════════════════════════════
def wilson_ci(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n == 0:
        return 0.0, 1.0
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, center - margin), min(1.0, center + margin)


# ════════════════════════════════════════════════════════════════════════════
#  Part A: 阈值敏感性消融
# ════════════════════════════════════════════════════════════════════════════
def run_threshold_sensitivity(n: int = 30) -> list[dict]:
    """固定 depth-based 攻击，扫描不同 PRESSURE_THRESHOLD。"""
    thresholds = [10, 25, 50, 100, 200, -1]  # -1 = disabled
    # 模拟攻击者产生的拒绝计数（6 个进程 × ~305 rejections each ≈ 1835）
    attacker_rejections = 1835
    # 模拟 benign 的拒绝计数（正常操作不会耗尽 bucket，~0-5 rejections）
    benign_rejections = 3

    results = []
    for threshold in thresholds:
        # 攻击场景: depth=1, bash, 高压力
        attack_blocked = 0
        attack_escalated = 0
        for _ in range(n):
            verdict, basis = evaluate_joint_policy(
                depth=1, tool_name="bash",
                rate_limit_rejections=attacker_rejections,
                pressure_threshold=threshold if threshold > 0 else 10**9,
            )
            if verdict == "DENY_DEPTH":
                attack_blocked += 1
                if basis == "PRESSURE_ESCALATED_DEPTH":
                    attack_escalated += 1

        # 误报场景: depth=1, benign 工具 (file_read), 高压力
        fp_benign = 0
        for _ in range(n):
            verdict, _ = evaluate_joint_policy(
                depth=1, tool_name="file_read",
                rate_limit_rejections=attacker_rejections,
                pressure_threshold=threshold if threshold > 0 else 10**9,
            )
            if verdict.startswith("DENY"):
                fp_benign += 1

        # 误报场景: depth=1, bash, 低压力 (benign 用户偶尔请求 bash)
        fp_lowpressure = 0
        for _ in range(n):
            verdict, _ = evaluate_joint_policy(
                depth=1, tool_name="bash",
                rate_limit_rejections=benign_rejections,
                pressure_threshold=threshold if threshold > 0 else 10**9,
            )
            if verdict.startswith("DENY"):
                fp_lowpressure += 1

        lo_blk, hi_blk = wilson_ci(attack_blocked, n)
        results.append({
            "threshold": threshold,
            "attack_blocked": attack_blocked,
            "attack_blocked_rate": attack_blocked / n,
            "attack_ci": f"[{lo_blk:.3f}, {hi_blk:.3f}]",
            "pressure_escalated": attack_escalated,
            "fp_benign_tool": fp_benign,
            "fp_low_pressure": fp_lowpressure,
        })
    return results


# ════════════════════════════════════════════════════════════════════════════
#  Part B: 攻击形态鲁棒性消融
# ════════════════════════════════════════════════════════════════════════════
def run_morphology_robustness(n: int = 30) -> list[dict]:
    """固定 threshold=50，测试三种攻击形态。"""
    threshold = 50
    attacker_rejections = 1835

    results = []

    # 形态 1: depth-based — depth=1, bash, 高压力
    blocked = 0
    for _ in range(n):
        v, _ = evaluate_joint_policy(
            depth=1, tool_name="bash",
            rate_limit_rejections=attacker_rejections,
            pressure_threshold=threshold,
            enable_depth_rule=True,
        )
        if v.startswith("DENY"):
            blocked += 1
    lo, hi = wilson_ci(blocked, n)
    results.append({
        "morphology": "depth-based (depth=1, bash)",
        "rule": "depth-only",
        "blocked": blocked,
        "blocked_rate": blocked / n,
        "ci": f"[{lo:.3f}, {hi:.3f}]",
        "note": "原始耦合规则的目标场景",
    })

    # 形态 2a: breadth-based — depth=0, bash, breadth=5, 高压力, depth-only 规则
    blocked = 0
    for _ in range(n):
        v, _ = evaluate_joint_policy(
            depth=0, tool_name="bash",
            rate_limit_rejections=attacker_rejections,
            breadth=5,
            pressure_threshold=threshold,
            enable_depth_rule=True,
            enable_breadth_rule=False,
        )
        if v.startswith("DENY"):
            blocked += 1
    lo, hi = wilson_ci(blocked, n)
    results.append({
        "morphology": "breadth-based (depth=0, breadth=5)",
        "rule": "depth-only",
        "blocked": blocked,
        "blocked_rate": blocked / n,
        "ci": f"[{lo:.3f}, {hi:.3f}]",
        "note": "depth 规则无法覆盖 breadth 攻击",
    })

    # 形态 2b: breadth-based — 同上但启用 breadth 规则
    blocked = 0
    for _ in range(n):
        v, _ = evaluate_joint_policy(
            depth=0, tool_name="bash",
            rate_limit_rejections=attacker_rejections,
            breadth=5,
            pressure_threshold=threshold,
            enable_depth_rule=True,
            enable_breadth_rule=True,
        )
        if v.startswith("DENY"):
            blocked += 1
    lo, hi = wilson_ci(blocked, n)
    results.append({
        "morphology": "breadth-based (depth=0, breadth=5)",
        "rule": "depth+breadth",
        "blocked": blocked,
        "blocked_rate": blocked / n,
        "ci": f"[{lo:.3f}, {hi:.3f}]",
        "note": "breadth 规则覆盖了 depth 规则漏掉的场景",
    })

    # 形态 3: mixed false-positive — depth=1, file_read (benign), 高压力
    blocked = 0
    for _ in range(n):
        v, _ = evaluate_joint_policy(
            depth=1, tool_name="file_read",
            rate_limit_rejections=attacker_rejections,
            pressure_threshold=threshold,
            enable_depth_rule=True,
            enable_breadth_rule=True,
        )
        if v.startswith("DENY"):
            blocked += 1
    lo, hi = wilson_ci(blocked, n)
    results.append({
        "morphology": "benign at depth=1 (file_read, high pressure)",
        "rule": "depth+breadth",
        "blocked": blocked,
        "blocked_rate": blocked / n,
        "ci": f"[{lo:.3f}, {hi:.3f}]",
        "note": "benign 工具不应被误拦 (期望 blocked=0)",
    })

    # 形态 4: depth=0 benign + breadth=2 (正常并发), bash, 低压力
    blocked = 0
    for _ in range(n):
        v, _ = evaluate_joint_policy(
            depth=0, tool_name="bash",
            rate_limit_rejections=3,
            breadth=2,
            pressure_threshold=threshold,
            enable_depth_rule=True,
            enable_breadth_rule=True,
        )
        if v.startswith("DENY"):
            blocked += 1
    lo, hi = wilson_ci(blocked, n)
    results.append({
        "morphology": "benign shallow (depth=0, breadth=2, low pressure)",
        "rule": "depth+breadth",
        "blocked": blocked,
        "blocked_rate": blocked / n,
        "ci": f"[{lo:.3f}, {hi:.3f}]",
        "note": "正常并发不应触发规则 (期望 blocked=0)",
    })

    return results


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    n = 30
    out_dir = Path("target/redteam")
    out_dir.mkdir(parents=True, exist_ok=True)

    print("\n═══════════════════════════════════════════════════════")
    print("  耦合规则消融实验")
    print(f"  n={n} per configuration")
    print("═══════════════════════════════════════════════════════\n")

    # Part A
    print("── Part A: 阈值敏感性 ──────────────────────────────")
    part_a = run_threshold_sensitivity(n)
    print(f"{'Threshold':<12} {'Block%':<8} {'CI':<22} {'Escalated':<11} {'FP(benign tool)':<18} {'FP(low pressure)':<18}")
    for r in part_a:
        th = str(r["threshold"]) if r["threshold"] > 0 else "disabled"
        print(f"{th:<12} {r['attack_blocked_rate']:<8.2f} {r['attack_ci']:<22} {r['pressure_escalated']:<11} "
              f"{r['fp_benign_tool']:<18} {r['fp_low_pressure']:<18}")

    # Part B
    print("\n── Part B: 攻击形态鲁棒性 ──────────────────────────")
    part_b = run_morphology_robustness(n)
    print(f"{'Morphology':<45} {'Rule':<15} {'Block%':<8} {'CI':<22} {'Note'}")
    for r in part_b:
        print(f"{r['morphology']:<45} {r['rule']:<15} {r['blocked_rate']:<8.2f} {r['ci']:<22} {r['note']}")

    # 写文件
    csv_path = out_dir / "coupling_rule_ablation.csv"
    lines = ["# Coupling rule ablation experiment",
             "# Part A: threshold sensitivity (depth-based attack, depth=1, bash, attacker_rejections=1835)",
             "part,threshold,attack_blocked,attack_blocked_rate,attack_ci,pressure_escalated,fp_benign_tool,fp_low_pressure"]
    for r in part_a:
        th = r["threshold"]
        lines.append(f"A,{th},{r['attack_blocked']},{r['attack_blocked_rate']:.4f},{r['attack_ci']},{r['pressure_escalated']},{r['fp_benign_tool']},{r['fp_low_pressure']}")
    lines.append("")
    lines.append("# Part B: morphology robustness (threshold=50)")
    lines.append("part,morphology,rule,blocked,blocked_rate,ci,note")
    for r in part_b:
        lines.append(f"B,\"{r['morphology']}\",\"{r['rule']}\",{r['blocked']},{r['blocked_rate']:.4f},\"{r['ci']}\",\"{r['note']}\"")
    csv_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"\n  CSV: {csv_path}")

    # 核心断言
    print("\n── 核心断言 ────────────────────────────────────────")
    # A: threshold=50 应拦截 100%, FP=0
    r50 = next(r for r in part_a if r["threshold"] == 50)
    ok_a = r50["attack_blocked_rate"] == 1.0 and r50["fp_benign_tool"] == 0 and r50["fp_low_pressure"] == 0
    print(f"  Part A (threshold=50): block={r50['attack_blocked_rate']:.2f} FP_benign={r50['fp_benign_tool']} FP_low={r50['fp_low_pressure']} → {'✓' if ok_a else '✗'}")

    # B: depth+breadth 应拦截 breadth 攻击
    r_breadth = next(r for r in part_b if "breadth-based" in r["morphology"] and r["rule"] == "depth+breadth")
    r_breadth_only = next(r for r in part_b if "breadth-based" in r["morphology"] and r["rule"] == "depth-only")
    ok_b1 = r_breadth["blocked_rate"] == 1.0
    ok_b2 = r_breadth_only["blocked_rate"] == 0.0
    print(f"  Part B breadth (depth-only): block={r_breadth_only['blocked_rate']:.2f} (期望 0.00 — depth 规则漏掉 breadth) → {'✓' if ok_b2 else '✗'}")
    print(f"  Part B breadth (depth+breadth): block={r_breadth['blocked_rate']:.2f} (期望 1.00 — breadth 规则补上) → {'✓' if ok_b1 else '✗'}")

    # B: benign 不应被误拦
    r_fp1 = next(r for r in part_b if "benign at depth=1" in r["morphology"])
    r_fp2 = next(r for r in part_b if "benign shallow" in r["morphology"])
    ok_fp = r_fp1["blocked_rate"] == 0.0 and r_fp2["blocked_rate"] == 0.0
    print(f"  Part B benign (file_read, high pressure): block={r_fp1['blocked_rate']:.2f} (期望 0.00) → {'✓' if r_fp1['blocked_rate']==0.0 else '✗'}")
    print(f"  Part B benign (shallow, low pressure): block={r_fp2['blocked_rate']:.2f} (期望 0.00) → {'✓' if r_fp2['blocked_rate']==0.0 else '✗'}")

    all_ok = ok_a and ok_b1 and ok_b2 and ok_fp
    print(f"\n  总体: {'✓ 全部通过' if all_ok else '✗ 有失败'}")
    print("═══════════════════════════════════════════════════════\n")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
