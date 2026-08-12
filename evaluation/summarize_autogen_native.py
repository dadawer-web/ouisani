#!/usr/bin/env python3
"""Create a manifest and model-level summary for a native AutoGen run."""

from __future__ import annotations

import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: summarize_autogen_native.py RUN_DIR", file=sys.stderr)
        return 2
    run_root = Path(sys.argv[1])
    if not run_root.is_absolute():
        run_root = ROOT / run_root
    rows = []
    for path in sorted(run_root.glob("*/paper_draft_autogen_rci_e2e_exploit.json")):
        result = json.loads(path.read_text(encoding="utf-8"))
        attack, benign = result["attack"], result["benign"]
        rows.append({
            "model": result["model"],
            "framework": "AutoGen",
            "autogen_version": result["autogen_version"],
            "n_per_variant": result["n_per_variant"],
            "attack_successes": attack["successes"],
            "attack_total": attack["total"],
            "attack_asr": attack["asr"],
            "attack_path_executed": attack["path_executed"],
            "attack_path_rate": attack["path_rate"],
            "attack_asr_given_path": attack["asr_given_path"],
            "benign_successes": benign["successes"],
            "benign_total": benign["total"],
            "benign_fpr": benign["asr"],
            "benign_path_rate": benign["path_rate"],
            "attack_errors": attack["errors"],
            "benign_errors": benign["errors"],
            "result_json": str(path.relative_to(ROOT)),
        })
    manifest = {
        "run_id": run_root.name,
        "framework": "AutoGen",
        "package_pin": "autogen-agentchat==0.4.7; autogen-core==0.4.7; autogen-ext[openai]==0.4.7",
        "n_per_variant": rows[0]["n_per_variant"] if rows else None,
        "status": "complete" if len(rows) == 3 and all(r["attack_errors"] == 0 and r["benign_errors"] == 0 for r in rows) else "incomplete_or_errors",
        "models": rows,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "interpretation": "Pilot n=50; native tool-error path execution and ASR are reported separately.",
    }
    (run_root / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    fields = list(rows[0]) if rows else []
    with (run_root / "pilot_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    (run_root / "pilot_summary.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
    print(run_root / "manifest.json")
    print(json.dumps(rows, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
