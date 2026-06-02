#!/usr/bin/env python3
"""AIOS WASM Syscall End-to-End Test

Validates the AIOS Standard C Library SDK (usr_include/aios.h) by:
  1. Writing C code that calls aios_vfs_read() / aios_think() / aios_log()
  2. Compiling it to .wasm with wasi-sdk clang + -I./usr_include
  3. Submitting the .wasm to the kernel via EXECUTE_MODULE
  4. Verifying that VFS read and LLM inference syscalls fire from pure C

Prerequisite: aios_core must be running on 127.0.0.1:8080.
"""

import json
import os
import socket
import subprocess
import sys
import time

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
USR_INCLUDE = os.path.join(PROJECT_ROOT, "usr_include")

WASI_SDK_PATH = "/opt/wasi-sdk"
WASI_SYSROOT = f"{WASI_SDK_PATH}/share/wasi-sysroot"

SYSCALL_PORT = 8080
AGENT_ID = 42

C_CODE = r"""
#include <stdio.h>
#include "aios.h"

int main() {
    printf("[WASM] Waking up...\n");

    char* camera_data = aios_vfs_read("/dev/camera0");
    if (camera_data) {
        printf("[WASM] Got data: %s\n", camera_data);
    } else {
        printf("[WASM] aios_vfs_read returned NULL\n");
    }

    char prompt[512];
    snprintf(prompt, sizeof(prompt),
             "分析这段数据是否有异常: %s",
             camera_data ? camera_data : "(no data)");

    char* llm_analysis = aios_think(prompt);
    if (llm_analysis) {
        printf("[WASM] LLM Analysis: %s\n", llm_analysis);
    } else {
        printf("[WASM] aios_think returned NULL\n");
    }

    aios_log("[WASM] Task completed successfully!");

    free(camera_data);
    free(llm_analysis);

    return 0;
}
"""


def compile_c_to_wasm(c_code: str, wasm_path: str) -> bool:
    c_file = wasm_path.replace(".wasm", ".c")
    with open(c_file, "w") as f:
        f.write(c_code)

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

    print(f"  [Compile] {clang} --target=wasm32-wasi -I{USR_INCLUDE}")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  [Compile FAILED]\n{result.stderr}")
        return False

    if not os.path.exists(wasm_path):
        print("  [Compile FAILED] .wasm not produced")
        return False

    size = os.path.getsize(wasm_path)
    print(f"  [Compile OK] {wasm_path}  ({size:,} bytes)")
    return True


def verify_wasm_imports(wasm_path: str) -> bool:
    raw = open(wasm_path, "rb").read()
    text = raw.decode("latin-1")
    required = ["aios", "__aios_vfs_read", "__aios_think", "kprint"]
    found_all = True
    for sym in required:
        if sym in text:
            print(f"  [Import] \u2713 {sym}")
        else:
            print(f"  [Import] \u2717 {sym} NOT FOUND")
            found_all = False
    return found_all


def send_payload(payload: dict, port: int = SYSCALL_PORT, timeout: float = 120):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(("127.0.0.1", port))
    client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


def main():
    print("=" * 60)
    print("  AIOS WASM Syscall 端到端测试")
    print("  验证 aios.h SDK -> WASM 编译 -> 内核宿主函数调用链路")
    print("=" * 60)

    wasm_dir = "/tmp/aios_tasks"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, f"test_syscall_agent_{AGENT_ID}.wasm")

    # -- Step 1: Compile --
    print("\n[Step 1] 编译 C 代码 (含 aios.h SDK) -> WASM")
    if not compile_c_to_wasm(C_CODE, wasm_path):
        print("\n!!! 编译失败，测试中止 !!!")
        sys.exit(1)

    # -- Step 2: Verify WASM imports --
    print("\n[Step 2] 验证 WASM 导入表")
    if not verify_wasm_imports(wasm_path):
        print("\n!!! WASM 导入表缺失必要符号，测试中止 !!!")
        sys.exit(1)

    # -- Step 3: Check kernel connectivity --
    print("\n[Step 3] 检查内核连接")
    try:
        probe = send_payload({
            "syscall": "VFS_CALL",
            "action": "READ",
            "path": "/proc/version",
        }, timeout=5)
        print(f"  [内核] status={probe.get('status', 'unknown')}")
        if probe.get("status") != "ok":
            print("  \u26a0 内核响应异常，继续尝试...")
    except Exception as e:
        print(f"  \u2717 无法连接内核 ({e})")
        print("  请先启动 aios_core:  ./build/aios_core")
        sys.exit(1)

    # -- Step 4: Submit WASM for execution --
    print("\n[Step 4] 提交 WASM 至内核执行 (EXECUTE_MODULE)")
    print(f"  [WASM] {wasm_path}")

    req = {
        "syscall": "VFS_CALL",
        "action": "EXECUTE_MODULE",
        "path": wasm_path,
        "payload": json.dumps({"path": wasm_path, "func": "_start"}),
        "caller_id": AGENT_ID,
    }

    try:
        resp = send_payload(req, timeout=120)
    except Exception as e:
        print(f"  \u2717 执行请求失败: {e}")
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
                    exec_status = out.get("status", "")
                    gas_used = out.get("gas_used", "")
                    exit_code = out.get("exit_code", "N/A")
                    print(f"  [执行状态] {exec_status}")
                    print(f"  [退出码]   {exit_code}")
                    if gas_used:
                        print(f"  [Gas]      {gas_used:,}")
                except json.JSONDecodeError:
                    print(f"  [输出] {output_raw[:300]}")

            if stdout_text:
                print(f"\n  ---- WASM stdout ----")
                for line in stdout_text.strip().split("\n"):
                    print(f"  {line}")
                print(f"  ---------------------")
    else:
        msg = resp.get("message", str(resp))
        print(f"  [执行失败] {msg}")

    # -- Step 5: Verify results --
    print("\n[Step 5] 验证结果")

    checks = [
        ("WASM 程序启动", "[WASM] Waking up" in stdout_text),
        ("aios_vfs_read() 调用", "Got data" in stdout_text or "aios_vfs_read" in stdout_text),
        ("aios_think() 调用", "LLM Analysis" in stdout_text or "aios_think" in stdout_text),
        ("内核执行成功", resp.get("status") == "ok"),
    ]

    all_pass = True
    for label, ok in checks:
        tag = "\u2713" if ok else "\u2717"
        print(f"  {tag} {label}")
        if not ok:
            all_pass = False

    print(f"\n{'=' * 60}")
    print(f"  测试{'通过' if all_pass else '失败'}")
    print(f"{'=' * 60}")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
