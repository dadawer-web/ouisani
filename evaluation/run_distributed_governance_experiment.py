#!/usr/bin/env python3
"""
Distributed Governance Experiment Runner
=========================================

Runs the Java integration test (DistributedGovernanceTest) via Maven, then
analyzes and formats the results. Outputs a summary table to stdout and
saves a CSV for downstream statistical analysis.

For the Neuron paper (JSA journal submission) — addresses reviewer concerns
about "single-node scope" by demonstrating cross-node governance invariants:
  D1: trace ID propagation
  D2: privilege non-increase
  D3: depth-aware escalation
  D4: joint-decision (resource pressure tightens verdict)
  D5: audit correlation across multi-hop (N1 -> N2 -> N3)

Usage:
    python run_distributed_governance_experiment.py
"""

import csv
import json
import os
import subprocess
import sys
from pathlib import Path

# ── Paths ──
NEURON_JAVA_DIR = r"e:\ouisani\neuron-java"
RESULTS_JSON = Path(r"e:\ouisani\evaluation\target\distributed_governance_results.json")
RESULTS_CSV = Path(r"e:\ouisani\evaluation\target\distributed_governance_results.csv")


def run_java_test():
    """Run the DistributedGovernanceTest via Maven."""
    print("=" * 70)
    print("  Distributed Governance Experiment — Java Integration Test")
    print("=" * 70)
    print()

    cmd = [
        "mvn", "test",
        "-Dtest=DistributedGovernanceTest",
        "-DfailIfNoTests=false",
    ]

    print(f"Running: {' '.join(cmd)}")
    print(f"Working dir: {NEURON_JAVA_DIR}")
    print()

    result = subprocess.run(cmd, cwd=NEURON_JAVA_DIR)

    if result.returncode != 0:
        print(f"ERROR: Maven test failed with return code {result.returncode}")
        return False

    return True


def load_results():
    """Load the JSON results produced by the Java test."""
    if not RESULTS_JSON.exists():
        print(f"ERROR: Results file not found: {RESULTS_JSON}")
        return None

    with open(RESULTS_JSON, "r", encoding="utf-8") as f:
        return json.load(f)


def print_summary(results):
    """Print a formatted summary table of the results."""
    print()
    print("=" * 70)
    print("  Distributed Governance Experiment — Results Summary")
    print("=" * 70)
    print()

    print(f"  Node count:            {results.get('node_count', 'N/A')}")
    print(f"  Trials per scenario:   {results.get('trials_per_scenario', 'N/A')}")
    print(f"  Timestamp:             {results.get('timestamp', 'N/A')}")
    print()

    scenarios = results.get("scenarios", {})

    # ── Summary table ──
    header = (
        f"{'Scenario':<30} "
        f"{'Local Mean':>12} "
        f"{'Cross Mean':>12} "
        f"{'Overhead':>10} "
        f"{'Passed':>10}"
    )
    print(header)
    print("-" * len(header))

    for name, data in scenarios.items():
        local = data.get("local", {})
        cross = data.get("cross_node", {})
        local_mean = local.get("mean", 0)
        cross_mean = cross.get("mean", 0)
        overhead = data.get("overhead_ratio", 0)
        correctness = data.get("correctness", {})
        passed = correctness.get("passed", 0)
        total = correctness.get("total", 0)

        print(
            f"{name:<30} "
            f"{local_mean:>12.4f} "
            f"{cross_mean:>12.4f} "
            f"{overhead:>9.2f}x "
            f"{passed:>5}/{total}"
        )

    print()

    # ── Detailed statistics ──
    print("=" * 70)
    print("  Detailed Statistics (latency in ms)")
    print("=" * 70)
    print()

    for name, data in scenarios.items():
        print(f"  {name}")
        print(
            f"    {'Variant':<12} "
            f"{'Mean':>10} "
            f"{'p50':>10} "
            f"{'p95':>10} "
            f"{'p99':>10} "
            f"{'StdDev':>10}"
        )

        for variant in ("local", "cross_node"):
            stats = data.get(variant, {})
            label = "Local" if variant == "local" else "Cross-Node"
            mean = stats.get("mean", 0)
            p50 = stats.get("p50", 0)
            p95 = stats.get("p95", 0)
            p99 = stats.get("p99", 0)
            stddev = stats.get("stddev", 0)
            print(
                f"    {label:<12} "
                f"{mean:>10.4f} "
                f"{p50:>10.4f} "
                f"{p95:>10.4f} "
                f"{p99:>10.4f} "
                f"{stddev:>10.4f}"
            )

        overhead = data.get("overhead_ratio", 0)
        correctness = data.get("correctness", {})
        passed = correctness.get("passed", 0)
        total = correctness.get("total", 0)
        print(f"    Overhead ratio: {overhead:.2f}x  |  Correctness: {passed}/{total}")
        print()


def save_csv(results):
    """Save results to CSV."""
    RESULTS_CSV.parent.mkdir(parents=True, exist_ok=True)

    with open(RESULTS_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "scenario", "variant", "mean_ms", "p50_ms", "p95_ms", "p99_ms",
            "stddev_ms", "overhead_ratio", "passed", "total",
        ])

        scenarios = results.get("scenarios", {})
        for name, data in scenarios.items():
            overhead = data.get("overhead_ratio", 0)
            correctness = data.get("correctness", {})
            passed = correctness.get("passed", 0)
            total = correctness.get("total", 0)

            for variant in ("local", "cross_node"):
                stats = data.get(variant, {})
                writer.writerow([
                    name,
                    variant,
                    f"{stats.get('mean', 0):.6f}",
                    f"{stats.get('p50', 0):.6f}",
                    f"{stats.get('p95', 0):.6f}",
                    f"{stats.get('p99', 0):.6f}",
                    f"{stats.get('stddev', 0):.6f}",
                    f"{overhead:.4f}",
                    passed,
                    total,
                ])

    print(f"CSV saved to: {RESULTS_CSV}")


def main():
    # Step 1: Run the Java test
    if not run_java_test():
        sys.exit(1)

    # Step 2: Load results
    results = load_results()
    if results is None:
        sys.exit(1)

    # Step 3: Print summary
    print_summary(results)

    # Step 4: Save CSV
    save_csv(results)

    print()
    print("=" * 70)
    print("  Experiment complete.")
    print("=" * 70)


if __name__ == "__main__":
    main()
