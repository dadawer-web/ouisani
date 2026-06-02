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
    response = client.recv(4096).decode('utf-8')
    client.close()
    end = time.perf_counter()
    return response, end - start

if __name__ == "__main__":
    print("=== 内核态 Wasm 零延迟沙盒测试 ===")

    wasm_path = "/home/xmy/tryaios/hello.wasm"

    print(f"\n[测试1] 调用 add(100, 250)")
    res, cost = syscall_vfs(
        agent_id=101,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"file": wasm_path, "func": "add", "args": [100, 250]})
    )
    print(f"[Wasm 沙盒返回] {res}")
    print(f"[执行耗时] {cost * 1000:.3f} 毫秒")

    print(f"\n[测试2] 调用 add(42, -8)")
    res, cost = syscall_vfs(
        agent_id=101,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=json.dumps({"file": wasm_path, "func": "add", "args": [42, -8]})
    )
    print(f"[Wasm 沙盒返回] {res}")
    print(f"[执行耗时] {cost * 1000:.3f} 毫秒")

    print(f"\n[测试3] 调用 add(999, 1) 连续3次测延迟")
    for i in range(3):
        res, cost = syscall_vfs(
            agent_id=101,
            action="EXECUTE",
            path="/bin/wasm_sandbox",
            payload=json.dumps({"file": wasm_path, "func": "add", "args": [999, 1]})
        )
        print(f"  第{i+1}次: {cost * 1000:.3f} ms | 返回: {res.strip()}")

    print(f"\n[测试4] 默认 wasm 文件 (test.wasm) _start 函数")
    res, cost = syscall_vfs(
        agent_id=101,
        action="EXECUTE",
        path="/bin/wasm_sandbox",
        payload=""
    )
    print(f"[Wasm 沙盒返回] {res}")
    print(f"[执行耗时] {cost * 1000:.3f} 毫秒")
