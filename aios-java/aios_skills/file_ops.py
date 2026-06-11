# aios_skills/file_ops.py
"""
AutoGPT-style 原子工具：高可用文件读写引擎。
核心特性：
  - 自动编码检测（chardet / BOM / 常见编码逐一尝试）
  - 原子写入（先写临时文件再 rename，防止半写损坏）
  - 路径安全校验（禁止路径穿越）
  - 大文件安全截断
  - JSON/YAML 智能解析
"""

import os
import json
import re
import logging
import tempfile
import shutil
from typing import Optional, Any, Dict, List

logger = logging.getLogger("aios.skills.file_ops")

# ── 安全约束 ──
_MAX_FILE_SIZE = 10 * 1024 * 1024  # 10MB
_ALLOWED_ROOTS = ["/factory", "/shared", "/tmp", "/home"]


def _validate_path(filepath: str) -> str:
    """
    路径安全校验 — 防止路径穿越攻击。
    解析符号链接和 .. 后，检查是否在允许的根目录下。
    """
    if not filepath:
        raise ValueError("文件路径不能为空")

    # 解析相对路径和符号链接
    resolved = os.path.realpath(filepath)

    # 检查是否在允许的根目录下
    allowed = any(resolved.startswith(root) for root in _ALLOWED_ROOTS)
    if not allowed:
        raise ValueError(
            f"路径越权: '{filepath}' 不在允许的目录 {_ALLOWED_ROOTS} 下"
        )

    return resolved


def _detect_encoding(filepath: str) -> str:
    """
    自动检测文件编码。
    优先级：BOM 标记 > chardet > 逐一尝试常见编码 > utf-8 兜底
    """
    # 1. BOM 检测
    with open(filepath, "rb") as f:
        raw_head = f.read(4)

    if raw_head.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"
    if raw_head.startswith(b"\xff\xfe"):
        return "utf-16-le"
    if raw_head.startswith(b"\xfe\xff"):
        return "utf-16-be"

    # 2. chardet 检测
    try:
        import chardet
        with open(filepath, "rb") as f:
            raw = f.read(65536)  # 只读前 64KB 做检测
        detected = chardet.detect(raw)
        if detected and detected.get("encoding") and detected.get("confidence", 0) > 0.7:
            return detected["encoding"]
    except ImportError:
        pass

    # 3. 逐一尝试常见编码
    common_encodings = ["utf-8", "gbk", "gb2312", "gb18030", "big5", "latin-1"]
    for enc in common_encodings:
        try:
            with open(filepath, "r", encoding=enc) as f:
                f.read(4096)
            return enc
        except (UnicodeDecodeError, UnicodeError):
            continue

    # 4. 兜底
    return "utf-8"


def read_file(filepath: str, max_length: int = 50000, encoding: Optional[str] = None) -> str:
    """
    安全读取文件内容 — 带编码检测、大小限制和路径校验。

    [API_SCHEMA_START]
    {
        "name": "read_file",
        "description": "安全读取文件内容，自动检测编码，防路径穿越。",
        "parameters": {
            "filepath": {"type": "string", "required": true, "description": "文件路径，必须在白名单目录内（/factory, /shared, /tmp, /home）"},
            "max_length": {"type": "integer", "required": false, "default": 50000, "minimum": 1000, "maximum": 1000000, "description": "最大读取字符数"},
            "encoding": {"type": "string", "required": false, "default": null, "description": "指定编码，null 时自动检测"}
        },
        "return": {
            "type": "string",
            "description": "文件文本内容。失败时返回 [SKILL_ERROR] 前缀错误信息。"
        }
    }
    [API_SCHEMA_END]

    Args:
        filepath:   文件路径
        max_length: 最大读取字符数
        encoding:   指定编码（None 则自动检测）

    Returns:
        文件文本内容。失败时返回 '[SKILL_ERROR] ...' 前缀错误信息。
    """
    try:
        resolved = _validate_path(filepath)

        if not os.path.isfile(resolved):
            return f"[SKILL_ERROR] 文件不存在: {filepath}"

        # 大小检查
        file_size = os.path.getsize(resolved)
        if file_size > _MAX_FILE_SIZE:
            return f"[SKILL_ERROR] 文件过大: {file_size} bytes (上限 {_MAX_FILE_SIZE} bytes)"

        # 编码检测
        enc = encoding or _detect_encoding(resolved)

        with open(resolved, "r", encoding=enc, errors="replace") as f:
            content = f.read()

        if len(content) > max_length:
            content = content[:max_length] + f"\n... [截断: 原文 {len(content)} 字符，已截取前 {max_length} 字符]"

        logger.info(f"[file_ops] 读取成功: {filepath} (size={file_size}, encoding={enc})")
        return content

    except ValueError as e:
        return f"[SKILL_ERROR] 路径校验失败: {e}"
    except Exception as e:
        return f"[SKILL_ERROR] 读取文件失败: {filepath}, error={e}"


def write_file(filepath: str, content: str, encoding: str = "utf-8") -> str:
    """
    原子写入文件 — 先写临时文件再 rename，防止半写损坏。

    [API_SCHEMA_START]
    {
        "name": "write_file",
        "description": "原子写入文件，先写临时文件再 rename，防半写损坏。",
        "parameters": {
            "filepath": {"type": "string", "required": true, "description": "目标文件路径，必须在白名单目录下"},
            "content": {"type": "string", "required": true, "description": "要写入的文本内容"},
            "encoding": {"type": "string", "required": false, "default": "utf-8", "description": "文件编码"}
        },
        "return": {
            "type": "string",
            "description": "成功返回 [SKILL_OK] 前缀信息，失败返回 [SKILL_ERROR] 前缀错误信息。"
        }
    }
    [API_SCHEMA_END]

    Args:
        filepath: 目标文件路径
        content:  要写入的内容
        encoding: 文件编码

    Returns:
        成功返回 '[SKILL_OK] ...'，失败返回 '[SKILL_ERROR] ...'
    """
    try:
        resolved = _validate_path(filepath)

        # 确保父目录存在
        parent = os.path.dirname(resolved)
        os.makedirs(parent, exist_ok=True)

        # 原子写入：临时文件 → rename
        dir_name = os.path.dirname(resolved)
        with tempfile.NamedTemporaryFile(
            mode="w", encoding=encoding, dir=dir_name,
            prefix=".aios_tmp_", suffix=".tmp", delete=False
        ) as tmp:
            tmp.write(content)
            tmp_path = tmp.name

        # rename 是原子操作（同一文件系统）
        os.replace(tmp_path, resolved)

        logger.info(f"[file_ops] 写入成功: {filepath} ({len(content)} chars)")
        return f"[SKILL_OK] 写入成功: {filepath} ({len(content)} 字符)"

    except ValueError as e:
        return f"[SKILL_ERROR] 路径校验失败: {e}"
    except Exception as e:
        # 清理临时文件
        if "tmp_path" in dir() and os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except Exception:
                pass
        return f"[SKILL_ERROR] 写入文件失败: {filepath}, error={e}"


def read_json(filepath: str) -> Any:
    """
    读取 JSON 文件并解析为 Python 对象。

    [API_SCHEMA_START]
    {
        "name": "read_json",
        "description": "读取 JSON 文件并解析为 Python 对象（dict 或 list）。",
        "parameters": {
            "filepath": {"type": "string", "required": true, "description": "JSON 文件路径"}
        },
        "return": {
            "type": "object",
            "description": "解析后的 Python 对象。失败时返回 [SKILL_ERROR] 前缀字符串，调用方必须先检查返回值类型。"
        }
    }
    [API_SCHEMA_END]

    Args:
        filepath: JSON 文件路径

    Returns:
        解析后的 Python 对象（dict/list），失败返回 '[SKILL_ERROR] ...' 字符串
    """
    content = read_file(filepath)
    if content.startswith("[SKILL_ERROR]"):
        return content
    try:
        return json.loads(content)
    except json.JSONDecodeError as e:
        return f"[SKILL_ERROR] JSON 解析失败: {filepath}, error={e}"


def write_json(filepath: str, data: Any, indent: int = 2, ensure_ascii: bool = False) -> str:
    """
    将 Python 对象序列化为 JSON 并原子写入文件。

    [API_SCHEMA_START]
    {
        "name": "write_json",
        "description": "将 Python 对象序列化为 JSON 并原子写入文件。",
        "parameters": {
            "filepath": {"type": "string", "required": true, "description": "目标 JSON 文件路径"},
            "data": {"type": "object", "required": true, "description": "要序列化的 Python 对象（必须 JSON 可序列化）"},
            "indent": {"type": "integer", "required": false, "default": 2, "minimum": 0, "maximum": 8, "description": "JSON 缩进空格数"},
            "ensure_ascii": {"type": "boolean", "required": false, "default": false, "description": "是否转义非 ASCII 字符"}
        },
        "return": {
            "type": "string",
            "description": "成功返回 [SKILL_OK] 前缀信息，失败返回 [SKILL_ERROR] 前缀错误信息。"
        }
    }
    [API_SCHEMA_END]

    Args:
        filepath:     目标文件路径
        data:         要序列化的 Python 对象
        indent:       缩进空格数
        ensure_ascii: 是否转义非 ASCII 字符

    Returns:
        成功返回 '[SKILL_OK] ...'，失败返回 '[SKILL_ERROR] ...'
    """
    try:
        content = json.dumps(data, indent=indent, ensure_ascii=ensure_ascii, default=str)
        return write_file(filepath, content)
    except (TypeError, ValueError) as e:
        return f"[SKILL_ERROR] JSON 序列化失败: {e}"


def list_files(directory: str, pattern: str = "*", recursive: bool = False) -> List[str]:
    """
    安全列出目录下的文件。

    [API_SCHEMA_START]
    {
        "name": "list_files",
        "description": "安全列出目录下的文件，支持 glob 模式和递归搜索。",
        "parameters": {
            "directory": {"type": "string", "required": true, "description": "目录路径，必须在白名单下"},
            "pattern": {"type": "string", "required": false, "default": "*", "description": "glob 匹配模式"},
            "recursive": {"type": "boolean", "required": false, "default": false, "description": "是否递归搜索子目录"}
        },
        "return": {
            "type": "array",
            "description": "文件相对路径列表（已排序）。失败时返回 [SKILL_ERROR] 前缀字符串。"
        }
    }
    [API_SCHEMA_END]

    Args:
        directory: 目录路径
        pattern:   glob 匹配模式
        recursive: 是否递归搜索

    Returns:
        文件路径列表，失败返回 '[SKILL_ERROR] ...' 字符串
    """
    try:
        resolved = _validate_path(directory)

        if not os.path.isdir(resolved):
            return f"[SKILL_ERROR] 目录不存在: {directory}"

        import glob
        search_pattern = os.path.join(resolved, "**" if recursive else "", pattern)
        files = glob.glob(search_pattern, recursive=recursive)
        # 只返回文件，不返回目录
        files = [f for f in files if os.path.isfile(f)]
        # 转为相对路径方便阅读
        rel_files = [os.path.relpath(f, resolved) for f in files]
        return sorted(rel_files)

    except ValueError as e:
        return f"[SKILL_ERROR] 路径校验失败: {e}"
    except Exception as e:
        return f"[SKILL_ERROR] 列出文件失败: {directory}, error={e}"
