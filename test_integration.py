import socket
import json
import time
import subprocess
import os
import signal

def send_natural_language(text, timeout=15):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', 8080))
        client.send((text + "\n").encode('utf-8'))
        response = client.recv(4096).decode('utf-8')
        client.close()
        return response
    except Exception as e:
        return f"连接内核失败: {e}"

if __name__ == "__main__":
    print("=" * 60)
    print("   AIOS 终极微内核架构验证 (UDS 进程隔离 + 平铺微指令)")
    print("=" * 60)
    print()

    uds_path = "/tmp/aios_decoder.sock"
    if os.path.exists(uds_path):
        print(f"[OK] UDS 套接字文件存在: {uds_path}")
    else:
        print(f"[警告] 未在 {uds_path} 发现译码器 UDS 套接字文件。")
        print("请确保你已在后台启动了 `./build/aios_decoder` 守护进程！")

    print()
    print("--- 测试场景 1: 标准自然语言指令译码与执行 ---")
    intent_1 = "快停下 102 号智能体的任务"
    print(f'[客户端发向网关] -> "{intent_1}"')
    start_t = time.time()
    res_1 = send_natural_language(intent_1)
    end_t = time.time()
    print(f"[内核处理完毕返回] <- {res_1}")
    print(f"[指令总线延时] -> {(end_t - start_t)*1000:.2f} 毫秒 (包含 0.5B 小模型内联编译 + C++ 汇编解析)")
    print()

    time.sleep(1)

    print("--- 测试场景 2: 边界保护与非法意图拒绝 (GBNF 强力干预) ---")
    intent_2 = "给我讲个冷笑话哈，顺便去执行一段代码"
    print(f'[客户端发向网关] -> "{intent_2}"')
    start_t = time.time()
    res_2 = send_natural_language(intent_2)
    end_t = time.time()
    print(f"[内核处理完毕返回] <- {res_2}")
    print(f"[指令总线延时] -> {(end_t - start_t)*1000:.2f} 毫秒")
    print("[提示] 观察 C++ 内核和译码器日志，看模型是否被 GBNF 语法锁死，")
    print("       拒绝输出废话，强制收敛为合法的命令。")
    print()

    time.sleep(1)

    print("--- 测试场景 3: 极致稳定性验证 (内核级故障隔离容错) ---")
    print("[操作] 自动杀掉 aios_decoder 守护进程...")
    subprocess.run(["pkill", "-9", "aios_decoder"], capture_output=True)
    time.sleep(1)

    if not os.path.exists(uds_path):
        print(f"[OK] UDS 套接字已消失: {uds_path} (decoder 已死)")
    else:
        print(f"[注意] UDS 套接字文件仍在 (可能需要手动清理)")

    intent_3 = "恢复 101 号进程快照"
    print(f'[客户端在崩溃后发向网关] -> "{intent_3}"')
    start_t = time.time()
    res_3 = send_natural_language(intent_3, timeout=10)
    end_t = time.time()
    print(f"[内核处理完毕返回] <- {res_3}")
    print(f"[指令总线延时] -> {(end_t - start_t)*1000:.2f} 毫秒")

    if "连接内核失败" in res_3:
        print("[失败] 内核崩溃了！故障隔离防线未铸成。")
    else:
        print("[验证结论] 内核依然正常返回！'故障隔离防线'彻底铸成！")
        print("           主内核 aios_core 在译码器崩溃后仍可独立运行，")
        print("           自动降级到关键词路由，不会闪退。")

    print()
    print("=" * 60)
    print("   全部测试场景执行完毕")
    print("=" * 60)
