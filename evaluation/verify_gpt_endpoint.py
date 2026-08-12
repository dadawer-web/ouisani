#!/usr/bin/env python3
"""Verify the documented and historical OpenCode Go GPT endpoints.

This is a one-request protocol check.  It deliberately records only request
metadata and response shape, not the API key or the full provider response.
Historical experiment artifacts are not rewritten by this script.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "results" / "emse_protocol_audit" / "gpt_endpoint_verification.json"


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in raw or raw.lstrip().startswith("#"):
            continue
        key, value = raw.strip().split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def response_text(data: dict) -> str:
    if isinstance(data.get("output_text"), str):
        return data["output_text"]
    chunks: list[str] = []
    for item in data.get("output", []) or []:
        for content in item.get("content", []) or []:
            if content.get("type") in {"output_text", "text"}:
                text = content.get("text")
                if isinstance(text, str):
                    chunks.append(text)
    if chunks:
        return "".join(chunks)
    choices = data.get("choices", []) or []
    if choices:
        return str(choices[0].get("message", {}).get("content", ""))
    return ""


def check_endpoint(endpoint: str, body: dict, api_key: str) -> dict:
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            data = json.loads(response.read().decode("utf-8"))
            text = response_text(data)
            return {
                "http_status": response.status,
                "response_top_level_keys": sorted(data.keys()),
                "response_text_preview": text[:80],
                "ok": response.status == 200 and bool(text),
            }
    except urllib.error.HTTPError as exc:
        try:
            body_text = exc.read().decode("utf-8", errors="replace")[:500]
        except Exception:
            body_text = ""
        return {
            "http_status": exc.code,
            "error": f"HTTPError: {exc.reason}",
            "error_body_preview": body_text,
            "ok": False,
        }
    except Exception as exc:  # retain a concise diagnostic without secrets
        return {"error": f"{type(exc).__name__}: {exc}", "ok": False}


def main() -> int:
    env = load_env(ROOT / ".env")
    api_key = os.environ.get("OPENCODE_API_KEY") or env.get("OPENCODE_API_KEY", "")
    model = env.get("EMSE_MODEL_GPT56_LUNA", "gpt-5.6-luna")
    endpoint = env.get(
        "OPENCODE_RESPONSES_ENDPOINT",
        "https://opencode.ai/zen/go/v1/responses",
    )
    chat_endpoint = env.get(
        "OPENCODE_CHAT_ENDPOINT",
        "https://opencode.ai/zen/go/v1/chat/completions",
    )
    result: dict = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "model": model,
        "documented_endpoint": endpoint,
        "historical_endpoint": chat_endpoint,
        "documented_protocol": "responses",
        "historical_protocol": "chat_completions",
        "request": {"temperature": 0.0, "max_output_tokens": 16},
        "api_key_recorded": False,
        "responses_ok": False,
        "chat_completions_ok": False,
    }
    if not api_key:
        result["error"] = "OPENCODE_API_KEY not configured"
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 2

    responses_body = {
        "model": model,
        "input": [
            {
                "role": "user",
                "content": [{"type": "input_text", "text": "Reply with exactly OK."}],
            }
        ],
        "temperature": 0.0,
        "max_output_tokens": 16,
    }
    chat_body = {
        "model": model,
        "messages": [{"role": "user", "content": "Reply with exactly OK."}],
        "temperature": 0.0,
        "max_tokens": 16,
    }
    result["responses_check"] = check_endpoint(endpoint, responses_body, api_key)
    result["chat_completions_check"] = check_endpoint(chat_endpoint, chat_body, api_key)
    result["responses_ok"] = bool(result["responses_check"].get("ok"))
    result["chat_completions_ok"] = bool(result["chat_completions_check"].get("ok"))
    result["ok"] = result["responses_ok"]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
