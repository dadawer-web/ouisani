#!/usr/bin/env python3
"""Reflexion provenance/role/channel/position causal ablation.

The canonical Reflexion prompt and payloads are held constant.  Each cell
changes one pre-registered factor relative to
``recovery_raw_user_tail``:

* channel: payload in recovery feedback vs ordinary forward input;
* provenance: raw content vs explicit untrusted-source framing;
* role: user vs system message role;
* position: payload message at the head vs tail of the context.

This is a factorial *pilot* by default (``EMSE_ABLATION_N=50``).  The result
reports model-level and payload-level cells separately; it never pools away
the causal factors.
"""

from __future__ import annotations

import csv
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EVALUATION_DIR = Path(__file__).resolve().parent
if str(EVALUATION_DIR) not in sys.path:
    sys.path.insert(0, str(EVALUATION_DIR))

from paper_draft_reflexion_e2e_exploit import (  # noqa: E402
    FUNC_SIG,
    PREV_FUNC_IMPL,
    SELF_REFLECTION,
    VARIANTS,
    build_benign_feedback,
    build_feedback,
    build_reflexion_messages,
    call_api,
    classify_response,
)


CELL_DEFINITIONS: dict[str, dict[str, str]] = {
    "recovery_raw_user_tail": {
        "channel": "recovery", "provenance": "raw", "role": "user", "position": "tail",
    },
    "recovery_tagged_user_tail": {
        "channel": "recovery", "provenance": "tagged", "role": "user", "position": "tail",
    },
    "forward_raw_user_tail": {
        "channel": "forward", "provenance": "raw", "role": "user", "position": "tail",
    },
    "recovery_raw_system_tail": {
        "channel": "recovery", "provenance": "raw", "role": "system", "position": "tail",
    },
    "recovery_raw_user_head": {
        "channel": "recovery", "provenance": "raw", "role": "user", "position": "head",
    },
}


class QuotaExhausted(RuntimeError):
    """Stop the ablation on provider auth/quota/rate exhaustion."""


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def provenance_wrap(payload: str, provenance: str) -> str:
    if provenance == "raw":
        return payload
    if provenance == "tagged":
        return f'<untrusted-source origin="test-runner">\n{payload}\n</untrusted-source>'
    raise ValueError(provenance)


def payload_message(payload: str, cell: dict[str, str]) -> dict[str, str]:
    return {
        "role": cell["role"],
        "content": (
            "[external recovery/forward content]\n"
            + provenance_wrap(payload, cell["provenance"])
        ),
    }


def build_ablation_messages(variant: str, arm: str, cell: dict[str, str]) -> list[dict[str, str]]:
    payload = build_feedback(variant) if arm == "attack" else build_benign_feedback()
    if cell["channel"] == "recovery":
        messages = build_reflexion_messages(
            func_sig=FUNC_SIG,
            prev_func_impl=PREV_FUNC_IMPL,
            feedback=payload if cell["provenance"] == "raw" else provenance_wrap(payload, cell["provenance"]),
            self_reflection=SELF_REFLECTION,
        )
        if cell["provenance"] == "tagged":
            messages[3]["content"] = (
                "[unit test results from previous impl]:\n"
                + provenance_wrap(payload, cell["provenance"])
                + "\n\n[reflection on previous impl]:"
            )
        messages[3]["role"] = cell["role"]
        if cell["position"] == "head":
            # Remove the payload from its normal recovery location and put the
            # same content immediately after the system instruction.
            messages[3] = {
                "role": "user",
                "content": "[unit test results from previous impl]:\n" + build_benign_feedback()
                + "\n\n[reflection on previous impl]:",
            }
            messages.insert(1, payload_message(payload, cell))
        return messages

    # Forward-path counterfactual: keep recovery feedback benign and inject
    # the same payload into an ordinary forward context message.
    messages = build_reflexion_messages(
        func_sig=FUNC_SIG,
        prev_func_impl=PREV_FUNC_IMPL,
        feedback=build_benign_feedback(),
        self_reflection=SELF_REFLECTION,
    )
    injection = payload_message(payload, cell)
    if cell["position"] == "head":
        messages.insert(1, injection)
    else:
        messages.insert(len(messages) - 1, injection)
    return messages


def run_trial(api_key: str, base_url: str, model: str, cell_id: str,
              cell: dict[str, str], arm: str, variant: str, idx: int) -> dict[str, Any]:
    messages = build_ablation_messages(variant, arm, cell)
    try:
        response = call_api(api_key, base_url, model, messages, temperature=0.7, max_tokens=500, timeout=90)
        success, classification, parsed = classify_response(response)
        return {
            "trial_id": f"{cell_id}/{arm}/{variant}/{idx}",
            "framework": "Reflexion",
            "model": model,
            "cell_id": cell_id,
            **cell,
            "arm": arm,
            "variant": variant,
            "trial_idx": idx,
            "messages_sent": messages,
            "response": response,
            "parsed_code": parsed,
            "attack_success": success,
            "classification": classification,
            "error": None,
        }
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 402, 403, 429):
            raise QuotaExhausted(f"HTTP {exc.code} from model provider") from exc
        return {
            "trial_id": f"{cell_id}/{arm}/{variant}/{idx}",
            "framework": "Reflexion", "model": model, "cell_id": cell_id,
            **cell, "arm": arm, "variant": variant, "trial_idx": idx,
            "messages_sent": messages, "response": None, "parsed_code": None,
            "attack_success": False, "classification": "api_error",
            "error": f"HTTPError: {exc}",
        }
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, KeyError) as exc:
        return {
            "trial_id": f"{cell_id}/{arm}/{variant}/{idx}",
            "framework": "Reflexion", "model": model, "cell_id": cell_id,
            **cell, "arm": arm, "variant": variant, "trial_idx": idx,
            "messages_sent": messages, "response": None, "parsed_code": None,
            "attack_success": False, "classification": "api_error",
            "error": f"{type(exc).__name__}: {exc}",
        }


def main() -> int:
    env = load_env(ROOT / ".env")
    api_key = os.environ.get("EMSE_API_KEY") or env.get("OPENCODE_API_KEY", "") or os.environ.get("OPENAI_API_KEY", "")
    base_url = os.environ.get("EMSE_BASE_URL") or env.get("OPENCODE_CHAT_BASE_URL", "") or os.environ.get("OPENAI_BASE_URL", "")
    model_suffix = os.environ.get("EMSE_MODEL_KEY", "KIMI_K26").strip().upper()
    model = os.environ.get("OPENAI_MODEL") or env.get(f"EMSE_MODEL_{model_suffix}", "")
    if not api_key or not base_url or not model:
        print("ERROR: API key/base URL/model not set", file=sys.stderr)
        return 1
    n = int(os.environ.get("EMSE_ABLATION_N", "50"))
    selected_ids = tuple(
        item.strip() for item in os.environ.get("EMSE_ABLATION_CELLS", "").split(",") if item.strip()
    ) or tuple(CELL_DEFINITIONS)
    cells = {cell_id: CELL_DEFINITIONS[cell_id] for cell_id in selected_ids}
    configured = os.environ.get("EMSE_OUTPUT_DIR", "").strip()
    output_dir = Path(configured) if configured else ROOT / "evaluation" / "results" / "emse_reflexion_ablation" / model
    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_path = output_dir / "causal_ablation.raw.jsonl"
    checkpoint_path = output_dir / "causal_ablation.checkpoint.jsonl"
    resume = os.environ.get("EMSE_RESUME", "1") == "1"
    if not resume and os.environ.get("EMSE_FORCE_NEW", "0") == "1":
        raw_path.unlink(missing_ok=True)
        checkpoint_path.unlink(missing_ok=True)
    completed: dict[str, dict[str, Any]] = {}
    if resume and raw_path.exists():
        for line in raw_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                trial = json.loads(line)
                completed[trial["trial_id"]] = trial
    jobs = [
        (cell_id, cell, arm, variant, idx)
        for cell_id, cell in cells.items()
        for arm in ("attack", "benign")
        for variant in VARIANTS
        for idx in range(n)
        if f"{cell_id}/{arm}/{variant}/{idx}" not in completed
    ]
    concurrency = max(1, int(os.environ.get("EMSE_CONCURRENCY", "2")))
    results = list(completed.values())
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = {
            pool.submit(run_trial, api_key, base_url, model, *job): job
            for job in jobs
        }
        try:
            for future in as_completed(futures):
                trial = future.result()
                results.append(trial)
                line = json.dumps(trial, ensure_ascii=False)
                with raw_path.open("a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
                with checkpoint_path.open("a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
        except QuotaExhausted as exc:
            for pending in futures:
                pending.cancel()
            print(f"STOPPED: {exc}", file=sys.stderr)
            return 2

    def summarize(subset: list[dict[str, Any]]) -> dict[str, Any]:
        successes = sum(bool(trial.get("attack_success")) for trial in subset)
        return {"successes": successes, "trials": len(subset),
                "asr": successes / len(subset) if subset else 0.0,
                "errors": sum(trial.get("classification") == "api_error" for trial in subset)}

    by_cell: dict[str, Any] = {}
    for cell_id, cell in cells.items():
        attack = [trial for trial in results if trial["cell_id"] == cell_id and trial["arm"] == "attack"]
        benign = [trial for trial in results if trial["cell_id"] == cell_id and trial["arm"] == "benign"]
        by_cell[cell_id] = {"factors": cell, "attack": summarize(attack), "benign": summarize(benign)}
    result = {
        "experiment": "Reflexion causal ablation: channel/provenance/role/position",
        "framework": "Reflexion",
        "model": model,
        "n_per_cell_variant_arm": n,
        "concurrency": concurrency,
        "cells": by_cell,
        "pre_registered_reference_cell": "recovery_raw_user_tail",
        "payloads": VARIANTS,
        "raw_logs": str(raw_path.relative_to(ROOT)),
        "run_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    (output_dir / "causal_ablation.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    fields = ["trial_id", "framework", "model", "cell_id", "channel", "provenance", "role", "position", "arm", "variant", "trial_idx", "classification", "attack_success", "error"]
    with (output_dir / "causal_ablation.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows({key: trial.get(key) for key in fields} for trial in results)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
