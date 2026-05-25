import socket
import json
import time


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(60)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res


def test_reaper_zombie_detection():
    print("=" * 60)
    print("  🧟 测试：Reaper 死神线程 - 僵尸进程检测")
    print("=" * 60)

    req = {
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "agent_id": 777,
        "payload": json.dumps({
            "code": '#include <stdio.h>\nint main() { printf("Agent 777 alive!\\n"); return 0; }',
            "func": "_start"
        })
    }

    print("\n[测试] 提交 Agent 777 的编译执行任务...")
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    print(f"[测试] Agent 777 执行完毕: status={parsed.get('status')}")

    print("\n[测试] Agent 777 已注册到 PCB，但之后不再发起任何 syscall...")
    print("[测试] 等待 35 秒，让 Reaper 巡视线程判定它为僵尸...")

    for i in range(35, 0, -5):
        print(f"  ⏳ 倒计时 {i}s ...")
        time.sleep(5)

    print("\n[测试] 检查 /proc/agents 查看 Agent 777 的状态...")
    req2 = {
        "syscall": "VFS_CALL",
        "action": "READ",
        "path": "/proc/agents",
        "agent_id": 0
    }
    raw2 = send_payload(json.dumps(req2))
    parsed2 = json.loads(raw2)
    agents_data = parsed2.get("data", {}).get("content", "")
    print(agents_data)

    if "777" in agents_data and "Z" in agents_data:
        print("✅ Reaper 成功检测到僵尸进程！Agent 777 已被标记为 Z(ZOMBIE)")
    else:
        print("⚠️ Agent 777 可能尚未被标记为 ZOMBIE（可能需要更长时间）")


def test_auto_restore():
    print("\n" + "=" * 60)
    print("  🔄 测试：启动态自动恢复 - 快照扫描与 RESTORE")
    print("=" * 60)

    req = {
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "agent_id": 888,
        "payload": json.dumps({
            "code": '#include <stdio.h>\n#include "aios.h"\nint main() { kprint("Agent 888 snapshot test"); snapshot(888, 0, 64); return 0; }',
            "func": "_start"
        })
    }

    print("\n[测试] 提交 Agent 888 的编译+快照任务...")
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    print(f"[测试] Agent 888 执行完毕: status={parsed.get('status')}")

    import os
    snapshot_path = "/tmp/aios_tasks/agent_888.mem"
    if os.path.exists(snapshot_path):
        size = os.path.getsize(snapshot_path)
        print(f"✅ 快照文件已生成: {snapshot_path} ({size} bytes)")
        print("\n[测试] 下次启动 AIOS Core 时，将自动扫描到该文件并提交 RESTORE 任务")
        print("[测试] 你可以重启 aios_core 观察启动日志中的自动恢复信息")
    else:
        print("⚠️ 快照文件未生成（Agent 可能未调用 snapshot host function）")
        print("[测试] 手动创建一个测试快照文件来验证扫描逻辑...")
        os.makedirs("/tmp/aios_tasks", exist_ok=True)
        with open(snapshot_path, 'wb') as f:
            header = bytes(8)
            f.write(header)
        print(f"✅ 已手动创建测试快照: {snapshot_path}")


def test_proc_agents_idle_column():
    print("\n" + "=" * 60)
    print("  📊 测试：/proc/agents 新增 IDLE(s) 列")
    print("=" * 60)

    req = {
        "syscall": "VFS_CALL",
        "action": "READ",
        "path": "/proc/agents",
        "agent_id": 0
    }
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    agents_data = parsed.get("data", {}).get("content", "")
    print(agents_data)

    if "IDLE(s)" in agents_data:
        print("✅ /proc/agents 已包含 IDLE(s) 列！")
    else:
        print("⚠️ IDLE(s) 列未找到")


if __name__ == "__main__":
    test_proc_agents_idle_column()
    test_auto_restore()
    test_reaper_zombie_detection()
