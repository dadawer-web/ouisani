# aios_skills/code_executor.py
"""
AutoGPT-style 原子工具：安全代码执行引擎。
核心特性：
  - 子进程隔离执行，绝不污染主进程
  - 严格超时控制（默认 30s，防止死循环）
  - 输出大小限制（防止 Token 爆炸）
  - 禁止危险操作（网络监听、文件删除等）
  - 结构化执行结果（stdout + stderr + returncode + duration）
"""

import subprocess
import sys
import os
import time
import logging
import signal
import re
from typing import Optional, Dict, Any

logger = logging.getLogger("aios.skills.code_executor")

# ── 安全约束 ──
_DEFAULT_TIMEOUT = 30  # 秒
_MAX_OUTPUT_LENGTH = 50000  # 字符
_FORBIDDEN_PATTERNS = [
    r"os\.system\s*\(",
    r"subprocess\.call\s*\(",
    r"subprocess\.Popen\s*\(",
    r"shutil\.rmtree\s*\(",
    r"os\.remove\s*\(",
    r"os\.unlink\s*\(",
    r"socket\.bind\s*\(",
    r"__import__\s*\(\s*['\"]os['\"]\s*\)",
]


def _check_dangerous_code(code: str) -> Optional[str]:
    """
    静态扫描危险代码模式。
    返回 None 表示安全，返回字符串表示发现的危险模式。
    """
    for pattern in _FORBIDDEN_PATTERNS:
        match = re.search(pattern, code)
        if match:
            return match.group(0)
    return None


def execute_python(
    code: str,
    timeout: int = _DEFAULT_TIMEOUT,
    working_dir: Optional[str] = None,
    env_vars: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """
    安全执行 Python 代码片段 — 子进程隔离 + 超时控制。

    [API_SCHEMA_START]
    {
        "name": "execute_python",
        "description": "安全执行 Python 代码片段，子进程隔离 + 超时控制 + 危险代码拦截。",
        "parameters": {
            "code": {"type": "string", "required": true, "description": "要执行的 Python 代码字符串"},
            "timeout": {"type": "integer", "required": false, "default": 30, "minimum": 5, "maximum": 300, "description": "执行超时秒数"},
            "working_dir": {"type": "string", "required": false, "default": null, "description": "工作目录，null 时使用当前目录"},
            "env_vars": {"type": "object", "required": false, "default": null, "description": "额外环境变量键值对"}
        },
        "return": {
            "type": "object",
            "description": "结构化执行结果",
            "schema": {
                "success": "boolean - true 表示 returncode == 0",
                "stdout": "string - 标准输出",
                "stderr": "string - 标准错误",
                "returncode": "integer - 0=成功, -9=超时, -1=异常或安全拦截",
                "duration_ms": "integer - 执行耗时毫秒",
                "error": "string|null - 错误描述"
            }
        }
    }
    [API_SCHEMA_END]

    Args:
        code:       Python 代码字符串
        timeout:    执行超时秒数
        working_dir: 工作目录（None 则使用当前目录）
        env_vars:   额外环境变量

    Returns:
        结构化执行结果字典：
        {
            "success": bool,
            "stdout": str,
            "stderr": str,
            "returncode": int,
            "duration_ms": int,
            "error": str | None
        }
    """
    # 危险代码扫描
    dangerous = _check_dangerous_code(code)
    if dangerous:
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[SECURITY] 检测到危险代码模式: {dangerous}。执行已被拦截。",
            "returncode": -1,
            "duration_ms": 0,
            "error": f"dangerous_code_detected: {dangerous}",
        }

    # 构建安全环境变量
    safe_env = os.environ.copy()
    # 移除敏感变量
    for key in list(safe_env.keys()):
        if any(s in key.upper() for s in ["PASSWORD", "SECRET", "TOKEN", "API_KEY", "PRIVATE"]):
            del safe_env[key]
    if env_vars:
        safe_env.update(env_vars)

    start_time = time.monotonic()

    try:
        # 使用子进程执行，确保隔离
        result = subprocess.run(
            [sys.executable, "-u", "-c", code],
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=working_dir or os.getcwd(),
            env=safe_env,
        )

        duration_ms = int((time.monotonic() - start_time) * 1000)

        # 截断过长输出
        stdout = result.stdout or ""
        stderr = result.stderr or ""
        if len(stdout) > _MAX_OUTPUT_LENGTH:
            stdout = stdout[:_MAX_OUTPUT_LENGTH] + f"\n... [截断: stdout 超过 {_MAX_OUTPUT_LENGTH} 字符]"
        if len(stderr) > _MAX_OUTPUT_LENGTH:
            stderr = stderr[:_MAX_OUTPUT_LENGTH] + f"\n... [截断: stderr 超过 {_MAX_OUTPUT_LENGTH} 字符]"

        return {
            "success": result.returncode == 0,
            "stdout": stdout,
            "stderr": stderr,
            "returncode": result.returncode,
            "duration_ms": duration_ms,
            "error": None if result.returncode == 0 else f"exit_code={result.returncode}",
        }

    except subprocess.TimeoutExpired:
        duration_ms = int((time.monotonic() - start_time) * 1000)
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[TIMEOUT] 代码执行超时（{timeout}s），已被强制终止。请检查是否存在死循环或阻塞操作。",
            "returncode": -9,
            "duration_ms": duration_ms,
            "error": f"timeout_after_{timeout}s",
        }

    except Exception as e:
        duration_ms = int((time.monotonic() - start_time) * 1000)
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[EXEC_ERROR] 执行异常: {e}",
            "returncode": -1,
            "duration_ms": duration_ms,
            "error": str(e),
        }


def execute_script(
    script_path: str,
    args: Optional[list] = None,
    timeout: int = _DEFAULT_TIMEOUT,
    working_dir: Optional[str] = None,
) -> Dict[str, Any]:
    """
    安全执行 Python 脚本文件 — 子进程隔离 + 超时控制。

    [API_SCHEMA_START]
    {
        "name": "execute_script",
        "description": "安全执行 Python 脚本文件，子进程隔离 + 超时控制 + 脚本内容安全扫描。",
        "parameters": {
            "script_path": {"type": "string", "required": true, "description": "Python 脚本文件路径"},
            "args": {"type": "array", "required": false, "default": null, "description": "命令行参数列表"},
            "timeout": {"type": "integer", "required": false, "default": 30, "minimum": 5, "maximum": 300, "description": "执行超时秒数"},
            "working_dir": {"type": "string", "required": false, "default": null, "description": "工作目录，null 时使用脚本所在目录"}
        },
        "return": {
            "type": "object",
            "description": "同 execute_python 的结构化结果字典"
        }
    }
    [API_SCHEMA_END]

    Args:
        script_path: Python 脚本文件路径
        args:        命令行参数列表
        timeout:     执行超时秒数
        working_dir: 工作目录

    Returns:
        同 execute_python 的结构化结果字典
    """
    if not os.path.isfile(script_path):
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[SKILL_ERROR] 脚本文件不存在: {script_path}",
            "returncode": -1,
            "duration_ms": 0,
            "error": "file_not_found",
        }

    # 读取脚本内容做安全检查
    try:
        with open(script_path, "r", encoding="utf-8", errors="replace") as f:
            code = f.read()
    except Exception as e:
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[SKILL_ERROR] 无法读取脚本: {e}",
            "returncode": -1,
            "duration_ms": 0,
            "error": str(e),
        }

    dangerous = _check_dangerous_code(code)
    if dangerous:
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[SECURITY] 脚本中检测到危险代码模式: {dangerous}。执行已被拦截。",
            "returncode": -1,
            "duration_ms": 0,
            "error": f"dangerous_code_detected: {dangerous}",
        }

    start_time = time.monotonic()
    cmd = [sys.executable, "-u", script_path]
    if args:
        cmd.extend(args)

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=working_dir or os.path.dirname(os.path.abspath(script_path)),
        )

        duration_ms = int((time.monotonic() - start_time) * 1000)

        stdout = result.stdout or ""
        stderr = result.stderr or ""
        if len(stdout) > _MAX_OUTPUT_LENGTH:
            stdout = stdout[:_MAX_OUTPUT_LENGTH] + f"\n... [截断: stdout 超过 {_MAX_OUTPUT_LENGTH} 字符]"
        if len(stderr) > _MAX_OUTPUT_LENGTH:
            stderr = stderr[:_MAX_OUTPUT_LENGTH] + f"\n... [截断: stderr 超过 {_MAX_OUTPUT_LENGTH} 字符]"

        return {
            "success": result.returncode == 0,
            "stdout": stdout,
            "stderr": stderr,
            "returncode": result.returncode,
            "duration_ms": duration_ms,
            "error": None if result.returncode == 0 else f"exit_code={result.returncode}",
        }

    except subprocess.TimeoutExpired:
        duration_ms = int((time.monotonic() - start_time) * 1000)
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[TIMEOUT] 脚本执行超时（{timeout}s），已被强制终止。",
            "returncode": -9,
            "duration_ms": duration_ms,
            "error": f"timeout_after_{timeout}s",
        }

    except Exception as e:
        duration_ms = int((time.monotonic() - start_time) * 1000)
        return {
            "success": False,
            "stdout": "",
            "stderr": f"[EXEC_ERROR] 执行异常: {e}",
            "returncode": -1,
            "duration_ms": duration_ms,
            "error": str(e),
        }
