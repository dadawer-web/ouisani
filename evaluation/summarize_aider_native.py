#!/usr/bin/env python3
"""Aggregate isolated native-Aider workers without rerunning trials."""

from __future__ import annotations

import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: summarize_aider_native.py RUN_ROOT", file=sys.stderr)
        return 2
    run_root = Path(sys.argv[1])
    if not run_root.is_absolute():
        run_root = ROOT / run_root
    rows: dict[str, dict] = {}
    for raw_path in sorted(run_root.glob("*/aider_native.raw.jsonl")):
        for raw in raw_path.read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(raw)
            except json.JSONDecodeError:
                continue
            trial_id = str(row.get("trial_id", ""))
            if trial_id:
                rows[trial_id] = row
    trials = list(rows.values())
    models = sorted({row.get("model") for row in trials if row.get("model")})
    def arm_summary(arm: str) -> dict:
        subset = [row for row in trials if row.get("arm") == arm]
        successes = sum(bool(row.get("attack_success")) for row in subset)
        paths = sum(bool(row.get("native_path_executed")) for row in subset)
        errors = sum(bool(row.get("error")) for row in subset)
        return {
            "successes": successes,
            "trials": len(subset),
            "asr": successes / len(subset) if subset else 0.0,
            "native_path_executed": paths,
            "native_path_rate": paths / len(subset) if subset else 0.0,
            "asr_given_path": successes / paths if paths else 0.0,
            "errors": errors,
        }
    summary = {
        "framework": "Aider",
        "aider_version": "0.86.2",
        "package_pin": "aider-chat==0.86.2",
        "n_per_variant": max(
            (sum(1 for row in trials if row.get("arm") == "attack" and row.get("variant") == variant)
             for variant in {row.get("variant") for row in trials}),
            default=0,
        ),
        "models": models,
        "variants": sorted({row.get("variant") for row in trials}),
        "attack": arm_summary("attack"),
        "benign": arm_summary("benign"),
        "trial_count": len(trials),
        "status": "complete" if trials and not any(row.get("error") for row in trials) else "in_progress_or_errors",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "native_execution": {
            "entrypoint": "aider.coders.base_coder.Coder.run_one",
            "error_transport": "Coder.commands.cmd_test -> run_cmd non-zero output",
            "recovery": "same Coder.run_one loop with reflected_message from cmd_test",
        },
    }
    (run_root / "pilot_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    fields = [
        "trial_id", "framework", "framework_version", "model", "arm", "variant",
        "trial_idx", "native_path_executed", "canary_executed", "attack_success",
        "classification", "duration_seconds", "error",
    ]
    with (run_root / "pilot_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows({field: row.get(field) for field in fields} for row in sorted(trials, key=lambda x: x.get("trial_id", "")))
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
