import socket
import json
import time

def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.send(payload.encode('utf-8'))
    client.settimeout(15)
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res

if __name__ == "__main__":
    print("=== AIOS STDIN/STDOUT 全双工 I/O 测试 ===")

    c_code = """
#include <stdio.h>
int main() {
    int age;
    float height;
    char name[50];

    printf("[Wasm 内部] 请输入你的名字: ");
    scanf("%49s", name);

    printf("[Wasm 内部] 请输入你的年龄和身高(空格分隔): ");
    scanf("%d %f", &age, &height);

    printf("\\n>>> 档案录入成功: %s, %d岁, %.2f米 <<<\\n", name, age, height);
    return 0;
}
    """

    simulated_keyboard_input = "Alice\n24 1.75\n"

    req = {
        "syscall": "VFS_CALL",
        "agent_id": 951,
        "action": "COMPILE_AND_EXECUTE",
        "path": "",
        "payload": json.dumps({
            "code": c_code,
            "stdin": simulated_keyboard_input
        })
    }

    start = time.perf_counter()
    print("[客户端] 正在向沙盒注入 C 代码及键盘输入流...")
    raw_res = send_payload(json.dumps(req))
    cost = time.perf_counter() - start

    print(f"\n[AIOS 内核返回] 耗时 {cost*1000:.2f} 毫秒\n")

    try:
        outer = json.loads(raw_res)
        if outer.get("status") == "ok" and "data" in outer:
            data = outer["data"]
            if isinstance(data.get("output"), str):
                wasm_result = json.loads(data["output"])
                stdout_text = wasm_result.get("stdout", "")
                wasm_lines = [l for l in stdout_text.split('\n')
                              if any(k in l for k in ['Wasm', '>>>', '档案'])]
                clean_output = '\n'.join(wasm_lines) if wasm_lines else stdout_text[:500]

                print("【沙盒 STDOUT 完整输出】:")
                print(clean_output if clean_output else stdout_text[:500])

                if "Alice" in stdout_text and "24" in stdout_text and "1.75" in stdout_text:
                    print("\n✅ STDIN/STDOUT 全双工 I/O 测试成功!")
                    print("   scanf 正确读取了名字、年龄、身高")
                    print("   printf 正确输出了档案信息")
                else:
                    print("\n⚠️ 输出内容不完整")
                    print(f"   Raw stdout (first 300): {stdout_text[:300]}")
            else:
                print("返回 data:", json.dumps(data, indent=2, ensure_ascii=False)[:500])
        else:
            print("返回:", raw_res[:500])
    except Exception as e:
        print(f"返回解析失败: {e}")
        print(f"Raw (first 500): {raw_res[:500]}")

    print("\n=== 测试结束 ===")
