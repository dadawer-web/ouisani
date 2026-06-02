#!/usr/bin/env python3
"""AIOS Semantic Page Fault End-to-End Test

Validates the full Semantic Page Fault mechanism:
  1. Pre-load obscure knowledge into /dev/vec_mem_101 (VectorDB)
  2. Compile a C program that asks about that knowledge via aios_think()
  3. Execute the WASM and observe the kernel transparently:
     a. LLM triggers <PAGE_FAULT: [keywords]>
     b. TaskScheduler catches the exception, searches VectorDB
     c. Knowledge injected, LLM retries with context
     d. C code prints the correct answer containing the obscure facts

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
AGENT_ID = 101

KNOWLEDGE_ENTRIES = [
    "2026年开发的农场AI裁判服务的核心模块是基于Spring WebFlux和Redis Streams实现的高并发评判引擎，"
    "该引擎使用响应式编程模型处理每秒超过10万次的赛事评判请求，并通过Redis Streams实现分布式事件溯源。",

    "农场AI裁判服务的架构分为三层：接入层使用Netty实现WebSocket长连接，"
    "计算层使用Spring WebFlux的Flux流式处理评判逻辑，持久层通过Redis Streams保证事件不丢失。",

    "该评判引擎的容错机制依赖Redis Streams的Consumer Group实现多实例消费，"
    "当某个裁判节点宕机时，未确认的消息会被其他节点自动接管，确保评判结果不丢失。",
]

C_CODE = r"""
#include <stdio.h>
#include "aios.h"

int main() {
    printf("[User-Space] 正在查询高并发架构方案...\n");

    char* response = aios_think("请告诉我农场AI裁判服务是怎么实现高并发的？");

    printf("[User-Space] AIOS 返回最终答案: %s\n", response ? response : "(null)");

    free(response);
    return 0;
}
"""


def send_payload(payload: dict, port: int = SYSCALL_PORT, timeout: float = 120):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(("127.0.0.1", port))
    client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


def step1_write_knowledge():
    print("\n[Step 1] 向 /dev/vec_mem_101 写入冷门知识")
    for i, entry in enumerate(KNOWLEDGE_ENTRIES):
        resp = send_payload({
            "syscall": "VFS_CALL",
            "action": "WRITE",
            "path": "/dev/vec_mem_101",
            "payload": entry,
            "caller_id": AGENT_ID,
        }, timeout=30)
        ok = resp.get("status") == "ok"
        print(f"  [{i+1}/{len(KNOWLEDGE_ENTRIES)}] {'OK' if ok else 'FAIL'}: {entry[:60]}...")
        if not ok:
            print(f"    Error: {resp.get('message', resp)}")

    print("\n  验证知识已写入 (SEARCH)...")
    resp = send_payload({
        "syscall": "VFS_CALL",
        "action": "SEARCH",
        "path": "/dev/vec_mem_101",
        "payload": "农场AI裁判服务高并发",
        "caller_id": AGENT_ID,
    }, timeout=30)
    if resp.get("status") == "ok":
        data = resp.get("data", "")
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                pass
        results = data if isinstance(data, list) else []
        print(f"  SEARCH 返回 {len(results)} 条结果")
        for r in results[:2]:
            text = r.get("text", "")[:80] if isinstance(r, dict) else str(r)[:80]
            print(f"    - {text}...")
    else:
        print(f"  SEARCH 失败: {resp.get('message', resp)}")


def step2_compile_wasm():
    wasm_dir = "/tmp/aios_tasks"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, "test_page_fault.wasm")
    c_file = os.path.join(wasm_dir, "test_page_fault.c")

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

    print(f"\n[Step 2] 编译 C 代码 -> WASM")
    print(f"  {clang} --target=wasm32-wasi -I{USR_INCLUDE}")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  [FAILED]\n{result.stderr}")
        sys.exit(1)

    size = os.path.getsize(wasm_path)
    print(f"  [OK] {wasm_path} ({size:,} bytes)")
    return wasm_path


def step3_execute_wasm(wasm_path: str):
    print(f"\n[Step 3] 提交 WASM 至内核执行 (EXECUTE_MODULE)")
    print(f"  [WASM] {wasm_path}")

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
                    gas = out.get("gas_used", "")
                    if gas:
                        print(f"  [Gas]      {gas:,}")
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


def step4_verify(stdout_text: str):
    print(f"\n[Step 4] 验证结果")

    checks = [
        ("WASM 程序启动", "[User-Space] 正在查询" in stdout_text),
        ("AIOS 返回最终答案", "[User-Space] AIOS 返回最终答案" in stdout_text),
        ("答案包含 Spring WebFlux", "Spring WebFlux" in stdout_text or "WebFlux" in stdout_text),
        ("答案包含 Redis Streams", "Redis Streams" in stdout_text or "Redis" in stdout_text),
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
    print("  AIOS 语义缺页中断 (Semantic Page Fault) 端到端测试")
    print("  验证: LLM 缺页 -> VectorDB 检索 -> 换页重试 -> 正确答案")
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

    step1_write_knowledge()
    wasm_path = step2_compile_wasm()
    stdout_text = step3_execute_wasm(wasm_path)
    all_pass = step4_verify(stdout_text)

    print(f"\n{'=' * 60}")
    print(f"  测试{'通过' if all_pass else '失败'}")
    print(f"{'=' * 60}")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
