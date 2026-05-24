import socket
import json
import threading
import time


def send_payload(agent_id, payload):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(120)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))
        res = client.recv(8192).decode('utf-8')
        client.close()
        print(f"[Agent {agent_id} 返回]: {res[:120]}...")
    except Exception as e:
        print(f"[Agent {agent_id} 异常]: {e}")


if __name__ == "__main__":
    print("=== AIOS 缺页中断与 Swap 机制 (kswapd) 极限测试 ===\n")
    print("【系统设定】当前物理内存最多只能同时容纳 2 个 Wasm 虚拟机。\n")

    heavy_code = """
#include <stdio.h>
int main() {
    printf("[Wasm] 成功获取物理内存，开始疯狂运算...\\n");
    volatile long long sum = 0;
    for(long long i=0; i<600000000; i++) { sum += i; }
    printf("[Wasm] 运算结束，释放物理内存。\\n");
    return 0;
}
    """

    print("🚀 正在瞬间唤醒 5 个 Agent 并发执行重度计算任务...")
    print("   (在没有 Swap 的旧系统中，内存将瞬间 OOM 崩溃！)\n")

    threads = []
    for agent_id in range(101, 106):
        req = json.dumps({
            "syscall": "VFS_CALL",
            "action": "COMPILE_AND_EXECUTE",
            "agent_id": agent_id,
            "payload": json.dumps({"code": heavy_code})
        })
        t = threading.Thread(target=send_payload, args=(agent_id, req))
        threads.append(t)
        t.start()
        time.sleep(0.1)

    for t in threads:
        t.join()

    print("\n=== 测试结束 ===")
