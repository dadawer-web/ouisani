#!/usr/bin/env python3
"""Fail-fast consistency gate for the Computing manuscript and artifact."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "evaluation/target/jvm_starvation_new"
HTTP_TARGET = ROOT / "evaluation/target/permission_http_remote"
HOLDOUT_TARGET = ROOT / "evaluation/target/threshold_holdout"
PAPER = ROOT / "addtions/paper"
EXCLUDES = {
    "jvm_permission_starvation_smoke_local.raw.jsonl",
    "jvm_permission_starvation_vps_smoke2.raw.jsonl",
    "jvm_permission_starvation_win_qos_01.raw.jsonl",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    raw_paths = [p for p in TARGET.glob("jvm_permission_starvation_*.raw.jsonl")
                 if p.name not in EXCLUDES]
    require(len(raw_paths) == 19, f"expected 19 core/capacity raw files, found {len(raw_paths)}")
    require(all(p.stat().st_size > 0 for p in raw_paths), "one or more raw JSONL files are empty")

    subprocess.run([
        sys.executable, str(ROOT / "evaluation/analyze_computing_experiments.py"),
        "--input-dir", str(TARGET), "--output-dir", str(TARGET),
        "--exclude", EXCLUDES.pop(), "--exclude", EXCLUDES.pop(),
        "--exclude", EXCLUDES.pop(),
    ], cwd=ROOT, check=True)
    subprocess.run([sys.executable, str(ROOT / "evaluation/analyze_qos_experiments.py")], cwd=ROOT, check=True)
    subprocess.run([sys.executable, str(ROOT / "evaluation/analyze_llm_replay.py")], cwd=ROOT, check=True)
    subprocess.run([
        sys.executable, str(ROOT / "evaluation/analyze_permission_http.py"),
        "--input-dir", str(HTTP_TARGET),
        "--output-dir", str(HTTP_TARGET),
        "--pattern", "permission_http_vps_http_prod_*.raw.jsonl",
    ], cwd=ROOT, check=True)
    subprocess.run([
        sys.executable, str(ROOT / "evaluation/analyze_threshold_holdout.py"),
    ], cwd=ROOT, check=True)

    manifest = json.loads((TARGET / "computing_analysis_manifest.json").read_text(encoding="utf-8"))
    require(manifest["observations"] == 71880, f"unexpected core/capacity observation count: {manifest['observations']}")
    require(len(manifest["run_ids"]) == 19, "unexpected core/capacity run count")
    require(len(manifest["hosts"]) == 2, "unexpected host count")

    qos_csv = TARGET / "computing_qos_summary.csv"
    require(qos_csv.exists() and qos_csv.stat().st_size > 0, "missing QoS summary")
    qos_rows = qos_csv.read_text(encoding="utf-8").splitlines()
    require(len(qos_rows) == 17, f"expected 16 QoS cells, found {len(qos_rows) - 1}")

    replay_summary = ROOT / "evaluation/target/computing_llm_replay_v3/llm_trace_replay_summary.csv"
    require(replay_summary.exists() and replay_summary.stat().st_size > 0, "missing LLM replay summary")
    replay_rows = replay_summary.read_text(encoding="utf-8").splitlines()
    require(len(replay_rows) == 9, "unexpected LLM replay summary rows")
    remote_replay = ROOT / "evaluation/target/computing_llm_replay_remote/llm_trace_replay_summary.csv"
    require(remote_replay.exists() and remote_replay.stat().st_size > 0,
            "missing VPS LLM replay summary")
    remote_replay_rows = remote_replay.read_text(encoding="utf-8").splitlines()
    require(len(remote_replay_rows) == 9, "unexpected VPS LLM replay summary rows")

    http_manifest_path = HTTP_TARGET / "permission_http_analysis_manifest.json"
    require(http_manifest_path.exists(), "missing process-separated permission manifest")
    http_manifest = json.loads(http_manifest_path.read_text(encoding="utf-8"))
    require(http_manifest["observations"] == 3200,
            f"unexpected process-separated observation count: {http_manifest['observations']}")
    require(len(http_manifest["run_ids"]) == 5, "unexpected process-separated run count")
    require(len(http_manifest["scenarios"]) == 8, "unexpected process-separated scenario count")
    http_csv = HTTP_TARGET / "permission_http_cross_run_summary.csv"
    require(http_csv.exists() and http_csv.stat().st_size > 0,
            "missing process-separated cross-run summary")

    holdout_report_path = HOLDOUT_TARGET / "threshold_holdout_report.json"
    require(holdout_report_path.exists(), "missing independent threshold holdout report")
    holdout_report = json.loads(holdout_report_path.read_text(encoding="utf-8"))
    require(holdout_report["selected_threshold"] == 50,
            "independent holdout changed the frozen threshold")
    require(holdout_report["observations"] == 180,
            f"unexpected threshold holdout observations: {holdout_report['observations']}")
    require(holdout_report["attack_n"] == 90 and holdout_report["benign_n"] == 90,
            "unexpected threshold holdout class counts")
    require(holdout_report["attack_detection_rate"] == 1.0,
            "threshold holdout attack detection is not 100%")
    require(holdout_report["benign_false_positive_rate"] == 0.0,
            "threshold holdout false-positive rate is not 0%")
    require(set(holdout_report["morphologies"]) == {
        "alternating-burst", "staggered-ramp", "long-hold-drip",
        "tenant-batch", "cooperative-burst", "background-ramp",
    }, "threshold holdout morphology set changed")

    required_fields = {
        "run_id", "host_id", "experiment", "architecture", "deadline_ms",
        "latency_ms", "timed_out", "verdict", "secure_decision",
        "resource_rejections", "queue_capacity",
    }
    total = 0
    for path in raw_paths:
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
            if not line.strip():
                continue
            row = json.loads(line)
            total += 1
            missing = required_fields - row.keys()
            require(not missing, f"{path.name}:{index} missing fields: {sorted(missing)}")
    require(total == 71880, f"expected 71880 raw observations, found {total}")

    tex = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                      for path in PAPER.rglob("*.tex")
                      if "generated" not in path.parts)
    banned = {
        "degrades to 83\\%": "retired abstract number",
        "66.67\\%": "retired Python starvation number",
        "63.33\\%": "retired Python starvation number",
        "near-optimal": "unsupported threshold claim",
        "multiprocessing.Manager for genuine": "retired Python evidence",
    }
    for needle, reason in banned.items():
        require(needle not in tex, f"{reason} remains in paper: {needle}")

    used = set(re.findall(r"\\(Computing[A-Za-z0-9]+|LlmReplay[A-Za-z0-9]+|PermissionHttp[A-Za-z0-9]+)", tex))
    defined = set()
    for path in (PAPER / "generated").glob("*.tex"):
        defined.update(re.findall(r"\\newcommand\{\\([A-Za-z0-9]+)\}", path.read_text(encoding="utf-8")))
    require(used <= defined, f"undefined generated macros: {sorted(used - defined)}")

    print("PASS: Computing raw data, derived statistics, manuscript macros, and retired claims are consistent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, subprocess.CalledProcessError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
