from ouisani_sdk import Kernel, Agent
import threading
import time

print("==================================================")
print(" 🌳 AIOS 进阶拼图：多智能体 OS 级进程树协同")
print("==================================================\n")

kernel = Kernel(host="127.0.0.1", syscall_port=8080)
boss_agent = Agent(kernel=kernel, agent_id=101)

print("👔 [包工头 Agent 101] 接到大项目：开发一个贪吃蛇游戏。")
print("👔 [包工头 Agent 101] 决定使用系统调用 AGENT_SPAWN 雇佣两个子进程...\n")

frontend_agent = boss_agent.spawn(role="前端开发工程师")
backend_agent = boss_agent.spawn(role="后端逻辑工程师")

print(f"   ✅ [内核响应] 成功分配子进程: 前端(PID={frontend_agent.agent_id}), 后端(PID={backend_agent.agent_id})\n")

def run_child_task(child_agent, task_prompt, mock_result):
    print(f"   👷 [子进程 {child_agent.agent_id}] 开始执行任务: {task_prompt}")
    time.sleep(2)
    print(f"   👷 [子进程 {child_agent.agent_id}] 任务完成，调用 exit() 上报结果...")
    child_agent.exit(result=mock_result)

t1 = threading.Thread(target=run_child_task, args=(frontend_agent, "写 Canvas 渲染代码", "【前端代码：canvas.fillRect(...)】"))
t2 = threading.Thread(target=run_child_task, args=(backend_agent, "写核心状态机逻辑", "【后端代码：if(snake.collide()) game_over();】"))

t1.start()
t2.start()

print(f"👔 [包工头 Agent 101] 调用 AGENT_WAIT，进入睡眠阻塞状态，不浪费任何 LLM 算力...\n")

start_wait = time.perf_counter()
res_front = boss_agent.wait(frontend_agent)
res_back = boss_agent.wait(backend_agent)
cost = time.perf_counter() - start_wait

t1.join()
t2.join()

print(f"\n⚡ [系统中断] 子进程全部结束，唤醒包工头 101！(总等待耗时 {cost:.2f}s)")
print("👔 [包工头 Agent 101] 收到了子进程的回传数据，开始合并：")
print("--------------------------------------------------")
print(res_front)
print(res_back)
print("--------------------------------------------------")
print("\n🎉 项目交付！完美体现了 Unix fork/wait/exit 哲学！")
