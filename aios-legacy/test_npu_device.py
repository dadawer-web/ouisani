import socket
import json
import time

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.settimeout(120)
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(131072).decode('utf-8')
    client.close()
    return res

if __name__ == "__main__":
    print("=== AIOS NPU 硬件抽象层 (虚拟设备驱动) 测试 ===\n")

    c_code = r"""
#include <stdio.h>
#include <string.h>

__attribute__((import_module("aios"), import_name("npu_infer")))
int aios_npu_infer(const char* prompt, int prompt_len, char* resp_buf, int max_len);

int main() {
    printf("[Wasm Agent] 遇到未知数据，准备调用底层 NPU 算力...\n");

    const char* task_prompt = "请用一句话解释什么是 WebAssembly？";
    char response[2048] = {0};

    printf("[Wasm Agent] 正在唤醒 /dev/npu，发起推理请求 (这可能需要几秒钟)...\n");

    int bytes_written = aios_npu_infer(task_prompt, strlen(task_prompt), response, 2048);

    if (bytes_written > 0) {
        printf("\n[Wasm Agent] NPU 推理完成！获取到 %d 字节的智慧结晶：\n", bytes_written);
        printf("----------------------------------------\n");
        printf("%s\n", response);
        printf("----------------------------------------\n");
    } else {
        printf("[Wasm Agent] NPU 设备离线或调用失败！错误码: %d\n", bytes_written);
    }

    return 0;
}
    """

    req = {
        "syscall": "VFS_CALL",
        "agent_id": 0,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({"code": c_code})
    }

    print("正在把自带'思考能力'的 C 代码打入内核...\n")
    start = time.perf_counter()

    try:
        raw_res = send_payload(json.dumps(req))
    except socket.timeout:
        print("❌ 请求超时！NPU 推理可能耗时过长。")
        exit(1)
    except ConnectionRefusedError:
        print("❌ 连接被拒绝！服务器未启动。")
        exit(1)

    cost = time.perf_counter() - start
    print(f"【系统级总耗时】{cost:.2f} 秒\n")

    try:
        outer = json.loads(raw_res.strip())
        status = outer.get("status", "unknown")
        print(f"【服务器状态】{status}")

        if status == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data, str):
                data = json.loads(data)

            stage = data.get("stage", "")
            compile_ok = data.get("compile", False)
            output_str = data.get("output", "")

            print(f"【阶段】{stage}")
            print(f"【编译】{'✅ 成功' if compile_ok else '❌ 失败'}")

            if output_str:
                try:
                    output = json.loads(output_str)
                    wasm_status = output.get("status", "unknown")
                    exit_code = output.get("exit_code", "N/A")
                    return_val = output.get("return_value", "N/A")
                    print(f"【WASM执行状态】{wasm_status}")
                    print(f"【WASM退出码】{exit_code}")
                    if isinstance(return_val, int) and return_val > 0:
                        print(f"\n🎉 NPU 算力下放成功！Wasm 沙盒通过虚拟设备驱动获取了 {return_val} 字节的 AI 推理结果！")
                    elif isinstance(return_val, int) and return_val < 0:
                        err_map = {
                            -1: "内存指针无效",
                            -2: "响应缓冲区指针无效"
                        }
                        print(f"\n⚠️ NPU 调用失败: {err_map.get(return_val, '未知错误')}")
                except json.JSONDecodeError:
                    print(f"【WASM输出】{output_str[:300]}")

            compile_err = data.get("error", "")
            if compile_err:
                print(f"【编译错误】{compile_err}")

        elif status == "error":
            msg = outer.get("message", "未知错误")
            print(f"【错误信息】{msg}")

    except json.JSONDecodeError as e:
        print(f"【响应解析异常】{e}")
        print(f"【原始响应】{raw_res[:500]}")

    print("\n--- 服务器日志 (Ring 0 NPU 驱动记录) ---")
    try:
        with open("/tmp/aios.log", "r", errors="replace") as f:
            lines = f.readlines()
            npu_lines = [l.strip() for l in lines if "NPU" in l or "npu" in l or "LLM" in l or "推理" in l]
            for line in npu_lines[-10:]:
                print(f"  {line}")
    except FileNotFoundError:
        pass

    print("\n=== 测试结束 ===")
