import socket
import json
import time
import threading

def send_syscall(req_dict):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req_dict) + '\n').encode('utf-8'))
        res = client.recv(81920).decode('utf-8')
        client.close()
        return json.loads(res) if "{" in res else res
    except Exception as e:
        return {}

def dashboard_worker(stop_event):
    print("🖥️  [仪表盘] 已连接到内核 /proc/events，正在监听系统心跳...\n")
    while not stop_event.is_set():
        res = send_syscall({
            "syscall": "VFS_CALL",
            "action": "READ",
            "path": "/proc/events",
            "caller_id": 0
        })
        events = res.get("events", [])
        if events:
            for ev in events:
                etype = ev.get("type", "?")
                source = ev.get("source", "?")
                message = ev.get("message", "")
                print(f"   📡 [内核 Hook] [{etype}] {source}: {message}")
        time.sleep(1)

def rag_worker():
    time.sleep(2)
    VECTOR_PATH = "/dev/vec_mem_101"

    print("✍️  [Agent 101] 开始写入含有真实 Embedding 的日记...")
    diaries = [
        "我今天在研究 WebAssembly 的底层内存隔离机制，发现 Gas 计费非常有效。",
        "午饭去楼下吃了一碗牛肉面，加了很多辣椒，味道很赞。",
        "大模型并发请求太多了，我用 C++ 写了一个抢占式的优先级调度队列来解决这个问题。"
    ]

    for i, text in enumerate(diaries):
        print(f"\n   ✍️ 写入日记 {i+1}: {text[:30]}...")
        send_syscall({
            "syscall": "VFS_CALL",
            "action": "WRITE",
            "path": VECTOR_PATH,
            "payload": text,
            "caller_id": 101
        })
        time.sleep(2)

    print("\n🔍 [应用层] 发起真实高维空间检索: '系统并发卡顿怎么办？'")
    query = "如何解决多个 Agent 同时请求导致的系统拥堵和算力分配问题？"

    res = send_syscall({
        "syscall": "VFS_CALL",
        "action": "SEARCH",
        "path": VECTOR_PATH,
        "payload": query,
        "caller_id": 101
    })

    print("\n======================================================")
    print("  ✅ [检索结果] 最相关的记忆:")
    results = res.get("results", [])
    if results:
        for r in results:
            score = r.get("score", "?")
            text = r.get("text", "")
            print(f"  >> score={score} | {text[:70]}")
    else:
        print(f"  >> {res}")
    print("======================================================\n")

if __name__ == "__main__":
    print("🚀 启动 AIOS 神经系统测试...")
    print("======================================================\n")

    stop_event = threading.Event()
    t_dash = threading.Thread(target=dashboard_worker, args=(stop_event,), daemon=True)
    t_rag = threading.Thread(target=rag_worker)

    t_dash.start()
    t_rag.start()
    t_rag.join()
    time.sleep(3)
    stop_event.set()
    time.sleep(1)
    print("🏁 测试结束。")
