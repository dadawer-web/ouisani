#!/usr/bin/env python3
"""Run the native AutoGen pilot for selected OpenCode models.

The default is a three-model n=50 screening matrix.  Each child is isolated
in the pinned AutoGen virtual environment by the caller and writes its own
raw trial log, checkpoint, JSON summary, and CSV.  A child that reports auth,
quota, or rate exhaustion is recorded and no new child is launched after it.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "evaluation" / "paper_draft_autogen_rci_e2e_exploit.py"
RESULT_ROOT = ROOT / "evaluation" / "results" / "emse_autogen_native"
MODEL_KEYS = ("GPT56_LUNA", "GLM52", "KIMI_K26")


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
    n = int(os.environ.get("EMSE_N_PER_VARIANT", "50"))
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    configured = os.environ.get("EMSE_RUN_DIR", "").strip()
    run_root = Path(configured) if configured else RESULT_ROOT / f"pilot_n{n}_{stamp}"
    if not run_root.is_absolute():
        run_root = ROOT / run_root
    run_root.mkdir(parents=True, exist_ok=True)
    selected = tuple(
        item.strip() for item in os.environ.get("EMSE_ONLY_MODELS", "").split(",") if item.strip()
    ) or MODEL_KEYS
    manifest_path = run_root / "manifest.json"
    manifest = {
        "run_id": run_root.name,
        "run_at_utc": datetime.now(timezone.utc).isoformat(),
        "framework": "AutoGen",
        "autogen_pin": "autogen-agentchat==0.4.7; autogen-core==0.4.7; autogen-ext[openai]==0.4.7",
        "n_per_variant": n,
        "status": "running",
        "models": [],
    }
    if manifest_path.exists():
        try:
            manifest.update(json.loads(manifest_path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError):
            pass

    failures = 0
    for suffix in selected:
        model = cfg[f"EMSE_MODEL_{suffix}"]
        model_out = run_root / model
        result_json = model_out / "paper_draft_autogen_rci_e2e_exploit.json"
        existing = next((row for row in manifest["models"] if row.get("model") == model), None)
        if existing and existing.get("ok") and result_json.exists():
            print(f"Skipping completed {model}", flush=True)
            continue
        child_env = os.environ.copy()
        child_env.update({
            "OPENAI_API_KEY": cfg["OPENCODE_API_KEY"],
            "OPENAI_BASE_URL": cfg["OPENCODE_CHAT_BASE_URL"],
            "OPENAI_MODEL": model,
            "EMSE_N_PER_VARIANT": str(n),
            "EMSE_OUTPUT_DIR": str(model_out),
            "EMSE_RESUME": "1",
        })
        log_path = run_root / f"{model}.log"
        print(f"Running native AutoGen {model}, n={n}", flush=True)
        with log_path.open("a", encoding="utf-8") as log:
            completed = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=ROOT,
                env=child_env,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        ok = completed.returncode == 0 and result_json.exists()
        failures += 0 if ok else 1
        row = {
            "model": model,
            "returncode": completed.returncode,
            "ok": ok,
            "output_dir": str(model_out.relative_to(ROOT)),
            "log": str(log_path.relative_to(ROOT)),
        }
        manifest["models"] = [item for item in manifest["models"] if item.get("model") != model]
        manifest["models"].append(row)
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Finished {model}: ok={ok}", flush=True)
        if not ok and os.environ.get("EMSE_STOP_MATRIX_ON_FAILURE", "1") == "1":
            manifest["status"] = "stopped_on_child_failure"
            manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
            break

    if manifest.get("status") == "running":
        manifest["status"] = "complete" if failures == 0 else "completed_with_failures"
    manifest["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print(manifest_path)
    return 0 if failures == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
