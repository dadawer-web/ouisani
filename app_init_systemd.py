import socket
import json
import time


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(60)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res


def LLM_translate_intent_to_c_pipeline(natural_language):
    print(f"\n[PID 1 Init 进程] 正在解析意图: '{natural_language}'")
    time.sleep(1)

    print("[PID 1 Init 进程] 意图分析完毕，决定采用经典 Unix 管道架构: [数据采集 Agent] -> [数据分析 Agent]")

    code_a = """
#include <stdio.h>
int main() {
    printf("APPLE:150.2\\n");
    printf("TESLA:210.5\\n");
    printf("NVIDIA:890.1\\n");
    printf("BTC:68000.0\\n");
    return 0;
}
    """

    code_b = """
#include <stdio.h>
#include <string.h>

int main() {
    char buffer[256];
    printf("【AIOS 自动化管道分析报告】\\n");
    printf("--------------------------------\\n");

    while (scanf("%s", buffer) != EOF) {
        if (strstr(buffer, "BTC")) {
            printf("🚨 警报: 发现加密货币资产 [%s]，波动风险极高！\\n", buffer);
        } else {
            printf("✅ 传统资产: %s 状态良好。\\n", buffer);
        }
    }
    printf("--------------------------------\\n");
    return 0;
}
    """
    return code_a, code_b


if __name__ == "__main__":
    print("==================================================")
    print("   🌐 AIOS PID 1 (Init Daemon) 创世守护进程启动   ")
    print("==================================================\n")

    user_intent = "帮我获取一下目前的资产列表，并且把里面的比特币标红高危预警。"

    code_a, code_b = LLM_translate_intent_to_c_pipeline(user_intent)

    print("[PID 1 Init 进程] 软件编写完成。正在向下层 C++ 内核下发 PIPE_EXECUTE 系统调用...")

    req = {
        "syscall": "VFS_CALL",
        "action": "PIPE_EXECUTE",
        "agent_id": 100,
        "payload": json.dumps({
            "codes": [code_a, code_b]
        })
    }

    start = time.perf_counter()
    raw_res = send_payload(json.dumps(req))
    cost = time.perf_counter() - start

    print(f"\n[PID 1 Init 进程] 内核执行完毕 (耗时 {cost*1000:.2f}ms)！回收临时进程。")
    print("\n【最终呈现给用户的执行结果】:")

    try:
        parsed = json.loads(raw_res)
        data_str = parsed.get("data", "{}")
        try:
            data = json.loads(data_str)
            pipe_output = data.get("pipe_output", "")
            if pipe_output:
                print(pipe_output)
            else:
                print(data_str)
        except:
            print(data_str)
    except:
        print(raw_res)
