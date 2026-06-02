import socket
import json


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(10)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(8192).decode('utf-8')
    client.close()
    return res


print("=== AIOS VFS 硬件级防越权测试 ===")

req_malicious = {
    "syscall": "VFS_CALL",
    "action": "READ",
    "path": "/dev/mem/101",
    "agent_id": 102
}

print("\n1. [恶意 Agent 102] 试图读取 101 号的内存空间...")
res1 = send_payload(json.dumps(req_malicious))
print(f"内核返回: {res1.strip()}")

req_legal = {
    "syscall": "VFS_CALL",
    "action": "READ",
    "path": "/dev/mem/101",
    "agent_id": 101
}

print("\n2. [合法 Agent 101] 试图读取自己的内存空间...")
res2 = send_payload(json.dumps(req_legal))
print(f"内核返回: {res2.strip()}")
