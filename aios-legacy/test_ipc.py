import socket
import json
import time
import threading


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
    client.sendall((json.dumps(req) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    try:
        return json.loads(line.decode('utf-8'))
    except Exception:
        return {"status": "error", "raw": line.decode('utf-8', errors='replace')}


def agent_102_tester():
    print("[Agent 102 测试员] 启动！开始读取管道 /tmp/pipes/agent_101_to_102...")
    print("[Agent 102 测试员] (此时管道为空，预期我的请求会被 C++ 内核挂起，让出 CPU)")

    start_time = time.time()
    res = syscall_vfs(102, "READ", "/tmp/pipes/agent_101_to_102")
    end_time = time.time()

    elapsed = end_time - start_time
    content = res.get('data', {}).get('content', '?') if isinstance(res.get('data'), dict) else '?'
    print(f"\n[Agent 102 测试员] ⚡ 硬件级唤醒！读取成功，阻塞耗时 {elapsed:.2f} 秒。")
    print(f"[Agent 102 测试员] 读取到的内容: {content}")


def agent_101_coder():
    print("[Agent 101 程序员] 启动！正在努力思考和编写代码 (模拟耗时 4 秒)...")
    time.sleep(4)

    code = "def fast_sort(arr): return sorted(arr) # AIOS IPC 编写"
    print(f"[Agent 101 程序员] 代码编写完毕，准备执行 VFS Write 写入管道...")
    res = syscall_vfs(101, "WRITE", "/tmp/pipes/agent_101_to_102", code)
    print(f"[Agent 101 程序员] 写入完成，已向内核发送 notify 信号！({res.get('status', '?')})")


if __name__ == "__main__":
    print("=== AIOS IPC 管道与条件变量并发测试 ===\n")

    t_tester = threading.Thread(target=agent_102_tester)
    t_coder = threading.Thread(target=agent_101_coder)

    t_tester.start()
    time.sleep(0.5)
    t_coder.start()

    t_tester.join()
    t_coder.join()

    print("\n=== IPC 测试圆满结束 ===")
