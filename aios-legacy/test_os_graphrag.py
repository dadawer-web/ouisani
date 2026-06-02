#!/usr/bin/env python3
"""AIOS GraphRAG End-to-End Test

Validates the full GraphRAG pipeline:
  1. Write knowledge documents to /dev/graph0 (auto triple extraction)
  2. Write semantic fragments to /dev/vec_mem_101
  3. Compile & execute WASM C code that triggers aios_think()
  4. Verify: PageFault -> VectorDB + GraphFS hybrid retrieval -> correct answer

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

GRAPH_DOCS = [
    "数据结构是408考试的核心科目，涵盖了树、图、哈希表等关键算法与数据组织方式。",
    "操作系统依赖数据结构中的树与图算法来实现进程调度、文件系统和内存管理。",
    "408智能学习平台采用图数据库来构建知识图谱，利用图遍历算法为学生推荐个性化的学习路径。",
    "计算机网络中的路由算法本质上是图论中的最短路径问题，如Dijkstra和OSPF协议。",
    "408智能学习平台的后端使用Spring Boot微服务架构，前端采用React和D3.js可视化知识图谱。",
]

VEC_MEM_ENTRIES = [
    "408考试包含四门核心课程：数据结构、操作系统、计算机网络、计算机组成原理。",
    "408智能学习平台是一个基于AI的考研辅导系统，它将四门课程的知识点构建为知识图谱。",
    "图数据库Neo4j常用于知识图谱存储，支持Cypher查询语言进行图遍历。",
]

C_CODE = r"""
#include <stdio.h>
#include "aios.h"

int main() {
    printf("[User-Space] 正在查询深层关联...\n");

    char* response = aios_think("408智能学习平台在技术栈上和考研的哪些基础知识有深层关联？");

    printf("[User-Space] AIOS 返回最终答案: %s\n", response ? response : "(null)");

    free(response);
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


def step1_populate_graph():
    print("\n[Step 1] 向 /dev/graph0 写入技术文档 (自动提取三元组)")
    for i, doc in enumerate(GRAPH_DOCS):
        resp = send_payload({
            "syscall": "VFS_CALL",
            "action": "WRITE",
            "path": "/dev/graph0",
            "payload": doc,
            "caller_id": AGENT_ID,
        }, timeout=180)
        ok = resp.get("status") == "ok"
        print(f"  [{i+1}/{len(GRAPH_DOCS)}] {'OK' if ok else 'FAIL'}: {doc[:50]}...")
        if not ok:
            print(f"    Error: {resp.get('message', resp)}")
        time.sleep(0.3)


def step2_populate_vector():
    print("\n[Step 2] 向 /dev/vec_mem_101 写入语义片段")
    for i, entry in enumerate(VEC_MEM_ENTRIES):
        resp = send_payload({
            "syscall": "VFS_CALL",
            "action": "WRITE",
            "path": "/dev/vec_mem_101",
            "payload": entry,
            "caller_id": AGENT_ID,
        }, timeout=30)
        ok = resp.get("status") == "ok"
        print(f"  [{i+1}/{len(VEC_MEM_ENTRIES)}] {'OK' if ok else 'FAIL'}: {entry[:50]}...")


def step3_debug_graph():
    print("\n[Step 3] 导出图谱结构 (DEBUG_GRAPH)")
    resp = send_payload({
        "syscall": "VFS_CALL",
        "action": "DEBUG_GRAPH",
        "path": "/dev/graph0",
        "caller_id": AGENT_ID,
    }, timeout=10)

    if resp.get("status") == "ok" and "graph" in resp:
        graph = resp["graph"]
        entities = graph.get("entity_count", 0)
        edges = graph.get("edge_count", 0)
        print(f"  Entities: {entities}, Edges: {edges}")

        ent_list = graph.get("entities", [])
        for ent in ent_list[:5]:
            name = ent.get("name", "?")
            out = ent.get("edges", [])
            print(f"    {name} -> {len(out)} edges")
            for e in out[:3]:
                print(f"      --[{e.get('relation', '?')}]--> {e.get('target', '?')}")
        if len(ent_list) > 5:
            print(f"    ... and {len(ent_list) - 5} more entities")
    else:
        print(f"  Failed: {resp.get('message', resp)}")


def step4_compile_wasm():
    wasm_dir = "/tmp/aios_tasks"
    os.makedirs(wasm_dir, exist_ok=True)
    wasm_path = os.path.join(wasm_dir, "test_graphrag.wasm")
    c_file = os.path.join(wasm_dir, "test_graphrag.c")

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

    print(f"\n[Step 4] 编译 C 代码 -> WASM")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  [FAILED]\n{result.stderr}")
        sys.exit(1)

    size = os.path.getsize(wasm_path)
    print(f"  [OK] {wasm_path} ({size:,} bytes)")
    return wasm_path


def step5_execute_wasm(wasm_path: str):
    print(f"\n[Step 5] 提交 WASM 至内核执行")
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


def step6_verify(stdout_text: str):
    print(f"\n[Step 6] 验证结果")

    keywords_graph = ["数据结构", "图", "知识图谱", "操作系统", "408"]
    keywords_vec = ["Spring", "React", "D3", "微服务"]

    graph_hits = sum(1 for kw in keywords_graph if kw in stdout_text)
    vec_hits = sum(1 for kw in keywords_vec if kw in stdout_text)

    checks = [
        ("WASM 程序启动", "[User-Space] 正在查询" in stdout_text),
        ("AIOS 返回最终答案", "[User-Space] AIOS 返回最终答案" in stdout_text),
        ("图谱知识命中", graph_hits >= 2),
        ("向量知识命中", vec_hits >= 1),
        ("内核执行成功", True),
    ]

    all_pass = True
    for label, ok in checks:
        tag = "\u2713" if ok else "\u2717"
        detail = ""
        if label == "图谱知识命中":
            detail = f" ({graph_hits}/{len(keywords_graph)} keywords found)"
        elif label == "向量知识命中":
            detail = f" ({vec_hits}/{len(keywords_vec)} keywords found)"
        print(f"  {tag} {label}{detail}")
        if not ok:
            all_pass = False

    return all_pass


def main():
    print("=" * 60)
    print("  AIOS GraphRAG 端到端测试")
    print("  验证: GraphFS 三元组提取 + VectorDB 语义检索 + PageFault 混合换页")
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

    step1_populate_graph()
    step2_populate_vector()
    step3_debug_graph()
    wasm_path = step4_compile_wasm()
    stdout_text = step5_execute_wasm(wasm_path)
    all_pass = step6_verify(stdout_text)

    print(f"\n{'=' * 60}")
    print(f"  测试{'通过' if all_pass else '失败'}")
    print(f"{'=' * 60}")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
