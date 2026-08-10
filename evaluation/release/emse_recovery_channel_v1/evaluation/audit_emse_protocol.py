#!/usr/bin/env python3
"""Build the model-snapshot audit and prospective power-analysis report."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from statistics import NormalDist

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "results" / "emse_protocol_audit"


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in raw or raw.lstrip().startswith("#"):
            continue
        key, value = raw.strip().split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def infer_model_config(model: str, env: dict[str, str]) -> dict:
    suffix = None
    for key, value in env.items():
        if key.startswith("EMSE_MODEL_") and value == model:
            suffix = key[len("EMSE_MODEL_"):]
            break
    protocol = env.get(f"EMSE_PROTOCOL_{suffix}", "unknown") if suffix else "unknown"
    if protocol == "messages":
        base_key = "OPENCODE_MESSAGES_BASE_URL"
    elif protocol == "responses":
        base_key = "OPENCODE_RESPONSES_BASE_URL"
    else:
        base_key = "OPENCODE_CHAT_BASE_URL"
    base_url = env.get(base_key, "")
    versioned = bool(re.search(r"(@|20\d{2}|v\d+(?:\.\d+)*)", model, re.IGNORECASE))
    return {
        "model_id": model,
        "env_suffix": suffix,
        "protocol": protocol,
        "base_url": base_url,
        "snapshot_or_version_in_id": versioned,
        "official_snapshot_verifiable": False,
        "snapshot_status": "unverified_alias_only",
        "api_key_recorded": False,
    }


def z_for(alpha: float) -> float:
    return NormalDist().inv_cdf(1.0 - alpha / 2.0)


def required_two_proportion_n(p1: float, p2: float, alpha: float = 0.05, power: float = 0.8) -> int | None:
    delta = abs(p1 - p2)
    if delta < 1e-12:
        return None
    p1 = min(max(p1, 1e-6), 1 - 1e-6)
    p2 = min(max(p2, 1e-6), 1 - 1e-6)
    pbar = (p1 + p2) / 2
    z_alpha = z_for(alpha)
    z_beta = NormalDist().inv_cdf(power)
    numerator = z_alpha * math.sqrt(2 * pbar * (1 - pbar)) + z_beta * math.sqrt(p1 * (1 - p1) + p2 * (1 - p2))
    return math.ceil((numerator / delta) ** 2)


def achieved_two_proportion_power(p1: float, p2: float, n: int, alpha: float = 0.05) -> float:
    if n <= 0:
        return 0.0
    p1c = min(max(p1, 1e-6), 1 - 1e-6)
    p2c = min(max(p2, 1e-6), 1 - 1e-6)
    pbar = (p1c + p2c) / 2
    se0 = math.sqrt(2 * pbar * (1 - pbar) / n)
    se1 = math.sqrt(p1c * (1 - p1c) / n + p2c * (1 - p2c) / n)
    if se0 == 0 or se1 == 0:
        return 1.0 if p1 != p2 else alpha
    z = z_for(alpha)
    delta = abs(p1 - p2)
    normal = NormalDist()
    return (1 - normal.cdf((z * se0 - delta) / se1)) + normal.cdf((-z * se0 - delta) / se1)


def required_one_proportion_n(p: float, null: float, alpha: float = 0.05, power: float = 0.8) -> int | None:
    delta = abs(p - null)
    if delta < 1e-12:
        return None
    pc = min(max(p, 1e-6), 1 - 1e-6)
    nc = min(max(null, 1e-6), 1 - 1e-6)
    z_alpha = z_for(alpha)
    z_beta = NormalDist().inv_cdf(power)
    numerator = z_alpha * math.sqrt(nc * (1 - nc)) + z_beta * math.sqrt(pc * (1 - pc))
    return math.ceil((numerator / delta) ** 2)


def main() -> int:
    env = load_env(ROOT / ".env")
    canonical = read_json(ROOT / "evaluation/results/emse_reflexion/canonical_baseline_n100/canonical_manifest.json")
    autogen = read_json(ROOT / "evaluation/results/emse_autogen_native/pilot_n50_20260809T190905/pilot_summary.json")
    aider = read_json(ROOT / "evaluation/results/emse_aider_native/pilot_gpt_n50_split_20260809T/pilot_summary.json")
    ablation = read_json(ROOT / "evaluation/results/emse_reflexion_ablation/kimi_screen_n20_20260809T/causal_ablation.json")
    defense = read_json(ROOT / "evaluation/results/emse_defense_aware/gpt_parallel_n20/paper_draft_forward_defense_bypass.json")
    if not defense:
        defense = read_json(ROOT / "evaluation/results/emse_defense_aware/gpt_pilot_n5/paper_draft_forward_defense_bypass.json")
    legal = read_json(ROOT / "evaluation/results/emse_legal_recovery/screen_gpt_n100/legal_recovery.json")
    if not legal:
        legal = read_json(ROOT / "evaluation/results/emse_legal_recovery/screen_gpt_n50_20260809T/legal_recovery.json")
    native_trustorigin = read_json(ROOT / "evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/native_trustorigin.json")
    gpt_endpoint = read_json(ROOT / "evaluation/results/emse_protocol_audit/gpt_endpoint_verification.json")

    models = {}
    for item in canonical.get("models", []):
        model = item.get("model", "")
        models[f"Reflexion:{model}"] = {"framework": "Reflexion", "run": item, **infer_model_config(model, env)}
    autogen_rows = autogen if isinstance(autogen, list) else autogen.get("models", [])
    for item in autogen_rows:
        model = item.get("model", "")
        models[f"AutoGen:{model}"] = {"framework": "AutoGen", "run": item, **infer_model_config(model, env)}
    for model in aider.get("models", []):
        models[f"Aider:{model}"] = {"framework": "Aider", "run": aider, **infer_model_config(model, env)}
    if native_trustorigin:
        native_model = native_trustorigin.get("model", "")
        models[f"Reflexion-TrustOrigin:{native_model}"] = {
            "framework": "Reflexion (native TrustOrigin port)",
            "run": native_trustorigin,
            **infer_model_config(native_model, env),
        }
    snapshot_audit = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "status": "incomplete_official_snapshot_disclosure",
        "reason": "The provider exposes model aliases through an OpenCode-compatible endpoint; no public snapshot/hash was returned in the experiment metadata.",
        "provider_catalog": {
            "source": "OpenCode Go /v1/models catalog supplied with the experiment record",
            "catalog_created_unix": 1786282996,
            "catalog_created_utc": "2026-08-09T13:43:16Z",
            "owned_by": "opencode",
            "interpretation": "All entries share the same created value; it is treated as catalog publication time, not an immutable model snapshot.",
            "model_ids": [
                "glm-5.2", "kimi-k2.6", "deepseek-v4-flash", "mimo-v2.5",
                "minimax-m3", "qwen3.7-plus", "gpt-5.6-luna"
            ],
        },
        "gpt_endpoint_audit": gpt_endpoint,
        "snapshot_candidates": [
            {
                "model_id": "glm-5.2",
                "official_hf_repo": "https://huggingface.co/zai-org/GLM-5.2",
                "provider_revision_mapping": "not disclosed",
            },
            {
                "model_id": "deepseek-v4-flash",
                "official_hf_repo": "https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash",
                "provider_revision_mapping": "not disclosed",
            },
            {
                "model_id": "kimi-k2.6",
                "official_hf_repo": "https://huggingface.co/moonshotai/Kimi-K2.6",
                "provider_revision_mapping": "not disclosed",
            },
        ],
        "models": list(models.values()),
        "run_artifact_hashes": {
            "canonical_manifest": sha256(ROOT / "evaluation/results/emse_reflexion/canonical_baseline_n100/canonical_manifest.json"),
            "autogen_summary": sha256(ROOT / "evaluation/results/emse_autogen_native/pilot_n50_20260809T190905/pilot_summary.json"),
            "aider_summary": sha256(ROOT / "evaluation/results/emse_aider_native/pilot_gpt_n50_split_20260809T/pilot_summary.json"),
            "ablation_summary": sha256(ROOT / "evaluation/results/emse_reflexion_ablation/kimi_screen_n20_20260809T/causal_ablation.json"),
            "legal_summary": sha256(ROOT / "evaluation/results/emse_legal_recovery/screen_gpt_n100/legal_recovery.json"),
            "native_trustorigin_summary": sha256(ROOT / "evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/native_trustorigin.json"),
            "native_trustorigin_raw_log": sha256(ROOT / "evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/native_trustorigin.raw.jsonl"),
            "source_audit": sha256(ROOT / "evaluation/results/emse_source_analysis/source_audit.json"),
            "number_generator": sha256(ROOT / "evaluation/generate_emse_paper_numbers.py"),
            "generated_numbers": sha256(ROOT / "addtions/paper_emse/generated_results.tex"),
        },
        "parameter_gaps": [
            "top_p not recorded in all result artifacts",
            "provider snapshot/hash unavailable",
            "OpenCode catalog does not expose an alias-to-Hugging-Face revision mapping",
            "system-prompt hash not recorded for every framework",
            "historical GPT artifacts use chat_completions while the supplied OpenCode documentation specifies /responses",
        ],
    }

    comparisons = []
    def add_two(name: str, p1: float, p2: float, n1: int, n2: int, estimand: str):
        n_req = required_two_proportion_n(p1, p2)
        comparisons.append({"comparison": name, "estimand": estimand, "p1": p1, "p2": p2, "n_per_arm_observed": min(n1, n2), "required_n_per_arm_80pct": n_req, "approx_power_at_observed_n": achieved_two_proportion_power(p1, p2, min(n1, n2)), "interpretation": "adequate_for_observed_effect" if n_req and min(n1, n2) >= n_req else "underpowered_or_effect_is_small"})
    add_two("AutoGen Kimi attack vs benign", 93 / 250, 0.0, 250, 250, "native recovery attack vs matched benign")
    add_two("Aider GPT attack vs benign", aider.get("attack", {}).get("asr", 0.0), aider.get("benign", {}).get("asr", 0.0), aider.get("attack", {}).get("trials", 0), aider.get("benign", {}).get("trials", 0), "native recovery attack vs matched benign")
    cells = ablation.get("cells", {})
    add_two("Reflexion provenance raw vs tagged", cells.get("recovery_raw_user_tail", {}).get("attack", {}).get("asr", 0.0), cells.get("recovery_tagged_user_tail", {}).get("attack", {}).get("asr", 0.0), 100, 100, "same recovery path, provenance marker")
    add_two("Reflexion recovery vs forward", cells.get("recovery_raw_user_tail", {}).get("attack", {}).get("asr", 0.0), cells.get("forward_raw_user_tail", {}).get("attack", {}).get("asr", 0.0), 100, 100, "same payload, channel contrast")
    if defense:
        add_two("Defense undefended vs defended", defense.get("config_A_undefended", {}).get("pooled_asr", 0.0), defense.get("config_B_defended", {}).get("pooled_asr", 0.0), defense.get("config_A_undefended", {}).get("total_trials", 0), defense.get("config_B_defended", {}).get("total_trials", 0), "supplementary forward-defense bypass")
    if native_trustorigin:
        native_cells = native_trustorigin.get("conditions", {})
        raw_attack = native_cells.get("raw_attack", {})
        tagged_attack = native_cells.get("tagged_attack", {})
        add_two("Native Reflexion TrustOrigin raw vs tagged", raw_attack.get("rate", 0.0), tagged_attack.get("rate", 0.0), raw_attack.get("trials", 0), tagged_attack.get("trials", 0), "same native recovery path, provenance frame")
        add_two("Native Reflexion raw attack vs matched benign", raw_attack.get("rate", 0.0), native_cells.get("raw_benign", {}).get("rate", 0.0), raw_attack.get("trials", 0), native_cells.get("raw_benign", {}).get("trials", 0), "native recovery attack vs matched benign")
        add_two("Native Reflexion tagged attack vs matched benign", tagged_attack.get("rate", 0.0), native_cells.get("tagged_benign", {}).get("rate", 0.0), tagged_attack.get("trials", 0), native_cells.get("tagged_benign", {}).get("trials", 0), "native TrustOrigin treatment vs matched benign")
    legal_rate = legal.get("recovery_rate", 0.0)
    legal_n = legal.get("n_tasks", 0)
    legal_required = required_one_proportion_n(legal_rate, 0.8)
    power = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "method": "normal approximation; two-sided alpha=0.05, target power=0.80; observed pilot proportions used only for planning",
        "comparisons": comparisons,
        "legal_recovery": {"observed_rate": legal_rate, "n_observed": legal_n, "null_rate_for_planning": 0.8, "required_n_vs_null_80pct": legal_required, "interpretation": "screening_only" if legal_n < (legal_required or 0) else "adequate_for_selected_null"},
        "caveat": "These are prospective planning calculations, not confirmatory post-hoc claims. Primary contrasts and stopping rules must be frozen before expansion.",
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "model_snapshot_audit.json").write_text(json.dumps(snapshot_audit, indent=2, ensure_ascii=False), encoding="utf-8")
    provenance = {
        "generated_at_utc": snapshot_audit["generated_at_utc"],
        "status": snapshot_audit["status"],
        "provider_catalog": snapshot_audit["provider_catalog"],
        "gpt_endpoint_audit": gpt_endpoint,
        "models": snapshot_audit["models"],
        "snapshot_candidates": snapshot_audit["snapshot_candidates"],
        "artifact_hashes": snapshot_audit["run_artifact_hashes"],
        "parameter_gaps": snapshot_audit["parameter_gaps"],
    }
    (OUT / "provenance_manifest.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT / "power_analysis.json").write_text(json.dumps(power, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT / "audit_report.md").write_text(
        "# EMSE protocol audit\n\n"
        "- Snapshot status: incomplete; provider aliases and catalog timestamp are recorded, but public snapshot/hash disclosure is unavailable.\n"
        "- GPT endpoint audit: historical artifacts use chat_completions; supplied OpenCode documentation specifies /responses. The current probe result is recorded without relabeling historical runs.\n"
        "- Top-p and per-framework system-prompt hashes are not present in every artifact.\n"
        "- Power calculations use a normal approximation and are planning evidence only.\n",
        encoding="utf-8",
    )
    print(json.dumps({"snapshot_audit": str(OUT / "model_snapshot_audit.json"), "power_analysis": str(OUT / "power_analysis.json"), "comparisons": comparisons}, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
