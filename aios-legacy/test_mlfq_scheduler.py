import socket
import json
import threading
import time


def send_payload(name, payload, expect_fast=False):
    start = time.perf_counter()
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(120)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))
        res = client.recv(8192).decode('utf-8')
        client.close()
        cost = time.perf_counter() - start

        if expect_fast and cost > 1.0:
            print(f"  ❌ [{name}] 抢占失败！耗时 {cost:.2f}s，被低优先级任务堵死了！")
        elif expect_fast:
            print(f"  ✅ [{name}] 成功插队！耗时仅 {cost:.2f}s！返回: {res.strip()}")
        else:
            print(f"  [{name}] 后台处理完毕 (耗时 {cost:.2f}s)。")
    except Exception as e:
        cost = time.perf_counter() - start
        print(f"  ⚠️ [{name}] 异常 (耗时 {cost:.2f}s): {e}")


if __name__ == "__main__":
    print("=== AIOS 多级反馈队列 (MLFQ) 绝对抢占测试 ===\n")

    heavy_code = """
#include <stdio.h>
int main() {
    volatile long long sum = 0;
    for(long long i=0; i<800000000; i++) { sum += i; }
    return 0;
}
    """

    heavy_req = json.dumps({
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "agent_id": 100,
        "payload": json.dumps({"code": heavy_code})
    })

    print("1. 正在向内核疯狂倾泻 10 个极度耗时的 Wasm 重负载任务...")
    print("   (在旧版单队列内核中，系统此时已经完全瘫痪)\n")

    threads = []
    for i in range(10):
        t = threading.Thread(target=send_payload, args=(f"Q2_Wasm_Task_{i}", heavy_req, False))
        t.start()
        threads.append(t)

    time.sleep(1)

    print("\n2. [警报] 此时外部控制器发送极高优先级 (Q0) 的系统控制指令！")
    control_req = json.dumps({
        "syscall": "PROCESS_CTRL",
        "action": "SNAPSHOT",
        "agent_id": 0
    })

    t_ctrl = threading.Thread(target=send_payload, args=("Q0_控制指令", control_req, True))
    t_ctrl.start()
    t_ctrl.join()

    print("\n=== 测试结束 (你可以按 Ctrl+C 退出，或者等后台慢慢跑完) ===")
