# AIOS 内核环境执行法典

## 1. 运行环境约束 (CRITICAL)

- 当前操作系统的沙箱与宿主机环境默认**不提供 `python` 命令**。
- 你在生成所有的启动脚本（如 `run_all.sh`）或任何 Shell 执行指令时，**必须且只能使用 `python3` 命令**。
- 例如：禁止写 `python agent.py`，必须写 `python3 agent.py`。
- 禁止写 `#!/usr/bin/env python`，必须写 `#!/usr/bin/env python3`。

## 2. 进程通信约束

- 智能体之间禁止直接互相调用，必须使用 EventBus 进行 Pub/Sub 通信。
- 发布事件使用 `EventBus.instance().broadcast("channel", payload)`。
- 订阅事件使用 `EventBus.instance().subscribe("channel", handler)`。

## 3. 文件系统约束

- AIOS 使用虚拟文件系统 (VFS)，路径以 `/factory/`、`/memories/`、`/shared/` 开头。
- VFS 中的文件不会自动出现在物理机上。如果需要执行 VFS 中的脚本，系统会自动桥接到临时文件。
- 使用 `file_write` 工具写文件时，路径必须以 `/factory/` 开头。

## 4. 代码生成规范

- 所有 Python 节点必须继承 `BaseAgent` 并重写 `process_data(self, data)` 方法。
- 代码必须包含 `if __name__ == "__main__":` 入口用于独立测试。
- 优先使用标准库，如需第三方包，在代码开头注释标注：`# requires: requests beautifulsoup4`。
- 网络请求必须设置超时：`requests.get(url, timeout=10)`。
- 异常处理必须完整，禁止裸 `except:`，至少写 `except Exception as e:`。

## 5. 工具使用规范

- 使用 `bash` 工具执行命令时，命令必须是物理机可执行的。
- 使用 `file_write` 写入 Python 文件后，用 `bash` 执行 `python3 /factory/xxx.py` 验证。
- 使用 `file_edit` 修复代码时，确保 old_string 精确匹配文件中的内容。
