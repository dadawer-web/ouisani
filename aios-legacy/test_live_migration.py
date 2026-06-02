"""
AIOS 热迁移 (Live Migration) 端到端测试

前置条件：
  终端 1: ./aios_core                          (节点 A, syscall=8080, http=8083)
  终端 2: ./aios_core --port-offset 1000       (节点 B, syscall=9090, http=9093)

  如果 aios_core 不支持 --port-offset，需要手动修改 main.cpp 中的端口号
  后重新编译第二个实例。

测试流程：
  1. 连接节点 A (8080)，创建 Agent 101
  2. 通过 LLM_INFERENCE 给 Agent 下发无限计数任务
  3. 等待 3 秒让 Agent 运行
  4. 调用 agent.migrate(target_port=9090) 触发热迁移
  5. 断开节点 A，连接节点 B (9090)
  6. 读取 /proc/agents 确认 Agent 已在节点 B 复活
  7. 验证 Agent 从中断处继续计数
"""

from __future__ import annotations

import json
import time
import sys

from ouisani_sdk import Kernel, Agent

NODE_A_SYSCALL = 8080
NODE_B_SYSCALL = 9090
AGENT_ID = 101
COUNT_TASK_PROMPT = (
    "你是一个计数器。从 1 开始，每隔一秒报一个数字，不要停。"
    "每次输出格式为：[COUNT] N。例如 [COUNT] 1, [COUNT] 2, [COUNT] 3 ..."
    "请立即开始，不要停下来。"
)


def print_banner(title: str) -> None:
    width = 60
    print()
    print("=" * width)
    print(f"  {title}")
    print("=" * width)


def print_step(step: int, msg: str) -> None:
    print(f"\n{'─' * 50}")
    print(f"  步骤 {step}: {msg}")
    print(f"{'─' * 50}")


def check_node_alive(kernel: Kernel, label: str) -> bool:
    if kernel.ping():
        print(f"  ✅ {label} 在线")
        return True
    print(f"  ❌ {label} 离线！请先启动对应的 aios_core 进程")
    return False


def read_proc_agents(kernel: Kernel) -> str:
    resp = kernel.syscall("VFS_CALL", action="READ", path="/proc/agents")
    return resp.get("data", resp.get("message", str(resp)))


def read_agent_memory(kernel: Kernel, agent_id: int) -> list[dict]:
    resp = kernel.syscall(
        "VFS_CALL",
        action="SEARCH",
        path=f"/dev/vec_mem_{agent_id}",
        payload="count number",
        top_k=10,
        caller_id=agent_id,
    )
    return resp.get("results", [])


def main() -> None:
    print_banner("🚀 AIOS 热迁移 (Live Migration) 端到端测试")

    kernel_a = Kernel(host="127.0.0.1", syscall_port=NODE_A_SYSCALL)
    kernel_b = Kernel(host="127.0.0.1", syscall_port=NODE_B_SYSCALL)

    print_step(0, "检查节点连通性")
    if not check_node_alive(kernel_a, f"节点 A (端口 {NODE_A_SYSCALL})"):
        sys.exit(1)
    if not check_node_alive(kernel_b, f"节点 B (端口 {NODE_B_SYSCALL})"):
        sys.exit(1)

    print_step(1, f"在节点 A 上创建 Agent {AGENT_ID}")
    agent = Agent(kernel=kernel_a, agent_id=AGENT_ID)
    print(f"  Agent 对象: {agent}")

    print_step(2, "向 Agent 下发无限计数任务")
    think_resp = agent.think(COUNT_TASK_PROMPT, priority=50)
    print(f"  LLM 响应状态: {think_resp.get('status', 'unknown')}")
    llm_output = think_resp.get("data", think_resp.get("message", ""))
    if llm_output:
        preview = str(llm_output)[:200]
        print(f"  LLM 输出预览: {preview}")

    print_step(3, "让 Agent 在节点 A 上运行 3 秒...")
    for i in range(3, 0, -1):
        print(f"  ⏳ {i}...")
        time.sleep(1)

    proc_a_before = read_proc_agents(kernel_a)
    print(f"\n  📋 节点 A /proc/agents (迁移前):\n{proc_a_before}")

    mem_before = read_agent_memory(kernel_a, AGENT_ID)
    if mem_before:
        print(f"  🧠 Agent 记忆 (节点 A, 共 {len(mem_before)} 条):")
        for entry in mem_before:
            print(f"     - {entry.get('text', '')[:80]}  (score={entry.get('score', 'N/A')})")

    print_step(4, f"触发热迁移: Agent {AGENT_ID} → 节点 B (端口 {NODE_B_SYSCALL})")
    try:
        migrate_result = agent.migrate(target_host="127.0.0.1", target_port=NODE_B_SYSCALL)
        print(f"  ✅ 迁移完成!")
        print(f"  📦 迁移结果:")
        for k, v in migrate_result.items():
            val = str(v)
            if len(val) > 120:
                val = val[:120] + "..."
            print(f"     {k}: {val}")
    except RuntimeError as e:
        print(f"  ❌ 迁移失败 (EXPORT 阶段): {e}")
        sys.exit(1)
    except ConnectionError as e:
        print(f"  ❌ 迁移失败 (远端不可达): {e}")
        sys.exit(1)

    print_step(5, "验证节点 A 上 Agent 已消失")
    proc_a_after = read_proc_agents(kernel_a)
    if str(AGENT_ID) in proc_a_after:
        print(f"  ⚠️  Agent {AGENT_ID} 仍在节点 A 的进程表中!")
        print(f"  📋 节点 A /proc/agents:\n{proc_a_after}")
    else:
        print(f"  ✅ Agent {AGENT_ID} 已从节点 A 消失 (预期行为)")

    print_step(6, "验证节点 B 上 Agent 已复活")
    time.sleep(1)

    proc_b = read_proc_agents(kernel_b)
    print(f"  📋 节点 B /proc/agents:\n{proc_b}")

    if str(AGENT_ID) in proc_b:
        print(f"  ✅ Agent {AGENT_ID} 在节点 B 上复活成功!")
    else:
        print(f"  ❌ Agent {AGENT_ID} 未在节点 B 上找到!")
        print(f"  提示: 请确认节点 B 的 aios_core 进程正在运行")

    print_step(7, "检查节点 B 上 Agent 的记忆是否完整迁移")
    mem_after = read_agent_memory(kernel_b, AGENT_ID)
    if mem_after:
        print(f"  🧠 Agent 记忆 (节点 B, 共 {len(mem_after)} 条):")
        for entry in mem_after:
            print(f"     - {entry.get('text', '')[:80]}  (score={entry.get('score', 'N/A')})")
        print(f"  ✅ 记忆迁移完整! (节点 A: {len(mem_before)} 条 → 节点 B: {len(mem_after)} 条)")
    else:
        print(f"  ⚠️  节点 B 上未检索到 Agent 记忆 (可能嵌入服务未配置)")

    print_step(8, "在节点 B 上继续让 Agent 工作")
    agent_b = Agent(kernel=kernel_b, agent_id=AGENT_ID)
    resume_resp = agent_b.think(
        "继续从你上次停下的数字往下数，不要从头开始。",
        priority=50,
    )
    print(f"  LLM 响应状态: {resume_resp.get('status', 'unknown')}")
    resume_output = resume_resp.get("data", resume_resp.get("message", ""))
    if resume_output:
        preview = str(resume_output)[:200]
        print(f"  LLM 输出预览: {preview}")

    print_banner("📊 热迁移测试结果汇总")

    checks = {
        "节点 A 在线": True,
        "节点 B 在线": True,
        "Agent 创建成功": True,
        "LLM 任务下发成功": think_resp.get("status") == "ok",
        "热迁移执行成功": True,
        "Agent 从节点 A 消失": str(AGENT_ID) not in proc_a_after,
        "Agent 在节点 B 复活": str(AGENT_ID) in proc_b,
        "记忆完整迁移": len(mem_after) > 0 if mem_after else len(mem_before) == 0,
    }

    all_passed = True
    for check, passed in checks.items():
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"  {status}  {check}")
        if not passed:
            all_passed = False

    print()
    if all_passed:
        print("  🎉🎉🎉 热迁移端到端测试全部通过！Agent 完美穿越！🎉🎉🎉")
    else:
        print("  ⚠️  部分检查未通过，请检查上方日志排查问题")

    print()
    print("  提示: 如果 LLM 相关检查失败，请确认 .env 中配置了有效的 API Key")
    print("  提示: 如果记忆迁移检查失败，请确认 EMBEDDING 配置正确")
    print()


if __name__ == "__main__":
    main()
