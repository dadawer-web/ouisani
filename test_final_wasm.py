import socket
import json
import time

def send_intent(text):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.send((text + '\n').encode('utf-8'))
    res = client.recv(8192).decode('utf-8')
    client.close()
    return res

def send_vfs_command(agent_id, action, path, payload):
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
    print("=" * 70)
    print("  AIOS 终极全链路测试")
    print("  NLP → UDS译码 → 动态编译 → Wasm运行 → I/O拦截")
    print("=" * 70)

    c_code = """#include <stdio.h>
int fib(int n) {
    if (n <= 1) return n;
    return fib(n-1) + fib(n-2);
}
int main() {
    int res = fib(30);
    printf("WasmEdge fib(30) = %d\\n", res);
    return 0;
}"""

    print("\n[测试1] 终极全链路: C代码 → WASI编译 → WasmEdge执行 → pipe捕获stdout")
    print(f"  C源码:\n{c_code}")

    start_t = time.perf_counter()
    result, cost = send_vfs_command(
        agent_id=301,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code, "func": "_start"})
    )
    end_t = time.perf_counter()
    total_ms = (end_t - start_t) * 1000

    print(f"\n  [内核返回]: {result}")

    try:
        resp = json.loads(result)
        if resp.get("status") == "ok" and "data" in resp:
            data = resp["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "{}")
            output = json.loads(output_str) if isinstance(output_str, str) else output_str
            stdout = output.get("stdout", "")
            if stdout:
                print(f"\n  ╔══════════════════════════════════════╗")
                print(f"  ║  WASM stdout 捕获结果:              ║")
                print(f"  ║  {stdout:<36s}║")
                print(f"  ╚══════════════════════════════════════╝")
    except:
        pass

    print(f"\n  [全链路总耗时]: {total_ms:.2f} 毫秒")
    print(f"  (含: TCP通信 + clang编译 + WasmEdge装载执行 + pipe I/O捕获)")

    print("\n" + "-" * 70)

    c_code_add = "int add(int a, int b) { return a + b; }"
    print(f"\n[测试2] 纯计算基准: add(999, 1) — 跳过printf，测WasmEdge纯速度")
    result2, cost2 = send_vfs_command(
        agent_id=302,
        action="COMPILE_AND_EXECUTE",
        path="",
        payload=json.dumps({"code": c_code_add, "func": "add", "args": [999, 1]})
    )
    print(f"  [返回]: {result2}")
    print(f"  [耗时]: {cost2*1000:.2f} 毫秒")

    print("\n" + "-" * 70)

    print(f"\n[测试3] 预编译WASM直接执行 (跳过编译，测纯WasmEdge+I/O重定向)")
    result3, cost3 = send_vfs_command(
        agent_id=301,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"file": "/home/xmy/tryaios/hello.wasm", "func": "add", "args": [999, 1]})
    )
    print(f"  [返回]: {result3}")
    print(f"  [耗时]: {cost3*1000:.2f} 毫秒")

    print("\n" + "-" * 70)

    c_code_oneline = c_code.replace('\\', '\\\\').replace('\n', '\\n')

    print(f"\n[测试4] 自然语言网关 - 简短C代码 (NLP → 关键词路由 → COMPILE_AND_EXECUTE)")
    short_nl_code = "编译运行代码 int add(int a, int b) { return a + b; }"
    print(f"  发送: {short_nl_code}")
    start_nl = time.perf_counter()
    nl_result = send_intent(short_nl_code)
    end_nl = time.perf_counter()
    nl_ms = (end_nl - start_nl) * 1000
    print(f"  [返回]: {nl_result[:500]}")
    print(f"  [耗时]: {nl_ms:.1f} 毫秒 (含UDS译码+编译+执行)")
    try:
        resp4 = json.loads(nl_result)
        if resp4.get("status") == "ok" and "data" in resp4:
            d4 = resp4["data"]
            if isinstance(d4, str):
                d4 = json.loads(d4)
            out4 = d4.get("output", "{}")
            o4 = json.loads(out4) if isinstance(out4, str) else out4
            if o4.get("status") == "ok" or o4.get("exit_code", -1) >= 0:
                print(f"  ✅ 自然语言 → COMPILE_AND_EXECUTE 路由成功!")
    except:
        pass

    print("\n" + "-" * 70)

    print(f"\n[测试5] 自然语言网关 - 含include的C代码 (关键词路由+代码提取)")
    long_nl_code = f"帮我编译并执行这段代码：{c_code_oneline}"
    print(f"  发送自然语言 (含fib代码, 单行化)...")
    start_short = time.perf_counter()
    short_result = send_intent(long_nl_code)
    end_short = time.perf_counter()
    short_ms = (end_short - start_short) * 1000
    print(f"  [返回]: {short_result[:500]}")
    print(f"  [耗时]: {short_ms:.1f} 毫秒")
    try:
        resp5 = json.loads(short_result)
        if resp5.get("status") == "ok" and "data" in resp5:
            d5 = resp5["data"]
            if isinstance(d5, str):
                d5 = json.loads(d5)
            out5 = d5.get("output", "{}")
            o5 = json.loads(out5) if isinstance(out5, str) else out5
            if o5.get("status") == "ok":
                stdout5 = o5.get("stdout", "")
                if "832040" in stdout5:
                    print(f"  ✅ 自然语言 → fib(30)=832040 全链路成功!")
                elif stdout5:
                    print(f"  ⚠️ 编译执行成功但输出: {stdout5}")
            else:
                print(f"  ⚠️ 编译或执行阶段失败 (可能因单行化导致WASI问题)")
        elif resp5.get("status") == "error" or (resp5.get("data") and "error" in str(resp5.get("data", ""))):
            print(f"  ⚠️ 编译失败 (单行化C代码可能不兼容WASI)")
    except:
        pass

    print("\n" + "=" * 70)
    print("  性能总结:")
    print(f"  COMPILE_AND_EXECUTE (含printf): {total_ms:.1f} ms")
    print(f"  COMPILE_AND_EXECUTE (纯计算):   {cost2*1000:.1f} ms")
    print(f"  纯WASM执行 (跳过编译):          {cost3*1000:.1f} ms")
    print(f"  Python FastAPI沙盒 (对比基准):  ~120 ms")
    print("=" * 70)
