#!/usr/bin/env python3
"""One-call connectivity test for every EMSE model; never persists credentials."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / ".env"
OUT_DIR = ROOT / "evaluation" / "results" / "emse_smoke"

MODEL_KEYS = (
    "GPT56_LUNA",
    "GLM52",
    "KIMI_K26",
    "MIMO_V25",
    "MINIMAX_M3",
    "QWEN37_PLUS",
    "DEEPSEEK_V4_FLASH",
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


def request_json(url: str, api_key: str, payload: dict) -> tuple[int, dict, float]:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
            "user-agent": "recova-emse-smoke/1.0",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            status = response.status
            data = json.loads(response.read().decode("utf-8", errors="replace"))
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            data = {"error": {"message": raw[:1000]}}
    return status, data, (time.perf_counter() - started) * 1000


def safe_summary(data: dict) -> dict:
    error = data.get("error")
    if isinstance(error, dict):
        error = error.get("message") or error.get("type") or str(error)
    content = ""
    choices = data.get("choices")
    if isinstance(choices, list) and choices:
        message = choices[0].get("message", {})
        content = str(message.get("content", ""))
    elif isinstance(data.get("content"), list) and data["content"]:
        content = str(data["content"][0].get("text", ""))
    return {
        "response_model": data.get("model"),
        "response_id": data.get("id"),
        "content_preview": content[:120],
        "error": str(error)[:500] if error else None,
    }


def main() -> int:
    env = {**load_env(ENV_PATH), **os.environ}
    api_key = env.get("OPENCODE_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENCODE_API_KEY is missing")

    results = []
    for suffix in MODEL_KEYS:
        model = env[f"EMSE_MODEL_{suffix}"]
        protocol = env[f"EMSE_PROTOCOL_{suffix}"]
        if protocol == "chat_completions":
            url = env["OPENCODE_CHAT_ENDPOINT"]
            payload = {
                "model": model,
                "messages": [{"role": "user", "content": "Reply with exactly OK."}],
                "temperature": 0,
                "max_tokens": 8,
            }
        else:
            url = env["OPENCODE_MESSAGES_ENDPOINT"]
            payload = {
                "model": model,
                "messages": [{"role": "user", "content": "Reply with exactly OK."}],
                "temperature": 0,
                "max_tokens": 8,
            }
        status, data, elapsed_ms = request_json(url, api_key, payload)
        row = {
            "requested_model": model,
            "protocol": protocol,
            "http_status": status,
            "latency_ms": round(elapsed_ms, 1),
            "ok": 200 <= status < 300,
            **safe_summary(data),
        }
        results.append(row)
        print(f"{model}: HTTP {status}, ok={row['ok']}, {row['latency_ms']} ms", flush=True)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = {
        "run_id": f"emse-smoke-{stamp}",
        "run_at_utc": datetime.now(timezone.utc).isoformat(),
        "temperature": 0,
        "max_tokens": 8,
        "results": results,
    }
    path = OUT_DIR / f"{stamp}.json"
    path.write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT_DIR / "latest.json").write_text(
        json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"Results: {path}")
    return 0 if all(row["ok"] for row in results) else 2


if __name__ == "__main__":
    raise SystemExit(main())
