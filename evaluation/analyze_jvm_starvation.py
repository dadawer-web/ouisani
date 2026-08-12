#!/usr/bin/env python3
"""Derive paper tables/figures from the JVM benchmark's raw JSONL only."""

from __future__ import annotations

import csv
import json
import math
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "evaluation/target/jvm_starvation/jvm_permission_starvation.raw.jsonl"
OUT = ROOT / "evaluation/target/jvm_starvation"
PAPER_GENERATED = ROOT / "addtions/paper/generated"
PAPER_FIGURES = ROOT / "addtions/paper/figures"


def wilson(successes: int, n: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if n == 0:
        return (math.nan, math.nan)
    p = successes / n
    d = 1 + z * z / n
    center = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return center - half, center + half


def percentile(values: list[float], q: float) -> float:
    xs = sorted(values)
    if not xs:
        return math.nan
    pos = q * (len(xs) - 1)
    lo, hi = math.floor(pos), math.ceil(pos)
    return xs[lo] if lo == hi else xs[lo] * (hi - pos) + xs[hi] * (pos - lo)


def load_rows() -> list[dict]:
    if not RAW.exists():
        raise SystemExit(f"missing raw JVM output: {RAW}")
    return [json.loads(line) for line in RAW.read_text(encoding="utf-8").splitlines() if line.strip()]


def choose_threshold(rows: list[dict]) -> tuple[int, dict]:
    train = [r for r in rows if r.get("split") == "train"
             and r["architecture"] == "NEURON_COUPLED"
             and (r["experiment"] == "threshold_training"
                  or (r["experiment"] == "deadline_sweep" and r["deadline_ms"] == 10))]
    # Predeclared operational candidates. On an accuracy tie, retain the
    # production default (50) rather than inventing a more aggressive value.
    candidates = (10, 25, 50, 100, 200)
    best = None
    for threshold in candidates:
        attack = [r for r in train if r["workload_class"] == "attack"]
        benign = [r for r in train if r["workload_class"] == "benign"]
        tpr = sum(r["resource_rejections"] > threshold for r in attack) / len(attack)
        tnr = sum(r["resource_rejections"] <= threshold for r in benign) / len(benign)
        score = (tpr + tnr) / 2
        candidate = (score, -abs(threshold - 50), -threshold, threshold,
                     {"train_tpr": tpr, "train_tnr": tnr})
        if best is None or candidate > best:
            best = candidate
    assert best is not None
    return best[3], best[4]


def main() -> None:
    rows = load_rows()
    OUT.mkdir(parents=True, exist_ok=True)
    PAPER_GENERATED.mkdir(parents=True, exist_ok=True)
    PAPER_FIGURES.mkdir(parents=True, exist_ok=True)

    group_fields = ("experiment", "architecture", "deadline_ms", "attackers",
                    "qps_per_attacker", "lock_hold_ms", "split", "morphology",
                    "workload_class")
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for row in rows:
        groups[tuple(row.get(k, "") for k in group_fields)].append(row)

    summary_path = OUT / "jvm_permission_starvation_analysis.csv"
    with summary_path.open("w", newline="", encoding="utf-8") as handle:
        fields = list(group_fields) + ["n", "secure_rate", "secure_ci_low", "secure_ci_high",
                                        "timeout_rate", "timeout_ci_low", "timeout_ci_high",
                                        "latency_p50_ms", "latency_p95_ms"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for key, observations in groups.items():
            secure = sum(bool(r["secure_decision"]) for r in observations)
            timeout = sum(bool(r["timed_out"]) for r in observations)
            slo, shi = wilson(secure, len(observations))
            tlo, thi = wilson(timeout, len(observations))
            record = dict(zip(group_fields, key))
            record.update(n=len(observations), secure_rate=secure / len(observations),
                          secure_ci_low=slo, secure_ci_high=shi,
                          timeout_rate=timeout / len(observations),
                          timeout_ci_low=tlo, timeout_ci_high=thi,
                          latency_p50_ms=percentile([r["latency_ms"] for r in observations], .50),
                          latency_p95_ms=percentile([r["latency_ms"] for r in observations], .95))
            writer.writerow(record)

    threshold, train_metrics = choose_threshold(rows)
    test = [r for r in rows if r.get("split") == "test"
            and r["architecture"] == "NEURON_COUPLED"
            and r["experiment"] in {"pressure_surface", "threshold_validation"}]
    attack = [r for r in test if r["workload_class"] == "attack"]
    benign = [r for r in test if r["workload_class"] == "benign"]
    attack_hits = sum(r["resource_rejections"] > threshold for r in attack)
    benign_hits = sum(r["resource_rejections"] > threshold for r in benign)
    threshold_report = {
        "selection_rule": (
            "maximize balanced accuracy over preregistered candidates "
            "[10,25,50,100,200]; retain production default 50 on a tie"
        ),
        "selected_threshold": threshold,
        **train_metrics,
        "test_attack_n": len(attack),
        "test_attack_detection_rate": attack_hits / len(attack),
        "test_attack_detection_ci": wilson(attack_hits, len(attack)),
        "test_benign_n": len(benign),
        "test_false_positive_rate": benign_hits / len(benign),
        "test_false_positive_ci": wilson(benign_hits, len(benign)),
        "test_morphologies": sorted({r["morphology"] for r in test}),
    }
    (OUT / "threshold_independent_validation.json").write_text(
        json.dumps(threshold_report, indent=2), encoding="utf-8")

    severe = [r for r in rows if r["experiment"] == "deadline_sweep" and r["deadline_ms"] == 10]
    by_arch = defaultdict(list)
    for row in severe:
        by_arch[row["architecture"]].append(row)

    def rate(arch: str, field: str) -> float:
        vals = by_arch[arch]
        return sum(bool(r[field]) for r in vals) / len(vals)

    joint = [r for r in rows if r["experiment"] == "joint_decision_probe"]
    def joint_deny(arch: str, pressure: int) -> float:
        vals = [r for r in joint if r["architecture"] == arch
                and r["resource_rejections"] == pressure]
        return sum(r["verdict"] == "DENY_DEPTH" for r in vals) / len(vals)

    macros = [
        "% Generated by evaluation/analyze_jvm_starvation.py; do not edit.",
        f"\\newcommand{{\\JvmObservationCount}}{{{len(rows):,}}}",
        f"\\newcommand{{\\SelectedPressureThreshold}}{{{threshold}}}",
        f"\\newcommand{{\\TestAttackDetectionRate}}{{{100 * threshold_report['test_attack_detection_rate']:.1f}\\%}}",
        f"\\newcommand{{\\TestBenignFalsePositiveRate}}{{{100 * threshold_report['test_false_positive_rate']:.1f}\\%}}",
        f"\\newcommand{{\\FailOpenTimeoutTen}}{{{100 * rate('LAYER_SEPARATED_FAIL_OPEN', 'timed_out'):.1f}\\%}}",
        f"\\newcommand{{\\FailClosedTimeoutTen}}{{{100 * rate('LAYER_SEPARATED_FAIL_CLOSED', 'timed_out'):.1f}\\%}}",
        f"\\newcommand{{\\IsolatedTimeoutTen}}{{{100 * rate('ISOLATED_NO_COUPLING', 'timed_out'):.1f}\\%}}",
        f"\\newcommand{{\\CoupledTimeoutTen}}{{{100 * rate('NEURON_COUPLED', 'timed_out'):.1f}\\%}}",
        f"\\newcommand{{\\FailOpenSecureTen}}{{{100 * rate('LAYER_SEPARATED_FAIL_OPEN', 'secure_decision'):.1f}\\%}}",
        f"\\newcommand{{\\FailClosedSecureTen}}{{{100 * rate('LAYER_SEPARATED_FAIL_CLOSED', 'secure_decision'):.1f}\\%}}",
        f"\\newcommand{{\\IsolatedSecureTen}}{{{100 * rate('ISOLATED_NO_COUPLING', 'secure_decision'):.1f}\\%}}",
        f"\\newcommand{{\\CoupledSecureTen}}{{{100 * rate('NEURON_COUPLED', 'secure_decision'):.1f}\\%}}",
        f"\\newcommand{{\\IsolatedPressureDeny}}{{{100 * joint_deny('ISOLATED_NO_COUPLING', 100):.1f}\\%}}",
        f"\\newcommand{{\\CoupledPressureDeny}}{{{100 * joint_deny('NEURON_COUPLED', 100):.1f}\\%}}",
    ]
    (PAPER_GENERATED / "jvm_results.tex").write_text("\n".join(macros) + "\n", encoding="utf-8")

    print(f"wrote {summary_path}")
    print(f"selected threshold={threshold}; test detection={threshold_report['test_attack_detection_rate']:.3f}; "
          f"test FP={threshold_report['test_false_positive_rate']:.3f}")


if __name__ == "__main__":
    main()
