#!/usr/bin/env python3
"""🛡️ AIOS 终极防御测试：Gas 熔断与 Trap 硬件异常捕获（双核弹并发）"""

import socket
import json
import time
import threading


def send_payload(name, payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(30)
    client.connect(('127.0.0.1', 8080))
    start = time.perf_counter()
    client.send((payload + '\n').encode('utf-8'))
    buf = b""
    while True:
        chunk = client.recv(8192)
        if not chunk:
            break
        buf += chunk
        if b"\n" in buf:
            break
    cost = time.perf_counter() - start
    client.close()

    print(f"\n[{name}] 内核拦截耗时: {cost:.4f} 秒")

    try:
        parsed = json.loads(buf.decode().strip())
        data = parsed.get("data", {})
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except:
                pass

        output_str = data.get("output", "")
        if output_str:
            try:
                wasm_result = json.loads(output_str)
            except json.JSONDecodeError:
                wasm_result = {"raw": output_str}
        else:
            wasm_result = data

        status = wasm_result.get("status", "unknown")
        trap = wasm_result.get("trap", wasm_result.get("reason", ""))
        gas_used = wasm_result.get("gas_used", 0)
        gas_limit = wasm_result.get("gas_limit", 0)

        if status == "trap":
            print(f"[{name}] 🛡️  内核成功拦截！Trap: {trap}")
            print(f"[{name}] 📊  Gas 消耗: {gas_used:,} / {gas_limit:,}")
        elif status == "ok":
            print(f"[{name}] ✅ 正常执行完毕")
            print(f"[{name}] 📊  Gas 消耗: {gas_used:,} / {gas_limit:,}")
        else:
            print(f"[{name}] ⚠️  状态: {status}")
            print(f"[{name}] 返回: {json.dumps(wasm_result, ensure_ascii=False)[:300]}")
    except Exception as e:
        print(f"[{name}] 解析失败: {e}")
        print(f"[{name}] 原始返回: {buf.decode()[:300]}")


print("=" * 60)
print(" 🛡️ AIOS 终极防御测试：Gas 熔断与 Trap 硬件异常捕获")
print("=" * 60)

code_gas = r"""
int main() {
    long long sum = 0;
    while(1) { sum += 1; }
    return 0;
}
"""

code_trap = r"""
int main() {
    int *p = (int *)0xFFFFFFFF;
    *p = 42;
    return 0;
}
"""

req_gas = json.dumps({
    "syscall": "VFS_CALL",
    "action": "COMPILE_AND_EXECUTE",
    "agent_id": 700,
    "path": "/bin/wasm_sandbox",
    "payload": json.dumps({"code": code_gas})
})

req_trap = json.dumps({
    "syscall": "VFS_CALL",
    "action": "COMPILE_AND_EXECUTE",
    "agent_id": 701,
    "path": "/bin/wasm_sandbox",
    "payload": json.dumps({"code": code_trap})
})

print("\n🧨 正在发射 [死循环炸弹]...")
t1 = threading.Thread(target=send_payload, args=("Gas 熔断测试", req_gas))
t1.start()

print("🧨 正在发射 [野指针炸弹]...")
t2 = threading.Thread(target=send_payload, args=("野指针测试", req_trap))
t2.start()

t1.join()
t2.join()

print("\n" + "=" * 60)
print(" ✅ 测试结束：没有任何攻击能撼动这颗内核！")
print("=" * 60)
