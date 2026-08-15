"""Summarize the Java 2x2 capacity/signal ablation at run level."""

from __future__ import annotations

import csv
import json
import math
import re
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / "evaluation" / "target" / "jvm_starvation_ablation"
OUT = ROOT / "evaluation" / "target" / "ccpe_ablation"
GENERATED = ROOT / "addtions" / "paper_ccpe" / "generated" / "ccpe_ablation_results.tex"
RUN_RE = re.compile(r"^ccpe_ablation_\d{2}$")
CONFIGS = ["shared_no_signal", "shared_signal", "isolated_no_signal", "isolated_signal"]
LOADS = [(1, 100, 0.5), (4, 500, 2.0), (16, 2000, 5.0)]
T_CRIT_95_DF4 = 2.7764451051977987


def percentile(values: list[float], q: float) -> float:
    values = sorted(values)
    if not values:
        return float("nan")
    pos = q * (len(values) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return values[lo]
    return values[lo] * (hi - pos) + values[hi] * (pos - lo)


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else float("nan")


def ci95(values: list[float]) -> tuple[float, float]:
    if len(values) < 2:
        return (values[0], values[0])
    m = mean(values)
    sd = math.sqrt(sum((x - m) ** 2 for x in values) / (len(values) - 1))
    half = T_CRIT_95_DF4 * sd / math.sqrt(len(values))
    return (m - half, m + half)


def load_rows() -> list[dict]:
    rows: list[dict] = []
    for path in sorted(INPUT.glob("jvm_permission_starvation_ccpe_ablation_*.raw.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row.get("experiment") == "ablation_2x2" and RUN_RE.fullmatch(row.get("run_id", "")):
                rows.append(row)
    return rows


def run_metrics(rows: list[dict]) -> dict[str, float]:
    latencies = [float(row["latency_ms"]) for row in rows]
    queues = [float(row["permission_queue_depth"]) for row in rows]
    return {
        "n": len(rows),
        "timeout_rate": mean([float(row["timed_out"]) for row in rows]),
        "secure_rate": mean([float(row["secure_decision"]) for row in rows]),
        "latency_p95_ms": percentile(latencies, 0.95),
        "queue_p95": percentile(queues, 0.95),
        "mean_resource_rejections": mean([float(row["resource_rejections"]) for row in rows]),
    }


def fmt_pct(value: float) -> str:
    return f"{100.0 * value:.1f}\\%"


def tex_macro(name: str, value: str) -> str:
    return f"\\newcommand{{\\{name}}}{{{value}}}\n"


def main() -> None:
    rows = load_rows()
    if not rows:
        raise SystemExit(f"no ablation rows under {INPUT}")
    runs = sorted({row["run_id"] for row in rows})
    if len(runs) != 5:
        raise SystemExit(f"expected five independent ablation runs, found {runs}")
    counts = {run: sum(1 for row in rows if row["run_id"] == run) for run in runs}
    if set(counts.values()) != {360}:
        raise SystemExit(f"unexpected observations per run: {counts}")

    by_cell_run: dict[tuple[str, int, int, float, str], list[dict]] = defaultdict(list)
    for row in rows:
        key = (
            row["architecture"], int(row["attackers"]), int(row["qps_per_attacker"]),
            float(row["lock_hold_ms"]), row["run_id"]
        )
        by_cell_run[key].append(row)

    run_summary: list[dict] = []
    for config in CONFIGS:
        for attackers, qps, hold in LOADS:
            for run in runs:
                metrics = run_metrics(by_cell_run[(config, attackers, qps, hold, run)])
                run_summary.append({
                    "architecture": config,
                    "attackers": attackers,
                    "qps_per_attacker": qps,
                    "lock_hold_ms": hold,
                    "run_id": run,
                    **metrics,
                })

    summary: list[dict] = []
    for config in CONFIGS:
        for attackers, qps, hold in LOADS:
            subset = [
                row for row in run_summary
                if row["architecture"] == config
                and row["attackers"] == attackers
                and row["qps_per_attacker"] == qps
                and row["lock_hold_ms"] == hold
            ]
            record = {
                "architecture": config,
                "attackers": attackers,
                "qps_per_attacker": qps,
                "lock_hold_ms": hold,
                "runs": len(subset),
            }
            for metric in ("timeout_rate", "secure_rate", "latency_p95_ms", "queue_p95", "mean_resource_rejections"):
                values = [float(row[metric]) for row in subset]
                low, high = ci95(values)
                record[f"{metric}_mean"] = mean(values)
                record[f"{metric}_ci95_low"] = low
                record[f"{metric}_ci95_high"] = high
            summary.append(record)

    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "ccpe_ablation_run_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=run_summary[0].keys())
        writer.writeheader()
        writer.writerows(run_summary)
    with (OUT / "ccpe_ablation_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=summary[0].keys())
        writer.writeheader()
        writer.writerows(summary)

    high = {
        row["architecture"]: row for row in summary
        if row["attackers"] == 16 and row["qps_per_attacker"] == 2000 and row["lock_hold_ms"] == 5.0
    }
    tex = [
        "% Generated by evaluation/analyze_ccpe_ablation.py; do not edit.\n",
        tex_macro("CCPEAblationObservationCount", f"{len(rows):,}"),
        tex_macro("CCPEAblationRunCount", str(len(runs))),
    ]
    for config, token in {
        "shared_no_signal": "SharedNoSignal",
        "shared_signal": "SharedSignal",
        "isolated_no_signal": "IsolatedNoSignal",
        "isolated_signal": "IsolatedSignal",
    }.items():
        row = high[config]
        tex.append(tex_macro(f"CCPEAblationHigh{token}Timeout", fmt_pct(row["timeout_rate_mean"])))
        tex.append(tex_macro(f"CCPEAblationHigh{token}QueuePninetyfive", f"{row['queue_p95_mean']:.1f}"))
        tex.append(tex_macro(f"CCPEAblationHigh{token}LatencyPninetyfive", f"{row['latency_p95_ms_mean']:.2f}"))
        tex.append(tex_macro(
            f"CCPEAblationHigh{token}TimeoutCI",
            f"[{100.0 * row['timeout_rate_ci95_low']:.1f}, {100.0 * row['timeout_rate_ci95_high']:.1f}]\\%",
        ))
    GENERATED.parent.mkdir(parents=True, exist_ok=True)
    GENERATED.write_text("".join(tex), encoding="utf-8")
    print(f"loaded {len(rows):,} observations from {len(runs)} runs")
    print(f"wrote {OUT / 'ccpe_ablation_summary.csv'}")
    print(f"wrote {GENERATED}")


if __name__ == "__main__":
    main()
