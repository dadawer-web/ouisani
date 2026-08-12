#!/usr/bin/env python3
"""Run isolated Reflexion pilots/full cells for every configured EMSE model."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "evaluation" / "paper_draft_reflexion_e2e_exploit.py"
RESULT_ROOT = ROOT / "evaluation" / "results" / "emse_reflexion"
MODEL_KEYS = (
    "GPT56_LUNA", "GLM52", "KIMI_K26", "MIMO_V25",
    "MINIMAX_M3", "QWEN37_PLUS", "DEEPSEEK_V4_FLASH",
)


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def main() -> int:
    cfg = load_env(ROOT / ".env")
    n = int(os.environ.get("EMSE_N_PER_VARIANT", "1"))
    configured_run = os.environ.get("EMSE_RUN_DIR", "").strip()
    if configured_run:
        run_root = Path(configured_run)
        if not run_root.is_absolute():
            run_root = ROOT / run_root
    else:
        run_stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        run_root = RESULT_ROOT / f"pilot_n{n}_{run_stamp}"
    run_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "run_id": run_root.name,
        "run_at_utc": datetime.now(timezone.utc).isoformat(),
        "framework": "Reflexion",
        "framework_commit": "218cf0ef1df84b05ce379dd4a8e47f17766733a0",
        "n_per_variant": n,
        "temperature": 0.7,
        "status": "running",
        "models": [],
    }
    failures = 0
    stopped_on_failure = False
    stop_on_failure = os.environ.get("EMSE_STOP_MATRIX_ON_FAILURE", "1") == "1"
    existing_manifest_path = run_root / "manifest.json"
    existing_models: dict[str, dict] = {}
    if existing_manifest_path.exists():
        try:
            existing = json.loads(existing_manifest_path.read_text(encoding="utf-8"))
            existing_models = {row["model"]: row for row in existing.get("models", [])}
            manifest["models"] = list(existing.get("models", []))
        except (OSError, json.JSONDecodeError, KeyError, TypeError):
            existing_models = {}
    selected = tuple(
        item.strip() for item in os.environ.get("EMSE_ONLY_MODELS", "").split(",") if item.strip()
    ) or MODEL_KEYS
    for suffix in selected:
        model = cfg[f"EMSE_MODEL_{suffix}"]
        protocol = cfg[f"EMSE_PROTOCOL_{suffix}"]
        model_out = run_root / model
        completed_json = model_out / "paper_draft_reflexion_e2e_exploit.json"
        if model in existing_models and existing_models[model].get("ok") and completed_json.exists():
            print(f"Skipping completed {model}", flush=True)
            continue
        child_env = os.environ.copy()
        child_env.update({
            "OPENAI_API_KEY": cfg["OPENCODE_API_KEY"],
            "OPENAI_BASE_URL": cfg["OPENCODE_CHAT_BASE_URL"],
            "OPENAI_MODEL": model,
            "EMSE_API_PROTOCOL": protocol,
            "EMSE_N_PER_VARIANT": str(n),
            "EMSE_OUTPUT_DIR": str(model_out),
        })
        log_path = run_root / f"{model}.log"
        print(f"Running {model} ({protocol}), n={n}", flush=True)
        with log_path.open("w", encoding="utf-8") as log:
            completed = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=ROOT,
                env=child_env,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        ok = completed.returncode == 0
        failures += 0 if ok else 1
        row = {
            "model": model,
            "protocol": protocol,
            "returncode": completed.returncode,
            "ok": ok,
            "output_dir": str(model_out.relative_to(ROOT)),
            "log": str(log_path.relative_to(ROOT)),
        }
        manifest["models"] = [item for item in manifest["models"] if item.get("model") != model]
        manifest["models"].append(row)
        (run_root / "manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"Finished {model}: ok={ok}", flush=True)
        if not ok and stop_on_failure:
            stopped_on_failure = True
            print("Stopping matrix after child failure (EMSE_STOP_MATRIX_ON_FAILURE=1)", flush=True)
            break
    if stopped_on_failure:
        manifest["status"] = "stopped_on_child_failure"
    else:
        manifest["status"] = "complete" if failures == 0 else "completed_with_failures"
    manifest["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
    (run_root / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"Manifest: {run_root / 'manifest.json'}")
    return 0 if failures == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
