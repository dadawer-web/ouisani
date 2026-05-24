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
    print("=== AIOS 虚拟网络栈穿透测试 ===")

    c_code = r"""
#include <stdio.h>
#include <string.h>

__attribute__((import_module("aios"), import_name("http_get")))
int aios_http_get(const char* host, int host_len, const char* path, int path_len, char* resp_buf, int max_len);

int main() {
    printf("[Wasm] 准备突破沙盒，请求互联网数据...\n");

    const char* host = "httpbin.org";
    const char* path = "/get?aios=awesome";

    char response[2048];
    memset(response, 0, 2048);

    int written_bytes = aios_http_get(host, strlen(host), path, strlen(path), response, 2048);

    if (written_bytes > 0) {
        printf("[Wasm] 网络请求成功！接收到 %d 字节数据。\n", written_bytes);
        printf("[Wasm] 数据内容:\n%s\n", response);
    } else {
        printf("[Wasm] 网络请求失败！错误码: %d\n", written_bytes);
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

    print("正在把网络爬虫代码打入内核...\n")
    start = time.perf_counter()

    try:
        raw_res = send_payload(json.dumps(req))
    except socket.timeout:
        print("❌ 请求超时！服务器未在30秒内响应。")
        exit(1)
    except ConnectionRefusedError:
        print("❌ 连接被拒绝！服务器未启动。")
        exit(1)

    cost = time.perf_counter() - start
    print(f"【耗时】{cost*1000:.2f}ms\n")

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
            wasm_path = data.get("wasm_path", "")
            output_str = data.get("output", "")

            print(f"【阶段】{stage}")
            print(f"【编译】{'✅ 成功' if compile_ok else '❌ 失败'}")
            if wasm_path:
                print(f"【WASM】{wasm_path}")

            if output_str:
                try:
                    output = json.loads(output_str)
                    wasm_status = output.get("status", "unknown")
                    exit_code = output.get("exit_code", "N/A")
                    return_val = output.get("return_value", "N/A")
                    print(f"【WASM执行状态】{wasm_status}")
                    print(f"【WASM退出码】{exit_code}")
                    if return_val != "N/A":
                        print(f"【WASM返回值】{return_val}")
                        if isinstance(return_val, int) and return_val > 0:
                            print(f"\n🎉 网络穿透成功！Wasm 沙盒通过宿主 API 获取了 {return_val} 字节的互联网数据！")
                        elif isinstance(return_val, int) and return_val < 0:
                            err_map = {
                                -1: "内存指针无效",
                                -2: "HTTP 请求失败（网络错误/超时）",
                                -3: "HTTP 状态码非 200",
                                -4: "响应缓冲区指针无效"
                            }
                            print(f"\n⚠️ 网络请求失败: {err_map.get(return_val, '未知错误')}")
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

    print("\n--- 服务器日志 (Ring 0 网络穿透记录) ---")
    try:
        with open("/tmp/aios.log", "r") as f:
            lines = f.readlines()
            net_lines = [l.strip() for l in lines if "Net" in l or "http_get" in l or "Wasm" in l]
            for line in net_lines[-15:]:
                print(f"  {line}")
    except FileNotFoundError:
        pass

    print("\n=== 测试结束 ===")
