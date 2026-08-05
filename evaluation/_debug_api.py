#!/usr/bin/env python3
"""Debug: check .env loading and API call."""
import json
import urllib.request
import urllib.error
from pathlib import Path

def load_dotenv(path):
    out = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        k, v = k.strip(), v.strip()
        if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
            v = v[1:-1]
        out.setdefault(k, v)
    return out

env = load_dotenv(Path("e:/ouisani/.env"))
print("OPENAI_API_KEY:", repr(env.get("OPENAI_API_KEY", "")[:30]) + "...")
print("OPENAI_BASE_URL:", repr(env.get("OPENAI_BASE_URL")))
print("OPENAI_MODEL:", repr(env.get("OPENAI_MODEL")))
print("FRONTIER_MODEL_PROVIDER:", repr(env.get("FRONTIER_MODEL_PROVIDER")))

api_key = env["OPENAI_API_KEY"]
base_url = env["OPENAI_BASE_URL"]
model = env["OPENAI_MODEL"]
url = base_url.rstrip("/")
if not url.endswith("/v1"):
    url += "/v1"
url += "/chat/completions"
print("URL:", url)

payload = json.dumps({
    "model": model,
    "messages": [{"role": "user", "content": "Say hello"}],
    "max_tokens": 10,
}).encode("utf-8")
req = urllib.request.Request(url, data=payload, method="POST")
req.add_header("Content-Type", "application/json")
req.add_header("Authorization", f"Bearer {api_key}")
req.add_header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
try:
    resp = urllib.request.urlopen(req, timeout=30)
    print("SUCCESS:", resp.read().decode()[:300])
except urllib.error.HTTPError as e:
    print(f"ERROR {e.code}:", e.read().decode()[:300])
