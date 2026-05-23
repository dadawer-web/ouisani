import socket
import json
import time

def syscall_vfs(agent_id, action, path="", payload=""):
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
    print("  AIOS 内核态 I/O 重定向沙盒测试")
    print("  C 源码 → clang → .wasm → WasmEdge → pipe 捕获 stdout")
    print("=" * 60)

    # 测试1: WASI _start 入口 + printf 输出捕获
    c_code_hello = (
        '#include <stdio.h>\n'
        'int main() {\n'
        '    printf("Hello from WASM sandbox!\\n");\n'
        '    printf("AIOS kernel captured this output via pipe.\\n");\n'
        '    return 0;\n'
        '}'
    )
    print(f"\n[测试1] WASI _start + printf 输出捕获")
    print(f"  C 源码:\n{c_code_hello}")
    res, cost = syscall_vfs(
        agent_id=201,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code_hello, "func": "_start"})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试2: 带参数的函数调用 + printf
    c_code_compute = (
        '#include <stdio.h>\n'
        'int compute(int a, int b) {\n'
        '    int result = a * a + b * b;\n'
        '    printf("compute(%d, %d) = %d\\n", a, b, result);\n'
        '    return result;\n'
        '}'
    )
    print(f"\n[测试2] 带参数函数 + printf 输出捕获")
    print(f"  C 源码:\n{c_code_compute}")
    res, cost = syscall_vfs(
        agent_id=202,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code_compute, "func": "compute", "args": [3, 4]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试3: 纯计算函数（无 printf，对比基准）
    c_code_add = "int add(int a, int b) { return a + b; }"
    print(f"\n[测试3] 纯计算函数（无 printf）")
    print(f"  C 源码: {c_code_add}")
    res, cost = syscall_vfs(
        agent_id=203,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code_add, "func": "add", "args": [100, 250]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试4: 预编译 WASM 直接执行（跳过编译，测纯 I/O 重定向）
    print(f"\n[测试4] 预编译 WASM 直接执行（I/O 重定向基准）")
    res, cost = syscall_vfs(
        agent_id=201,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"file": "/home/xmy/tryaios/hello.wasm", "func": "add", "args": [100, 250]})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    # 测试5: 编译错误检测
    c_code_bad = "int broken( { return; }"
    print(f"\n[测试5] 编译错误检测")
    print(f"  C 源码: {c_code_bad}")
    res, cost = syscall_vfs(
        agent_id=204,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code_bad, "func": "broken"})
    )
    print(f"  [返回] {res}")
    print(f"  [耗时] {cost * 1000:.3f} 毫秒")

    print(f"\n{'=' * 60}")
    print(f"  I/O 重定向核心机制:")
    print(f"  pipe() → dup(STDOUT) → dup2(pipe_write, STDOUT)")
    print(f"  → WasmEdge 执行 → fflush → dup2(restore STDOUT)")
    print(f"  → read(pipe_read) → 捕获完整 stdout 输出")
    print(f"{'=' * 60}")
