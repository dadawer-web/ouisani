#!/usr/bin/env python3
"""AIOS Lip Sync End-to-End Test

Validates the full TTS + Viseme + AudioFS pipeline:
  1. Compile C code that calls aios_speak()
  2. Execute WASM in sandbox -> triggers stream_tts -> writes to /dev/audio
  3. Verify PCM data and viseme frames were written to AudioNode ring buffers
  4. Verify /audio/stream and /audio/visemes HTTP endpoints are reachable

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

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
USR_INCLUDE = os.path.join(PROJECT_ROOT, "usr_include")

WASI_SDK_PATH = "/opt/wasi-sdk"
WASI_SYSROOT = f"{WASI_SDK_PATH}/share/wasi-sysroot"

SYSCALL_PORT = 8080
HTTP_PORT = 8083
AGENT_ID = 101

C_CODE = r"""
#include <stdio.h>
#include "aios.h"

int main() {
    printf("[User-Space] Starting lip sync test...\n");

    aios_speak("Hello, AIOS system is now running with lip sync");

    printf("[User-Space] aios_speak returned\n");
    return 0;
}
"""


def send_payload(payload: dict, port: int = SYSCALL_PORT, timeout: float = 180):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(("127.0.0.1", port))
    client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


def step1_compile_wasm():
    wasm_dir = "/tmp/aios_tasks"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, "test_lip_sync.wasm")
    c_file = os.path.join(wasm_dir, "test_lip_sync.c")

    with open(c_file, "w") as f:
        f.write(C_CODE)

    clang = os.path.join(WASI_SDK_PATH, "bin", "clang")
    if not os.path.exists(clang):
        clang = "clang"

    cmd = [
        clang,
        "--target=wasm32-wasi",
        f"--sysroot={WASI_SYSROOT}",
        f"-I{USR_INCLUDE}",
        "-O3",
        "-Wl,--allow-undefined",
        "-o", wasm_path,
        c_file,
    ]

    print(f"\n[Step 1] 编译 C 代码 -> WASM")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  [FAILED]\n{result.stderr}")
        sys.exit(1)

    size = os.path.getsize(wasm_path)
    print(f"  [OK] {wasm_path} ({size:,} bytes)")
    return wasm_path


def step2_execute_wasm(wasm_path: str):
    print(f"\n[Step 2] 提交 WASM 至内核执行 (aios_speak)")

    req = {
        "syscall": "VFS_CALL",
        "action": "EXECUTE_MODULE",
        "path": wasm_path,
        "payload": json.dumps({"path": wasm_path, "func": "_start"}),
        "caller_id": AGENT_ID,
    }

    try:
        resp = send_payload(req, timeout=180)
    except Exception as e:
        print(f"  [FAIL] 执行请求失败: {e}")
        sys.exit(1)

    print(f"  [内核响应] status={resp.get('status', 'unknown')}")

    stdout_text = ""
    if resp.get("status") == "ok" and "data" in resp:
        data = resp["data"]
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                pass

        if isinstance(data, dict):
            stdout_text = data.get("stdout", "")
            output_raw = data.get("output", "")

            if output_raw:
                try:
                    out = json.loads(output_raw)
                    print(f"  [执行状态] {out.get('status', '')}")
                    print(f"  [退出码]   {out.get('exit_code', 'N/A')}")
                except json.JSONDecodeError:
                    pass

            if stdout_text:
                print(f"\n  ---- WASM stdout ----")
                for line in stdout_text.strip().split("\n"):
                    print(f"  {line}")
                print(f"  ---------------------")
    else:
        msg = resp.get("message", str(resp))
        print(f"  [执行失败] {msg}")

    return stdout_text


def step3_verify_audio_buffers():
    print(f"\n[Step 3] 验证 AudioNode 缓冲区")

    resp = send_payload({
        "syscall": "VFS_CALL",
        "action": "AUDIO_STATUS",
        "path": "/dev/audio/pcm",
        "caller_id": 0,
    }, timeout=10)

    audio = resp.get("audio", {})
    pcm = audio.get("pcm", {})
    vis = audio.get("visemes", {})

    pcm_written = pcm.get("total_written", 0)
    vis_written = vis.get("total_written", 0)

    print(f"  PCM:     written={pcm_written} bytes | available={pcm.get('available', 0)}")
    print(f"  Visemes: written={vis_written} bytes | available={vis.get('available', 0)}")

    return pcm_written > 0 and vis_written > 0


def step4_verify_http_endpoints():
    print(f"\n[Step 4] 验证 HTTP 流式接口")

    checks = {}

    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{HTTP_PORT}/audio/stream",
            headers={"Range": "bytes=0-99"}
        )
        resp = urllib.request.urlopen(req, timeout=3)
        content_type = resp.headers.get("Content-Type", "")
        data = resp.read(100)
        checks["pcm_stream"] = len(data) > 0 or content_type == "application/octet-stream"
        print(f"  /audio/stream:  Content-Type={content_type} | data={len(data)} bytes")
    except urllib.error.URLError as e:
        checks["pcm_stream"] = False
        print(f"  /audio/stream:  FAILED ({e.reason})")
    except Exception as e:
        checks["pcm_stream"] = False
        print(f"  /audio/stream:  FAILED ({e})")

    try:
        req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/audio/visemes")
        resp = urllib.request.urlopen(req, timeout=3)
        content_type = resp.headers.get("Content-Type", "")
        data = resp.read(500).decode("utf-8", errors="replace")
        has_sse = "data:" in data or content_type == "text/event-stream"
        checks["viseme_sse"] = has_sse
        print(f"  /audio/visemes: Content-Type={content_type} | SSE={'yes' if has_sse else 'no'}")
        if data:
            preview = data[:200].replace("\n", "\\n")
            print(f"    Preview: {preview}")
    except urllib.error.URLError as e:
        checks["viseme_sse"] = False
        print(f"  /audio/visemes: FAILED ({e.reason})")
    except Exception as e:
        checks["viseme_sse"] = False
        print(f"  /audio/visemes: FAILED ({e})")

    return all(checks.values())


def step5_verify_stdout(stdout_text: str):
    print(f"\n[Step 5] 验证 WASM 输出")

    checks = [
        ("WASM 启动", "[User-Space] Starting lip sync" in stdout_text),
        ("aios_speak 返回", "[User-Space] aios_speak returned" in stdout_text),
    ]

    all_pass = True
    for label, ok in checks:
        tag = "\u2713" if ok else "\u2717"
        print(f"  {tag} {label}")
        if not ok:
            all_pass = False

    return all_pass


def main():
    print("=" * 60)
    print("  AIOS Lip Sync 端到端测试")
    print("  验证: aios_speak -> stream_tts -> AudioNode -> HTTP SSE")
    print("=" * 60)

    print("\n[Pre-check] 检查内核连接")
    try:
        probe = send_payload({
            "syscall": "VFS_CALL",
            "action": "READ",
            "path": "/proc/version",
        }, timeout=5)
        print(f"  [内核] status={probe.get('status', 'unknown')}")
    except Exception as e:
        print(f"  \u2717 无法连接内核 ({e})")
        print("  请先启动 aios_core:  ./build/aios_core")
        sys.exit(1)

    wasm_path = step1_compile_wasm()
    stdout_text = step2_execute_wasm(wasm_path)
    audio_ok = step3_verify_audio_buffers()
    http_ok = step4_verify_http_endpoints()
    stdout_ok = step5_verify_stdout(stdout_text)

    print(f"\n{'=' * 60}")
    print(f"  结果:")
    print(f"    AudioNode 缓冲区: {'PASS' if audio_ok else 'FAIL'}")
    print(f"    HTTP 流式接口:    {'PASS' if http_ok else 'FAIL'}")
    print(f"    WASM 输出验证:    {'PASS' if stdout_ok else 'FAIL'}")
    all_pass = audio_ok and http_ok and stdout_ok
    print(f"  测试{'通过' if all_pass else '失败'}")
    print(f"{'=' * 60}")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
