#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
statistical_analysis.py — ASR 统计严谨性分析

功能
----
1. Wilson 置信区间（二项比例）—— 用于 Scenario 6 和补充实验的 ASR/拦截率
2. Bootstrap 置信区间 —— 用于延迟数据 (Table 1 / scenario4_latency_raw.jsonl)
3. Fisher 精确检验 —— 比较 Baseline vs Coupled Governance 的 ASR 差异显著性
4. McNemar 检验 —— 配对比较 Permission-only vs Coupled Governance

数据来源
--------
- neuron-java/target/redteam/real_llm_spawn_escalation.csv  (补充实验 N=50)
- Scenario 6 硬编码数据 (n=30, Table 2)
- scenario4_latency_raw.jsonl (如存在)

输出
----
- target/redteam/statistical_analysis.json  (结构化结果)
- stdout 人类可读摘要

用法
----
    python evaluation/statistical_analysis.py
"""

from __future__ import annotations

import json
import math
import os
import random
from pathlib import Path
from typing import Any

# ════════════════════════════════════════════════════════════════════════════
#  1. Wilson 置信区间（二项比例）
# ════════════════════════════════════════════════════════════════════════════
def wilson_ci(successes: int, trials: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval for a binomial proportion.

    Returns (lower, upper) at the given z-level (default 95%).
    For 0 successes: returns (0, upper) — never returns a point estimate of 0.
    """
    if trials == 0:
        return (0.0, 0.0)
    n = trials
    p = successes / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    margin = (z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))) / denom
    return (max(0.0, center - margin), min(1.0, center + margin))


def wilson_ci_string(successes: int, trials: int, z: float = 1.96) -> str:
    lo, hi = wilson_ci(successes, trials, z)
    p = successes / trials if trials > 0 else 0.0
    return f"{p:.4f} [{lo:.4f}, {hi:.4f}]"


# ════════════════════════════════════════════════════════════════════════════
#  2. Bootstrap 置信区间（用于延迟数据）
# ════════════════════════════════════════════════════════════════════════════
def bootstrap_ci(
    data: list[float],
    stat_fn=max,
    n_bootstrap: int = 10000,
    confidence: float = 0.95,
    seed: int = 42,
) -> tuple[float, float]:
    """Bootstrap CI for any statistic (default: max, for p99-like metrics)."""
    if not data:
        return (0.0, 0.0)
    rng = random.Random(seed)
    n = len(data)
    stats: list[float] = []
    for _ in range(n_bootstrap):
        sample = [data[rng.randint(0, n - 1)] for _ in range(n)]
        stats.append(stat_fn(sample))
    stats.sort()
    alpha = (1 - confidence) / 2
    lo_idx = int(n_bootstrap * alpha)
    hi_idx = int(n_bootstrap * (1 - alpha))
    return (stats[lo_idx], stats[hi_idx])


def bootstrap_percentile_ci(
    data: list[float],
    percentile: float,
    n_bootstrap: int = 10000,
    confidence: float = 0.95,
    seed: int = 42,
) -> tuple[float, float]:
    """Bootstrap CI for a specific percentile (e.g., p95=0.95, p99=0.99)."""

    def pct_fn(sample: list[float]) -> float:
        if not sample:
            return 0.0
        s = sorted(sample)
        idx = int(len(s) * percentile)
        idx = min(idx, len(s) - 1)
        return s[idx]

    return bootstrap_ci(data, pct_fn, n_bootstrap, confidence, seed)


# ════════════════════════════════════════════════════════════════════════════
#  3. Fisher 精确检验（2×2 列联表）
# ════════════════════════════════════════════════════════════════════════════
def _lgamma(x: float) -> float:
    return math.lgamma(x)


def fisher_exact(a: int, b: int, c: int, d: int) -> float:
    """Fisher's exact test for a 2×2 table.

    Table:
        | Success | Failure |
    A   |    a    |    b    |
    B   |    c    |    d    |

    Returns two-sided p-value.
    """
    n = a + b + c + d
    if n == 0:
        return 1.0

    # Log factorial via lgamma
    def log_fact(k: int) -> float:
        return _lgamma(k + 1)

    # Hypergeometric probability of observing exactly (a, b, c, d)
    def log_hypergeom(aa: int) -> float:
        row1 = a + b
        col1 = a + c
        return (
            log_fact(col1)
            + log_fact(n - col1)
            + log_fact(row1)
            + log_fact(n - row1)
            - log_fact(n)
            - log_fact(aa)
            - log_fact(col1 - aa)
            - log_fact(row1 - aa)
            - log_fact(n - row1 - col1 + aa)
        )

    # Find the range of possible a values
    col1 = a + c
    row1 = a + b
    lo = max(0, row1 + col1 - n)
    hi = min(row1, col1)

    log_observed = log_hypergeom(a)
    p_value = 0.0
    for aa in range(lo, hi + 1):
        lp = log_hypergeom(aa)
        # Two-sided: include probabilities <= observed
        if lp <= log_observed + 1e-12:
            p_value += math.exp(lp)

    return min(1.0, p_value)


# ════════════════════════════════════════════════════════════════════════════
#  4. McNemar 检验（配对二项比较）
# ════════════════════════════════════════════════════════════════════════════
def mcnemar_exact(b: int, c: int) -> float:
    """McNemar's exact test (binomial).

    b = #discordant pairs where A succeeds, B fails
    c = #discordant pairs where A fails, B succeeds

    Returns two-sided p-value.
    """
    n = b + c
    if n == 0:
        return 1.0
    # Under H0, b ~ Binomial(n, 0.5)
    k = min(b, c)
    p = 0.0
    for i in range(k + 1):
        p += math.comb(n, i) * (0.5 ** n)
    p *= 2  # two-sided
    # If b == c, the doubling overcounts; but b==c means p=1.0 anyway
    if b == c:
        p = 1.0
    return min(1.0, p)


# ════════════════════════════════════════════════════════════════════════════
#  5. 读取现有数据并分析
# ════════════════════════════════════════════════════════════════════════════
def load_supplementary_csv(path: Path) -> list[dict[str, Any]]:
    """Load real_llm_spawn_escalation.csv into list of dicts."""
    rows: list[dict[str, Any]] = []
    if not path.is_file():
        return rows
    lines = path.read_text(encoding="utf-8").strip().split("\n")
    # Skip comment lines and find header
    header: list[str] | None = None
    for line in lines:
        if line.startswith("#") or not line.strip():
            continue
        if header is None:
            header = line.split(",")
            continue
        vals = line.split(",")
        if len(vals) != len(header):
            continue
        row = {header[i]: vals[i] for i in range(len(header))}
        rows.append(row)
    return rows


def analyze_supplementary_experiment(csv_path: Path) -> dict[str, Any]:
    """Analyze the supplementary real-LLM experiment (N=50)."""
    rows = load_supplementary_csv(csv_path)
    if not rows:
        return {"error": f"No data found at {csv_path}"}

    n = len(rows)
    structural = sum(1 for r in rows if r.get("structural_triggered") == "1")
    blocked = sum(1 for r in rows if r.get("policy_denied") == "1")
    attack_success = sum(1 for r in rows if r.get("attack_success") == "1")
    # Non-structural trials (LLM didn't trigger the condition)
    non_structural = n - structural

    # Interception rate = blocked / structural
    interception_rate = blocked / structural if structural > 0 else 0.0
    # ASR = attack_success / n (overall)
    asr_overall = attack_success / n
    # ASR among structural = attack_success / structural
    asr_structural = attack_success / structural if structural > 0 else 0.0

    # Wilson CIs
    ir_ci = wilson_ci(blocked, structural)
    asr_ci = wilson_ci(attack_success, n)
    asr_struct_ci = wilson_ci(attack_success, structural) if structural > 0 else (0.0, 0.0)

    # By variant
    variant_stats: dict[str, dict] = {}
    for r in rows:
        v = r.get("variant", "?")
        if v not in variant_stats:
            variant_stats[v] = {"trials": 0, "structural": 0, "blocked": 0, "success": 0}
        variant_stats[v]["trials"] += 1
        if r.get("structural_triggered") == "1":
            variant_stats[v]["structural"] += 1
        if r.get("policy_denied") == "1":
            variant_stats[v]["blocked"] += 1
        if r.get("attack_success") == "1":
            variant_stats[v]["success"] += 1

    variant_ci: dict[str, Any] = {}
    for v, s in sorted(variant_stats.items()):
        variant_ci[f"V{v}"] = {
            "trials": s["trials"],
            "structural": s["structural"],
            "blocked": s["blocked"],
            "interception": s["blocked"] / s["structural"] if s["structural"] > 0 else 0.0,
            "interception_ci": wilson_ci(s["blocked"], s["structural"]) if s["structural"] > 0 else (0.0, 0.0),
        }

    return {
        "experiment": "Supplementary Real-LLM Escalation (N=50)",
        "n": n,
        "structural_triggered": structural,
        "blocked": blocked,
        "attack_success": attack_success,
        "non_structural": non_structural,
        "interception_rate": interception_rate,
        "interception_wilson_95ci": ir_ci,
        "asr_overall": asr_overall,
        "asr_overall_wilson_95ci": asr_ci,
        "asr_among_structural": asr_structural,
        "asr_structural_wilson_95ci": asr_struct_ci,
        "by_variant": variant_ci,
    }


def analyze_scenario6() -> dict[str, Any]:
    """Analyze Scenario 6 data (Table 2, n=30 per configuration).

    Scenario 6 tests 3 attack vectors:
    - escalation_granted
    - spawn_escalation_success
    - cross_tenant_access

    Each vector is tested n=30 times under 3 configurations.
    """
    n = 30

    # Table 2 data (rates)
    configs = {
        "Baseline": {"escalation_granted": 1.00, "spawn_escalation": 1.00, "cross_tenant": 1.00},
        "Permission-only": {"escalation_granted": 0.00, "spawn_escalation": 0.00, "cross_tenant": 1.00},
        "Coupled": {"escalation_granted": 0.00, "spawn_escalation": 0.00, "cross_tenant": 0.00},
    }

    results: dict[str, Any] = {}
    for config, rates in configs.items():
        # Each rate × n = successes
        vector_results: dict[str, Any] = {}
        for vector, rate in rates.items():
            successes = int(round(rate * n))
            ci = wilson_ci(successes, n)
            vector_results[vector] = {
                "rate": rate,
                "successes": successes,
                "trials": n,
                "wilson_95ci": ci,
            }
        # Overall ASR = mean of 3 vectors
        overall_asr = sum(rates.values()) / 3
        overall_successes = sum(int(round(r * n)) for r in rates.values())
        overall_ci = wilson_ci(overall_successes, 3 * n)
        results[config] = {
            "vectors": vector_results,
            "overall_asr": overall_asr,
            "overall_wilson_95ci": overall_ci,
        }

    # Fisher exact tests
    # Baseline vs Coupled (overall): 90 successes out of 90 vs 0 out of 90
    fisher_baseline_vs_coupled = fisher_exact(90, 0, 0, 90)
    # Permission-only vs Coupled: 30 successes out of 90 vs 0 out of 90
    fisher_perm_vs_coupled = fisher_exact(30, 60, 0, 90)
    # Baseline vs Permission-only: 90 vs 30
    fisher_baseline_vs_perm = fisher_exact(90, 0, 30, 60)

    results["fisher_exact_tests"] = {
        "baseline_vs_coupled": fisher_baseline_vs_coupled,
        "permission_only_vs_coupled": fisher_perm_vs_coupled,
        "baseline_vs_permission_only": fisher_baseline_vs_perm,
    }

    return results


def analyze_false_positive() -> dict[str, Any]:
    """Analyze false-positive data (Table 3)."""
    # 14,850 operations, 0 false positives
    total = 14850
    fp = 0
    ci = wilson_ci(fp, total)
    return {
        "total_operations": total,
        "false_positives": fp,
        "fp_rate": fp / total,
        "wilson_95ci": ci,
        "note": "Upper bound of Wilson CI is the meaningful quantity for 0 FP.",
    }


def load_latency_data(path: Path) -> list[float]:
    """Load latency samples from scenario4_latency_raw.jsonl."""
    data: list[float] = []
    if not path.is_file():
        return data
    for line in path.read_text(encoding="utf-8").strip().split("\n"):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
            if "latency_ms" in obj:
                data.append(float(obj["latency_ms"]))
            elif "latency" in obj:
                data.append(float(obj["latency"]))
        except (json.JSONDecodeError, ValueError):
            continue
    return data


def analyze_latency(path: Path) -> dict[str, Any]:
    """Bootstrap CI for latency percentiles."""
    data = load_latency_data(path)
    if not data:
        return {"error": f"No latency data at {path}"}

    mean = sum(data) / len(data)
    p95_ci = bootstrap_percentile_ci(data, 0.95)
    p99_ci = bootstrap_percentile_ci(data, 0.99)

    return {
        "n": len(data),
        "mean": mean,
        "p95_bootstrap_95ci": p95_ci,
        "p99_bootstrap_95ci": p99_ci,
    }


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    base = Path("e:/ouisani")
    csv_path = base / "neuron-java/target/redteam/real_llm_spawn_escalation.csv"
    latency_path = base / "neuron-java/target/redteam/scenario4_latency_raw.jsonl"
    out_path = base / "neuron-java/target/redteam/statistical_analysis.json"

    print("═" * 65)
    print("  Neuron ASR 统计严谨性分析")
    print("═" * 65)

    results: dict[str, Any] = {}

    # 1. Supplementary experiment (N=50)
    print("\n[1] 补充实验 (Supplementary Real-LLM, N=50)")
    print("─" * 65)
    supp = analyze_supplementary_experiment(csv_path)
    results["supplementary_experiment"] = supp
    if "error" not in supp:
        print(f"  N = {supp['n']}")
        print(f"  Structural triggered  = {supp['structural_triggered']}")
        print(f"  Blocked               = {supp['blocked']}")
        print(f"  Attack success        = {supp['attack_success']}")
        print(f"  Interception rate     = {supp['interception_rate']:.4f}")
        lo, hi = supp["interception_wilson_95ci"]
        print(f"    Wilson 95% CI       = [{lo:.4f}, {hi:.4f}]")
        print(f"  ASR (overall)         = {supp['asr_overall']:.4f}")
        lo, hi = supp["asr_overall_wilson_95ci"]
        print(f"    Wilson 95% CI       = [{lo:.4f}, {hi:.4f}]")
        print(f"  ASR (among structural)= {supp['asr_among_structural']:.4f}")
        lo, hi = supp["asr_structural_wilson_95ci"]
        print(f"    Wilson 95% CI       = [{lo:.4f}, {hi:.4f}]")
        print("\n  By variant:")
        for v, s in supp.get("by_variant", {}).items():
            lo, hi = s["interception_ci"]
            print(f"    {v}: {s['blocked']}/{s['structural']} = {s['interception']:.4f} "
                  f"[{lo:.4f}, {hi:.4f}]")

    # 2. Scenario 6
    print("\n[2] Scenario 6 (Cross-Attack Interception, n=30)")
    print("─" * 65)
    sc6 = analyze_scenario6()
    results["scenario6"] = sc6
    for config, data in sc6.items():
        if config == "fisher_exact_tests":
            continue
        print(f"\n  {config}:")
        print(f"    Overall ASR = {data['overall_asr']:.4f}")
        lo, hi = data["overall_wilson_95ci"]
        print(f"      Wilson 95% CI = [{lo:.4f}, {hi:.4f}]")
        for vec, vd in data["vectors"].items():
            lo, hi = vd["wilson_95ci"]
            print(f"    {vec}: {vd['successes']}/{vd['trials']} = {vd['rate']:.2f} "
                  f"[{lo:.4f}, {hi:.4f}]")

    print("\n  Fisher exact tests (two-sided p-values):")
    ft = sc6["fisher_exact_tests"]
    print(f"    Baseline vs Coupled         p = {ft['baseline_vs_coupled']:.2e}")
    print(f"    Permission-only vs Coupled  p = {ft['permission_only_vs_coupled']:.2e}")
    print(f"    Baseline vs Permission-only p = {ft['baseline_vs_permission_only']:.2e}")

    # 3. False positives
    print("\n[3] False-Positive Analysis (14,850 operations)")
    print("─" * 65)
    fp = analyze_false_positive()
    results["false_positive"] = fp
    lo, hi = fp["wilson_95ci"]
    print(f"  Total = {fp['total_operations']}, FP = {fp['false_positives']}")
    print(f"  FP rate = {fp['fp_rate']:.6f}")
    print(f"  Wilson 95% CI = [{lo:.6f}, {hi:.6f}]")
    print(f"  → Upper bound = {hi:.6f} ({hi*100:.4f}%)")

    # 4. Latency bootstrap (if data exists)
    print("\n[4] Latency Bootstrap (Scenario 4)")
    print("─" * 65)
    lat = analyze_latency(latency_path)
    results["latency_bootstrap"] = lat
    if "error" not in lat:
        print(f"  N = {lat['n']}")
        print(f"  Mean = {lat['mean']:.4f} ms")
        lo, hi = lat["p95_bootstrap_95ci"]
        print(f"  P95 bootstrap 95% CI = [{lo:.4f}, {hi:.4f}] ms")
        lo, hi = lat["p99_bootstrap_95ci"]
        print(f"  P99 bootstrap 95% CI = [{lo:.4f}, {hi:.4f}] ms")
    else:
        print(f"  {lat['error']}")

    # Write JSON
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n[Output] {out_path}")
    print("═" * 65)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
