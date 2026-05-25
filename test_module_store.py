import socket
import json
import time
import os


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(60)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res


def test_compile_only():
    print("=" * 60)
    print("  📦 测试 COMPILE_ONLY - 编译并持久化到指定 path")
    print("=" * 60)

    code = r'''
#include <stdio.h>
#include <string.h>

int main() {
    char data[][32] = {"APPLE:150.2", "TESLA:210.5", "NVIDIA:890.1", "BTC:68000.0"};
    printf("=== Asset Analysis Module ===\n");
    for (int i = 0; i < 4; i++) {
        if (strstr(data[i], "BTC")) {
            printf("[ALERT] Crypto: %s - High risk!\n", data[i]);
        } else {
            printf("[OK] Stock: %s\n", data[i]);
        }
    }
    printf("=============================\n");
    return 0;
}
    '''

    wasm_path = "/tmp/aios_workspace/modules/101/sort_tool.wasm"

    req = {
        "syscall": "VFS_CALL",
        "action": "COMPILE_ONLY",
        "agent_id": 101,
        "path": wasm_path,
        "payload": json.dumps({
            "code": code
        })
    }

    print(f"\n[步骤1] 提交 COMPILE_ONLY 请求 (path={wasm_path})...")
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    print(f"[步骤1] 结果: status={parsed.get('status')}, message={parsed.get('message', '')}")

    data = parsed.get("data", {})
    if isinstance(data, str):
        data = json.loads(data)

    if data.get("success"):
        saved_path = data.get('wasm_path', '')
        print(f"  ✅ 模块已保存: {saved_path}")
        print(f"  Agent: {data.get('agent_id')}")
        if os.path.exists(saved_path):
            size = os.path.getsize(saved_path)
            print(f"  文件大小: {size} bytes")
        return True
    else:
        print(f"  ❌ 编译失败: {data.get('error', '')}")
        return False


def test_execute_module():
    print("\n" + "=" * 60)
    print("  🚀 测试 EXECUTE_MODULE - 直接执行 WASM (跳过编译)")
    print("=" * 60)

    wasm_path = "/tmp/aios_workspace/modules/101/sort_tool.wasm"

    req = {
        "syscall": "VFS_CALL",
        "action": "EXECUTE_MODULE",
        "agent_id": 101,
        "path": wasm_path,
        "payload": json.dumps({
            "func": "_start"
        })
    }

    print(f"\n[步骤2] 首次执行模块 (Cache MISS, path={wasm_path})...")
    start = time.perf_counter()
    raw = send_payload(json.dumps(req))
    cost1 = time.perf_counter() - start
    parsed = json.loads(raw)
    print(f"  耗时: {cost1*1000:.1f}ms, status={parsed.get('status')}")

    data = parsed.get("data", {})
    if isinstance(data, str):
        data = json.loads(data)

    cache_hit = data.get("cache_hit", False)
    print(f"  Cache HIT: {cache_hit}")

    print(f"\n[步骤3] 再次执行同一模块 (应该 Cache HIT)...")
    start = time.perf_counter()
    raw = send_payload(json.dumps(req))
    cost2 = time.perf_counter() - start
    parsed = json.loads(raw)
    print(f"  耗时: {cost2*1000:.1f}ms, status={parsed.get('status')}")

    data = parsed.get("data", {})
    if isinstance(data, str):
        data = json.loads(data)

    cache_hit2 = data.get("cache_hit", False)
    print(f"  Cache HIT: {cache_hit2}")

    if cache_hit2:
        speedup = ((cost1 - cost2) / cost1 * 100) if cost1 > 0 else 0
        print(f"  ✅ LRU Cache 命中！加速: {speedup:.1f}%")
    else:
        print(f"  ⚠️ Cache 未命中")

    output_str = data.get("output", "")
    if output_str:
        try:
            out = json.loads(output_str)
            print(f"\n  执行状态: {out.get('status')}")
        except:
            print(f"\n  输出: {output_str[:200]}")


def test_execute_nonexistent():
    print("\n" + "=" * 60)
    print("  ❌ 测试 EXECUTE_MODULE - 执行不存在的 WASM")
    print("=" * 60)

    req = {
        "syscall": "VFS_CALL",
        "action": "EXECUTE_MODULE",
        "agent_id": 101,
        "path": "/tmp/aios_workspace/modules/nonexistent.wasm",
        "payload": json.dumps({"func": "_start"})
    }

    print("\n[测试] 尝试执行不存在的 WASM 文件...")
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    print(f"  结果: status={parsed.get('status')}, message={parsed.get('message', '')}")

    if parsed.get("status") == "error":
        print("  ✅ 内核正确返回了错误信息！")
    else:
        print("  ⚠️ 应该返回错误但未返回")


if __name__ == "__main__":
    compile_ok = test_compile_only()
    if compile_ok:
        test_execute_module()
    test_execute_nonexistent()
