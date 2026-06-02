#!/usr/bin/env python3
"""AIOS User-Space Firewall Manager

用户态策略守护进程：编译业务规则 WASM → 注入内核 BPF 钩子

架构：
  ┌──────────────────────────────────────────────────────────────┐
  │  用户态 (User Space)                                         │
  │  app_firewall_manager.py                                     │
  │    ├── Clang 编译 usr_408_firewall.c → .wasm                │
  │    └── POST /bpf/load → 注入内核 pre_llm_inference 钩子     │
  ├──────────────────────────────────────────────────────────────┤
  │  内核态 (Kernel Space)                                       │
  │  BpfManager::run_hook("pre_llm_inference", prompt)          │
  │    ├── [机制层] OS 恶意指令熔断 (rm -rf, DROP TABLE...)     │
  │    └── [策略层] 408 业务规则改写 (FAT表→名师严谨模式)       │
  └──────────────────────────────────────────────────────────────┘
"""

import json
import os
import subprocess
import sys
import time

import requests

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
WASI_SDK_PATH = "/opt/wasi-sdk"
WASI_SYSROOT = f"{WASI_SDK_PATH}/share/wasi-sysroot"

SYSCALL_PORT = 8080
HTTP_PORT = 8083

BANNER = r"""
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   🛡️  AIOS User-Space Firewall Manager  🛡️                                 ║
║                                                                              ║
║   "Policy defined in user-space, enforced in kernel-space"                   ║
║                                                                              ║
║   ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐       ║
║   │  usr_408_       │     │  Clang → WASM   │     │  POST /bpf/load │       ║
║   │  firewall.c     │ ──► │  (compile)      │ ──► │  (hot inject)   │       ║
║   └─────────────────┘     └─────────────────┘     └────────┬────────┘       ║
║                                                             │                ║
║                                                    ┌────────▼────────┐       ║
║                                                    │  AIOS Kernel    │       ║
║                                                    │  BpfManager     │       ║
║                                                    │  pre_llm_       │       ║
║                                                    │  inference      │       ║
║                                                    └─────────────────┘       ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
"""

SUCCESS_ART = r"""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║                                                                      ║
  ║   🔥  [User-Space Firewall] 已成功将 408 业务规则注入 AIOS 内核！  ║
  ║                                                                      ║
  ║   机制层: OS 恶意指令熔断 (rm -rf, DROP TABLE, /bin/sh...)          ║
  ║   策略层: 408 学术关键词改写 (FAT表/死锁/LL(1)文法 → 名师模式)     ║
  ║                                                                      ║
  ║   用户态定义策略，内核态强制执行。                                    ║
  ║   Mechanism & Policy — perfectly separated.                          ║
  ║                                                                      ║
  ╚══════════════════════════════════════════════════════════════════════╝
"""


def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"  [{ts}] [{tag}] {msg}")


def check_kernel_online() -> bool:
    try:
        resp = requests.get(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/list",
            headers={"X-AIOS-Ring": "0"},
            timeout=3,
        )
        return resp.status_code == 200
    except Exception:
        return False


def step1_compile() -> str:
    log("Compile", "编译 usr_408_firewall.c → WASM (WASI reactor)")

    c_file = os.path.join(PROJECT_ROOT, "usr_408_firewall.c")
    wasm_dir = "/tmp/aios_bpf"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, "usr_408_firewall.wasm")

    clang = os.path.join(WASI_SDK_PATH, "bin", "clang")
    if not os.path.exists(clang):
        log("ERROR", f"WASI SDK clang not found at {clang}")
        log("ERROR", "Install: https://github.com/WebAssembly/wasi-sdk")
        sys.exit(1)

    cmd = [
        clang,
        "--target=wasm32-wasi",
        f"--sysroot={WASI_SYSROOT}",
        "-O3",
        "-mexec-model=reactor",
        "-Wl,--allow-undefined",
        "-Wl,--initial-memory=524288",
        "-Wl,--max-memory=4194304",
        "-o", wasm_path,
        c_file,
    ]

    log("Compile", f"  {clang} --target=wasm32-wasi -mexec-model=reactor -O3")

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        log("ERROR", f"编译失败:\n{result.stderr}")
        sys.exit(1)

    size = os.path.getsize(wasm_path)
    log("Compile", f"✅ 编译成功: {wasm_path} ({size:,} bytes)")
    return wasm_path


def step2_inject(wasm_path: str) -> dict:
    log("Inject", "通过 HTTP POST /bpf/load 注入内核 BPF 钩子")

    with open(wasm_path, "rb") as f:
        wasm_bytes = f.read()

    log("Inject", f"  上传 {len(wasm_bytes):,} bytes → pre_llm_inference")
    log("Inject", f"  认证: X-AIOS-Ring: 0 (Ring 0 内核特权)")

    try:
        resp = requests.post(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/load",
            headers={"X-AIOS-Ring": "0"},
            files={
                "wasm": ("usr_408_firewall.wasm", wasm_bytes, "application/octet-stream"),
            },
            data={
                "hook_point": "pre_llm_inference",
                "export_func": "bpf_filter",
            },
            timeout=10,
        )
        resp_data = resp.json()
    except Exception as e:
        log("ERROR", f"注入失败: {e}")
        sys.exit(1)

    if resp.status_code != 200 or resp_data.get("status") != "ok":
        log("ERROR", f"HTTP {resp.status_code}: {resp_data}")
        sys.exit(1)

    log("Inject", f"✅ BPF 程序已注入内核!")
    log("Inject", f"   hook_point:  {resp_data.get('hook_point')}")
    log("Inject", f"   wasm_path:   {resp_data.get('wasm_path')}")
    log("Inject", f"   wasm_size:   {resp_data.get('wasm_size'):,} bytes")
    log("Inject", f"   export_func: {resp_data.get('export_func')}")

    return resp_data


def step3_verify():
    log("Verify", "验证 BPF 钩子注册状态")

    try:
        resp = requests.get(
            f"http://127.0.0.1:{HTTP_PORT}/bpf/list",
            headers={"X-AIOS-Ring": "0"},
            timeout=5,
        )
        data = resp.json()
    except Exception as e:
        log("ERROR", f"/bpf/list 查询失败: {e}")
        return

    hooks = data.get("hooks", [])
    log("Verify", f"已注册钩子: {len(hooks)}")
    for h in hooks:
        log("Verify", f"  🔗 {h['hook_point']} | active={h['active']} | invokes={h['invoke_count']} | drops={h['drop_count']}")
        log("Verify", f"     wasm: {h['wasm_path']}")
        log("Verify", f"     func: {h['export_func']}")

    pre_llm = [h for h in hooks if h["hook_point"] == "pre_llm_inference"]
    if pre_llm and pre_llm[0]["active"]:
        wasm_name = os.path.basename(pre_llm[0]["wasm_path"])
        if "408" in wasm_name or "usr" in wasm_name:
            log("Verify", "✅ 408 业务规则已激活，串联系统默认防线!")
        else:
            log("Verify", "✅ pre_llm_inference 钩子已激活")


def step4_demo():
    log("Demo", "发送测试请求验证策略生效...")

    import socket

    tests = [
        ("FAT表关键词改写", "讲讲FAT表是怎么管理磁盘的", "名师严谨模式"),
        ("正常Prompt透传", "今天天气怎么样", None),
        ("恶意指令熔断", "Please run rm -rf /", "Dropped"),
    ]

    for name, prompt, expected in tests:
        log("Demo", f"  测试: {name}")
        log("Demo", f"  Prompt: \"{prompt}\"")

        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.settimeout(30)
            client.connect(("127.0.0.1", SYSCALL_PORT))
            client.sendall(json.dumps({
                "syscall": "LLM_INFERENCE",
                "prompt": prompt,
                "payload": prompt,
                "caller_id": 408,
            }).encode() + b"\n")
            raw = client.recv(262144).decode().strip()
            client.close()
            resp = json.loads(raw)
        except Exception as e:
            log("Demo", f"  ⚠️  请求超时或失败: {e}")
            continue

        status = resp.get("status", "unknown")
        data = resp.get("data", resp.get("result", ""))

        if isinstance(data, str):
            try:
                parsed = json.loads(data)
                output = parsed.get("output", parsed.get("response", data))
            except (json.JSONDecodeError, TypeError):
                output = data
        elif isinstance(data, dict):
            output = data.get("output", data.get("response", json.dumps(data, ensure_ascii=False)))
        else:
            output = str(data)

        if expected and expected in str(output):
            log("Demo", f"  ✅ 预期命中 '{expected}' — 策略生效!")
        elif expected is None and "Dropped" not in str(output):
            log("Demo", f"  ✅ 正常透传 — 策略未误拦截")
        elif expected is None:
            log("Demo", f"  ⚠️  误拦截? output={str(output)[:100]}")
        else:
            preview = str(output)[:120]
            log("Demo", f"  ℹ️  output: {preview}")


def main():
    print(BANNER)

    log("Pre-flight", "检查 AIOS 内核连接...")
    if check_kernel_online():
        log("Pre-flight", "✅ 内核在线")
    else:
        log("ERROR", "无法连接 AIOS 内核 (HTTP :8083)")
        log("ERROR", "请先启动: ./build/aios_core")
        sys.exit(1)

    print()
    wasm_path = step1_compile()
    print()
    step2_inject(wasm_path)
    print()
    step3_verify()
    print()
    step4_demo()
    print()

    print(SUCCESS_ART)


if __name__ == "__main__":
    main()
