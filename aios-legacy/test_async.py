import socket
import threading
import time
import json

def send_payload(name, payload, expect_delay=False):
    print(f"[{name}] 开始发送系统调用...")
    start = time.perf_counter()
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.send(payload.encode('utf-8'))
    res = client.recv(8192).decode('utf-8')
    client.close()
    cost = time.perf_counter() - start
    tag = "⏱️ 慢" if cost > 1.0 else "⚡ 快"
    print(f"[{name}] {tag} 收到返回! 耗时: {cost:.2f} 秒. 结果: {res[:80]}")

if __name__ == "__main__":
    heavy_code = """
#include <stdio.h>
int main() {
    volatile long long sum = 0;
    for(long long i=0; i<300000000; i++) { sum += i; }
    printf("Done: %lld\\n", sum);
    return 0;
}
    """

    heavy_req = json.dumps({
        "syscall": "VFS_CALL",
        "agent_id": 301,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": heavy_code, "func": "_start"})
    }) + "\n"

    light_req = json.dumps({
        "syscall": "CANCEL_TASK",
        "agent_id": 999
    }) + "\n"

    print("=" * 70)
    print("  AIOS 异步调度与脏队列抢占测试")
    print("  线程1: 耗时 Wasm 任务 (3亿次循环, 进入 wasm_pool_)")
    print("  线程2: 轻量抢占任务 (CANCEL_TASK, 走主调度器)")
    print("  预期: 线程2 瞬间返回, 线程1 数秒后返回")
    print("=" * 70)

    t1 = threading.Thread(target=send_payload, args=("耗时Wasm任务", heavy_req))
    t1.start()

    time.sleep(0.5)

    t2 = threading.Thread(target=send_payload, args=("轻量抢占任务", light_req))
    t2.start()

    t1.join()
    t2.join()

    print("\n" + "=" * 70)
    print("  测试结束")
    print("=" * 70)
