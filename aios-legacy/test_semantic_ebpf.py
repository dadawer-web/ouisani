#!/usr/bin/env python3
"""AIOS Semantic eBPF Dynamic Interception Test

Demonstrates real-time "semantic man-in-the-middle" prompt rewriting:
  1. Compile semantic_ebpf.c -> WASM (BPF filter)
  2. Hot-load the BPF program onto pre_llm_inference hook via /bpf/load
  3. Spawn an Agent, let it aios_think("讲讲FAT表是怎么管理磁盘的")
  4. Verify the kernel transparently rewrote the prompt at millisecond speed
  5. Verify non-matching prompts pass through untouched

Prerequisite: aios_core must be running on 127.0.0.1:8080.
"""

import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

import requests

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
WASI_SDK_PATH = "/opt/wasi-sdk"
WASI_SYSROOT = f"{WASI_SDK_PATH}/share/wasi-sysroot"

SYSCALL_PORT = 8080
HTTP_PORT = 8083
AGENT_ID = 42

BANNER = r"""
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   🔬  AIOS Semantic eBPF — Dynamic Interception Test  🔬                    ║
║                                                                              ║
║   "In the kernel's shadow, every thought is observed..."                     ║
║                                                                              ║
║   ┌─────────────────────────────────────────────────────────────────┐        ║
║   │  Agent Prompt ──► [BPF pre_llm_inference] ──► LLM Provider    │        ║
║   │                        │                                       │        ║
║   │                   ┌────┴────┐                                  │        ║
║   │                   │ WASM    │                                  │        ║
║   │                   │ Filter  │                                  │        ║
║   │                   └────┬────┘                                  │        ║
║   │                        │                                       │        ║
║   │              ┌─────────┼─────────┐                             │        ║
║   │              ▼                   ▼                             │        ║
║   │         [MODIFY]            [PASS/DROP]                       │        ║
║   └─────────────────────────────────────────────────────────────────┘        ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
"""

INTERCEPT_ART = r"""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║                                                                      ║
  ║   🎯  SEMANTIC MAN-IN-THE-MIDDLE — INTERCEPTION CONFIRMED  🎯      ║
  ║                                                                      ║
  ║   The BPF filter has transparently rewritten the Agent's thought!   ║
  ║   Original:  "讲讲FAT表是怎么管理磁盘的"                              ║
  ║   Rewritten: "【名师严谨模式触发】请以操作系统与编译原理的..."         ║
  ║                                                                      ║
  ║   The Agent has NO idea its prompt was hijacked.                     ║
  ║   The kernel sees everything. The kernel changes everything.         ║
  ║                                                                      ║
  ╚══════════════════════════════════════════════════════════════════════╝
"""

PASS_THROUGH_ART = r"""
  ┌──────────────────────────────────────────────────────────────┐
  │  ✅  PASS-THROUGH VERIFIED — Non-matching prompt untouched  │
  └──────────────────────────────────────────────────────────────┘
"""


def send_payload(payload: dict, timeout: float = 120):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(("127.0.0.1", SYSCALL_PORT))
    client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


def step1_compile_bpf_wasm():
    print(f"\n{'━' * 70}")
    print(f"  📦 STEP 1: Compile Semantic eBPF Filter → WASM")
    print(f"{'━' * 70}")

    c_file = os.path.join(PROJECT_ROOT, "app_semantic_ebpf.c")
    wasm_dir = "/tmp/aios_bpf"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, "bpf_pre_llm_inference.wasm")

    clang = os.path.join(WASI_SDK_PATH, "bin", "clang")
    if not os.path.exists(clang):
        clang = "clang"

    cmd = [
        clang,
        "--target=wasm32-wasi",
        f"--sysroot={WASI_SYSROOT}",
        "-O3",
        "-mexec-model=reactor",
        "-Wl,--allow-undefined",
        "-Wl,--initial-memory=393216",
        "-Wl,--max-memory=4194304",
        "-o", wasm_path,
        c_file,
    ]

    print(f"  [Compile] {clang} --target=wasm32-wasi -mexec-model=reactor -O3")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  ❌ Compilation FAILED:\n{result.stderr}")
        sys.exit(1)

    size = os.path.getsize(wasm_path)
    print(f"  ✅ WASM compiled: {wasm_path} ({size:,} bytes)")
    print(f"     Keywords: FAT表 | 死锁 | LL(1)文法 | LR分析")
    print(f"     Action:   Rewrite → 【名师严谨模式触发】... + original prompt")
    return wasm_path


def step2_load_bpf(wasm_path: str):
    print(f"\n{'━' * 70}")
    print(f"  🔥 STEP 2: Hot-Load BPF Program onto pre_llm_inference Hook")
    print(f"{'━' * 70}")

    with open(wasm_path, "rb") as f:
        wasm_bytes = f.read()

    print(f"  [Upload] {len(wasm_bytes):,} bytes → POST /bpf/load")
    print(f"  [Auth]   X-AIOS-Ring: 0 (Ring 0 kernel privilege)")

    try:
        resp = requests.post(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/load",
            headers={"X-AIOS-Ring": "0"},
            files={
                "wasm": ("bpf_pre_llm_inference.wasm", wasm_bytes, "application/octet-stream"),
            },
            data={
                "hook_point": "pre_llm_inference",
                "export_func": "bpf_filter",
            },
            timeout=10,
        )
        resp_data = resp.json()
    except Exception as e:
        print(f"  ❌ Upload failed: {e}")
        sys.exit(1)

    if resp.status_code != 200:
        print(f"  ❌ HTTP {resp.status_code}: {resp_data}")
        sys.exit(1)

    if resp_data.get("status") == "ok":
        print(f"  ✅ BPF program LOADED!")
        print(f"     hook_point:  {resp_data.get('hook_point')}")
        print(f"     wasm_path:   {resp_data.get('wasm_path')}")
        print(f"     wasm_size:   {resp_data.get('wasm_size'):,} bytes")
        print(f"     export_func: {resp_data.get('export_func')}")
    else:
        print(f"  ❌ Load FAILED: {resp_data}")
        sys.exit(1)

    return resp_data


def step3_verify_bpf_list():
    print(f"\n{'━' * 70}")
    print(f"  📋 STEP 3: Verify BPF Hook Registration")
    print(f"{'━' * 70}")

    try:
        resp = requests.get(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/list",
            headers={"X-AIOS-Ring": "0"},
            timeout=5,
        )
        resp_data = resp.json()
    except Exception as e:
        print(f"  ❌ /bpf/list failed: {e}")
        return False

    hooks = resp_data.get("hooks", [])
    print(f"  Registered hooks: {len(hooks)}")
    for h in hooks:
        print(f"    🔗 {h['hook_point']}")
        print(f"       wasm:    {h['wasm_path']}")
        print(f"       func:    {h['export_func']}")
        print(f"       active:  {h['active']}")
        print(f"       invokes: {h['invoke_count']}")
        print(f"       drops:   {h['drop_count']}")

    pre_llm = [h for h in hooks if h["hook_point"] == "pre_llm_inference"]
    if pre_llm and pre_llm[0]["active"]:
        print(f"  ✅ pre_llm_inference hook is ACTIVE and ready")
        return True
    else:
        print(f"  ❌ pre_llm_inference hook not found or inactive")
        return False


def step4_intercept_test():
    print(f"\n{'━' * 70}")
    print(f"  🎯 STEP 4: Semantic Interception — FAT表 Prompt Rewrite")
    print(f"{'━' * 70}")

    prompt = "讲讲FAT表是怎么管理磁盘的"
    print(f"\n  🤖 Agent #{AGENT_ID} calls: aios_think(\"{prompt}\")")
    print(f"  🤖 The Agent has NO idea a BPF filter is watching...\n")

    resp = send_payload({
        "syscall": "LLM_INFERENCE",
        "prompt": prompt,
        "payload": prompt,
        "caller_id": AGENT_ID,
    }, timeout=60)

    print(f"  [Kernel Response] status={resp.get('status', 'unknown')}")

    llm_output = ""
    if resp.get("status") == "ok":
        data = resp.get("data", resp.get("result", ""))
        if isinstance(data, str):
            try:
                parsed = json.loads(data)
                llm_output = parsed.get("output", parsed.get("result", data))
            except json.JSONDecodeError:
                llm_output = data
        elif isinstance(data, dict):
            llm_output = data.get("output", data.get("result", json.dumps(data, ensure_ascii=False)))
        else:
            llm_output = str(data)
    else:
        llm_output = resp.get("message", json.dumps(resp, ensure_ascii=False))

    intercepted = "名师严谨模式触发" in llm_output or "严谨模式" in llm_output

    if intercepted:
        print(f"\n  💥 ─────────────────────────────────────────────────")
        print(f"  💥  LLM RECEIVED REWRITTEN PROMPT:")
        preview = llm_output[:200] if len(llm_output) > 200 else llm_output
        print(f"  💥  \"{preview}...\"")
        print(f"  💥 ─────────────────────────────────────────────────")
        print(INTERCEPT_ART)
    else:
        print(f"\n  ⚠️  LLM output (no interception detected in response):")
        preview = llm_output[:200] if len(llm_output) > 200 else llm_output
        print(f"  ⚠️  \"{preview}\"")
        print(f"\n  ℹ️  Note: The BPF filter may have modified the prompt,")
        print(f"  ℹ️  but the LLM provider's response format may not echo it.")
        print(f"  ℹ️  Checking BPF stats for confirmation...")

    return intercepted


def step5_passthrough_test():
    print(f"\n{'━' * 70}")
    print(f"  🛡️  STEP 5: Pass-Through — Non-Matching Prompt Unchanged")
    print(f"{'━' * 70}")

    prompt = "今天天气怎么样"
    print(f"\n  🤖 Agent #{AGENT_ID} calls: aios_think(\"{prompt}\")")
    print(f"  🤖 This prompt has NO compiler/OS keywords...\n")

    resp = send_payload({
        "syscall": "LLM_INFERENCE",
        "prompt": prompt,
        "payload": prompt,
        "caller_id": AGENT_ID,
    }, timeout=60)

    llm_output = ""
    if resp.get("status") == "ok":
        data = resp.get("data", resp.get("result", ""))
        if isinstance(data, str):
            try:
                parsed = json.loads(data)
                llm_output = parsed.get("output", parsed.get("result", data))
            except json.JSONDecodeError:
                llm_output = data
        elif isinstance(data, dict):
            llm_output = data.get("output", data.get("result", json.dumps(data, ensure_ascii=False)))
        else:
            llm_output = str(data)
    else:
        llm_output = resp.get("message", json.dumps(resp, ensure_ascii=False))

    not_modified = "名师严谨模式触发" not in llm_output

    if not_modified:
        print(f"  ✅ Prompt passed through WITHOUT modification")
        preview = llm_output[:150] if len(llm_output) > 150 else llm_output
        print(f"     LLM output: \"{preview}\"")
        print(PASS_THROUGH_ART)
    else:
        print(f"  ❌ UNEXPECTED: Non-matching prompt was modified!")
        print(f"     Output: {llm_output[:200]}")

    return not_modified


def step6_bpf_stats():
    print(f"\n{'━' * 70}")
    print(f"  📊 STEP 6: BPF Hook Statistics")
    print(f"{'━' * 70}")

    try:
        resp = requests.get(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/list",
            headers={"X-AIOS-Ring": "0"},
            timeout=5,
        )
        resp_data = resp.json()
    except Exception as e:
        print(f"  ❌ /bpf/list failed: {e}")
        return False

    hooks = resp_data.get("hooks", [])
    total_invokes = resp_data.get("total_invokes", 0)
    total_drops = resp_data.get("total_drops", 0)

    print(f"  Total invokes: {total_invokes}")
    print(f"  Total drops:   {total_drops}")

    for h in hooks:
        print(f"\n  🔗 {h['hook_point']}:")
        print(f"     invokes: {h['invoke_count']}")
        print(f"     drops:   {h['drop_count']}")
        print(f"     active:  {h['active']}")

    has_invokes = any(h["invoke_count"] > 0 for h in hooks if h["hook_point"] == "pre_llm_inference")
    if has_invokes:
        print(f"\n  ✅ BPF hook was invoked! Dynamic tracing is working.")
    else:
        print(f"\n  ⚠️  BPF hook was NOT invoked. Check kernel logs.")

    return has_invokes


def epilogue(results: dict):
    print(f"\n{'━' * 70}")
    all_pass = all(results.values())

    if all_pass:
        print("""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║                                                                      ║
  ║   🏆  ALL TESTS PASSED — Semantic eBPF Interception Verified  🏆   ║
  ║                                                                      ║
  ║   The kernel can now:                                                ║
  ║     • Observe every LLM inference request in real-time               ║
  ║     • Rewrite prompts based on semantic content                      ║
  ║     • Drop requests via circuit-break semantics                      ║
  ║     • Do all of this WITHOUT the Agent's knowledge                  ║
  ║                                                                      ║
  ║   "With great power comes great responsibility." — eBPF, probably   ║
  ║                                                                      ║
  ╚══════════════════════════════════════════════════════════════════════╝
""")
    else:
        print("""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║                                                                      ║
  ║   ❌  SOME TESTS FAILED — Review the output above  ❌               ║
  ║                                                                      ║
  ╚══════════════════════════════════════════════════════════════════════╝
""")

    for name, passed in results.items():
        icon = "✅" if passed else "❌"
        print(f"  {icon} {name}")

    print(f"{'━' * 70}\n")
    sys.exit(0 if all_pass else 1)


def main():
    print(BANNER)

    print("  🔍 Pre-flight: Checking kernel connection...")
    try:
        probe = send_payload({
            "syscall": "VFS_CALL",
            "action": "READ",
            "path": "/proc/version",
        }, timeout=5)
        print(f"  ✅ Kernel online (status={probe.get('status')})\n")
    except Exception as e:
        print(f"  ❌ Cannot connect to kernel: {e}")
        print(f"  Please start aios_core first: ./build/aios_core")
        sys.exit(1)

    wasm_path = step1_compile_bpf_wasm()
    step2_load_bpf(wasm_path)
    list_ok = step3_verify_bpf_list()
    intercept_ok = step4_intercept_test()
    passthrough_ok = step5_passthrough_test()
    stats_ok = step6_bpf_stats()

    results = {
        "BPF Hook Registration": list_ok,
        "Semantic Interception (FAT表)": intercept_ok,
        "Pass-Through (non-matching)": passthrough_ok,
        "BPF Stats Verification": stats_ok,
    }

    epilogue(results)


if __name__ == "__main__":
    main()
