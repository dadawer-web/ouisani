import socket
import json
import time


def send_payload(payload, timeout=120):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))

        start_time = time.perf_counter()
        buf = b""
        while b"\n" not in buf:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk

        line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
        cost = time.perf_counter() - start_time

        client.close()
        return line, cost
    except socket.timeout:
        return json.dumps({"status": "error", "message": f"请求超时 ({timeout}s)"}), 0
    except ConnectionRefusedError:
        return json.dumps({"status": "error", "message": "连接被拒绝！内核未启动？"}), 0
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}), 0


def print_separator(char="=", length=60):
    print(char * length)


print_separator()
print("  👑 AIOS 终极形态演示：基于 LLM 的端到端语义造物闭环")
print_separator()
print()
print("  📖 测试原理:")
print("  你不必写哪怕一行 C 代码，也不必查阅任何底层 API。")
print("  只用一句大白话，命令内核自动完成：")
print("  理解意图 → 编写代码 → JIT 编译 → 物理级沙箱执行 → 结果返回")
print()

user_intent = (
    "系统你好，请帮我写一段高性能的 C 语言程序。"
    "这段程序需要计算出第42个斐波那契数。"
    "写好之后，请直接在底层编译并执行它，把最终的计算结果告诉我。"
)

print(f"  🗣️  [人类用户] 下达指令:")
print(f"     「{user_intent}」")
print()
print("  ⏳ [系统底座] 正在将意图写入 /dev/semantic，激发内核级 AI 神经元...")
print()

req_semantic = {
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/semantic",
    "caller_id": 0,
    "payload": user_intent
}

raw_res, cost = send_payload(json.dumps(req_semantic), timeout=120)

print()
print_separator("=", 60)
print("  【内核响应】")
print_separator("=", 60)

try:
    parsed = json.loads(raw_res)

    if parsed.get("status") == "ok":
        print(f"  ✅ [执行成功] 整个造物闭环耗时: {cost:.2f} 秒")
        print()

        data = parsed.get("data", "")
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                pass

        if isinstance(data, dict):
            action = data.get("action", "unknown")
            print(f"  📋 语义动作: {action}")
            print()

            if action == "COMPILE_AND_EXECUTE":
                compile_stage = data.get("compile_stage", "")
                wasm_path = data.get("wasm_path", "")
                output = data.get("output", "")
                stdout = data.get("stdout", "")
                message = data.get("message", "")

                if compile_stage:
                    print(f"  🔧 编译阶段: {compile_stage}")
                if wasm_path:
                    print(f"  📦 WASM 路径: {wasm_path}")
                if message:
                    print(f"  📝 内核消息: {message}")
                print()

                print("  【沙盒最终输出】:")
                print_separator("-")
                if stdout:
                    print(f"  {stdout.strip()}")
                elif output:
                    print(f"  {output.strip()}")
                else:
                    print(f"  {json.dumps(data, ensure_ascii=False, indent=2)}")
                print_separator("-")
            else:
                print(f"  📄 结果:")
                print(json.dumps(data, ensure_ascii=False, indent=2))
        else:
            print(f"  📄 原始数据: {data}")
    else:
        print(f"  ❌ [执行失败] 耗时 {cost:.2f} 秒")
        msg = parsed.get("message", "")
        if msg:
            print(f"  错误信息: {msg}")
        print()
        print(f"  完整响应: {json.dumps(parsed, ensure_ascii=False, indent=2)}")

except json.JSONDecodeError:
    print(f"  ⚠️ 解析结果失败 (耗时 {cost:.2f} 秒):")
    print(f"  {raw_res[:500]}")

print()
print_separator("=", 60)
print("  🤯 刚刚在这几秒钟里，你的系统底层发生了什么：")
print_separator("=", 60)
print()
print("  1. 🎯 意图拦截：大白话写入 /dev/semantic，触发底层异常。")
print("  2. ⚡ 特权调度：调度器将其塞入 LLM_Queue (Priority 99) 插队处理。")
print("  3. 🧠 硅基编程：大模型瞬间用 C 语言写出了求斐波那契数列的代码。")
print("  4. 🔄 意图翻译：大模型将意图转化为合法的 Syscall: COMPILE_AND_EXECUTE。")
print("  5. ⚙️  JIT 编译：内核桥接 Clang，将 C 源码编译成安全的 .wasm 字节码。")
print("  6. 💨 极限计算：WasmEdge 虚拟机拉起，在纳秒级算力下求出第 42 个斐波那契数。")
print("  7. 📡 结果回传：数字从 C++ 内存穿透到 Python 终端，阅后即焚！")
print()
print_separator()
print("  🏁 Software 3.0 语义造物闭环测试结束")
print_separator()
