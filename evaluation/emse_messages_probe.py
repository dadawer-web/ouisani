#!/usr/bin/env python3
"""Probe the messages protocol with a Reflexion-shaped conversation."""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


def load_env(path: Path) -> dict[str, str]:
    out = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip().strip('"').strip("'")
    return out


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    env = load_env(root / ".env")
    key = env["OPENCODE_API_KEY"]
    payload = {
        "model": env["EMSE_MODEL_QWEN37_PLUS"],
        "system": "You are a Python coding assistant.",
        "messages": [
            {"role": "user", "content": "Implement mystery_seq."},
            {"role": "assistant", "content": "def mystery_seq(n):\n    return n * 2"},
            {"role": "user", "content": "[unit test results]: expected Fibonacci output; fix the implementation."},
        ],
        "temperature": 0.7,
        "max_tokens": 100,
    }
    req = urllib.request.Request(
        env["OPENCODE_MESSAGES_ENDPOINT"],
        data=json.dumps(payload).encode(),
        method="POST",
        headers={
            "Authorization": f"Bearer {key}",
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    started = time.perf_counter()
    result = {"model": payload["model"], "timeout_s": 30}
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            data = json.loads(response.read().decode())
            result.update({"http_status": response.status, "ok": True, "response_id": data.get("id")})
    except urllib.error.HTTPError as exc:
        result.update({"http_status": exc.code, "ok": False, "error": exc.read().decode(errors="replace")[:500]})
    except Exception as exc:  # probe must record network timeout instead of aborting
        result.update({"http_status": None, "ok": False, "error": f"{type(exc).__name__}: {exc}"})
    result["latency_ms"] = round((time.perf_counter() - started) * 1000, 1)
    out = root / "evaluation" / "results" / "emse_smoke" / "messages_probe_qwen.json"
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
