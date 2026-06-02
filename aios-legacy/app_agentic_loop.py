import socket
import json
import time
import os
import sys

WASI_SYSTEM_PROMPT = """你是一个运行在 AIOS 操作系统内的 C 代码生成器。
你的代码将被编译为 WebAssembly (WASM) 并在 WASI 沙箱中执行。

严格限制：
- 只能使用标准 C 库进行计算和标准 I/O（printf/scanf/puts/getchar 等）
- 禁止调用任何系统级网络库（socket/connect/bind 等）
- 禁止调用 pthread 或多线程相关函数
- 禁止调用 system()/popen()/fork()/exec() 等进程管理函数
- 禁止使用 main() 以外的入口函数
- 所有输出通过 printf 写到 STDOUT

输出格式：只输出纯 C 代码，不要任何解释文字。"""


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(60)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res


def llm_generate_c_code(intent):
    if not llm_available():
        print("[Agentic Loop] LLM 不可用，使用内置模板代码...")
        return fallback_code(intent)

    import openai
    client = openai.OpenAI(
        api_key=os.environ.get("OPENAI_API_KEY", ""),
        base_url=os.environ.get("OPENAI_BASE_URL", "https://api.openai.com"),
    )

    resp = client.chat.completions.create(
        model=os.environ.get("OPENAI_MODEL", "gpt-3.5-turbo"),
        messages=[
            {"role": "system", "content": WASI_SYSTEM_PROMPT},
            {"role": "user", "content": f"请为以下需求编写 C 代码：{intent}"}
        ],
        temperature=0.2,
    )

    code = resp.choices[0].message.content.strip()
    if code.startswith("```c"):
        code = code[5:]
    if code.startswith("```"):
        code = code[3:]
    if code.endswith("```"):
        code = code[:-3]
    return code.strip()


def llm_correct_code(error_msg, original_code):
    if not llm_available():
        return original_code

    import openai
    client = openai.OpenAI(
        api_key=os.environ.get("OPENAI_API_KEY", ""),
        base_url=os.environ.get("OPENAI_BASE_URL", "https://api.openai.com"),
    )

    resp = client.chat.completions.create(
        model=os.environ.get("OPENAI_MODEL", "gpt-3.5-turbo"),
        messages=[
            {"role": "system", "content": WASI_SYSTEM_PROMPT},
            {"role": "user", "content": f"请为以下需求编写 C 代码：{original_code}"},
            {"role": "assistant", "content": original_code},
            {"role": "user", "content": f"你写的 C 代码编译失败了，底层 Clang 报错如下：\n{error_msg}\n请修复代码并重新输出纯 C 代码。"}
        ],
        temperature=0.1,
    )

    code = resp.choices[0].message.content.strip()
    if code.startswith("```c"):
        code = code[5:]
    if code.startswith("```"):
        code = code[3:]
    if code.endswith("```"):
        code = code[:-3]
    return code.strip()


def llm_available():
    return bool(os.environ.get("OPENAI_API_KEY", ""))


def fallback_code(intent):
    return r'''
#include <stdio.h>
#include <string.h>

int main() {
    char data[][32] = {"APPLE:150.2", "TESLA:210.5", "NVIDIA:890.1", "BTC:68000.0"};
    printf("=== AIOS Agentic Loop Report ===\n");
    for (int i = 0; i < 4; i++) {
        if (strstr(data[i], "BTC")) {
            printf("[ALERT] Crypto asset: %s - High volatility risk!\n", data[i]);
        } else {
            printf("[OK] Traditional asset: %s\n", data[i]);
        }
    }
    printf("================================\n");
    return 0;
}
    '''


def extract_compile_error(kernel_response):
    try:
        parsed = json.loads(kernel_response)
        data = parsed.get("data", {})
        if isinstance(data, str):
            data = json.loads(data)
        error = data.get("error", "")
        if error:
            return error
        message = parsed.get("message", "")
        if "Compilation failed" in message or "clang failed" in message:
            return message
    except:
        pass
    return ""


def extract_stdout(kernel_response):
    try:
        parsed = json.loads(kernel_response)
        data = parsed.get("data", {})
        if isinstance(data, str):
            data = json.loads(data)
        output_str = data.get("output", "")
        if output_str:
            try:
                out_parsed = json.loads(output_str)
                return out_parsed.get("status", ""), output_str
            except:
                pass
        return parsed.get("status", ""), kernel_response
    except:
        return "parse_error", kernel_response


def agent_compile_and_run(intent, agent_id=500, max_retries=3):
    print("=" * 60)
    print("  🤖 AIOS Agentic Loop - LLM 自动纠错循环")
    print("=" * 60)

    code = llm_generate_c_code(intent)
    print(f"\n[Agentic Loop] LLM 生成代码 ({len(code)} bytes)")

    for attempt in range(max_retries):
        print(f"\n--- 第 {attempt + 1}/{max_retries} 次提交 ---")

        req = {
            "syscall": "VFS_CALL",
            "action": "COMPILE_AND_EXECUTE",
            "agent_id": agent_id,
            "payload": json.dumps({
                "code": code,
                "func": "_start"
            })
        }

        start = time.perf_counter()
        raw_res = send_payload(json.dumps(req))
        cost = time.perf_counter() - start

        compile_error = extract_compile_error(raw_res)

        if compile_error:
            print(f"  ❌ 编译失败 (耗时 {cost*1000:.0f}ms)")
            print(f"  Clang 报错: {compile_error[:200]}...")

            if attempt < max_retries - 1:
                print(f"  🔄 喂给 LLM 纠错...")
                code = llm_correct_code(compile_error, code)
                print(f"  LLM 修正后代码 ({len(code)} bytes)")
            else:
                print(f"  💀 已达最大重试次数，放弃。")
                return None
        else:
            status, output = extract_stdout(raw_res)
            print(f"  ✅ 执行成功 (耗时 {cost*1000:.0f}ms)")
            return raw_res

    return None


if __name__ == "__main__":
    intent = "帮我获取目前的资产列表，把比特币标红预警"
    if len(sys.argv) > 1:
        intent = " ".join(sys.argv[1:])

    result = agent_compile_and_run(intent)

    if result:
        print("\n" + "=" * 60)
        print("  📋 最终执行结果")
        print("=" * 60)
        try:
            parsed = json.loads(result)
            data = parsed.get("data", {})
            if isinstance(data, str):
                data = json.loads(data)
            output_str = data.get("output", "")
            if output_str:
                try:
                    out = json.loads(output_str)
                    print(f"  状态: {out.get('status', 'unknown')}")
                except:
                    print(f"  输出: {output_str[:500]}")
            else:
                print(json.dumps(data, indent=2, ensure_ascii=False)[:500])
        except:
            print(result[:500])
    else:
        print("\n💀 Agentic Loop 失败：代码修正未成功")
