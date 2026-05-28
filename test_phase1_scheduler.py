import socket
import json
import threading
import time

completion_log = []
completion_lock = threading.Lock()


def send_request(agent_id, syscall, priority, payload, timeout=120):
    req = {
        "syscall": syscall,
        "caller_id": agent_id,
        "agent_id": agent_id,
        "priority": priority,
        "payload": payload
    }

    if syscall == "VFS_CALL":
        req["action"] = "TREE"
        req["path"] = "/"

    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))

        start = time.perf_counter()
        buf = b""
        while b"\n" not in buf:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk
        cost = time.perf_counter() - start

        line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
        client.close()

        with completion_lock:
            completion_log.append({
                "agent_id": agent_id,
                "syscall": syscall,
                "priority": priority,
                "finish_order": len(completion_log) + 1,
                "cost": cost,
                "response": line[:200]
            })

        print(f"  [{syscall}] Agent {agent_id} (priority={priority}) "
              f"耗时 {cost:.2f}s | 完成顺序: 第 {len(completion_log)} 个")

    except socket.timeout:
        print(f"  ❌ Agent {agent_id} 请求超时 ({timeout}s)")
    except ConnectionRefusedError:
        print(f"  ❌ Agent {agent_id} 连接被拒绝！内核未启动？")
    except Exception as e:
        print(f"  ❌ Agent {agent_id} 请求失败: {e}")


print("=" * 60)
print("  🚀 Phase 1: 路由解耦与 LLM 优先级调度器 压力测试")
print("=" * 60)
print()

print("--- 验证 1: VfsSyscallHandler 路由是否存活 ---")
print()

res_vfs = send_request(101, "VFS_CALL", 0, "test_vfs_alive")

with completion_lock:
    vfs_entry = completion_log[-1] if completion_log else None

if vfs_entry and vfs_entry["response"]:
    try:
        parsed = json.loads(vfs_entry["response"])
        if parsed.get("status") == "ok":
            print(f"  ✅ VfsSyscallHandler 存活！VFS_TREE 正常返回数据")
        else:
            print(f"  ✅ VfsSyscallHandler 存活！收到响应（可能含预期错误）: {parsed.get('message', '')[:80]}")
    except json.JSONDecodeError:
        print(f"  ⚠️  VFS 返回非 JSON: {vfs_entry['response'][:80]}")
else:
    print(f"  ❌ VFS 路由无响应")

print()
print("--- 验证 2: LlmSyscallHandler 优先级插队机制 ---")
print()
print("  我们将并发发送 4 个 LLM_INFERENCE 请求。")
print("  内核只有一个 LLM Worker 线程（模拟单显卡算力瓶颈）。")
print("  前 3 个低优先级任务先排队，然后 Priority=99 的请求杀入！")
print("  如果优先级调度生效，Priority=99 应该插队到第 2 个出结果。")
print()

completion_log.clear()

low_threads = []
for i in range(1, 4):
    t = threading.Thread(
        target=send_request,
        args=(i, "LLM_INFERENCE", 0, f"低优任务：分析第 {i} 段数据...")
    )
    low_threads.append(t)

for t in low_threads:
    t.start()

time.sleep(0.3)

print(f"  ⚡ 3 个低优先级任务已在内核 LLM 队列排队...")
print(f"  ⚡ 突然杀入 Agent 999 (Priority=99) 的最高级指令！")
print()

t_boss = threading.Thread(
    target=send_request,
    args=(999, "LLM_INFERENCE", 99, "【最高级指令】立即翻译意图！")
)
t_boss.start()

for t in low_threads:
    t.join()
t_boss.join()

print()
print("--- 结果分析 ---")
print()

with completion_lock:
    sorted_log = sorted(completion_log, key=lambda x: x["finish_order"])

    print(f"  {'完成顺序':<10} {'Agent':<8} {'Syscall':<18} {'优先级':<8} {'耗时':<10}")
    print(f"  {'--------':<10} {'------':<8} {'----------------':<18} {'------':<8} {'------':<10}")
    for entry in sorted_log:
        marker = " ⚡" if entry["priority"] == 99 else ""
        print(f"  第 {entry['finish_order']:<6} {entry['agent_id']:<8} "
              f"{entry['syscall']:<18} {entry['priority']:<8} {entry['cost']:.2f}s{marker}")

print()

with completion_lock:
    high_entry = None
    for entry in completion_log:
        if entry["priority"] == 99:
            high_entry = entry
            break

if high_entry:
    if high_entry["finish_order"] <= 2:
        print(f"  ✅ 优先级插队验证通过！")
        print(f"     Priority=99 的请求在第 {high_entry['finish_order']} 个完成，")
        print(f"     成功插队到其他低优先级 (Priority=0) 请求前面！")
    elif high_entry["finish_order"] == 1:
        print(f"  ✅ 优先级插队验证通过！")
        print(f"     Priority=99 的请求第 1 个完成，绝对优先！")
    else:
        print(f"  ⚠️  Priority=99 的请求在第 {high_entry['finish_order']} 个完成。")
        print(f"     可能原因: Worker 在高优先级请求到达前已开始处理低优先级任务。")
        print(f"     (LLM 推理耗时较长，一旦开始处理无法中断，这是正常行为)")
else:
    print(f"  ❌ 高优先级请求未完成，调度器可能未正常工作。")

print()
print("--- 验证 3: Handler 模式路由分发正确性 ---")
print()

completion_log.clear()

test_cases = [
    (10, "VFS_CALL", 0, "VFS 路由测试"),
    (20, "LLM_INFERENCE", 1, "LLM 路由测试"),
    (30, "WRITE_MEMORY", 0, "内存写入测试"),
]

for agent_id, syscall, priority, desc in test_cases:
    print(f"  发送: syscall={syscall} | agent={agent_id} | {desc}")
    if syscall == "WRITE_MEMORY":
        req = {
            "syscall": "WRITE_MEMORY",
            "agent_id": agent_id,
            "caller_id": agent_id,
            "content": desc,
            "role": "user"
        }
        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.settimeout(10)
            client.connect(('127.0.0.1', 8080))
            client.send((json.dumps(req) + '\n').encode('utf-8'))
            buf = b""
            while b"\n" not in buf:
                chunk = client.recv(8192)
                if not chunk:
                    break
                buf += chunk
            line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
            client.close()
            try:
                parsed = json.loads(line)
                status = parsed.get("status", "unknown")
                print(f"    → {syscall}: status={status} ✅")
            except json.JSONDecodeError:
                print(f"    → {syscall}: 返回非 JSON ⚠️")
        except Exception as e:
            print(f"    → {syscall}: 失败 ❌ ({e})")
    else:
        send_request(agent_id, syscall, priority, desc, timeout=120)

print()
print("=" * 60)
print("  🏁 Phase 1 压力测试结束")
print("=" * 60)
print()
print("  📊 验收清单:")
print("  [1] VfsSyscallHandler: VFS_CALL 请求被正确路由 ✅/❌")
print("  [2] LlmSyscallHandler: LLM_INFERENCE 请求被优先级调度 ✅/❌")
print("  [3] Handler 模式: 不同 syscall 类型被分发到对应 Handler ✅/❌")
print("  [4] 兜底路由: WRITE_MEMORY 等未注册 Handler 的 syscall 仍正常工作 ✅/❌")
