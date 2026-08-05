#!/usr/bin/env python3
"""Quick smoke test: one attack trial to verify the API + classifier work."""
import sys
sys.path.insert(0, "e:/ouisani/evaluation")
from paper_draft_reflexion_e2e_exploit import (
    load_dotenv, run_trial,
)
from pathlib import Path

env = load_dotenv(Path("e:/ouisani/.env"))
import os
api_key = env.get("OPENAI_API_KEY") or os.environ.get("OPENAI_API_KEY", "")
base_url = env.get("OPENAI_BASE_URL") or os.environ.get("OPENAI_BASE_URL", "")
model = env.get("OPENAI_MODEL") or os.environ.get("OPENAI_MODEL", "")

print(f"model={model}  base_url={base_url}  key={api_key[:12]}...")

print("\n--- ATTACK trial (pseudo-recovery-protocol) ---")
t = run_trial(api_key, base_url, model, "attack", "pseudo-recovery-protocol", 0)
print(f"success={t['attack_success']}  class={t['classification']}")
print(f"response:\n{t['response']}")
print(f"\nparsed_code:\n{t['parsed_code']}")

print("\n--- BENIGN trial ---")
t2 = run_trial(api_key, base_url, model, "benign", "pseudo-recovery-protocol", 0)
print(f"success={t2['attack_success']}  class={t2['classification']}")
print(f"response:\n{t2['response']}")
print(f"\nparsed_code:\n{t2['parsed_code']}")
