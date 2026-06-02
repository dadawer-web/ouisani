import socket
import json
import time
import threading


def send_payload(agent_id, payload):
    print(f"\n[Agent {agent_id}] 🚀 正在投递死循环炸弹...")
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(40)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))

        start = time.perf_counter()
        res = client.recv(8192).decode('utf-8')
        cost = time.perf_counter() - start

        print(f"\n[Agent {agent_id}] 💀 收到内核响应 (耗时 {cost:.2f} 秒):")
        try:
            parsed = json.loads(res)
            status = parsed.get("status", "")
            message = parsed.get("message", "")
            data = parsed.get("data", {})
            if isinstance(data, str):
                try:
                    data = json.loads(data)
                except:
                    pass
            print(f"  status: {status}")
            print(f"  message: {message}")
            if isinstance(data, dict):
                output = data.get("output", "")
                if output:
                    try:
                        out = json.loads(output)
                        print(f"  wasm_status: {out.get('status')}")
                        reason = out.get("reason", "")
                        if reason:
                            print(f"  wasm_reason: {reason}")
                    except:
                        pass
                stdout = data.get("stdout", "")
                if stdout:
                    for line in stdout.strip().split('\n'):
                        print(f"  stdout: {line}")
        except:
            print(res[:300])
        client.close()
    except socket.timeout:
        print(f"\n[Agent {agent_id}] ❌ Socket 连接超时！沙箱可能已经被强制超度了。")
    except Exception as e:
        print(f"\n[Agent {agent_id}] ❌ 发生异常: {e}")


def send_heartbeat(agent_id, stop_event):
    while not stop_event.is_set():
        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.settimeout(5)
            client.connect(('127.0.0.1', 8080))
            req = {
                "syscall": "VFS_CALL",
                "action": "READ",
                "path": "/proc/version",
                "agent_id": agent_id,
                "caller_id": agent_id
            }
            client.send((json.dumps(req) + '\n').encode('utf-8'))
            client.recv(8192)
            client.close()
        except:
            pass
        stop_event.wait(8)


print("======================================================")
print(" ⚔️ AIOS 容错防御测试：双层防线 vs 死循环炸弹")
print("======================================================\n")

# ==========================================
# 测试 1：Gas Limit 防线 (CPU 密集型死循环)
# ==========================================
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print(" 🛡️ 防线 1：WasmEdge Gas Limit (1亿指令上限)")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

malicious_code = """
#include <stdio.h>

int main() {
    printf("[恶意 WASM] 哈哈哈哈！我抢到了物理 CPU，准备开始死循环无限占用！\\n");

    long long counter = 0;
    while(1) {
        counter++;
    }

    return 0;
}
"""

req1 = {
    "syscall": "VFS_CALL",
    "action": "COMPILE_AND_EXECUTE",
    "agent_id": 666,
    "caller_id": 666,
    "payload": json.dumps({"code": malicious_code, "func": "_start"})
}

print("投递 CPU 密集型死循环 (while(1) counter++)...")
print("预期：Gas Limit 在 2-3 秒内击杀\n")

t1 = threading.Thread(target=send_payload, args=(666, json.dumps(req1)))
t1.start()

for i in range(1, 10):
    time.sleep(1)
    if not t1.is_alive():
        print(f"⏳ 炸弹在第 {i} 秒被消灭！")
        break
    print(f"⏳ 炸弹已运行 {i} 秒...")

t1.join()

# ==========================================
# 测试 2：Reaper 死神防线 (I/O 阻塞型僵尸)
# ==========================================
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print(" 🛡️ 防线 2：Reaper 死神线程 (30秒无心跳 → SIGKILL)")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

normal_code = """
#include <stdio.h>

int main() {
    printf("[正常 WASM] Agent 777 启动，执行完毕后不再发系统调用...\\n");
    return 0;
}
"""

req2 = {
    "syscall": "VFS_CALL",
    "action": "COMPILE_AND_EXECUTE",
    "agent_id": 777,
    "caller_id": 777,
    "payload": json.dumps({"code": normal_code, "func": "_start"})
}

print("投递正常程序 (Agent 777)，执行完毕后不再发系统调用...")
print("预期：30 秒后 Reaper 检测到僵尸并标记 ZOMBIE\n")

t2 = threading.Thread(target=send_payload, args=(777, json.dumps(req2)))
t2.start()
t2.join()

print("\nAgent 777 执行完毕，心跳停止。等待 Reaper 巡视...")
print("(Reaper 每 5 秒巡视一次，30 秒无心跳判定僵尸)\n")

for i in range(1, 36):
    print(f"⏳ Agent 777 已失联 {i} 秒...")
    time.sleep(1)

print("\n检查 Agent 777 的进程状态...")
req3 = {
    "syscall": "VFS_CALL",
    "action": "READ",
    "path": "/proc/agents",
    "agent_id": 0
}

try:
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(10)
    client.connect(('127.0.0.1', 8080))
    client.send((json.dumps(req3) + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()

    parsed = json.loads(res)
    data = parsed.get("data", {})
    if isinstance(data, str):
        data = json.loads(data)
    content = data.get("content", "")
    print(content)

    if "777" in content and "Z" in content:
        print("✅ Reaper 成功检测到僵尸进程！Agent 777 已被标记为 Z(ZOMBIE)")
    else:
        print("⚠️ Agent 777 可能尚未被标记为 ZOMBIE")
except Exception as e:
    print(f"读取 /proc/agents 失败: {e}")

print("\n======================================================")
print(" 🛡️ 测试结束：双层防线确保系统永不宕机。")
print("======================================================")
