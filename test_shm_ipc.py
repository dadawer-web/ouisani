import socket
import json
import threading
import time

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.settimeout(30)
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res

def run_agent_a():
    c_code_a = r"""
#include <stdio.h>
#include <string.h>

__attribute__((import_module("aios"), import_name("shm_write")))
void aios_shm_write(int shm_offset, const char* wasm_ptr, int len);

int main() {
    printf("[Agent A] 启动！我正在向 Ring 0 物理内存池注入数据...\n");
    const char* secret = "【AIOS 核心机密】: 这是来自 Agent A 的内存级跨界传输测试！";

    aios_shm_write(0, secret, strlen(secret) + 1);

    printf("[Agent A] 数据写入完毕，退出沙盒。\n");
    return 0;
}
    """
    req = {
        "syscall": "VFS_CALL",
        "agent_id": 1,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code_a})
    }
    try:
        raw_res = send_payload(json.dumps(req))
        outer = json.loads(raw_res.strip())
        status = outer.get("status", "unknown")
        print(f"  [Agent A 服务器状态]: {status}")
        if status == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "")
            if output_str:
                try:
                    output = json.loads(output_str)
                    print(f"  [Agent A WASM状态]: {output.get('status', 'unknown')}")
                except json.JSONDecodeError:
                    pass
    except Exception as e:
        print(f"  [Agent A 异常]: {e}")

def run_agent_b():
    c_code_b = r"""
#include <stdio.h>

__attribute__((import_module("aios"), import_name("shm_read")))
void aios_shm_read(int shm_offset, char* wasm_ptr, int len);

int main() {
    printf("[Agent B] 启动！我正在监听 Ring 0 物理内存池...\n");

    char buffer[256] = {0};

    aios_shm_read(0, buffer, 100);

    printf("[Agent B] 成功截获内存数据: %s\n", buffer);
    return 0;
}
    """
    req = {
        "syscall": "VFS_CALL",
        "agent_id": 2,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code_b})
    }
    try:
        raw_res = send_payload(json.dumps(req))
        outer = json.loads(raw_res.strip())
        status = outer.get("status", "unknown")
        print(f"  [Agent B 服务器状态]: {status}")
        if status == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "")
            if output_str:
                try:
                    output = json.loads(output_str)
                    print(f"  [Agent B WASM状态]: {output.get('status', 'unknown')}")
                except json.JSONDecodeError:
                    pass
    except Exception as e:
        print(f"  [Agent B 异常]: {e}")

if __name__ == "__main__":
    print("=== AIOS 跨沙盒零拷贝 (RAM IPC) 测试 ===\n")

    print("1. 释放 Agent A 进入虚拟机...")
    t1 = threading.Thread(target=run_agent_a)
    t1.start()
    t1.join()

    time.sleep(0.5)

    print("\n2. 释放 Agent B 进入另一个全新的虚拟机...")
    t2 = threading.Thread(target=run_agent_b)
    t2.start()
    t2.join()

    print("\n--- 服务器日志 (Ring 0 SHM 操作记录) ---")
    try:
        with open("/tmp/aios.log", "r") as f:
            lines = f.readlines()
            shm_lines = [l.strip() for l in lines if "SHM" in l]
            for line in shm_lines[-10:]:
                print(f"  {line}")
    except FileNotFoundError:
        pass

    print("\n=== 测试结束 ===")
