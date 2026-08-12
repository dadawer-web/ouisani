#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_adaptive_threshold_experiment.py — EWMA 自适应阈值对比实验

动机
----
§5.7 的 cross-layer joint-decision 使用 hand-tuned 静态阈值 PRESSURE_THRESHOLD=50：
当 rate-limit 拒绝次数 > 50 时，把 depth 阈值从 2 收紧到 1。
审稿人批评这是 "hand-tuned heuristic"（拍脑袋定的）。

本实验实现 EWMA (Exponentially Weighted Moving Average) 自适应阈值版本，
与 hand-tuned 版本做对比，验证：
  1. 自适应阈值是否在压力变化时真正调整（阈值轨迹特征）
  2. 自适应阈值是否改善 interception_rate / false_positive_rate
  3. 如果没有改善，诚实报告（简单规则可能已足够）

设计
----
配置 A: hand_tuned (PRESSURE_THRESHOLD=50, 固定)
配置 B: adaptive (EWMA-based, 动态)

EWMA 追踪 rate-limit 拒绝速率：
  ewma = α * current_rejection_rate + (1-α) * ewma_prev,  α=0.3

动态阈值：
  adaptive_threshold = base_threshold * (1 - k * (ewma - baseline) / baseline)
  base_threshold=50, k=0.5, baseline_rejection_rate=10
  上下界: [10, 200]

探针集：与 run_coupling_rule_ablation.py Part A 一致的场景 + borderline 场景
每配置 N=30 trials, 固定随机种子

测量：interception_rate, false_positive_rate, threshold_trajectory, response_latency

输出：
  target/adaptive_threshold/adaptive_threshold_results.csv
  target/adaptive_threshold/adaptive_threshold_results.json

注意
----
本实验为 Python 纯函数模拟（与 run_coupling_rule_ablation.py 一致），
不修改 Java 生产代码，不调用 LLM API。

公式行为说明
-------------
公式 adaptive_threshold = base * (1 - k * (ewma - baseline) / baseline)
（符号为负）使得 ewma > baseline (高压力) 时阈值降低（更敏感），
ewma < baseline (低压力) 时阈值升高（更宽松）。符合 "压力升高时阈值降低（更敏感）"
的设计意图。此前版本误用 "+" 符号导致行为相反，并造成 borderline_benign 场景
100% 误拦截（FP=33.3%）；本版本为修正后的结果。
"""

from __future__ import annotations

import json
import math
import random
import sys
import time
from pathlib import Path
from typing import Any

# ════════════════════════════════════════════════════════════════════════════
#  常量 (与 EscalationPolicy.java 和 run_coupling_rule_ablation.py 一致)
# ════════════════════════════════════════════════════════════════════════════
DESTRUCTIVE_TOOLS = {"bash", "security_scan", "shell"}
MAX_ESCALATION_DEPTH = 2
HAND_TUNED_PRESSURE_THRESHOLD = 50

# EWMA 参数 (来自任务规格)
EWMA_ALPHA = 0.3
BASE_THRESHOLD = 50.0
K_SENSITIVITY = 0.5
BASELINE_REJECTION_RATE = 10.0
ADAPTIVE_MIN = 10.0
ADAPTIVE_MAX = 200.0

# 实验参数
N_STEPS = 50  # 时间序列步数 (模拟 50 个时间窗口的拒绝速率)
N_TRIALS = 30
RANDOM_SEED_BASE = 42  # 固定随机种子基线，确保可复现

OUTPUT_DIR = Path(__file__).parent / "target" / "adaptive_threshold"


# ════════════════════════════════════════════════════════════════════════════
#  Wilson 置信区间
# ════════════════════════════════════════════════════════════════════════════
def wilson_ci(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson 95% CI for a binomial proportion."""
    if n == 0:
        return 0.0, 1.0
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, center - margin), min(1.0, center + margin)


# ════════════════════════════════════════════════════════════════════════════
#  联合决策策略 (与 run_coupling_rule_ablation.py 的 evaluate_joint_policy 一致)
# ════════════════════════════════════════════════════════════════════════════
def evaluate_joint_policy(
    depth: int,
    tool_name: str,
    rate_limit_rejections: float,
    pressure_threshold: float = HAND_TUNED_PRESSURE_THRESHOLD,
    static_max_depth: int = MAX_ESCALATION_DEPTH,
    tightening_step: int = 1,
) -> tuple[str, str]:
    """返回 (verdict, basis)。

    verdict: 'DENY_DEPTH' 或 'ASK_WITH_CONTEXT'
    basis:   'STATIC' 或 'PRESSURE_ESCALATED_DEPTH'

    逻辑与 EscalationPolicy.java 的 6-arg evaluate 方法一致：
      1. 非破坏性工具 → ASK_WITH_CONTEXT (STATIC)
      2. depth >= maxDepth + 破坏性 → DENY_DEPTH (STATIC)
      3. rejections > threshold + depth >= maxDepth-1 + 破坏性 → DENY_DEPTH (PRESSURE_ESCALATED)
      4. 否则 → ASK_WITH_CONTEXT (STATIC)
    """
    is_destructive = bool(tool_name and tool_name.lower() in DESTRUCTIVE_TOOLS)
    if not is_destructive:
        return "ASK_WITH_CONTEXT", "STATIC"

    # 静态 depth 判定
    if depth >= static_max_depth:
        return "DENY_DEPTH", "STATIC"

    # 联合判定：资源压力 > 阈值时收紧到 depth >= maxDepth - 1
    if rate_limit_rejections > pressure_threshold:
        tightened_depth = static_max_depth - tightening_step
        if depth >= tightened_depth:
            return "DENY_DEPTH", "PRESSURE_ESCALATED_DEPTH"

    return "ASK_WITH_CONTEXT", "STATIC"


# ════════════════════════════════════════════════════════════════════════════
#  EWMA 自适应阈值追踪器
# ════════════════════════════════════════════════════════════════════════════
class AdaptiveThresholdTracker:
    """
    EWMA 追踪 rate-limit 拒绝速率，动态计算 pressure threshold。

    EWMA 更新:  ewma = α * current_rate + (1-α) * ewma_prev
    动态阈值:  adaptive_threshold = base * (1 - k * (ewma - baseline) / baseline)
    上下界:    [ADAPTIVE_MIN, ADAPTIVE_MAX]

    公式行为 (修正后, 符号为负):
      - ewma > baseline (高压力) → 阈值降低 → 更敏感 (更易拦截攻击)
      - ewma < baseline (低压力) → 阈值升高 → 更宽松 (更不易误拦良性)
    """

    def __init__(self) -> None:
        self.alpha = EWMA_ALPHA
        self.base = BASE_THRESHOLD
        self.k = K_SENSITIVITY
        self.baseline = BASELINE_REJECTION_RATE
        self.ewma = self.baseline  # 初始化为基线值

    def update(self, current_rejection_rate: float) -> float:
        """更新 EWMA 并返回新值。"""
        self.ewma = self.alpha * current_rejection_rate + (1 - self.alpha) * self.ewma
        return self.ewma

    def compute_threshold(self) -> float:
        """根据当前 EWMA 计算自适应阈值 (含上下界约束)。

        公式: adaptive_threshold = base * (1 - k * (ewma - baseline) / baseline)
        符号为负，使得压力升高时阈值降低（更敏感），压力降低时阈值升高（更宽松）。
        """
        ratio = (self.ewma - self.baseline) / self.baseline
        threshold = self.base * (1 - self.k * ratio)
        return max(ADAPTIVE_MIN, min(ADAPTIVE_MAX, threshold))


# ════════════════════════════════════════════════════════════════════════════
#  探针场景定义
# ════════════════════════════════════════════════════════════════════════════
# 与 run_coupling_rule_ablation.py Part A 一致的探针 + borderline 场景
# 每个场景通过高斯分布生成每步拒绝速率的时间序列，
# 累计拒绝数与原 ablation 实验的静态值匹配。

PROBE_SCENARIOS: list[dict[str, Any]] = [
    # 1. 持续高压力攻击 (对应 ablation: depth=1, bash, rejections=1835)
    #    ~36.7/step × 50 = ~1835 cumulative
    {
        "name": "attack_sustained",
        "type": "attack",
        "depth": 1,
        "tool": "bash",
        "rate_mean": 36.7,
        "rate_std": 3.0,
        "expected_block": True,
        "note": "对应 ablation Part A: depth=1, bash, rejections=1835",
    },
    # 2. 高压力良性工具 (对应 ablation: depth=1, file_read, rejections=1835)
    #    file_read 不在 DESTRUCTIVE_TOOLS，无论阈值如何都不应被拦
    {
        "name": "benign_tool_highpressure",
        "type": "benign",
        "depth": 1,
        "tool": "file_read",
        "rate_mean": 36.7,
        "rate_std": 3.0,
        "expected_block": False,
        "note": "对应 ablation Part A: depth=1, file_read, rejections=1835",
    },
    # 3. 低压力良性 bash (对应 ablation: depth=1, bash, rejections=3)
    #    ~0.06/step × 50 = ~3 cumulative
    {
        "name": "benign_lowpressure",
        "type": "benign",
        "depth": 1,
        "tool": "bash",
        "rate_mean": 0.06,
        "rate_std": 0.05,
        "expected_block": False,
        "note": "对应 ablation Part A: depth=1, bash, rejections=3",
    },
    # 4. Borderline 攻击 (cumulative ~55, 刚好超过 hand-tuned 阈值 50)
    #    用于测试 adaptive 与 hand-tuned 在边界附近的差异
    {
        "name": "borderline_attack",
        "type": "attack",
        "depth": 1,
        "tool": "bash",
        "rate_mean": 1.1,
        "rate_std": 0.2,
        "expected_block": True,
        "note": "Borderline: cumulative ~55, 刚好 > 50 (hand-tuned 应拦截)",
    },
    # 5. Borderline 良性 (cumulative ~45, 刚好低于 hand-tuned 阈值 50)
    #    用于测试 adaptive 是否误拦边界附近的良性请求
    {
        "name": "borderline_benign",
        "type": "benign",
        "depth": 1,
        "tool": "bash",
        "rate_mean": 0.9,
        "rate_std": 0.2,
        "expected_block": False,
        "note": "Borderline: cumulative ~45, 刚好 < 50 (hand-tuned 不应拦截)",
    },
]


# ════════════════════════════════════════════════════════════════════════════
#  生成拒绝速率时间序列
# ════════════════════════════════════════════════════════════════════════════
def generate_rejection_series(scenario: dict, n_steps: int, rng: random.Random) -> list[float]:
    """生成每步拒绝速率的时间序列 (带高斯噪声，确保每 trial 不同)。"""
    return [max(0.0, rng.gauss(scenario["rate_mean"], scenario["rate_std"]))
            for _ in range(n_steps)]


# ════════════════════════════════════════════════════════════════════════════
#  运行单次试验
# ════════════════════════════════════════════════════════════════════════════
def run_trial(config: str, scenario: dict, seed: int) -> dict[str, Any]:
    """运行单次试验，返回包含决策结果和阈值轨迹的字典。

    config: "hand_tuned" 或 "adaptive"
    seed: 随机种子 (相同 seed 跨 config → 公平比较)
    """
    rng = random.Random(seed)
    rates = generate_rejection_series(scenario, N_STEPS, rng)

    # EWMA 追踪器和轨迹记录 (两个 config 都记录以便对比)
    tracker = AdaptiveThresholdTracker()
    threshold_trajectory: list[float] = []
    ewma_trajectory: list[float] = []
    cumulative_rejections = 0.0

    for rate in rates:
        cumulative_rejections += rate
        tracker.update(rate)
        threshold_trajectory.append(tracker.compute_threshold())
        ewma_trajectory.append(tracker.ewma)

    final_threshold = threshold_trajectory[-1] if threshold_trajectory else BASE_THRESHOLD

    # 决策 (计时)
    if config == "hand_tuned":
        t0 = time.perf_counter_ns()
        threshold = float(HAND_TUNED_PRESSURE_THRESHOLD)
        verdict, basis = evaluate_joint_policy(
            depth=scenario["depth"],
            tool_name=scenario["tool"],
            rate_limit_rejections=cumulative_rejections,
            pressure_threshold=threshold,
        )
        t1 = time.perf_counter_ns()
    else:  # adaptive: 阈值计算是决策路径的一部分
        t0 = time.perf_counter_ns()
        threshold = tracker.compute_threshold()
        verdict, basis = evaluate_joint_policy(
            depth=scenario["depth"],
            tool_name=scenario["tool"],
            rate_limit_rejections=cumulative_rejections,
            pressure_threshold=threshold,
        )
        t1 = time.perf_counter_ns()

    latency_ns = t1 - t0
    blocked = verdict.startswith("DENY")

    return {
        "config": config,
        "scenario": scenario["name"],
        "scenario_type": scenario["type"],
        "seed": seed,
        "depth": scenario["depth"],
        "tool": scenario["tool"],
        "cumulative_rejections": round(cumulative_rejections, 4),
        "final_ewma": round(tracker.ewma, 4),
        "final_threshold": round(threshold, 4),
        "threshold_trajectory": [round(t, 4) for t in threshold_trajectory],
        "ewma_trajectory": [round(e, 4) for e in ewma_trajectory],
        "verdict": verdict,
        "basis": basis,
        "blocked": blocked,
        "expected_block": scenario["expected_block"],
        "correct": blocked == scenario["expected_block"],
        "latency_ns": latency_ns,
    }


# ════════════════════════════════════════════════════════════════════════════
#  运行完整实验
# ════════════════════════════════════════════════════════════════════════════
def run_experiment() -> dict[str, Any]:
    """运行所有配置 × 场景 × 试验。"""
    configs = ["hand_tuned", "adaptive"]
    all_trials: list[dict[str, Any]] = []

    for scenario in PROBE_SCENARIOS:
        for config in configs:
            for trial_idx in range(N_TRIALS):
                # 相同 seed 跨 config → 公平比较 (相同的拒绝序列)
                seed = RANDOM_SEED_BASE + trial_idx
                result = run_trial(config, scenario, seed)
                result["trial_idx"] = trial_idx
                all_trials.append(result)

    # ── 汇总统计 (按 config × scenario) ──
    summary: list[dict[str, Any]] = []
    for scenario in PROBE_SCENARIOS:
        for config in configs:
            trials = [t for t in all_trials
                      if t["scenario"] == scenario["name"] and t["config"] == config]
            n = len(trials)
            blocked_count = sum(1 for t in trials if t["blocked"])
            correct_count = sum(1 for t in trials if t["correct"])
            thresholds = [t["final_threshold"] for t in trials]
            latencies = [t["latency_ns"] for t in trials]
            cumulatives = [t["cumulative_rejections"] for t in trials]
            ewmas = [t["final_ewma"] for t in trials]

            lo, hi = wilson_ci(blocked_count, n)
            mean_thr = sum(thresholds) / n if thresholds else 0.0
            var_thr = sum((t - mean_thr) ** 2 for t in thresholds) / n if thresholds else 0.0

            summary.append({
                "config": config,
                "scenario": scenario["name"],
                "scenario_type": scenario["type"],
                "n_trials": n,
                "blocked_count": blocked_count,
                "block_rate": round(blocked_count / n, 4) if n else 0.0,
                "block_ci": f"[{lo:.3f}, {hi:.3f}]",
                "correct_count": correct_count,
                "accuracy": round(correct_count / n, 4) if n else 0.0,
                "mean_threshold": round(mean_thr, 4),
                "threshold_min": round(min(thresholds), 4) if thresholds else 0.0,
                "threshold_max": round(max(thresholds), 4) if thresholds else 0.0,
                "threshold_std": round(math.sqrt(var_thr), 4),
                "mean_latency_ns": round(sum(latencies) / n, 2) if n else 0.0,
                "mean_cumulative_rejections": round(sum(cumulatives) / n, 4) if n else 0.0,
                "mean_final_ewma": round(sum(ewmas) / n, 4) if n else 0.0,
            })

    # ── 整体指标 (按 config 聚合) ──
    overall: dict[str, dict] = {}
    for config in configs:
        attack_trials = [t for t in all_trials
                         if t["config"] == config and t["scenario_type"] == "attack"]
        benign_trials = [t for t in all_trials
                         if t["config"] == config and t["scenario_type"] == "benign"]
        n_atk = len(attack_trials)
        n_ben = len(benign_trials)
        overall[config] = {
            "interception_rate": round(sum(1 for t in attack_trials if t["blocked"]) / n_atk, 4) if n_atk else 0.0,
            "interception_count": sum(1 for t in attack_trials if t["blocked"]),
            "n_attack": n_atk,
            "false_positive_rate": round(sum(1 for t in benign_trials if t["blocked"]) / n_ben, 4) if n_ben else 0.0,
            "false_positive_count": sum(1 for t in benign_trials if t["blocked"]),
            "n_benign": n_ben,
            "accuracy": round(
                sum(1 for t in attack_trials + benign_trials if t["correct"]) / (n_atk + n_ben), 4
            ) if (n_atk + n_ben) else 0.0,
        }

    # ── Adaptive 阈值轨迹特征 (仅 adaptive config) ──
    adaptive_traj_features: dict[str, Any] = {}
    for scenario in PROBE_SCENARIOS:
        trials = [t for t in all_trials
                  if t["scenario"] == scenario["name"] and t["config"] == "adaptive"]
        if not trials:
            continue
        # 取 trial 0 的轨迹作为代表 (所有 trial 因 seed 相同而轨迹一致)
        rep_traj = trials[0]["threshold_trajectory"]
        rep_ewma = trials[0]["ewma_trajectory"]
        t_min = min(rep_traj)
        t_max = max(rep_traj)
        adaptive_traj_features[scenario["name"]] = {
            "representative_threshold_trajectory": rep_traj,
            "representative_ewma_trajectory": rep_ewma,
            "trajectory_min": round(t_min, 4),
            "trajectory_max": round(t_max, 4),
            "trajectory_range": round(t_max - t_min, 4),
            "first_value": round(rep_traj[0], 4),
            "last_value": round(rep_traj[-1], 4),
            "varies": t_max != t_min,
            "n_steps": len(rep_traj),
        }

    return {
        "trials": all_trials,
        "summary": summary,
        "overall": overall,
        "adaptive_trajectory_features": adaptive_traj_features,
    }


# ════════════════════════════════════════════════════════════════════════════
#  保存结果
# ════════════════════════════════════════════════════════════════════════════
def save_results(experiment_data: dict) -> tuple[Path, Path]:
    """保存 CSV 和 JSON 结果文件。"""
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # ── CSV ──
    csv_path = OUTPUT_DIR / "adaptive_threshold_results.csv"
    lines = [
        "# EWMA Adaptive Threshold Comparison Experiment",
        f"# Config A: hand_tuned (PRESSURE_THRESHOLD={HAND_TUNED_PRESSURE_THRESHOLD}, fixed)",
        f"# Config B: adaptive (EWMA alpha={EWMA_ALPHA}, base={BASE_THRESHOLD}, "
        f"k={K_SENSITIVITY}, baseline={BASELINE_REJECTION_RATE}, clamp=[{ADAPTIVE_MIN},{ADAPTIVE_MAX}])",
        f"# N_TRIALS={N_TRIALS}, N_STEPS={N_STEPS}, RANDOM_SEED_BASE={RANDOM_SEED_BASE}",
        "",
        "config,scenario,scenario_type,n_trials,blocked_count,block_rate,block_ci,"
        "correct_count,accuracy,mean_threshold,threshold_min,threshold_max,threshold_std,"
        "mean_latency_ns,mean_cumulative_rejections,mean_final_ewma",
    ]
    for r in experiment_data["summary"]:
        lines.append(
            f"{r['config']},{r['scenario']},{r['scenario_type']},{r['n_trials']},"
            f"{r['blocked_count']},{r['block_rate']:.4f},\"{r['block_ci']}\","
            f"{r['correct_count']},{r['accuracy']:.4f},"
            f"{r['mean_threshold']:.4f},{r['threshold_min']:.4f},"
            f"{r['threshold_max']:.4f},{r['threshold_std']:.4f},"
            f"{r['mean_latency_ns']:.2f},{r['mean_cumulative_rejections']:.4f},"
            f"{r['mean_final_ewma']:.4f}"
        )
    csv_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # ── JSON ──
    json_path = OUTPUT_DIR / "adaptive_threshold_results.json"
    json_data = {
        "experiment": "adaptive_threshold_comparison",
        "motivation": (
            "Respond to reviewer criticism: PRESSURE_THRESHOLD=50 is a 'hand-tuned heuristic'. "
            "This experiment compares the static threshold against an EWMA-based adaptive threshold "
            "to determine if adaptivity improves interception/FP rates."
        ),
        "design": {
            "config_A": {
                "name": "hand_tuned",
                "description": "Static PRESSURE_THRESHOLD=50 (as in EscalationPolicy.java line 59)",
                "pressure_threshold": HAND_TUNED_PRESSURE_THRESHOLD,
            },
            "config_B": {
                "name": "adaptive",
                "description": "EWMA-based dynamic threshold",
                "ewma_alpha": EWMA_ALPHA,
                "base_threshold": BASE_THRESHOLD,
                "k_sensitivity": K_SENSITIVITY,
                "baseline_rejection_rate": BASELINE_REJECTION_RATE,
                "clamp": [ADAPTIVE_MIN, ADAPTIVE_MAX],
                "formula": "adaptive_threshold = base * (1 - k * (ewma - baseline) / baseline)",
                "formula_behavior_note": (
                    "Corrected formula (sign flipped from '+' to '-'): raises the threshold "
                    "when ewma < baseline (low pressure → less sensitive, more permissive) and "
                    "lowers it when ewma > baseline (high pressure → more sensitive, more "
                    "interceptive). This matches the stated intent 'pressure up → threshold "
                    "down → more sensitive'. The previous '+' version caused a 33.3% FP rate "
                    "on borderline_benign; this '-' version fixes that."
                ),
            },
            "n_steps": N_STEPS,
            "n_trials": N_TRIALS,
            "random_seed_base": RANDOM_SEED_BASE,
            "probe_set_description": (
                "Matching run_coupling_rule_ablation.py Part A probes (3 core scenarios) "
                "+ 2 borderline scenarios to test threshold sensitivity near the boundary."
            ),
            "probe_scenarios": [
                {
                    "name": s["name"], "type": s["type"], "depth": s["depth"],
                    "tool": s["tool"], "rate_mean": s["rate_mean"],
                    "rate_std": s["rate_std"], "expected_block": s["expected_block"],
                    "note": s["note"],
                }
                for s in PROBE_SCENARIOS
            ],
            "reproducibility": (
                f"Fixed random seeds: seed = {RANDOM_SEED_BASE} + trial_idx. "
                "Same seed used across configs for fair comparison."
            ),
        },
        "overall_metrics": experiment_data["overall"],
        "per_scenario_summary": experiment_data["summary"],
        "adaptive_threshold_trajectory_features": experiment_data["adaptive_trajectory_features"],
        "per_trial_results": experiment_data["trials"],
    }

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)

    return csv_path, json_path


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    print("=" * 72)
    print("  EWMA 自适应阈值对比实验")
    print("  回应审稿人对 hand-tuned PRESSURE_THRESHOLD=50 的批评")
    print("=" * 72)
    print(f"  Config A: hand_tuned (PRESSURE_THRESHOLD={HAND_TUNED_PRESSURE_THRESHOLD}, 固定)")
    print(f"  Config B: adaptive (EWMA α={EWMA_ALPHA}, base={BASE_THRESHOLD}, "
          f"k={K_SENSITIVITY}, baseline={BASELINE_REJECTION_RATE})")
    print(f"  N_TRIALS={N_TRIALS} per config × {len(PROBE_SCENARIOS)} scenarios "
          f"× 2 configs = {N_TRIALS * len(PROBE_SCENARIOS) * 2} trials")
    print("=" * 72)

    # 运行实验
    experiment_data = run_experiment()
    csv_path, json_path = save_results(experiment_data)

    # ── 打印按场景汇总 ──
    print("\n── 按场景汇总 ──────────────────────────────────────────")
    header = (f"{'Scenario':<26} {'Type':<8} {'Config':<12} {'Block%':<8} "
              f"{'CI':<20} {'Thr(mean)':<10} {'Thr[range]':<16} {'Acc':<6}")
    print(header)
    print("-" * len(header) * 2)
    for r in experiment_data["summary"]:
        print(f"{r['scenario']:<26} {r['scenario_type']:<8} {r['config']:<12} "
              f"{r['block_rate']:<8.2f} {r['block_ci']:<20} "
              f"{r['mean_threshold']:<10.2f} "
              f"[{r['threshold_min']:.1f},{r['threshold_max']:.1f}]{'':<6} "
              f"{r['accuracy']:<6.2f}")

    # ── 打印整体指标 ──
    print("\n── 整体指标 (跨所有场景聚合) ─────────────────────────")
    ht = experiment_data["overall"]["hand_tuned"]
    ad = experiment_data["overall"]["adaptive"]
    print(f"  {'Config':<12} {'Interception':>14} {'False Positive':>16} {'Accuracy':>10}")
    print(f"  {'hand_tuned':<12} {ht['interception_rate']:>13.2%} "
          f"{ht['false_positive_rate']:>15.2%} {ht['accuracy']:>9.2%}")
    print(f"  {'adaptive':<12} {ad['interception_rate']:>13.2%} "
          f"{ad['false_positive_rate']:>15.2%} {ad['accuracy']:>9.2%}")

    # ── Adaptive 阈值轨迹特征 ──
    print("\n── Adaptive 阈值轨迹特征 (随时间变化) ──────────────────")
    for scenario_name, feat in experiment_data["adaptive_trajectory_features"].items():
        varies = "变化" if feat["varies"] else "恒定"
        print(f"  {scenario_name:<26}: {varies}, "
              f"range=[{feat['trajectory_min']:.2f}, {feat['trajectory_max']:.2f}], "
              f"跨度={feat['trajectory_range']:.2f}, "
              f"首={feat['first_value']:.2f} → 末={feat['last_value']:.2f}")

    # ── 关键发现 ──
    print("\n── 关键发现 ──────────────────────────────────────────────")
    print(f"  Interception Rate:  hand_tuned={ht['interception_rate']:.2%}  "
          f"vs  adaptive={ad['interception_rate']:.2%}")
    print(f"  False Positive Rate: hand_tuned={ht['false_positive_rate']:.2%}  "
          f"vs  adaptive={ad['false_positive_rate']:.2%}")

    any_varies = any(f["varies"] for f in experiment_data["adaptive_trajectory_features"].values())
    print(f"  Adaptive 阈值是否真正变化: {'是' if any_varies else '否'}")

    if ad["interception_rate"] > ht["interception_rate"] and \
       ad["false_positive_rate"] <= ht["false_positive_rate"]:
        verdict = "Adaptive 优于 hand-tuned (拦截率更高且 FP 不增加)"
    elif ad["interception_rate"] == ht["interception_rate"] and \
         ad["false_positive_rate"] == ht["false_positive_rate"]:
        verdict = "Adaptive 与 hand-tuned 决策一致 (简单规则已足够)"
    elif ad["interception_rate"] >= ht["interception_rate"] and \
         ad["false_positive_rate"] > ht["false_positive_rate"]:
        verdict = "Adaptive 拦截率不降但 FP 升高 (公式或参数可能需调整)"
    else:
        verdict = "Adaptive 与 hand-tuned 存在差异 (详见各场景)"
    print(f"  结论: {verdict}")

    # ── 公式符号说明 ──
    print("\n── 公式行为说明 ─────────────────────────────────────────")
    print("  修正后公式: threshold = base * (1 - k * (ewma - baseline) / baseline)")
    print("  当 ewma > baseline (高压力): 阈值降低 → 更敏感 (更易拦截攻击)")
    print("  当 ewma < baseline (低压力): 阈值升高 → 更宽松 (更不易误拦良性)")
    print("  符合 '压力升高时阈值降低' 的设计意图。")

    print(f"\n  CSV:  {csv_path}")
    print(f"  JSON: {json_path}")
    print("=" * 72)

    return 0


if __name__ == "__main__":
    sys.exit(main())
