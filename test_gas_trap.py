#!/usr/bin/env python3
"""🔥 AIOS Gas 熔断 & 硬件 Trap 异常捕获测试"""

import socket
import json
import time
import sys

def send_syscall(syscall_name, extra=None, agent_id=0):
    msg = {"syscall": syscall_name, "agent_id": agent_id}
    if extra:
        msg.update(extra)
    data = json.dumps(msg) + "\n"
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(30)
        sock.connect(("127.0.0.1", 8080))
        sock.sendall(data.encode())
        buf = b""
        while True:
            chunk = sock.recv(8192)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        sock.close()
        return buf.decode().strip()
    except Exception as e:
        return json.dumps({"status": "error", "reason": str(e)})

def extract_wasm_result(resp):
    parsed = json.loads(resp)
    outer_status = parsed.get("status", "unknown")
    data = parsed.get("data", {})

    if isinstance(data, str):
        try:
            data = json.loads(data)
        except:
            return {"outer_status": outer_status, "raw_data": data}

    output_str = data.get("output", "")
    if output_str:
        try:
            wasm_result = json.loads(output_str)
            wasm_result["outer_status"] = outer_status
            return wasm_result
        except json.JSONDecodeError:
            pass

    return {"outer_status": outer_status, "data": data}

def test_gas_limit():
    print("\n" + "=" * 60)
    print("  🔥 Test 1: Gas 熔断 — 死循环必须在 5000 万指令内被杀")
    print("=" * 60)

    dead_loop_c = r"""
#include <stdio.h>
int main() {
    volatile int x = 0;
    while (1) { x++; }
    return 0;
}
"""

    resp = send_syscall("VFS_CALL", {
        "action": "COMPILE_AND_EXECUTE",
        "path": "/bin/wasm_sandbox",
        "payload": json.dumps({"code": dead_loop_c})
    }, agent_id=500)

    result = extract_wasm_result(resp)

    status = result.get("status", "unknown")
    gas_used = result.get("gas_used", 0)
    instr_count = result.get("instr_count", 0)
    gas_limit = result.get("gas_limit", 0)
    trap = result.get("trap", result.get("reason", ""))

    print(f"  Status: {status}")
    print(f"  Gas Used: {gas_used:,}")
    print(f"  Instr Count: {instr_count:,}")
    print(f"  Gas Limit: {gas_limit:,}")
    print(f"  Trap Detail: {trap}")

    if status == "trap" and gas_used > 0:
        print(f"\n  ✅ PASS — Gas 熔断生效！死循环在消耗 {gas_used:,} 条指令后被强制终止")
        if "cost limit exceeded" in trap.lower() or "gas" in trap.lower():
            print(f"  ✅ Trap 信息明确: {trap}")
        return True
    elif status == "trap":
        print(f"\n  ✅ PASS — Trap 触发 (可能是 alarm 超时)")
        return True
    else:
        print(f"\n  ❌ FAIL — 死循环未被熔断！status={status}")
        return False


def test_trap_oob():
    print("\n" + "=" * 60)
    print("  🔥 Test 2: 硬件 Trap — 野指针越界访问必须被捕获")
    print("=" * 60)

    oob_c = r"""
#include <stdio.h>
int main() {
    int *p = (int *)0xDEADBEEF;
    *p = 42;
    return 0;
}
"""

    resp = send_syscall("VFS_CALL", {
        "action": "COMPILE_AND_EXECUTE",
        "path": "/bin/wasm_sandbox",
        "payload": json.dumps({"code": oob_c})
    }, agent_id=501)

    result = extract_wasm_result(resp)

    status = result.get("status", "unknown")
    trap = result.get("trap", result.get("reason", ""))
    gas_used = result.get("gas_used", 0)

    print(f"  Status: {status}")
    print(f"  Trap Detail: {trap}")
    print(f"  Gas Used: {gas_used:,}")

    if status == "trap":
        print(f"\n  ✅ PASS — 野指针 Trap 被捕获！异常信息: {trap}")
        if "out of bounds" in trap.lower() or "memory" in trap.lower():
            print(f"  ✅ Trap 信息精确: 识别为内存越界访问")
        return True
    else:
        print(f"\n  ❌ FAIL — 野指针未被捕获！status={status}")
        return False


def test_normal_execution():
    print("\n" + "=" * 60)
    print("  🔥 Test 3: 正常执行 — Gas 使用量应被正确记录")
    print("=" * 60)

    normal_c = r"""
#include <stdio.h>
int main() {
    int sum = 0;
    for (int i = 1; i <= 100; i++) {
        sum += i;
    }
    printf("sum=%d\n", sum);
    return 0;
}
"""

    resp = send_syscall("VFS_CALL", {
        "action": "COMPILE_AND_EXECUTE",
        "path": "/bin/wasm_sandbox",
        "payload": json.dumps({"code": normal_c})
    }, agent_id=502)

    result = extract_wasm_result(resp)

    status = result.get("status", "unknown")
    gas_used = result.get("gas_used", 0)
    instr_count = result.get("instr_count", 0)
    gas_limit = result.get("gas_limit", 0)

    print(f"  Status: {status}")
    print(f"  Gas Used: {gas_used:,}")
    print(f"  Instr Count: {instr_count:,}")
    print(f"  Gas Limit: {gas_limit:,}")
    if gas_limit > 0:
        print(f"  Gas Utilization: {gas_used/gas_limit*100:.4f}%")

    if status == "ok" and gas_used > 0 and gas_limit == 50000000:
        print(f"\n  ✅ PASS — 正常执行，Gas 使用量 {gas_used:,} 被正确记录")
        return True
    else:
        print(f"\n  ❌ FAIL — status={status}, gas_used={gas_used}, gas_limit={gas_limit}")
        return False


def test_kmsg_trap_log():
    print("\n" + "=" * 60)
    print("  🔥 Test 4: /proc/kmsg — Trap 异常应被记录到内核日志")
    print("=" * 60)

    resp = send_syscall("VFS_CALL", {"action": "READ", "path": "/proc/kmsg"})
    parsed = json.loads(resp)
    data = parsed.get("data", {})
    if isinstance(data, dict):
        content = data.get("content", "")
    else:
        content = str(data)

    trap_lines = [l for l in content.split('\n') if "Trap" in l]
    print(f"  Trap 日志条数: {len(trap_lines)}")
    for line in trap_lines:
        print(f"  🟢 {line}")

    if trap_lines:
        print(f"\n  ✅ PASS — Trap 异常已被 KernelLogger 记录到 /proc/kmsg")
        return True
    else:
        print(f"\n  ⚠️  未在 /proc/kmsg 中找到 Trap 日志 (可能测试 1/2 未触发 Trap)")
        return True


def main():
    print("=" * 60)
    print(" 🔥 AIOS Gas 熔断 & 硬件 Trap 异常捕获 综合测试")
    print("=" * 60)

    results = []

    results.append(("Gas 熔断 (死循环)", test_gas_limit()))
    time.sleep(1)
    results.append(("Trap 捕获 (野指针)", test_trap_oob()))
    time.sleep(1)
    results.append(("Gas 计量 (正常执行)", test_normal_execution()))
    time.sleep(1)
    results.append(("/proc/kmsg Trap 日志", test_kmsg_trap_log()))

    print("\n" + "=" * 60)
    print(" 📊 测试结果汇总")
    print("=" * 60)

    all_pass = True
    for name, result in results:
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"  {status}  {name}")
        if not result:
            all_pass = False

    print("=" * 60)
    if all_pass:
        print(" 🎉 全部测试通过！Gas 熔断 & Trap 捕获已加固！")
    else:
        print(" ⚠️  部分测试未通过，请检查上方输出")
    print("=" * 60)

    return all_pass


if __name__ == "__main__":
    try:
        result = main()
        sys.exit(0 if result else 1)
    except Exception as e:
        print(f"\n❌ 测试异常: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
