import socket
import json
import time


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(10)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(8192).decode('utf-8')
    client.close()
    return res


print("=== AIOS 内核进程表 (PCB) 观测测试 ===")

print("\n正在向内核注册进程并产生系统调用...")
for i in [101, 102, 105]:
    req = {
        "syscall": "WRITE_MEMORY",
        "caller_id": i,
        "agent_id": i,
        "content": "心跳激活"
    }
    send_payload(json.dumps(req))

time.sleep(1)

req_top = {
    "syscall": "VFS_CALL",
    "action": "READ",
    "path": "/proc/agents",
    "agent_id": 0
}

print("\n[Ring 0 管理员] 正在读取 /proc/agents...")
res = send_payload(json.dumps(req_top))

try:
    parsed = json.loads(res)
    content = parsed.get("data", {}).get("content", "")
    if not content:
        content = parsed.get("data", str(parsed))
    print("\n----------------------------------------------------")
    print(content)
    print("----------------------------------------------------")
except:
    print("返回内容：\n", res)
