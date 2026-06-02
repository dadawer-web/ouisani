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


def mock_llm(attempt, error_msg=None):
    if attempt == 1:
        print("[LLM 思考中...] 生成初始代码 (故意漏掉分号)")
        return """
#include <stdio.h>
int main() {
    printf("Hello AIOS, I am learning to write C code!\\n")
    return 0;
}
        """
    else:
        print(f"\n[LLM 收到报错] 哎呀，底层 Clang 骂我了：\n{error_msg[:150]}...\n")
        print("[LLM 思考中...] 懂了，第 4 行少了分号，马上修复！")
        return """
#include <stdio.h>
int main() {
    printf("Hello AIOS, I am learning to write C code!\\n");
    return 0;
}
        """


def extract_compile_error(raw_res):
    try:
        parsed = json.loads(raw_res)
        data = parsed.get("data", {})
        if isinstance(data, str):
            data = json.loads(data)
        error = data.get("error", "")
        if error:
            return error, True
        message = parsed.get("message", "")
        if "failed" in message.lower() or "compilation" in message.lower():
            return message, True
        return "", False
    except:
        return "", False


if __name__ == "__main__":
    print("=== AIOS 第 3 阶段：Agentic 编译自我修正循环测试 ===\n")

    intent = "写一个打印 Hello AIOS 的 C 程序"
    code = mock_llm(attempt=1)

    max_retries = 3
    for attempt in range(1, max_retries + 1):
        print(f"\n🚀 [Agent 调度器] 第 {attempt} 次向内核提交编译请求...")

        req = {
            "syscall": "VFS_CALL",
            "action": "COMPILE_AND_EXECUTE",
            "agent_id": 600,
            "payload": json.dumps({"code": code, "func": "_start"})
        }

        start_time = time.perf_counter()
        raw_res = send_payload(json.dumps(req))
        cost = time.perf_counter() - start_time

        error_msg, is_error = extract_compile_error(raw_res)

        if is_error:
            print(f"❌ [内核拒绝] 耗时 {cost:.2f}s。编译失败！")
            print(f"   Clang 报错: {error_msg[:120]}...")

            if attempt < max_retries:
                code = mock_llm(attempt=2, error_msg=error_msg)
            else:
                print("\n💀 已达最大重试次数，放弃。")
        else:
            print(f"✅ [内核放行] 耗时 {cost:.2f}s。执行成功！")
            print("------------------------------------------------")

            try:
                parsed = json.loads(raw_res)
                data = parsed.get("data", {})
                if isinstance(data, str):
                    data = json.loads(data)
                output_str = data.get("output", "")
                if output_str:
                    try:
                        out = json.loads(output_str)
                        print(f"  WASM 状态: {out.get('status')}")
                        if out.get('status') == 'ok':
                            print("  🎉 Hello AIOS 程序运行成功！")
                    except:
                        print(f"  输出: {output_str[:300]}")
            except:
                print(raw_res[:300])

            print("------------------------------------------------")
            break
