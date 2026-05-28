import socket
import json
import threading
import time

results = []
results_lock = threading.Lock()

def send_request(agent_id, syscall, priority, payload):
    req = {
        "syscall": syscall,
        "caller_id": agent_id,
        "priority": priority,
        "payload": payload
    }
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))

        start = time.perf_counter()
        res = client.recv(8192).decode('utf-8')
        cost = time.perf_counter() - start

        finish_order = 0
        with results_lock:
            results.append(agent_id)
            finish_order = len(results)

        print(f"[Agent {agent_id}] 优先级={priority} | 完成顺序=第{finish_order}个 | 耗时: {cost:.2f}s | 结果: {res.strip()[:120]}")
        client.close()
    except Exception as e:
        print(f"[Agent {agent_id}] 请求失败: {e}")

print("======================================================")
print("  🧠 AIOS 可插拔策略调度器 (Pluggable Scheduler) 测试")
print("======================================================\n")
print("发送 3 个低优先级任务 (先发)，和 1 个高优先级任务 (后发)。\n")

threads = []
for i in range(1, 4):
    t = threading.Thread(target=send_request, args=(i, "LLM_INFERENCE", 0, f"普通任务 {i}"))
    threads.append(t)

for t in threads:
    t.start()
time.sleep(0.3)

t_boss = threading.Thread(target=send_request, args=(999, "LLM_INFERENCE", 99, "【紧急最高级任务】"))
t_boss.start()

for t in threads:
    t.join()
t_boss.join()

print(f"\n📊 完成顺序: {results}")
if results[0] == 999:
    print("✅ 高优先级 Agent 999 抢占成功！当前大脑 = PrioritySchedulerStrategy")
else:
    print("✅ 先来后到，公平排队！当前大脑 = FifoSchedulerStrategy")

print("\n======================================================")
