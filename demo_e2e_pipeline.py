#!/usr/bin/env python3
"""=== AIOS 端到端 (E2E) 多智能体协同流水线大考 ===

同时拉起 Agent 101 和 102，测试：
1. Agent 101 计算斐波那契 → 写入 IPC 管道
2. Agent 102 阻塞等待管道数据 → 格式化输出

暴露内核真实短板：管道阻塞、线程池饿死、沙箱并发隔离
"""

import socket
import json
import threading
import time


def send_syscall(agent_id, req_dict):
    req_dict["agent_id"] = agent_id
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(60)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req_dict) + '\n').encode('utf-8'))
        buf = b""
        while True:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        client.close()
        raw = buf.decode().strip()
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"status": "raw", "data": raw}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def extract_stdout(resp):
    if not isinstance(resp, dict):
        return None, "", str(resp)
    data = resp.get("data", {})
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except:
            return resp.get("status", "unknown"), "", data

    stdout = ""
    output_str = ""
    if isinstance(data, dict):
        stdout = data.get("stdout", "")
        output_str = data.get("output", "")

    wasm_status = "unknown"
    if output_str:
        try:
            wasm_result = json.loads(output_str)
            wasm_status = wasm_result.get("status", "unknown")
        except:
            pass

    return wasm_status, stdout, output_str


PIPE_PATH = "/tmp/pipes/agent_101_to_102"


def agent_101_worker():
    print("[Agent 101] 🟢 启动！接到任务：计算斐波那契数列前 10 项...")

    code_101 = r"""
#include <stdio.h>
int main() {
    int a = 0, b = 1;
    printf("0");
    for (int i = 1; i < 10; i++) {
        printf(",%d", b);
        int tmp = b;
        b = a + b;
        a = tmp;
    }
    return 0;
}
"""

    print("[Agent 101] 正在通过 VFS 呼叫 /bin/wasm_sandbox 执行计算...")
    res_exec = send_syscall(101, {
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "path": "/bin/wasm_sandbox",
        "payload": json.dumps({"code": code_101})
    })

    wasm_status, stdout_content, _ = extract_stdout(res_exec)
    calc_result = stdout_content.strip() if stdout_content.strip() else "NO_STDOUT"

    print(f"[Agent 101] 运算完成 (wasm={wasm_status}), 结果: {calc_result}")

    time.sleep(2)

    print(f"[Agent 101] 正在将结果推入 IPC 管道: {PIPE_PATH}")
    res_write = send_syscall(101, {
        "syscall": "VFS_CALL",
        "action": "WRITE",
        "path": PIPE_PATH,
        "payload": calc_result
    })
    write_status = res_write.get("status", "unknown") if isinstance(res_write, dict) else "unknown"
    print(f"[Agent 101] 管道写入响应: {write_status}")
    print("[Agent 101] 任务完成，退出。")


def agent_102_worker():
    print("[Agent 102] 🔵 启动！我的任务是等待 101 的数据，并进行格式化输出...")

    print(f"[Agent 102] 正在监听 IPC 管道 {PIPE_PATH} (如果内核管用，我将在这里阻塞等待)...")
    t0 = time.perf_counter()
    res_read = send_syscall(102, {
        "syscall": "VFS_CALL",
        "action": "READ",
        "path": PIPE_PATH
    })
    elapsed = time.perf_counter() - t0

    raw_data = ""
    if isinstance(res_read, dict):
        data = res_read.get("data", {})
        if isinstance(data, dict):
            raw_data = data.get("content", str(data))
        else:
            raw_data = str(data)

    print(f"[Agent 102] ⚡ 收到来自管道的数据 (等待了 {elapsed:.2f}s): {raw_data}")

    safe_data = raw_data.replace('\\', '\\\\').replace('"', '\\"')[:200]

    code_102 = f"""
#include <stdio.h>
int main() {{
    printf("===================================\\n");
    printf("  AIOS Pipeline Report\\n");
    printf("===================================\\n");
    printf("  Source: Agent 101 -> Agent 102\\n");
    printf("  Transport: IPC Pipe\\n");
    printf("  Fibonacci: {safe_data}\\n");
    printf("===================================\\n");
    return 0;
}}
"""

    print("[Agent 102] 正在通过 VFS 呼叫 /bin/wasm_sandbox 进行格式化渲染...")
    res_exec = send_syscall(102, {
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "path": "/bin/wasm_sandbox",
        "payload": json.dumps({"code": code_102})
    })

    wasm_status, stdout_content, _ = extract_stdout(res_exec)
    print("\n【最终呈现结果】:")
    if stdout_content.strip():
        print(stdout_content.strip())
    else:
        print("(无 stdout 输出)")
    print(f"[Agent 102] 渲染完成 (wasm={wasm_status})，退出。")


if __name__ == "__main__":
    print("=" * 60)
    print(" 🚀 AIOS 端到端 (E2E) 多智能体协同流水线大考")
    print("=" * 60)

    t102 = threading.Thread(target=agent_102_worker)
    t101 = threading.Thread(target=agent_101_worker)

    print("\n⚠️  故意先启动 102，让它去读空管道，测试内核阻塞机制！\n")

    t102.start()
    time.sleep(0.5)
    t101.start()

    t102.join(timeout=60)
    t101.join(timeout=60)

    print("\n" + "=" * 60)
    print(" === 端到端测试结束 ===")
    print("=" * 60)
