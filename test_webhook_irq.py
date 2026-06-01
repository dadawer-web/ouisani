import threading
import time
import json
import urllib.request

from ouisani_sdk import Kernel, Agent

print("==================================================")
print(" 🪝 AIOS 外部中断 (IRQ) 测试：Webhook 唤醒 Agent")
print("==================================================\n")

kernel = Kernel(host="127.0.0.1", syscall_port=8080)
agent = Agent(kernel=kernel, agent_id=101)

received_event = None


def agent_wait_thread():
    global received_event
    print("🤖 [Agent 101] 调用 wait_for_webhook()，进入阻塞等待...")
    print("   (底层 VFS READ /dev/irq/webhook0 → condition_variable.wait())\n")
    result = agent.irq.wait_for_webhook()
    received_event = result
    print(f"\n⚡ [Agent 101] 被内核唤醒！收到 IRQ 中断信号！")
    print(f"   📩 Payload: {result}")
    print("--------------------------------------------------")


t = threading.Thread(target=agent_wait_thread, daemon=True)
t.start()

time.sleep(2)

payload = json.dumps({"event": "user_login", "user": "alice"})
print(f"🌐 [外部世界] 向 http://127.0.0.1:8083/webhook/trigger 发送 POST 请求...")
print(f"   📤 Payload: {payload}\n")

try:
    req = urllib.request.Request(
        "http://127.0.0.1:8083/webhook/trigger",
        data=payload.encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode("utf-8")
        print(f"   ✅ HTTP {resp.status}: {body}\n")
except Exception as e:
    print(f"   ❌ Request failed: {e}\n")

t.join(timeout=15)

print("\n==================================================")
if received_event:
    print(" 🎉 测试成功！Agent 被外部 Webhook 中断成功唤醒！")
    print(f" 收到的事件: {received_event}")
else:
    print(" ❌ 测试失败：Agent 未收到中断信号。")
print("==================================================")
