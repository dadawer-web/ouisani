import socket
import json
import threading
import time

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.settimeout(30)
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res

def run_agent_worker():
    c_code = r"""
#include <stdio.h>

__attribute__((import_module("aios"), import_name("check_signal")))
int aios_check_signal(int agent_id);

int main() {
    printf("[Wasm 101] 我是一个长时间运行的后台任务，开始工作...\n");

    for (int i = 1; i <= 10; i++) {
        volatile long long sum = 0;
        for(long long j=0; j<1000000; j++) { sum += j; }

        printf("[Wasm 101] 正在处理第 %d 批数据...\n", i);

        int sig = aios_check_signal(101);
        if (sig == 15) {
            printf("\n[Wasm 101 收到 SIGTERM (15) 终止信号]\n");
            printf("[Wasm 101] 触发异常处理流：正在保存进度... 释放内存... 关闭文件...\n");
            printf("[Wasm 101] 现场清理完毕，体面地自杀。Farewell!\n");
            return 0;
        }
    }

    printf("[Wasm 101] 任务自然结束。\n");
    return 0;
}
    """
    req = {
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code})
    }
    try:
        raw_res = send_payload(json.dumps(req))
        outer = json.loads(raw_res.strip())
        status = outer.get("status", "unknown")
        print(f"  [服务器状态]: {status}")
        if status == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "")
            if output_str:
                try:
                    output = json.loads(output_str)
                    wasm_status = output.get("status", "unknown")
                    exit_code = output.get("exit_code", "N/A")
                    print(f"  [WASM执行状态]: {wasm_status}")
                    print(f"  [WASM退出码]: {exit_code}")
                    if wasm_status == "ok" and exit_code == 0:
                        print("\n  ✅ Agent 收到 SIGTERM 后优雅退出！")
                except json.JSONDecodeError:
                    print(f"  [WASM输出]: {output_str[:300]}")
            compile_err = data.get("error", "")
            if compile_err:
                print(f"  [编译错误]: {compile_err}")
    except Exception as e:
        print(f"  [Agent Worker 异常]: {e}")

def send_kill_signal():
    time.sleep(1.5)
    print("\n>>> [外部控制器] 发现 101 号任务不需要了，发送取消指令 (触发 SIGTERM) <<<")
    req = {
        "syscall": "CANCEL_TASK",
        "agent_id": 0,
        "target_agent_id": 101
    }
    try:
        raw_res = send_payload(json.dumps(req))
        outer = json.loads(raw_res.strip())
        print(f">>> [CANCEL_TASK 响应]: {outer.get('status', 'unknown')} - {outer.get('message', '')}")
    except Exception as e:
        print(f">>> [CANCEL_TASK 异常]: {e}")

if __name__ == "__main__":
    print("=== AIOS POSIX 软件中断与优雅退出测试 ===\n")

    t1 = threading.Thread(target=run_agent_worker)
    t2 = threading.Thread(target=send_kill_signal)

    t1.start()
    t2.start()

    t1.join()
    t2.join()

    print("\n--- 服务器日志 (Ring 0 信号投递记录) ---")
    try:
        with open("/tmp/aios.log", "r", errors="replace") as f:
            lines = f.readlines()
            sig_lines = [l.strip() for l in lines if "Signal" in l or "SIGTERM" in l or "signal" in l or "正在处理" in l or "Farewell" in l or "清理" in l]
            for line in sig_lines[-15:]:
                print(f"  {line}")
    except FileNotFoundError:
        pass

    print("\n=== 测试结束 ===")
