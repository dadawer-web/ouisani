#!/usr/bin/env python3
"""Consistency gate for the CCPE-specific executor replication."""

from __future__ import annotations

import csv
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "evaluation/target/jvm_starvation_new/jvm_permission_starvation_ccpe_core_01.raw.jsonl"
SUMMARY = ROOT / "evaluation/target/ccpe_concurrency/ccpe_concurrency_surface.csv"
MACROS = ROOT / "addtions/paper_ccpe/generated/ccpe_concurrency_results.tex"
ABLATION_INPUT = ROOT / "evaluation/target/jvm_starvation_ablation"
ABLATION_SUMMARY = ROOT / "evaluation/target/ccpe_ablation/ccpe_ablation_summary.csv"
ABLATION_MACROS = ROOT / "addtions/paper_ccpe/generated/ccpe_ablation_results.tex"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    require(RAW.exists() and RAW.stat().st_size > 0, "missing CCPE raw JSONL")
    rows = [json.loads(line) for line in RAW.read_text(encoding="utf-8").splitlines() if line.strip()]
    require(len(rows) == 3840, f"expected 3840 CCPE observations, found {len(rows)}")
    require({row.get("run_id") for row in rows} == {"ccpe_core_01"}, "unexpected CCPE run id")
    require({row.get("host_id") for row in rows} == {"windows-ccpe-core-01"}, "unexpected CCPE host")
    require({row.get("experiment") for row in rows} == {"pressure_surface", "deadline_sweep", "threshold_validation", "threshold_training", "joint_decision_probe"},
            "unexpected CCPE experiment set")

    subprocess.run([sys.executable, str(ROOT / "evaluation/analyze_ccpe_concurrency.py")], cwd=ROOT, check=True)
    require(SUMMARY.exists() and SUMMARY.stat().st_size > 0, "missing CCPE concurrency summary")
    summary = [row for row in csv.DictReader(SUMMARY.open(encoding="utf-8"))
               if row["host_id"] == "windows-ccpe-core-01"]
    require(len(summary) == 12, f"expected 12 CCPE pressure cells, found {len(summary)}")
    high = [row for row in summary if row["level"] == "high"]
    require(len(high) == 4, "missing high-pressure CCPE cells")
    shared = [row for row in high if row["architecture"] in {
        "LAYER_SEPARATED_FAIL_OPEN", "LAYER_SEPARATED_FAIL_CLOSED"}]
    reserved = [row for row in high if row["architecture"] in {
        "ISOLATED_NO_COUPLING", "NEURON_COUPLED"}]
    require(all(float(row["timeout_rate"]) == 1.0 and float(row["queue_p95"]) == 512.0 for row in shared),
            "shared high-pressure result changed")
    require(all(float(row["timeout_rate"]) == 0.0 and float(row["queue_p95"]) == 0.0 for row in reserved),
            "reserved high-pressure result changed")
    require(MACROS.exists() and MACROS.stat().st_size > 0, "missing CCPE generated macros")
    ablation_files = sorted(ABLATION_INPUT.glob("jvm_permission_starvation_ccpe_ablation_[0-9][0-9].raw.jsonl"))
    require(len(ablation_files) == 5, f"expected five ablation raw files, found {len(ablation_files)}")
    ablation_rows = []
    for path in ablation_files:
        rows_for_run = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        require(len(rows_for_run) == 360, f"unexpected ablation row count in {path.name}: {len(rows_for_run)}")
        ablation_rows.extend(rows_for_run)
    require(len(ablation_rows) == 1800, f"expected 1800 ablation observations, found {len(ablation_rows)}")
    require({row.get("experiment") for row in ablation_rows} == {"ablation_2x2"}, "unexpected ablation experiment")
    subprocess.run([sys.executable, str(ROOT / "evaluation/analyze_ccpe_ablation.py")], cwd=ROOT, check=True)
    require(ABLATION_SUMMARY.exists() and ABLATION_SUMMARY.stat().st_size > 0, "missing ablation summary")
    ablation_summary = list(csv.DictReader(ABLATION_SUMMARY.open(encoding="utf-8")))
    require(len(ablation_summary) == 12, f"expected 12 ablation cells, found {len(ablation_summary)}")
    high = {row["architecture"]: row for row in ablation_summary
            if row["attackers"] == "16" and row["qps_per_attacker"] == "2000" and row["lock_hold_ms"] == "5.0"}
    require(float(high["shared_no_signal"]["timeout_rate_mean"]) == 1.0,
            "shared no-signal ablation result changed")
    require(float(high["shared_signal"]["timeout_rate_mean"]) < 0.5,
            "shared signal ablation did not reduce timeout rate")
    require(float(high["isolated_no_signal"]["timeout_rate_mean"]) == 0.0 and
            float(high["isolated_signal"]["timeout_rate_mean"]) == 0.0,
            "reserved ablation result changed")
    require(ABLATION_MACROS.exists() and ABLATION_MACROS.stat().st_size > 0, "missing ablation macros")
    print("PASS: CCPE concurrency replication, 2x2 ablation, summaries, and manuscript macros are consistent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, subprocess.CalledProcessError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
