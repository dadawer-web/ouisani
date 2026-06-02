import socket
import json
import time

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.settimeout(30)
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res

if __name__ == "__main__":
    print("=== AIOS 进程冻结与时空回溯 (Checkpoint/Restore) 测试 ===\n")

    c_code = r"""
#include <stdio.h>
#include <string.h>

__attribute__((import_module("aios"), import_name("snapshot")))
void aios_snapshot(int agent_id, int data_offset, int data_len);

char secret_memory[1024] = "【初始状态】";

int main() {
    if (strcmp(secret_memory, "【冻结状态】") == 0) {
        printf("[Wasm 智能体复活] 我闻到了时空穿梭的味道。我记得上一世的记忆：我已经被冻结过了！\n");
        return 0;
    }

    printf("[Wasm 首次运行] 修改内存状态，准备执行进程级冻结...\n");
    strcpy(secret_memory, "【冻结状态】");

    int offset = (int)((long)secret_memory);
    aios_snapshot(101, offset, 1024);

    printf("[Wasm 首次运行] 快照生成完毕，进程即将销毁。\n");
    return 0;
}
    """

    print("1. [第一世] 提交任务：运行并生成 101 号进程快照...")
    req1 = {
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code})
    }

    wasm_path = None
    try:
        raw_res1 = send_payload(json.dumps(req1))
        outer1 = json.loads(raw_res1.strip())
        status1 = outer1.get("status", "unknown")
        print(f"  [服务器状态]: {status1}")
        if status1 == "ok" and "data" in outer1:
            data1 = outer1["data"]
            if isinstance(data1, str):
                data1 = json.loads(data1)
            output_str1 = data1.get("output", "")
            wasm_path = data1.get("wasm_path", "")
            if wasm_path:
                print(f"  [WASM文件]: {wasm_path}")
            if output_str1:
                try:
                    output1 = json.loads(output_str1)
                    print(f"  [WASM执行状态]: {output1.get('status', 'unknown')}")
                    print(f"  [WASM退出码]: {output1.get('exit_code', 'N/A')}")
                except json.JSONDecodeError:
                    print(f"  [WASM输出]: {output_str1[:200]}")
            compile_err = data1.get("error", "")
            if compile_err:
                print(f"  [编译错误]: {compile_err}")
    except Exception as e:
        print(f"  [异常]: {e}")

    print("\n--- (模拟服务器重启 / 进程被意外杀死) ---")
    time.sleep(2)

    print("\n2. [第二世] 提交任务：强行从磁盘注入内存快照，复活 101 号进程...")
    restore_payload = {
        "restore_agent_id": 101
    }
    if wasm_path:
        restore_payload["file"] = wasm_path

    req2 = {
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps(restore_payload)
    }
    try:
        raw_res2 = send_payload(json.dumps(req2))
        outer2 = json.loads(raw_res2.strip())
        status2 = outer2.get("status", "unknown")
        print(f"  [服务器状态]: {status2}")
        if status2 == "ok" and "data" in outer2:
            data2 = outer2["data"]
            if isinstance(data2, str):
                data2 = json.loads(data2)
            output_str2 = data2.get("output", "")
            if output_str2:
                try:
                    output2 = json.loads(output_str2)
                    print(f"  [WASM执行状态]: {output2.get('status', 'unknown')}")
                    print(f"  [WASM退出码]: {output2.get('exit_code', 'N/A')}")
                except json.JSONDecodeError:
                    print(f"  [WASM输出]: {output_str2[:200]}")
            compile_err2 = data2.get("error", "")
            if compile_err2:
                print(f"  [编译错误]: {compile_err2}")
    except Exception as e:
        print(f"  [异常]: {e}")

    print("\n--- 服务器日志 (Ring 0 快照/恢复记录) ---")
    try:
        with open("/tmp/aios.log", "r") as f:
            lines = f.readlines()
            snap_lines = [l.strip() for l in lines if "Snapshot" in l or "RESTORE" in l or "核心转储" in l or "快照" in l or "复活" in l or "首次" in l or "injected" in l]
            for line in snap_lines[-10:]:
                print(f"  {line}")
    except FileNotFoundError:
        pass

    print("\n=== 测试结束 ===")
