#!/usr/bin/env python3
"""Freeze one canonical Reflexion baseline result per model.

The raw runs are intentionally preserved.  This utility copies only the
selected complete model outputs into a stable directory and writes manifests
that make duplicate/partial runs explicit instead of pooling them.
"""

from __future__ import annotations

import csv
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RESULT_ROOT = ROOT / "evaluation" / "results" / "emse_reflexion"
CANONICAL_ROOT = RESULT_ROOT / "canonical_baseline_n100"

# Selection rule: prefer a standalone run whose matrix manifest completed
# successfully.  GPT has no standalone completed run, so use its complete
# model output from the combined run and record that exception explicitly.
SELECTIONS: dict[str, dict[str, str]] = {
    "gpt-5.6-luna": {
        "source_run": "full_n100_20260809T",
        "source_dir": "gpt-5.6-luna",
        "reason": "Only complete GPT output; combined matrix was later stopped after baseline completion.",
    },
    "glm-5.2": {
        "source_run": "full_n100_glm52",
        "source_dir": "glm-5.2",
        "reason": "Standalone run with manifest status complete; excludes duplicate combined-matrix run.",
    },
    "kimi-k2.6": {
        "source_run": "full_n100_kimi_k26",
        "source_dir": "kimi-k2.6",
        "reason": "Standalone run with manifest status complete; excludes duplicate combined-matrix run.",
    },
    "deepseek-v4-flash": {
        "source_run": "full_n100_deepseek_v4_flash",
        "source_dir": "deepseek-v4-flash",
        "reason": "Standalone run with manifest status complete.",
    },
    "mimo-v2.5": {
        "source_run": "full_n100_mimo_v25",
        "source_dir": "mimo-v2.5",
        "reason": "Standalone run with manifest status complete; excludes stopped duplicate checkpoint.",
    },
}

ARTIFACTS = (
    "paper_draft_reflexion_e2e_exploit.json",
    "paper_draft_reflexion_e2e_exploit.csv",
    "paper_draft_reflexion_e2e_exploit.raw.jsonl",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    CANONICAL_ROOT.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []
    manifest_models: list[dict[str, Any]] = []

    for model, selection in SELECTIONS.items():
        source_root = RESULT_ROOT / selection["source_run"] / selection["source_dir"]
        source_json = source_root / ARTIFACTS[0]
        if not source_json.is_file():
            raise FileNotFoundError(source_json)
        result = load_json(source_json)
        attack = result["attack"]
        benign = result["benign"]
        expected = {
            "model": model,
            "n_per_variant": 100,
            "attack_total": 500,
            "benign_total": 500,
            "attack_errors": 0,
            "benign_errors": 0,
        }
        actual = {
            "model": result.get("model"),
            "n_per_variant": result.get("n_per_variant"),
            "attack_total": attack.get("total"),
            "benign_total": benign.get("total"),
            "attack_errors": attack.get("errors"),
            "benign_errors": benign.get("errors"),
        }
        if actual != expected:
            raise ValueError(f"Canonical validation failed for {model}: {actual} != {expected}")

        target_root = CANONICAL_ROOT / model
        target_root.mkdir(parents=True, exist_ok=True)
        copied: dict[str, str] = {}
        for name in ARTIFACTS:
            src = source_root / name
            if not src.is_file():
                raise FileNotFoundError(src)
            dst = target_root / name
            shutil.copy2(src, dst)
            copied[name] = sha256(dst)

        row = {
            "model": model,
            "framework": result.get("framework", "Reflexion"),
            "framework_commit": result.get("commit"),
            "protocol": "messages" if model == "qwen3.7-plus" else "chat_completions",
            "source_run": selection["source_run"],
            "source_dir": str(source_root.relative_to(ROOT)),
            "canonical_dir": str(target_root.relative_to(ROOT)),
            "selection_reason": selection["reason"],
            "n_per_variant": result["n_per_variant"],
            "attack_successes": attack["successes"],
            "attack_total": attack["total"],
            "attack_asr": attack["asr"],
            "benign_successes": benign["successes"],
            "benign_total": benign["total"],
            "benign_fpr": benign["fpr"],
            "attack_errors": attack["errors"],
            "benign_errors": benign["errors"],
            "artifacts_sha256": copied,
        }
        rows.append(row)
        manifest_models.append(row)

    generated_at = datetime.now(timezone.utc).isoformat()
    manifest = {
        "baseline_id": "reflexion_content_recovery_n100",
        "generated_at_utc": generated_at,
        "framework": "Reflexion",
        "selection_rule": "One complete n=100/model result; standalone completed runs preferred; duplicates and partial runs excluded without deletion.",
        "models": manifest_models,
        "excluded_runs": [
            {
                "run": "full_n100_20260809T/glm-5.2",
                "reason": "Duplicate GLM full run; standalone complete run selected.",
            },
            {
                "run": "full_n100_20260809T/kimi-k2.6",
                "reason": "Duplicate Kimi full run; standalone complete run selected.",
            },
            {
                "run": "full_n100_20260809T/mimo-v2.5",
                "reason": "Partial duplicate stopped manually; checkpoint retained but not pooled.",
            },
            {
                "run": "full_n100_minimax_m3/minimax-m3",
                "reason": "Partial run stopped on provider response-schema failure (72 attack trials).",
            },
            {
                "run": "pilot_n1_20260809T045528Z/qwen3.7-plus",
                "reason": "System-bearing messages probe rejected by provider (HTTP 403); no valid full baseline.",
            },
        ],
    }
    (CANONICAL_ROOT / "canonical_manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    fieldnames = [
        "model", "framework", "framework_commit", "protocol", "source_run",
        "source_dir", "canonical_dir", "n_per_variant", "attack_successes",
        "attack_total", "attack_asr", "benign_successes", "benign_total",
        "benign_fpr", "attack_errors", "benign_errors", "selection_reason",
    ]
    with (CANONICAL_ROOT / "canonical_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows({key: row[key] for key in fieldnames} for row in rows)

    readme = """# Canonical Reflexion baseline (n=100)

This directory freezes one complete result per model for downstream analysis.
Each selected model has 5 attack payloads × 100 trials and 5 matched benign
controls × 100 trials. Duplicate and partial runs remain under the parent
`evaluation/results/emse_reflexion/` directory but are excluded from pooling.

Use `canonical_manifest.json` for provenance and SHA-256 hashes, and
`canonical_summary.csv` for model-level statistics. The copied JSON/CSV/JSONL
artifacts under each model directory are the canonical inputs for subsequent
tables and plots.
"""
    (CANONICAL_ROOT / "README.md").write_text(readme, encoding="utf-8")
    print(CANONICAL_ROOT)
    print(json.dumps(rows, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
