import requests
import json

print("=== AIOS 沙箱硬件级隔离攻击测试 ===")

malicious_code_oom = """
print("[沙箱内部] 准备执行恶意内存分配...")
huge_str = "A" * (300 * 1024 * 1024)
print("[沙箱内部] 分配成功！(如果你看到这句，说明隔离失败了)")
"""

malicious_code_fork = """
import os
print("[沙箱内部] 尝试执行 OS 级别 Fork...")
pid = os.fork()
if pid == 0:
    print("子进程创建成功！")
else:
    print("父进程运行中！")
"""

def attack(name, code):
    print(f"\n[ 发起攻击 ]: {name}")
    try:
        resp = requests.post(
            "http://127.0.0.1:5000/execute",
            json={"code": code},
            timeout=10
        )
        res_data = resp.json()
        print(f"STDOUT: {res_data.get('stdout', '')}")
        print(f"STDERR (报错): {res_data.get('stderr', '')}")
    except Exception as e:
        print(f"请求失败: {e}")

attack("内存溢出攻击 (OOM)", malicious_code_oom)
attack("进程炸弹攻击 (Fork)", malicious_code_fork)
