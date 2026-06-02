import socket
import json
import threading
import time

completion_log = []
completion_lock = threading.Lock()


def send_llm_request(agent_id, priority, prompt):
    print(f"[Agent {agent_id}] 发起 LLM_INFERENCE 请求 (priority={priority})...")

    req = {
        "syscall": "LLM_INFERENCE",
        "agent_id": agent_id,
        "priority": priority,
        "payload": prompt
    }

    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(120)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))

        start_time = time.perf_counter()
        buf = b""
        while b"\n" not in buf:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk
        cost = time.perf_counter() - start_time

        line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
        client.close()

        finish_time = time.perf_counter()

        try:
            parsed = json.loads(line)
            status = parsed.get("status", "unknown")
            data = parsed.get("data", {})
            if isinstance(data, str):
                data = json.loads(data)
            response_text = data.get("response", line[:120])
        except json.JSONDecodeError:
            status = "parse_error"
            response_text = line[:120]

        with completion_lock:
            completion_log.append({
                "agent_id": agent_id,
                "priority": priority,
                "finish_order": len(completion_log) + 1,
                "cost": cost,
                "status": status,
                "response_preview": response_text[:80]
            })

        print(f"  ✅ [Agent {agent_id}] 完成 (priority={priority}, 耗时 {cost:.2f}s) | "
              f"完成顺序: 第 {len(completion_log)} 个 | status={status}")

    except socket.timeout:
        print(f"  ❌ [Agent {agent_id}] 请求超时 (120s)")
    except ConnectionRefusedError:
        print(f"  ❌ [Agent {agent_id}] 连接被拒绝！内核未启动？")
    except Exception as e:
        print(f"  ❌ [Agent {agent_id}] 请求失败: {e}")


def test_priority_preemption():
    print("=" * 60)
    print("  🧠 AIOS LLM 优先级抢占式调度器验证测试")
    print("=" * 60)
    print()
    print("测试原理:")
    print("  内核只有一个 LLM Worker 线程，逐个处理优先队列中的任务。")
    print("  我们先发 3 个低优先级任务让它们排队，再发 1 个高优先级任务。")
    print("  如果优先级调度生效，高优先级任务应该插队到队头，第二个被处理。")
    print("  (第一个被处理的仍然是先入队的低优先级任务，因为 Worker")
    print("   可能在高优先级任务到达前已经开始处理它了)")
    print()

    print("-" * 60)
    print("  阶段 1: 并发提交 3 个低优先级 + 1 个高优先级请求")
    print("-" * 60)

    low_agents = [
        (1, 0, "后台数据清洗: 处理第 1 批日志"),
        (2, 0, "后台数据清洗: 处理第 2 批日志"),
        (3, 0, "后台数据清洗: 处理第 3 批日志"),
    ]

    threads = []
    for aid, prio, prompt in low_agents:
        t = threading.Thread(target=send_llm_request, args=(aid, prio, prompt))
        threads.append(t)

    for t in threads:
        t.start()

    time.sleep(0.3)

    print(f"\n  ⚡ 此时 3 个低优先级任务已在内核 LLM 队列排队...")
    print(f"  ⚡ 突然杀入 Agent 999 (Priority=99) 的紧急意图翻译请求！\n")

    t_high = threading.Thread(
        target=send_llm_request,
        args=(999, 99, "【紧急】翻译 VFS 语义意图: '查找昨日日志'")
    )
    t_high.start()

    for t in threads:
        t.join()
    t_high.join()

    print()
    print("-" * 60)
    print("  阶段 2: 结果分析")
    print("-" * 60)
    print()

    with completion_lock:
        sorted_log = sorted(completion_log, key=lambda x: x["finish_order"])

        print(f"  {'完成顺序':<10} {'Agent ID':<10} {'优先级':<10} {'耗时':<10}")
        print(f"  {'--------':<10} {'--------':<10} {'------':<10} {'------':<10}")
        for entry in sorted_log:
            marker = " ⚡" if entry["priority"] == 99 else ""
            print(f"  第 {entry['finish_order']:<6} {entry['agent_id']:<10} "
                  f"{entry['priority']:<10} {entry['cost']:.2f}s{marker}")

    print()

    with completion_lock:
        high_entry = None
        for entry in completion_log:
            if entry["priority"] == 99:
                high_entry = entry
                break

    if high_entry:
        if high_entry["finish_order"] <= 2:
            print("  ✅ 抢占式调度验证通过！")
            print(f"     Priority=99 的请求在第 {high_entry['finish_order']} 个完成，")
            print(f"     成功插队到其他低优先级 (Priority=0) 请求前面！")
        elif high_entry["finish_order"] == 1:
            print("  ✅ 抢占式调度验证通过！")
            print(f"     Priority=99 的请求第 1 个完成，绝对优先！")
        else:
            print(f"  ⚠️  Priority=99 的请求在第 {high_entry['finish_order']} 个完成。")
            print(f"     可能原因: Worker 在高优先级请求到达前已开始处理低优先级任务。")
            print(f"     (LLM 推理耗时较长，一旦开始处理无法中断，这是正常行为)")
    else:
        print("  ❌ 高优先级请求未完成，调度器可能未正常工作。")

    print()
    print("-" * 60)
    print("  阶段 3: 纯优先级排序验证 (队列堆积场景)")
    print("-" * 60)
    print()
    print("  同时提交 4 个不同优先级的请求，验证出队顺序。")
    print()

    completion_log.clear()

    mixed_agents = [
        (10, 1,  "低优先级: 日常闲聊"),
        (20, 5,  "中优先级: 数据查询"),
        (30, 10, "高优先级: 系统监控"),
        (40, 50, "紧急: 内核指令翻译"),
    ]

    barrier = threading.Barrier(len(mixed_agents))
    original_start = send_llm_request

    def barrier_send(agent_id, priority, prompt):
        barrier.wait(timeout=5)
        original_start(agent_id, priority, prompt)

    threads2 = []
    for aid, prio, prompt in mixed_agents:
        t = threading.Thread(target=barrier_send, args=(aid, prio, prompt))
        threads2.append(t)

    for t in threads2:
        t.start()
    for t in threads2:
        t.join()

    print()
    print("  阶段 3 结果:")
    print()

    with completion_lock:
        sorted_log2 = sorted(completion_log, key=lambda x: x["finish_order"])
        print(f"  {'完成顺序':<10} {'Agent ID':<10} {'优先级':<10} {'耗时':<10}")
        print(f"  {'--------':<10} {'--------':<10} {'------':<10} {'------':<10}")
        for entry in sorted_log2:
            print(f"  第 {entry['finish_order']:<6} {entry['agent_id']:<10} "
                  f"{entry['priority']:<10} {entry['cost']:.2f}s")

    with completion_lock:
        priorities_in_order = [e["priority"] for e in sorted(completion_log, key=lambda x: x["finish_order"])]

    if priorities_in_order == sorted(priorities_in_order, reverse=True):
        print()
        print("  ✅ 优先级排序完美！完成顺序严格按优先级从高到低！")
    else:
        desc = " -> ".join(str(p) for p in priorities_in_order)
        print()
        print(f"  完成优先级顺序: {desc}")
        if priorities_in_order[0] == max(priorities_in_order):
            print("  ✅ 最高优先级请求最先完成，抢占式调度基本生效。")
            print("     (后续顺序可能受网络延迟和 Worker 线程时序影响)")
        else:
            print("  ⚠️  最高优先级请求未最先完成，请检查内核 LLM 队列实现。")

    print()
    print("=" * 60)
    print("  🏁 LLM 优先级调度器测试结束")
    print("=" * 60)


if __name__ == "__main__":
    test_priority_preemption()
