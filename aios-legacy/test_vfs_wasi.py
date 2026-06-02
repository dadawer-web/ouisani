import socket
import json
import time
import os

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.settimeout(30)
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res

if __name__ == "__main__":
    print("=== AIOS WASI 目录穿透与持久化存储测试 ===")

    host_file_path = "/tmp/aios_workspace/agent_secret.txt"

    if os.path.exists(host_file_path):
        os.remove(host_file_path)

    c_code = r"""
#include <stdio.h>

int main() {
    printf("[Wasm 内部] 正在尝试穿透沙盒，将情报写入 /workspace/agent_secret.txt\n");

    FILE *f = fopen("/workspace/agent_secret.txt", "w");
    if (f == NULL) {
        printf("[Wasm 内部] 失败: 没有权限或目录未挂载！\n");
        return 1;
    }

    fprintf(f, "【绝密情报】: 这是 102 号 Agent 在内存沙盒中计算出的核心数据。\n");
    fprintf(f, "跨次元传输成功，AIOS VFS Preopen 工作正常！\n");
    fclose(f);

    printf("[Wasm 内部] 文件写入完成！\n");
    return 0;
}
    """

    req = {
        "syscall": "VFS_CALL",
        "agent_id": 102,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code})
    }

    print("1. 正在将代码注入 Wasm 结界...")
    try:
        raw_res = send_payload(json.dumps(req))
    except socket.timeout:
        print("❌ 请求超时！服务器未在30秒内响应。")
        print("   请检查 aios_core 是否正在运行: ./build/aios_core")
        exit(1)
    except ConnectionRefusedError:
        print("❌ 连接被拒绝！服务器未启动。")
        print("   请先启动: ./build/aios_core")
        exit(1)

    print(f"\n[服务器原始响应]: {raw_res[:500]}")

    try:
        outer = json.loads(raw_res.strip())
        status = outer.get("status", "unknown")
        print(f"[响应状态]: {status}")

        if status == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)
            if isinstance(data, dict):
                stage = data.get("stage", "")
                output = data.get("output", "")
                print(f"[阶段]: {stage}")
                if output:
                    try:
                        output_json = json.loads(output)
                        print(f"[WASM执行状态]: {output_json.get('status', 'unknown')}")
                        print(f"[WASM退出码]: {output_json.get('exit_code', 'N/A')}")
                    except json.JSONDecodeError:
                        print(f"[WASM输出]: {output[:200]}")
                compile_err = data.get("error", "")
                if compile_err:
                    print(f"[编译错误]: {compile_err}")
        elif status == "error":
            msg = outer.get("message", outer.get("data", "未知错误"))
            print(f"[错误信息]: {msg}")
    except json.JSONDecodeError as e:
        print(f"[响应解析异常]: {e}")

    print("\n2. [宿主机视角] 验证次元壁是否被打破...")
    time.sleep(0.5)

    if os.path.exists(host_file_path):
        print(f"✅ 成功！在宿主机的 {host_file_path} 发现了文件。文件内容如下：")
        print("-" * 40)
        with open(host_file_path, "r") as f:
            print(f.read())
        print("-" * 40)
        print("结论：Wasm 智能体已经拥有了受控的宿主机文件系统访问权限！")
    else:
        print("❌ 失败：在宿主机上找不到该文件，WASI 映射未生效。")
        print("   可能原因：")
        print("   1. WASI SDK 未安装 (需要 /opt/wasi-sdk)")
        print("   2. 编译失败 (检查服务器日志)")
        print("   3. WASM 执行失败 (检查 exit_code)")

    print("\n=== 测试结束 ===")
