#!/usr/bin/env python3
"""Aggregate per-model Reflexion pilot summaries without pooling paper results."""
from __future__ import annotations

import csv
import json
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    run = root / "evaluation" / "results" / "emse_reflexion" / "pilot_n1_20260809T045528Z"
    rows = []
    for path in sorted(run.glob("*/paper_draft_reflexion_e2e_exploit.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        rows.append({
            "model": data["model"],
            "protocol": "messages" if data["model"] == "minimax-m3" else "chat_completions",
            "n_per_variant": data["n_per_variant"],
            "attack_successes": data["attack"]["successes"],
            "attack_total": data["attack"]["total"],
            "asr": data["attack"]["asr"],
            "asr_wilson_low": data["attack"]["wilson_95ci"][0],
            "asr_wilson_high": data["attack"]["wilson_95ci"][1],
            "benign_successes": data["benign"]["successes"],
            "benign_total": data["benign"]["total"],
            "fpr": data["benign"]["fpr"],
            "fisher_p": data["fisher_p_attack_vs_benign"],
            "attack_errors": data["attack"]["errors"],
            "benign_errors": data["benign"]["errors"],
            "status": "pilot_only",
        })
    out_json = run / "pilot_summary.json"
    out_csv = run / "pilot_summary.csv"
    out_json.write_text(json.dumps({"run": run.name, "rows": rows}, indent=2, ensure_ascii=False), encoding="utf-8")
    with out_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(out_json)
    print(out_csv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
