#!/usr/bin/env python3
"""
AIOS 技能注册表自动生成器 (Manifest Generator)
灵感来源：OpenClaw 的轻量级技能热插拔机制

用法：
  python3 generate_manifest.py [--skills-dir /path/to/aios_skills] [--output MANIFEST.md]

工作原理：
  1. 扫描 skills 目录下所有 .py 文件（排除 __init__.py 和本脚本）
  2. 用 ast 模块解析每个文件的公开函数（非 _ 开头）
  3. 提取函数签名（参数名、类型标注、默认值）、返回类型、docstring
  4. 将 Python 类型映射为 JSON Schema 类型
  5. 从 docstring 中提取 Args/Returns 描述
  6. 生成 Dify / OpenAPI 风格的 YAML 契约块
  7. 合并写入 MANIFEST.md
"""

import ast
import json
import os
import sys
import argparse
import re
from typing import Dict, List, Optional, Tuple, Any

# ── Python 类型 → JSON Schema 类型映射 ──
TYPE_MAP = {
    "str": "string",
    "int": "integer",
    "float": "number",
    "bool": "boolean",
    "list": "array",
    "dict": "object",
    "List": "array",
    "Dict": "object",
    "Any": "object",
    "Optional": "object",
    "Tuple": "array",
    "Set": "array",
    "None": "null",
    # 带泛型的常见写法
    "list[str]": "array",
    "list[int]": "array",
    "List[str]": "array",
    "Dict[str, Any]": "object",
}

# ── 默认值 → YAML 字面量 ──
def default_to_yaml(val) -> str:
    if val is None:
        return "null"
    if isinstance(val, bool):
        return "true" if val else "false"
    if isinstance(val, str):
        return f'"{val}"'
    return str(val)


def resolve_type(annotation) -> str:
    """将 ast 类型标注节点解析为 JSON Schema 类型字符串"""
    if annotation is None:
        return "object"

    # 简单名称：str, int, bool, etc.
    if isinstance(annotation, ast.Constant):
        return TYPE_MAP.get(str(annotation.value), "object")

    if isinstance(annotation, ast.Name):
        return TYPE_MAP.get(annotation.id, annotation.id)

    # Optional[X] → X | null
    if isinstance(annotation, ast.Subscript):
        if isinstance(annotation.value, ast.Name):
            container = annotation.value.id
            if container == "Optional":
                inner = resolve_type(annotation.slice)
                return f"{inner} | null"
            if container in ("List", "list"):
                return "array"
            if container in ("Dict", "dict"):
                return "object"
            if container == "Union":
                # 简化处理
                return "object"
        return "object"

    # 位或运算：str | None (Python 3.10+)
    if isinstance(annotation, ast.BinOp) and isinstance(annotation.op, ast.BitOr):
        left = resolve_type(annotation.left)
        right = resolve_type(annotation.right)
        return f"{left} | {right}"

    return "object"


def resolve_default(default_node) -> Tuple[str, Optional[str]]:
    """解析默认值节点，返回 (yaml_value, description_suffix)"""
    if default_node is None:
        return ("null", None)

    # None / True / False / 数字 / 字符串 — 统一用 ast.Constant (Python 3.8+)
    if isinstance(default_node, ast.Constant):
        return (default_to_yaml(default_node.value), None)

    # Name (True, False, None in older Python)
    if isinstance(default_node, ast.Name):
        if default_node.id in ("True", "False", "None"):
            return (default_node.id.lower(), None)
        # 模块常量引用（如 _TIMEOUT, _DEFAULT_TIMEOUT）— 标记为常量引用
        return ("null", f"默认为模块常量 {default_node.id}")

    # 列表 []
    if isinstance(default_node, ast.List):
        if len(default_node.elts) == 0:
            return ("[]", None)
        return ("[]", None)

    # 字典 {}
    if isinstance(default_node, ast.Dict):
        return ("{}", None)

    return ("null", None)


def parse_docstring(docstring: str) -> Dict[str, str]:
    """
    从 Google 风格 docstring 中提取参数描述和返回值描述。
    支持 Args: 和 Returns: 段落。
    """
    if not docstring:
        return {"params": {}, "returns": ""}

    result = {"params": {}, "returns": ""}
    lines = docstring.strip().splitlines()

    current_section = None  # "args" | "returns" | None
    current_param = None

    for line in lines:
        stripped = line.strip()

        if stripped.startswith("Args:"):
            current_section = "args"
            continue
        if stripped.startswith("Returns:"):
            current_section = "returns"
            continue
        if stripped.startswith("Raises:"):
            current_section = None
            continue

        if current_section == "args":
            # 匹配 "param_name: description" 或 "param_name (type): description"
            param_match = re.match(r"^(\w+)\s*(?:\([^)]*\))?\s*:\s*(.*)", stripped)
            if param_match:
                current_param = param_match.group(1)
                result["params"][current_param] = param_match.group(2).strip()
            elif current_param and stripped:
                # 续行
                result["params"][current_param] += " " + stripped

        elif current_section == "returns":
            if stripped:
                result["returns"] += (" " if result["returns"] else "") + stripped

    return result


def extract_module_constants(source: str) -> Dict[str, Any]:
    """从 Python 源码中提取模块级常量赋值（大写/下划线开头的简单赋值）"""
    tree = ast.parse(source)
    constants = {}

    for node in ast.iter_child_nodes(tree):
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if isinstance(target, ast.Name) and (target.id.isupper() or target.id.startswith("_")):
                # 只提取简单常量（数字、字符串、布尔）
                if isinstance(node.value, ast.Constant):
                    constants[target.id] = node.value.value
                elif isinstance(node.value, ast.UnaryOp) and isinstance(node.value.op, ast.USub):
                    # 负数
                    if isinstance(node.value.operand, ast.Constant):
                        constants[target.id] = -node.value.operand.value

    return constants


def extract_api_schema(docstring: str) -> Optional[Dict[str, Any]]:
    """
    从 docstring 中提取 [API_SCHEMA_START]/[API_SCHEMA_END] 内嵌 JSON 契约。
    如果存在，返回解析后的 dict；否则返回 None。
    """
    if not docstring:
        return None

    start_marker = "[API_SCHEMA_START]"
    end_marker = "[API_SCHEMA_END]"

    start_idx = docstring.find(start_marker)
    end_idx = docstring.find(end_marker)

    if start_idx == -1 or end_idx == -1 or end_idx <= start_idx:
        return None

    json_str = docstring[start_idx + len(start_marker):end_idx].strip()
    try:
        return json.loads(json_str)
    except json.JSONDecodeError as e:
        print(f"[WARN] API_SCHEMA JSON 解析失败: {e}", file=sys.stderr)
        return None


def extract_public_functions(source: str) -> List[Dict[str, Any]]:
    """从 Python 源码中提取所有公开函数的元信息"""
    tree = ast.parse(source)
    functions = []

    # 先提取模块常量，用于解析默认值
    constants = extract_module_constants(source)

    for node in ast.iter_child_nodes(tree):
        if not isinstance(node, ast.FunctionDef):
            continue
        # 跳过私有函数
        if node.name.startswith("_"):
            continue

        func_info = {
            "name": node.name,
            "params": [],
            "return_type": "object",
            "docstring": ast.get_docstring(node) or "",
            "description": "",
            "api_schema": None,  # 内嵌 API_SCHEMA 契约
        }

        # ── 优先检查内嵌 API_SCHEMA ──
        api_schema = extract_api_schema(func_info["docstring"])
        if api_schema:
            func_info["api_schema"] = api_schema
            func_info["description"] = api_schema.get("description", "")
            # 从 API_SCHEMA 构建参数信息（权威来源）
            schema_params = api_schema.get("parameters", {})
            for pname, pspec in schema_params.items():
                param_info = {
                    "name": pname,
                    "type": pspec.get("type", "object"),
                    "required": pspec.get("required", True),
                    "default": pspec.get("default", None),
                    "description": pspec.get("description", ""),
                }
                if "minimum" in pspec:
                    param_info["minimum"] = pspec["minimum"]
                if "maximum" in pspec:
                    param_info["maximum"] = pspec["maximum"]
                func_info["params"].append(param_info)
            # 返回值
            return_schema = api_schema.get("return", {})
            func_info["return_type"] = return_schema.get("type", "object")
            func_info["return_description"] = return_schema.get("description", "")
            func_info["return_schema"] = return_schema.get("schema")

            functions.append(func_info)
            continue  # API_SCHEMA 已是权威来源，跳过 ast 推断

        # ── 无 API_SCHEMA 时，走 ast 推断 ──
        doc = parse_docstring(func_info["docstring"])
        first_line = func_info["docstring"].split("\n")[0].strip() if func_info["docstring"] else ""
        func_info["description"] = first_line

        args = node.args
        defaults = args.defaults
        num_required = len(args.args) - len(defaults)

        for i, arg in enumerate(args.args):
            if arg.arg == "self":
                continue

            param_info = {
                "name": arg.arg,
                "type": resolve_type(arg.annotation),
                "required": i < num_required,
                "default": None,
                "description": doc["params"].get(arg.arg, ""),
            }

            default_idx = i - num_required
            if default_idx >= 0 and default_idx < len(defaults):
                yaml_val, suffix = resolve_default(defaults[default_idx])
                if isinstance(defaults[default_idx], ast.Name) and defaults[default_idx].id in constants:
                    actual_val = constants[defaults[default_idx].id]
                    yaml_val = default_to_yaml(actual_val)
                    suffix = f"默认 {actual_val}（模块常量 {defaults[default_idx].id}）"
                param_info["default"] = yaml_val
                if suffix:
                    param_info["description"] += f" ({suffix})" if param_info["description"] else suffix

            func_info["params"].append(param_info)

        if node.returns:
            func_info["return_type"] = resolve_type(node.returns)

        func_info["return_description"] = doc.get("returns", "")

        functions.append(func_info)

    return functions


def extract_module_docstring(source: str) -> str:
    """提取模块级 docstring"""
    tree = ast.parse(source)
    return ast.get_docstring(tree) or ""


def generate_yaml_block(module_name: str, func: Dict[str, Any]) -> str:
    """为单个函数生成 Dify 风格的 YAML 契约块"""
    lines = []
    lines.append(f"### {func['name']}")
    lines.append("")
    lines.append("```yaml")
    lines.append(f"function: {func['name']}")
    lines.append(f"module: skills.{module_name}")
    lines.append(f"description: {func['description']}")
    lines.append(f"import_statement: \"from skills.{module_name} import {func['name']}\"")

    # 参数
    if func["params"]:
        lines.append("")
        lines.append("parameters:")
        for p in func["params"]:
            lines.append(f"  {p['name']}:")
            lines.append(f"    type: {p['type']}")
            lines.append(f"    required: {'true' if p['required'] else 'false'}")
            if not p["required"] and p["default"] is not None:
                lines.append(f"    default: {p['default']}")
            if p.get("minimum") is not None:
                lines.append(f"    minimum: {p['minimum']}")
            if p.get("maximum") is not None:
                lines.append(f"    maximum: {p['maximum']}")
            if p["description"]:
                lines.append(f"    description: {p['description']}")

    # 返回值
    lines.append("")
    lines.append("return:")
    lines.append(f"  type: {func['return_type']}")
    if func.get("return_description"):
        ret_desc = func["return_description"]
        if "\n" in ret_desc or len(ret_desc) > 80:
            lines.append("  description: |")
            for desc_line in ret_desc.split("\n"):
                lines.append(f"    {desc_line}")
        else:
            lines.append(f"  description: {ret_desc}")
    # 内嵌 schema（来自 API_SCHEMA）
    if func.get("return_schema"):
        lines.append("  schema:")
        for key, val in func["return_schema"].items():
            if isinstance(val, dict):
                lines.append(f"    {key}:")
                for k2, v2 in val.items():
                    lines.append(f"      {k2}: {v2}")
            elif isinstance(val, list):
                lines.append(f"    {key}:")
                for item in val:
                    if isinstance(item, dict):
                        lines.append(f"      -")
                        for k2, v2 in item.items():
                            lines.append(f"          {k2}: {v2}")
                    else:
                        lines.append(f"      - {item}")
            else:
                lines.append(f"    {key}: {val}")

    lines.append("```")
    return "\n".join(lines)


def scan_skills_dir(skills_dir: str) -> List[Dict[str, Any]]:
    """扫描技能目录，提取所有模块和函数信息"""
    modules = []

    for filename in sorted(os.listdir(skills_dir)):
        if not filename.endswith(".py"):
            continue
        if filename.startswith("__"):
            continue
        if filename == "generate_manifest.py":
            continue

        module_name = filename[:-3]  # 去掉 .py
        filepath = os.path.join(skills_dir, filename)

        try:
            with open(filepath, "r", encoding="utf-8") as f:
                source = f.read()
        except Exception as e:
            print(f"[WARN] 无法读取 {filepath}: {e}", file=sys.stderr)
            continue

        module_doc = extract_module_docstring(source)
        functions = extract_public_functions(source)

        if not functions:
            continue  # 跳过没有公开函数的模块

        # 从模块 docstring 提取简短描述
        short_desc = module_doc.split("\n")[0].strip() if module_doc else f"{module_name} 技能模块"

        modules.append({
            "name": module_name,
            "description": short_desc,
            "functions": functions,
        })

    return modules


def generate_manifest(modules: List[Dict[str, Any]]) -> str:
    """生成完整的 MANIFEST.md 内容"""
    parts = []

    # 头部
    parts.append("# AIOS 本地技能注册表 — 强类型契约版 (Strongly-Typed Skills Registry)")
    parts.append("")
    parts.append("> 本文件由 `generate_manifest.py` 自动生成。修改技能文件后重新运行即可更新。")
    parts.append("> 本文件采用 Dify / OpenAPI 风格的 JSON Schema 契约描述每个技能的参数和返回值。")
    parts.append("> 大模型在调用前必须严格遵循此契约，参数类型不匹配将导致运行时错误。")
    parts.append("")

    # 各模块
    for idx, mod in enumerate(modules, 1):
        parts.append("---")
        parts.append("")
        parts.append(f"## {idx}. {mod['description']} (skills.{mod['name']})")

        for func in mod["functions"]:
            parts.append("")
            parts.append(generate_yaml_block(mod["name"], func))

        parts.append("")

    # 尾部：未来扩展占位
    parts.append("---")
    parts.append("")
    parts.append("## 自动发现说明")
    parts.append("")
    parts.append("将新的 `.py` 技能文件放入本目录后，运行以下命令重新生成此文件：")
    parts.append("```bash")
    parts.append("python3 /home/xmy/tryaios/aios-java/aios_skills/generate_manifest.py")
    parts.append("```")
    parts.append("")
    parts.append("生成器会自动：")
    parts.append("1. 扫描目录下所有 `.py` 文件（排除 `__init__.py` 和 `generate_manifest.py`）")
    parts.append("2. 用 `ast` 模块解析公开函数（非 `_` 开头）的签名和 docstring")
    parts.append("3. 将 Python 类型标注映射为 JSON Schema 类型")
    parts.append("4. 生成 Dify 风格的 YAML 契约块并写入本文件")

    return "\n".join(parts)


def main():
    parser = argparse.ArgumentParser(description="AIOS 技能注册表自动生成器")
    parser.add_argument(
        "--skills-dir",
        default="/home/xmy/tryaios/aios-java/aios_skills",
        help="技能目录路径",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="输出文件路径（默认覆盖 skills-dir/MANIFEST.md）",
    )
    args = parser.parse_args()

    skills_dir = args.skills_dir
    output_path = args.output or os.path.join(skills_dir, "MANIFEST.md")

    if not os.path.isdir(skills_dir):
        print(f"[ERROR] 技能目录不存在: {skills_dir}", file=sys.stderr)
        sys.exit(1)

    # 扫描
    modules = scan_skills_dir(skills_dir)

    if not modules:
        print("[WARN] 未发现任何技能模块（含公开函数的 .py 文件）", file=sys.stderr)
        sys.exit(0)

    # 生成
    manifest = generate_manifest(modules)

    # 写入
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(manifest)

    # 统计
    total_funcs = sum(len(m["functions"]) for m in modules)
    print(f"[OK] MANIFEST.md 已生成: {output_path}")
    print(f"     模块数: {len(modules)}")
    print(f"     函数数: {total_funcs}")
    for m in modules:
        func_names = [f["name"] for f in m["functions"]]
        print(f"     - skills.{m['name']}: {', '.join(func_names)}")


if __name__ == "__main__":
    main()
