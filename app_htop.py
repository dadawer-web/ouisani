import socket
import json
import time
import os


def fetch_proc_top():
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(5)
        client.connect(('127.0.0.1', 8080))

        req = json.dumps({
            "syscall": "VFS_CALL",
            "action": "READ",
            "agent_id": 0,
            "path": "/proc/agent_top",
            "payload": ""
        })

        client.send((req + '\n').encode('utf-8'))
        res = client.recv(8192).decode('utf-8')
        client.close()

        try:
            outer = json.loads(res)
            content = outer.get("data", {}).get("content", res)
            try:
                return json.loads(content)
            except:
                return content
        except:
            return res

    except Exception as e:
        return f"内核连接失败: {e}"


def render_bar(label, value, max_val, width=30):
    if max_val <= 0:
        max_val = 1
    ratio = min(value / max_val, 1.0)
    filled = int(ratio * width)
    bar = "█" * filled + "░" * (width - filled)
    pct = ratio * 100
    return f"  {label} [{bar}] {value}/{max_val} ({pct:.0f}%)"


if __name__ == "__main__":
    print("正在连接 AIOS 内核探针...")
    time.sleep(1)

    while True:
        os.system('cls' if os.name == 'nt' else 'clear')

        stat = fetch_proc_top()

        print("=" * 60)
        print("   🤖 AIOS Kernel Task Manager (Agent Top)   ")
        print("=" * 60)
        print(f"  Timestamp: {time.strftime('%Y-%m-%d %H:%M:%S')}")
        print("-" * 60)

        if isinstance(stat, dict):
            q0 = stat.get("q0_len", 0)
            q1 = stat.get("q1_len", 0)
            q2 = stat.get("q2_len", 0)
            active = stat.get("active_vms", 0)
            max_vms = stat.get("max_vms", 2)
            pf = stat.get("page_faults", 0)
            total = stat.get("total_tasks", 0)
            io = stat.get("active_io", 0)

            print(f"  🔥 Q0 [CTRL] 控制指令队列 : {q0} pending")
            print(f"  💬 Q1 [I/O]  标准 I/O 队列 : {q1} pending")
            print(f"  🧠 Q2 [CPU]  Wasm CPU 队列 : {q2} pending")
            print()
            print(render_bar("⚡ Wasm VMs", active, max_vms))
            print()
            print(f"  💾 历史触发缺页中断次数  : {pf} (Swap IN/OUT)")
            print(f"  ✅ 历史累计执行任务总数  : {total}")
            print(f"  📊 I/O Pool 活跃线程数   : {io}")
        else:
            print(f"  {stat}")

        print("=" * 60)
        print("  Press Ctrl+C to exit...")

        try:
            time.sleep(1)
        except KeyboardInterrupt:
            print("\n  退出 AIOS Agent Top")
            break
