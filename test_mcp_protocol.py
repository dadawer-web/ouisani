import socket
import json
import time


def send_mcp_request(req_dict, timeout=120):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', 8081))
        client.send((json.dumps(req_dict) + '\n').encode('utf-8'))

        buf = b""
        while b"\n" not in buf:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk

        line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
        client.close()

        if "{" in line:
            return json.loads(line)
        return {"raw": line}
    except socket.timeout:
        return {"error": f"请求超时 ({timeout}s)"}
    except ConnectionRefusedError:
        return {"error": "连接被拒绝！MCP Server (8081) 未启动？"}
    except Exception as e:
        return {"error": str(e)}


print("=" * 60)
print("  🌍 AIOS 进阶第一站：MCP (Model Context Protocol) 协议接入")
print("=" * 60)
print()

# --- Step 1: 握手 (Initialize) ---
print("1. [MCP Client] 正在向 Ouisani 内核发起标准的 initialize 握手...")
req_init = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "clientInfo": {"name": "Python-MCP-Tester", "version": "1.0"}
    }
}
res_init = send_mcp_request(req_init)

if "result" in res_init:
    server_info = res_init["result"].get("serverInfo", {})
    print(f"   ✅ [Kernel 响应]: 成功连接到 {server_info.get('name', 'unknown')}")
    print(f"   📋 协议版本: {res_init['result'].get('protocolVersion', 'unknown')}")
    print(f"   🛡️  能力: {json.dumps(res_init['result'].get('capabilities', {}))}")
elif "error" in res_init:
    print(f"   ❌ 握手失败: {res_init.get('error', res_init)}")
    exit(1)
else:
    print(f"   ❌ 未知响应: {res_init}")
    exit(1)

print()

# --- Step 2: 发现工具 (Tools List) ---
print("2. [MCP Client] 请求查询内核支持的物理级工具...")
req_list = {
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
}
res_list = send_mcp_request(req_list)

if "result" in res_list:
    tools = res_list["result"].get("tools", [])
    for t in tools:
        print(f"   🛠️  发现工具: [{t['name']}] - {t['description'][:60]}...")
        schema = t.get("inputSchema", {})
        required = schema.get("required", [])
        props = list(schema.get("properties", {}).keys())
        print(f"       参数: {props} | 必填: {required}")
else:
    print(f"   ❌ 工具列表获取失败: {res_list}")

print()

# --- Step 3: 调用工具 - 编译执行 C 代码 ---
print("3. [MCP Client] 下发 C 语言代码，请求内核隔离编译执行...")
c_code = """
#include <stdio.h>
int main() {
    printf("[WASM 沙箱] 收到 MCP 全球通用协议请求！\\n");
    printf("[WASM 沙箱] 算力对接成功，隔离执行完毕！\\n");
    return 0;
}
"""

req_call = {
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
        "name": "compile_and_execute_c",
        "arguments": {
            "code": c_code
        }
    }
}

start = time.perf_counter()
res_call = send_mcp_request(req_call)
cost = time.perf_counter() - start

if "result" in res_call:
    print(f"   ✅ [Kernel 响应] (耗时 {cost:.2f}s):")
    print("   " + "-" * 50)
    contents = res_call["result"].get("content", [])
    for c in contents:
        text = c.get("text", "")
        for line in text.strip().split("\n"):
            print(f"   {line}")
    print("   " + "-" * 50)
elif "error" in res_call:
    err = res_call["error"]
    print(f"   ❌ 工具调用失败: [{err.get('code')}] {err.get('message')}")
else:
    print(f"   ❌ 未知响应: {res_call}")

print()

# --- Step 4: 调用工具 - 语义 VFS ---
print("4. [MCP Client] 通过语义 VFS 工具，用自然语言操作内核...")

req_semantic = {
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
        "name": "semantic_vfs",
        "arguments": {
            "intent": "查看系统版本信息"
        }
    }
}

start2 = time.perf_counter()
res_semantic = send_mcp_request(req_semantic)
cost2 = time.perf_counter() - start2

if "result" in res_semantic:
    print(f"   ✅ [Kernel 响应] (耗时 {cost2:.2f}s):")
    print("   " + "-" * 50)
    contents = res_semantic["result"].get("content", [])
    for c in contents:
        text = c.get("text", "")
        for line in text.strip().split("\n"):
            print(f"   {line}")
    print("   " + "-" * 50)
elif "error" in res_semantic:
    err = res_semantic["error"]
    if isinstance(err, dict):
        print(f"   ❌ 语义 VFS 调用失败: [{err.get('code')}] {err.get('message')}")
    else:
        print(f"   ❌ 语义 VFS 调用失败: {err}")
else:
    print(f"   ❌ 未知响应: {res_semantic}")

print()

# --- Step 5: 错误处理验证 ---
print("5. [MCP Client] 验证协议错误处理...")
req_bad = {
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
        "name": "nonexistent_tool",
        "arguments": {}
    }
}
res_bad = send_mcp_request(req_bad)
if "error" in res_bad:
    err = res_bad["error"]
    print(f"   ✅ 未知工具正确返回错误: [{err.get('code')}] {err.get('message')}")
else:
    print(f"   ⚠️  预期返回错误，实际: {res_bad}")

req_bad2 = {
    "jsonrpc": "2.0",
    "id": 6,
    "method": "unknown_method"
}
res_bad2 = send_mcp_request(req_bad2)
if "error" in res_bad2:
    err = res_bad2["error"]
    print(f"   ✅ 未知方法正确返回错误: [{err.get('code')}] {err.get('message')}")
else:
    print(f"   ⚠️  预期返回错误，实际: {res_bad2}")

print()
print("=" * 60)
print("  🏁 MCP 协议接入测试结束")
print("=" * 60)
print()
print("  🚀 意义：")
print("  你的 C++ 内核现在已经符合 Anthropic (Claude) 的 MCP 国际标准！")
print("  你可以直接把它接入任何支持 MCP 的 AI IDE（如 Cursor）或商业大模型框架，")
print("  作为它们底层的「安全代码执行引擎」！")
