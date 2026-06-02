import socket
import json
import time

def syscall_vfs(agent_id, action, path, payload=""):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    req = {
        "syscall": "VFS_CALL",
        "agent_id": agent_id,
        "action": action,
        "path": path,
        "payload": payload
    }
    start = time.perf_counter()
    client.send((json.dumps(req) + '\n').encode('utf-8'))
    response = client.recv(8192).decode('utf-8')
    client.close()
    end = time.perf_counter()
    return response, end - start

if __name__ == "__main__":
    print("=" * 60)
    print("  AIOS 内核态动态编译流水线测试")
    print("  C 源码 → clang → .wasm → WasmEdge 执行")
    print("=" * 60)

    # 测试1: 编译并执行 add 函数
    c_code_add = "int add(int a, int b) { return a + b; }"
    print(f"\n[测试1] 动态编译 add 函数")
    print(f"  C 源码: {c_code_add}")
    res, cost = syscall_vfs(
        agent_id=101,
        action="COMPILE_AND_EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"code": c_code_add, "func": "add", "args": [100, 250]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试2: 编译并执行 multiply 函数
    c_code_mul = "int multiply(int a, int b) { return a * b; }"
    print(f"\n[测试2] 动态编译 multiply 函数")
    print(f"  C 源码: {c_code_mul}")
    res, cost = syscall_vfs(
        agent_id=102,
        action="COMPILE_AND_EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"code": c_code_mul, "func": "multiply", "args": [7, 8]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试3: 编译并执行 fibonacci 函数
    c_code_fib = (
        "int fib(int n) {"
        "  if (n <= 1) return n;"
        "  int a = 0, b = 1;"
        "  for (int i = 2; i <= n; i++) {"
        "    int t = a + b;"
        "    a = b;"
        "    b = t;"
        "  }"
        "  return b;"
        "}"
    )
    print(f"\n[测试3] 动态编译 Fibonacci 函数")
    print(f"  C 源码: {c_code_fib[:60]}...")
    res, cost = syscall_vfs(
        agent_id=103,
        action="COMPILE_AND_EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"code": c_code_fib, "func": "fib", "args": [10]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试4: 编译错误检测
    c_code_bad = "int broken( { return; }"
    print(f"\n[测试4] 编译错误检测（故意写错代码）")
    print(f"  C 源码: {c_code_bad}")
    res, cost = syscall_vfs(
        agent_id=104,
        action="COMPILE_AND_EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"code": c_code_bad, "func": "broken"})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试5: 纯 WASM 执行（不编译，对比基准）
    print(f"\n[测试5] 纯 WASM 执行对比基准（跳过编译）")
    res, cost = syscall_vfs(
        agent_id=101,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"file": "/home/xmy/tryaios/hello.wasm", "func": "add", "args": [100, 250]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    print(f"\n{'=' * 60}")
    print(f"  测试完成！对比：")
    print(f"  - COMPILE_AND_EXECUTE: 包含 clang 编译 + WasmEdge 执行")
    print(f"  - 纯 EXECUTE: 仅 WasmEdge 执行（跳过编译）")
    print(f"  - Python FastAPI 沙盒: ~120ms")
    print(f"{'=' * 60}")
