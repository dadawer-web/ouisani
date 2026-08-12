#!/usr/bin/env python3
"""Aggregate repeatable Computing-targeted JVM runs.

The unit of replication is ``run_id``.  Observation-level summaries are kept
for latency plots, while the cross-run table reports one value per independent
JVM invocation before calculating means and ranges.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path


def percentile(values: list[float], q: float) -> float:
    values = sorted(values)
    if not values:
        return math.nan
    pos = q * (len(values) - 1)
    lo, hi = math.floor(pos), math.ceil(pos)
    return values[lo] if lo == hi else values[lo] * (hi - pos) + values[hi] * (pos - lo)


def wilson(successes: int, n: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if not n:
        return math.nan, math.nan
    p = successes / n
    d = 1 + z * z / n
    center = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return center - half, center + half


def run_mean_ci(values: list[float]) -> tuple[float, float]:
    """Mean and a two-sided 95% t interval over independent JVM runs."""
    if not values:
        return math.nan, math.nan
    mean = sum(values) / len(values)
    if len(values) < 2:
        return mean, mean
    variance = sum((value - mean) ** 2 for value in values) / (len(values) - 1)
    # Critical values for the small replication counts used here (df 1--10).
    t95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
           6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}.get(
               len(values) - 1, 1.96)
    half = t95 * math.sqrt(variance / len(values))
    return mean - half, mean + half


def read_rows(input_dir: Path, include: str, exclude: set[str]) -> list[dict]:
    rows: list[dict] = []
    for path in sorted(input_dir.glob(include)):
        if path.name in exclude:
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                row.setdefault("run_id", path.stem.replace("jvm_permission_starvation_", ""))
                row.setdefault("host_id", "unspecified")
                rows.append(row)
    if not rows:
        raise SystemExit(f"no raw JSONL files found in {input_dir}")
    return rows


def key(row: dict) -> tuple:
    return tuple(row.get(name, "") for name in (
        "host_id", "benchmark_mode", "experiment", "architecture",
        "deadline_ms", "attackers", "qps_per_attacker", "lock_hold_ms",
        "split", "morphology", "workload_class", "shared_pool_size",
        "permission_pool_size", "queue_capacity"))


def summarize(observations: list[dict]) -> dict:
    n = len(observations)
    secure = sum(bool(r.get("secure_decision")) for r in observations)
    timeout = sum(bool(r.get("timed_out")) for r in observations)
    secure_low, secure_high = wilson(secure, n)
    timeout_low, timeout_high = wilson(timeout, n)
    return {
        "n": n,
        "run_count": len({r.get("run_id") for r in observations}),
        "secure_rate": secure / n if n else math.nan,
        "secure_ci_low": secure_low,
        "secure_ci_high": secure_high,
        "timeout_rate": timeout / n if n else math.nan,
        "timeout_ci_low": timeout_low,
        "timeout_ci_high": timeout_high,
        "latency_p50_ms": percentile([float(r["latency_ms"]) for r in observations], .50),
        "latency_p95_ms": percentile([float(r["latency_ms"]) for r in observations], .95),
        "mean_resource_rejections": sum(int(r.get("resource_rejections", 0)) for r in observations) / n if n else math.nan,
    }


def write_csv(path: Path, records: list[dict]) -> None:
    if not records:
        return
    fields = list(records[0])
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(records)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path,
                        default=Path(__file__).resolve().parents[1] / "evaluation/target/jvm_starvation")
    parser.add_argument("--include", default="jvm_permission_starvation_*.raw.jsonl")
    parser.add_argument("--exclude", action="append", default=[])
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    out = args.output_dir or args.input_dir
    out.mkdir(parents=True, exist_ok=True)
    rows = read_rows(args.input_dir, args.include, set(args.exclude))

    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[key(row)].append(row)
    observation_records: list[dict] = []
    for group_key, observations in sorted(grouped.items(), key=lambda item: str(item[0])):
        record = dict(zip((
            "host_id", "benchmark_mode", "experiment", "architecture",
            "deadline_ms", "attackers", "qps_per_attacker", "lock_hold_ms",
            "split", "morphology", "workload_class", "shared_pool_size",
            "permission_pool_size", "queue_capacity"), group_key))
        record.update(summarize(observations))
        observation_records.append(record)
    write_csv(out / "computing_observation_summary.csv", observation_records)

    # Run-level severe-pressure summary: each run contributes one number.
    severe = [r for r in rows if r.get("experiment") == "deadline_sweep"
              and r.get("deadline_ms") == 10
              and "core_" in str(r.get("run_id", ""))]
    by_run_arch: dict[tuple, list[dict]] = defaultdict(list)
    for row in severe:
        by_run_arch[(row.get("host_id"), row.get("run_id"), row.get("architecture"))].append(row)
    run_records: list[dict] = []
    for (host, run_id, architecture), observations in sorted(by_run_arch.items()):
        stats = summarize(observations)
        run_records.append({"host_id": host, "run_id": run_id, "architecture": architecture, **stats})
    write_csv(out / "computing_run_level_severe.csv", run_records)

    by_host_arch: dict[tuple, list[dict]] = defaultdict(list)
    for record in run_records:
        by_host_arch[(record["host_id"], record["architecture"])].append(record)
    cross_records: list[dict] = []
    for (host, architecture), records in sorted(by_host_arch.items()):
        secure = [float(r["secure_rate"]) for r in records]
        timeout = [float(r["timeout_rate"]) for r in records]
        secure_ci_low, secure_ci_high = run_mean_ci(secure)
        timeout_ci_low, timeout_ci_high = run_mean_ci(timeout)
        cross_records.append({
            "host_id": host,
            "architecture": architecture,
            "runs": len(records),
            "secure_rate_mean": sum(secure) / len(secure),
            "secure_rate_min": min(secure),
            "secure_rate_max": max(secure),
            "secure_rate_ci_low": secure_ci_low,
            "secure_rate_ci_high": secure_ci_high,
            "timeout_rate_mean": sum(timeout) / len(timeout),
            "timeout_rate_min": min(timeout),
            "timeout_rate_max": max(timeout),
            "timeout_rate_ci_low": timeout_ci_low,
            "timeout_rate_ci_high": timeout_ci_high,
            "latency_p50_mean_ms": sum(float(r["latency_p50_ms"]) for r in records) / len(records),
            "latency_p95_mean_ms": sum(float(r["latency_p95_ms"]) for r in records) / len(records),
        })
    write_csv(out / "computing_cross_run_summary.csv", cross_records)

    # Capacity sensitivity is kept separate from the repeated core runs.  A
    # capacity run is identified by its run_id prefix and is summarized at the
    # representative 10 ms deadline so the nine configurations remain visible.
    capacity_rows = [r for r in rows if str(r.get("run_id", "")).startswith("win_capacity_")
                     and r.get("deadline_ms") == 10]
    by_capacity: dict[tuple, list[dict]] = defaultdict(list)
    for row in capacity_rows:
        by_capacity[(row.get("host_id"), row.get("run_id"), row.get("permission_pool_size"),
                     row.get("queue_capacity"), row.get("architecture"))].append(row)
    capacity_records: list[dict] = []
    for group_key, observations in sorted(by_capacity.items(), key=lambda item: str(item[0])):
        host, run_id, permission_pool, queue_capacity, architecture = group_key
        capacity_records.append({
            "host_id": host,
            "run_id": run_id,
            "permission_pool_size": permission_pool,
            "queue_capacity": queue_capacity,
            "architecture": architecture,
            **summarize(observations),
        })
    write_csv(out / "computing_capacity_summary.csv", capacity_records)

    hosts = sorted({r.get("host_id", "unspecified") for r in rows})
    run_ids = sorted({r.get("run_id", "") for r in rows})
    def macro_token(value: object) -> str:
        """Make a stable LaTeX command-name fragment from an identifier."""
        return "".join(ch for ch in str(value) if ch.isalnum())

    macros = [
        "% Generated by evaluation/analyze_computing_experiments.py; do not edit.",
        f"\\newcommand{{\\ComputingObservationCount}}{{{len(rows):,}}}",
        f"\\newcommand{{\\ComputingRunCount}}{{{len(run_ids)}}}",
        f"\\newcommand{{\\ComputingHostCount}}{{{len(hosts)}}}",
        f"\\newcommand{{\\ComputingCoreRunCount}}{{{len([r for r in run_ids if 'core_' in r])}}}",
        f"\\newcommand{{\\ComputingCapacityConfigCount}}{{{len({r.get('run_id') for r in capacity_rows})}}}",
    ]
    for record in cross_records:
        macro = macro_token(record["host_id"])
        arch = macro_token(record["architecture"])
        macros.append(f"\\newcommand{{\\Computing{macro}{arch}Runs}}{{{record['runs']}}}")
        macros.append(f"\\newcommand{{\\Computing{macro}{arch}TimeoutMean}}{{{100 * record['timeout_rate_mean']:.1f}\\%}}")
        macros.append(f"\\newcommand{{\\Computing{macro}{arch}SecureMean}}{{{100 * record['secure_rate_mean']:.1f}\\%}}")
    # Convenience names used in the main evaluation table.  These are pooled
    # across the five independent core runs per host, not observation-level
    # replicates from one JVM process.
    for host in hosts:
        token = macro_token(host)
        host_records = {r["architecture"]: r for r in cross_records if r["host_id"] == host}
        for architecture, short in (
            ("LAYER_SEPARATED_FAIL_OPEN", "FailOpen"),
            ("LAYER_SEPARATED_FAIL_CLOSED", "FailClosed"),
            ("ISOLATED_NO_COUPLING", "Isolated"),
            ("NEURON_COUPLED", "Coupled"),
        ):
            if architecture in host_records:
                r = host_records[architecture]
                macros.append(f"\\newcommand{{\\Computing{token}{short}Timeout}}{{{100 * r['timeout_rate_mean']:.1f}\\%}}")
                macros.append(f"\\newcommand{{\\Computing{token}{short}Secure}}{{{100 * r['secure_rate_mean']:.1f}\\%}}")
    paper_generated = Path(__file__).resolve().parents[1] / "addtions/paper/generated"
    paper_generated.mkdir(parents=True, exist_ok=True)
    (paper_generated / "computing_results.tex").write_text("\n".join(macros) + "\n", encoding="utf-8")
    manifest = {
        "raw_files": sorted(str(p) for p in args.input_dir.glob(args.include) if p.name not in set(args.exclude)),
        "hosts": hosts,
        "run_ids": run_ids,
        "observations": len(rows),
    }
    (out / "computing_analysis_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"loaded {len(rows):,} observations from {len(run_ids)} runs on {len(hosts)} hosts")
    print(f"wrote {out / 'computing_cross_run_summary.csv'}")


if __name__ == "__main__":
    main()
