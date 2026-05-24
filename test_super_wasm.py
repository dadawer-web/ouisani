import socket
import json
import time
import subprocess
import os

def send_vfs_compile(agent_id, c_code):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    req = {
        "syscall": "VFS_CALL",
        "agent_id": agent_id,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code, "func": "_start"})
    }
    start = time.perf_counter()
    client.send((json.dumps(req) + '\n').encode('utf-8'))
    resp = client.recv(16384).decode('utf-8')
    elapsed = (time.perf_counter() - start) * 1000
    client.close()
    return resp, elapsed

code_host_func = r"""
#include <stdio.h>
#include <string.h>

__attribute__((import_module("aios"), import_name("kprint")))
void aios_kprint(const char* ptr, int len);

int main() {
    printf("Sandbox: preparing data...\n");

    const char* secret = "Hello AIOS Kernel! I am an Agent inside the sandbox.";

    aios_kprint(secret, strlen(secret));

    return 0;
}
"""

code_malicious = r"""
#include <stdio.h>
int main() {
    printf("Sandbox: launching infinite loop attack!\n");
    int i = 0;
    while (1) {
        i++;
    }
    return 0;
}
"""

if __name__ == "__main__":
    print("=" * 70)
    print("  AIOS Super WASM Test")
    print("  A: Host Function Reverse Syscall (aios.kprint)")
    print("  B: Gas Limit Defense (Malicious Infinite Loop)")
    print("=" * 70)

    print("\n=== 测试 A: Host Function 反向系统调用 ===")
    print("  C代码中声明了 __attribute__((import_module(\"aios\"), import_name(\"kprint\")))")
    print("  这将让 Wasm 沙盒内的代码反向呼叫 C++ 内核的 aios_host_kprint 函数\n")

    resp_a, time_a = send_vfs_compile(201, code_host_func)
    print(f"  [返回] {resp_a[:500]}")
    print(f"  [耗时] {time_a:.1f} ms")

    try:
        outer = json.loads(resp_a)
        if outer.get("status") == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "{}")
            output = json.loads(output_str) if isinstance(output_str, str) else output_str
            if output.get("status") == "ok":
                print(f"\n  ✅ Host Function 反向系统调用成功!")
                print(f"     WASM 沙盒成功调用了 C++ 内核的 aios.kprint!")
            else:
                print(f"\n  ⚠️ WASM 执行返回: {output.get('reason', 'unknown')}")
    except Exception as e:
        print(f"  ⚠️ 解析异常: {e}")

    print("\n" + "-" * 70)

    print("\n=== 测试 B: Gas Limit 防御机制 (恶意死循环) ===")
    print("  while(1) 死循环代码将消耗 CPU 周期")
    print("  Gas Limit 设置为 10,000,000 条指令，超限将被内核强杀\n")

    resp_b, time_b = send_vfs_compile(202, code_malicious)
    print(f"  [返回] {resp_b[:500]}")
    print(f"  [耗时] {time_b:.1f} ms")

    try:
        outer = json.loads(resp_b)
        if outer.get("status") == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "{}")
            output = json.loads(output_str) if isinstance(output_str, str) else output_str
            if output.get("status") == "error":
                reason = output.get("reason", "")
                if "Gas" in reason or "trapped" in reason or "Exceeded" in reason:
                    print(f"\n  ✅ Gas Limit 防御成功! 恶意死循环已被内核强杀!")
                    print(f"     原因: {reason}")
                else:
                    print(f"\n  ⚠️ 执行失败: {reason}")
            else:
                print(f"\n  ❌ 意外: 死循环居然执行成功了?!")
    except Exception as e:
        print(f"  ⚠️ 解析异常: {e}")

    print("\n" + "=" * 70)
    print("  测试完成")
    print("=" * 70)
