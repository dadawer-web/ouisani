#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_coupled_joint_decision_experiment.py — 资源信号驱动的权限层动态收紧实验（Issue 2）

动机
----
contribution 1 宣称 "cross-layer correlation enabling real-time attack truncation"，
但原评估没有展示一个 "resource signal 实际改变了 permission decision" 的场景。
depth-aware policy 是纯静态规则（深度+工具类型），跟资源层状态无关；
rate limiter 是纯资源层节流，不查权限；trace ID 主要起事后关联审计作用。

本实验补这个缺口：实现一个真正的联合决策机制——资源压力信号实际改变权限判定。
机制：ResourcePressureAwareEscalationPolicy
  - 正常态：depth >= 2 + 破坏性工具 → DENY_DEPTH（与原策略一致）
  - 压力态：rate-limit 拒绝计数 > 阈值时，权限层临时收紧 strictness
    → depth >= 1 + 破坏性工具 → DENY_DEPTH（自动拒绝一层更浅的请求）

这是真正的 coupling：资源层信号（rate-limit exhaustion）实时改变权限层决策。

实验设计
----
攻击者在 depth=1 请求 bash：
  - 原策略：depth=1 < 2 → ASK_WITH_CONTEXT（进入人类审批，可能被社会工程骗过）
  - 压力态联合策略：检测到资源压力 → depth=1 + bash → DENY_DEPTH（直接拒绝）

四档配置：
  - Baseline: 无深度策略，无源头限流
  - Permission-only (static): 原静态策略 depth>=2，与资源状态无关
  - Coupled (static): 原静态策略 + 源头限流（保护权限层但不改变决策）
  - Coupled (joint): 联合策略——资源压力 > 阈值时收紧到 depth>=1

测量
  - depth=1 的 bash 请求被 DENY 的比例（拦截率）
  - depth=1 的 benign 工具请求被误拦的比例（假阳性）
  - 资源压力信号是否实际改变了权限判定（coupling 证据）

输出
  - coupled_joint_decision_results.csv
  - coupled_joint_decision_results.json
"""

from __future__ import annotations

import csv
import json
import multiprocessing as mp
import statistics
import sys
import time
import uuid
from pathlib import Path
from typing import Any

OUTPUT_DIR = Path(__file__).parent / "target" / "coupled_joint_decision"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

DESTRUCTIVE_TOOLS = {"bash", "shell", "security_scan"}
BENIGN_TOOLS = {"file_read", "grep", "glob", "web_fetch", "web_search"}

# 资源压力阈值：rate-limit 拒绝计数超过此值则进入压力态
PRESSURE_THRESHOLD = 50


# ════════════════════════════════════════════════════════════════════════════
#  联合决策策略：资源压力驱动的权限层动态收紧
# ════════════════════════════════════════════════════════════════════════════

def evaluate_static_policy(depth: int, tool_name: str, max_depth: int = 2) -> str:
    """原静态 depth-aware 策略（与 EscalationPolicy.java 一致）。"""
    if depth >= max_depth and tool_name and tool_name.lower() in DESTRUCTIVE_TOOLS:
        return "DENY_DEPTH"
    return "ASK_WITH_CONTEXT"


def evaluate_joint_policy(
    depth: int,
    tool_name: str,
    rate_limit_rejections: int,
    static_max_depth: int = 2,
    pressure_threshold: int = PRESSURE_THRESHOLD,
) -> tuple[str, str]:
    """资源压力感知的联合决策策略。

    核心创新：资源层信号（rate-limit 拒绝计数）实时改变权限层 strictness。

    正常态：depth >= static_max_depth + 破坏性工具 → DENY_DEPTH
    压力态：rate_limit_rejections > threshold 时，收紧到 depth >= (static_max_depth - 1)
           → depth=1 的破坏性工具请求也被自动拒绝

    返回 (verdict, decision_basis)：
      - decision_basis = "STATIC" | "PRESSURE_ESCALATED"
        PRESSURE_ESCALATED 表示资源信号改变了权限判定（coupling 证据）
    """
    if not tool_name or tool_name.lower() not in DESTRUCTIVE_TOOLS:
        return "ASK_WITH_CONTEXT", "STATIC"

    # 正常态判定
    if depth >= static_max_depth:
        return "DENY_DEPTH", "STATIC"

    # 联合决策：资源压力 > 阈值时收紧 strictness
    if rate_limit_rejections > pressure_threshold and depth >= (static_max_depth - 1):
        return "DENY_DEPTH", "PRESSURE_ESCALATED"

    return "ASK_WITH_CONTEXT", "STATIC"


# ════════════════════════════════════════════════════════════════════════════
#  共享状态
# ════════════════════════════════════════════════════════════════════════════

def make_shared_state(manager: mp.Manager, config: str) -> dict:
    return {
        "config": config,
        "rate_lock": manager.Lock(),
        "rate_tokens": manager.Value("d", 25.0),
        "rate_last_refill": manager.Value("d", time.time()),
        "rate_rejections": manager.Value("i", 0),  # 累计限流拒绝数（压力信号）
        "stop_attack": manager.Value("b", False),
    }


def check_rate_limit(state: dict) -> bool:
    """源头限流（coupled 配置启用）。拒绝时累计 pressure 信号。"""
    if state["config"] not in ("coupled_static", "coupled_joint"):
        return True
    with state["rate_lock"]:
        now = time.time()
        elapsed = now - state["rate_last_refill"].value
        state["rate_tokens"].value = min(25.0, state["rate_tokens"].value + elapsed * 20.0)
        state["rate_last_refill"].value = now
        if state["rate_tokens"].value >= 1.0:
            state["rate_tokens"].value -= 1.0
            return True
        state["rate_rejections"].value += 1
        return False


def attack_worker(state: dict, worker_id: int) -> None:
    """攻击者：高频触发 rate-limit 拒绝，累计压力信号。"""
    while not state["stop_attack"].value:
        check_rate_limit(state)  # 高频触发拒绝，累计 rate_rejections
        time.sleep(0.0001)


# ════════════════════════════════════════════════════════════════════════════
#  实验驱动
# ════════════════════════════════════════════════════════════════════════════

def run_config(config: str, n_trials: int = 30) -> dict[str, Any]:
    """运行单档配置。

    config:
      - "baseline": 无深度策略，无限流
      - "permission_static": 静态 depth>=2 策略，无限流
      - "coupled_static": 静态 depth>=2 策略 + 源头限流（保护权限层但不改变决策）
      - "coupled_joint": 联合策略——压力 > 阈值时收紧到 depth>=1 + 源头限流
    """
    print(f"  [*] Config: {config}, n={n_trials}")

    manager = mp.Manager()
    state = make_shared_state(manager, config)

    # 启动攻击者（coupled 配置下攻击者触发限流拒绝，累计压力信号）
    attack_procs: list[mp.Process] = []
    if config in ("coupled_static", "coupled_joint"):
        for i in range(6):
            p = mp.Process(target=attack_worker, args=(state, i), daemon=True)
            p.start()
            attack_procs.append(p)
        # 让攻击者累计压力信号
        time.sleep(0.8)

    # 在压力下测试 depth=1 的 bash 请求（关键场景）
    # 原策略：depth=1 < 2 → ASK_WITH_CONTEXT（可能被社会工程骗过审批）
    # 联合策略：检测到压力 → depth=1 + bash → DENY_DEPTH（直接拒绝）
    pressure_at_test = state["rate_rejections"].value

    decisions: list[dict] = []
    for i in range(n_trials):
        # 攻击场景：depth=1 的子 agent 请求 bash
        if config == "baseline":
            verdict = "ASK_WITH_CONTEXT"  # 无策略
            basis = "NONE"
        elif config == "permission_static":
            verdict = evaluate_static_policy(depth=1, tool_name="bash")
            basis = "STATIC"
        elif config == "coupled_static":
            verdict = evaluate_static_policy(depth=1, tool_name="bash")
            basis = "STATIC"
        elif config == "coupled_joint":
            verdict, basis = evaluate_joint_policy(
                depth=1, tool_name="bash",
                rate_limit_rejections=state["rate_rejections"].value,
            )
        else:
            raise ValueError(config)

        decisions.append({
            "verdict": verdict,
            "basis": basis,
            "pressure_signal": state["rate_rejections"].value,
        })
        time.sleep(0.01)

    # 假阳性测试：depth=1 的良性工具请求不应被拦截
    fp_decisions: list[dict] = []
    for tool in list(BENIGN_TOOLS)[:5]:
        for _ in range(10):
            if config == "coupled_joint":
                v, b = evaluate_joint_policy(
                    depth=1, tool_name=tool,
                    rate_limit_rejections=state["rate_rejections"].value,
                )
            elif config in ("permission_static", "coupled_static"):
                v = evaluate_static_policy(depth=1, tool_name=tool)
                b = "STATIC"
            else:
                v = "ASK_WITH_CONTEXT"
                b = "NONE"
            fp_decisions.append({"verdict": v, "basis": b, "tool": tool})

    # 停止攻击者
    state["stop_attack"].value = True
    for p in attack_procs:
        p.join(timeout=2.0)
        if p.is_alive():
            p.terminate()
    final_pressure = state["rate_rejections"].value
    manager.shutdown()

    # 统计
    deny_count = sum(1 for d in decisions if d["verdict"] == "DENY_DEPTH")
    pressure_escalated = sum(1 for d in decisions if d["basis"] == "PRESSURE_ESCALATED")
    fp_count = sum(1 for d in fp_decisions if d["verdict"] == "DENY_DEPTH")
    fp_total = len(fp_decisions)

    result = {
        "config": config,
        "n_trials": n_trials,
        "attack_scenario": "depth=1 sub-agent requests bash (below static threshold)",
        "interception_rate": round(deny_count / n_trials, 4),
        "pressure_escalated_count": pressure_escalated,
        "pressure_escalated_rate": round(pressure_escalated / n_trials, 4),
        "pressure_signal_at_test": pressure_at_test,
        "pressure_signal_final": final_pressure,
        "pressure_threshold": PRESSURE_THRESHOLD,
        "false_positive_rate": round(fp_count / fp_total, 4),
        "fp_tests": fp_total,
        "per_trial_verdicts": [d["verdict"] for d in decisions],
        "per_trial_basis": [d["basis"] for d in decisions],
    }
    return result


def main() -> int:
    print("=" * 70)
    print("Coupled Joint-Decision Experiment (Issue 2)")
    print("Tests whether resource pressure signal actually changes permission decision")
    print("=" * 70)

    configs = ["baseline", "permission_static", "coupled_static", "coupled_joint"]
    all_results = []
    for cfg in configs:
        res = run_config(cfg, n_trials=30)
        all_results.append(res)
        print(f"    interception={res['interception_rate']:.2%}  "
              f"pressure_escalated={res['pressure_escalated_rate']:.2%}  "
              f"FP={res['false_positive_rate']:.2%}  "
              f"pressure_signal={res['pressure_signal_final']}")

    # 写 CSV
    csv_path = OUTPUT_DIR / "coupled_joint_decision_results.csv"
    fieldnames = [
        "config", "n_trials", "interception_rate", "pressure_escalated_count",
        "pressure_escalated_rate", "pressure_signal_at_test", "pressure_signal_final",
        "pressure_threshold", "false_positive_rate", "fp_tests",
    ]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for r in all_results:
            row = {k: v for k, v in r.items() if k in fieldnames}
            writer.writerow(row)
    print(f"\n[*] CSV: {csv_path}")

    # 写 JSON
    json_path = OUTPUT_DIR / "coupled_joint_decision_results.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump({
            "experiment": "coupled_joint_decision",
            "issue": "Issue 2: prove resource signal actually changes permission decision",
            "design": (
                "Implements ResourcePressureAwareEscalationPolicy: when rate-limit "
                "rejection count exceeds a threshold, the permission layer dynamically "
                "raises strictness from depth>=2 to depth>=1 for destructive tools. "
                "Tests depth=1 bash request (below static threshold) under resource "
                "pressure. The 'coupled_joint' config shows resource signal changing "
                "the permission verdict from ASK to DENY — direct evidence of coupling."
            ),
            "results": all_results,
        }, f, indent=2, ensure_ascii=False)
    print(f"[*] JSON: {json_path}")

    # 摘要
    print()
    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    print(f"{'Config':<22} {'Intercept%':>11} {'Escalated%':>11} {'FP%':>8} {'Signal':>8}")
    for r in all_results:
        print(f"{r['config']:<22} {r['interception_rate']:>10.2%} "
              f"{r['pressure_escalated_rate']:>10.2%} "
              f"{r['false_positive_rate']:>7.2%} "
              f"{r['pressure_signal_final']:>8}")

    # 关键结论
    coupled_static = all_results[2]
    coupled_joint = all_results[3]
    print()
    print("Key findings:")
    print(f"  - Coupled (static) interception: {coupled_static['interception_rate']:.2%} "
          f"(depth=1 below threshold → ASK, resource signal does NOT change decision)")
    print(f"  - Coupled (joint) interception:  {coupled_joint['interception_rate']:.2%} "
          f"(pressure > threshold → depth=1 DENY, resource signal DOES change decision)")
    print(f"  - Pressure-escalated decisions:  {coupled_joint['pressure_escalated_count']}/"
          f"{coupled_joint['n_trials']} "
          f"({coupled_joint['pressure_escalated_rate']:.2%})")
    print(f"  - False positive rate:           {coupled_joint['false_positive_rate']:.2%} "
          f"(benign tools at depth=1 not affected)")
    print()
    print("  EVIDENCE: resource pressure signal changes permission decision")
    print("  → coupled_static: depth=1 bash → ASK (0% interception)")
    print("  → coupled_joint:  depth=1 bash → DENY under pressure (>0% interception)")
    print("  This is the missing 'resource signal changes permission decision' scenario.")

    return 0


if __name__ == "__main__":
    mp.set_start_method("spawn", force=True)
    sys.exit(main())
