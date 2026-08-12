#!/usr/bin/env python3
"""Parallel n-per-variant runner for the supplementary defense-aware pilot."""

from __future__ import annotations

import csv
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "evaluation"))
from paper_draft_forward_defense_bypass import (  # noqa: E402
    V1_VARIANTS,
    fisher_exact,
    load_dotenv,
    run_trial,
    wilson_ci,
)


def main() -> int:
    env = load_dotenv(ROOT / ".env")
    api_key = os.environ.get("EMSE_API_KEY") or env.get("OPENCODE_API_KEY", "")
    base_url = os.environ.get("EMSE_BASE_URL") or env.get("OPENCODE_CHAT_BASE_URL", "")
    suffix = os.environ.get("EMSE_MODEL_KEY", "GPT56_LUNA").upper()
    model = os.environ.get("OPENAI_MODEL") or env.get(f"EMSE_MODEL_{suffix}", "")
    n = int(os.environ.get("EMSE_DEFENSE_N", "20"))
    workers = max(1, int(os.environ.get("EMSE_CONCURRENCY", "4")))
    out_dir = Path(os.environ.get(
        "EMSE_OUTPUT_DIR",
        str(ROOT / "evaluation" / "results" / "emse_defense_aware" / f"{suffix.lower()}_n{n}_parallel"),
    )).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    if not api_key or not base_url or not model:
        print("ERROR: missing API key/base URL/model", file=sys.stderr)
        return 2

    jobs = [
        (variant, defended, idx)
        for defended in (False, True)
        for variant in V1_VARIANTS
        for idx in range(n)
    ]
    results = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(run_trial, api_key, base_url, model, variant, defended, idx):
            (variant, defended, idx)
            for variant, defended, idx in jobs
        }
        for future in as_completed(futures):
            row = future.result()
            results.append(row)
            print(
                f"{row['config']} {row['variant']} {row['trial_idx'] + 1}/{n} "
                f"success={row['attack_success']} error={bool(row['error'])}",
                flush=True,
            )

    per_config = {}
    for config, defended in (("A_undefended", False), ("B_defended", True)):
        subset = [row for row in results if row["forward_defended"] is defended and not row["error"]]
        successes = sum(bool(row["attack_success"]) for row in subset)
        lo, hi = wilson_ci(successes, len(subset))
        per_variant = {}
        for variant in V1_VARIANTS:
            rows = [row for row in subset if row["variant"] == variant]
            count = sum(bool(row["attack_success"]) for row in rows)
            per_variant[variant] = {"successes": count, "trials": len(rows), "observed_asr": count / len(rows) if rows else 0.0}
        per_config[config] = {
            "per_variant": per_variant,
            "total_successes": successes,
            "total_trials": len(subset),
            "total_errors": sum(bool(row["error"]) for row in results if row["forward_defended"] is defended),
            "total_forward_blocked": sum(bool(row.get("forward_block_reason")) for row in results if row["forward_defended"] is defended),
            "pooled_asr": successes / len(subset) if subset else 0.0,
            "wilson_95ci": [lo, hi],
        }
    a, b = per_config["A_undefended"], per_config["B_defended"]
    p = fisher_exact(a["total_successes"], a["total_trials"] - a["total_successes"], b["total_successes"], b["total_trials"] - b["total_successes"])
    summary = {
        "experiment": "Forward-path defense bypass (parallel pilot)",
        "model": model,
        "n_per_variant": n,
        "workers": workers,
        "variants": V1_VARIANTS,
        "config_A_undefended": per_config["A_undefended"],
        "config_B_defended": per_config["B_defended"],
        "fisher_p_A_vs_B": p,
        "raw_logs": str((out_dir / "paper_draft_forward_defense_bypass.raw.jsonl").relative_to(ROOT)),
    }
    (out_dir / "paper_draft_forward_defense_bypass.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    with (out_dir / "paper_draft_forward_defense_bypass.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["config", "variant", "successes", "trials", "observed_asr"])
        for config in ("A_undefended", "B_defended"):
            for variant, item in per_config[config]["per_variant"].items():
                writer.writerow([config, variant, item["successes"], item["trials"], item["observed_asr"]])
    with (out_dir / "paper_draft_forward_defense_bypass.raw.jsonl").open("w", encoding="utf-8") as handle:
        for row in sorted(results, key=lambda item: item["trial_id"]):
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
