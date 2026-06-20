#!/usr/bin/env python3
"""
AIOS 技能索引器 (__skill_indexer__.py)
灵感来源：OpenClaw 的轻量级技能热插拔

核心逻辑：
  1. 遍历当前目录下所有 .py 文件（排除 __ 开头的文件）
  2. 解析每个函数的 Docstring，提取 [API_SCHEMA_START]/[API_SCHEMA_END] 之间的 JSON
  3. 整合所有 JSON Schema，自动生成 MANIFEST.md

用法：
  python3 __skill_indexer__.py [--dir /path/to/aios_skills]
"""

import os
import re
import json
import sys
import argparse
from typing import Dict, List, Optional, Any

# ── 常量 ──
SCHEMA_START = "[API_SCHEMA_START]"
SCHEMA_END = "[API_SCHEMA_END]"
MANIFEST_FILENAME = "MANIFEST.md"

# ── MANIFEST.md 顶部前言 ──
PREAMBLE = """\
# AIOS 技能舱 — 强类型 API 字典

> 这是 AIOS 技能舱的强类型 API 字典。请严格按照以下 JSON Schema 要求的参数类型，通过 `import skills.xxx` 调用。
> 本文件由 `__skill_indexer__.py` 自动生成，请勿手动编辑。

"""


def extract_schemas_from_file(filepath: str) -> List[Dict[str, Any]]:
    """
    从单个 Python 文件中提取所有 [API_SCHEMA_START]/[API_SCHEMA_END] 块。
    使用正则而非 ast，确保轻量且不依赖代码语法完整性。
    """
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except Exception as e:
        print(f"[WARN] 无法读取 {filepath}: {e}", file=sys.stderr)
        return []

    schemas = []
    pattern = re.escape(SCHEMA_START) + r"(.*?)" + re.escape(SCHEMA_END)
    matches = re.findall(pattern, content, re.DOTALL)

    for match in matches:
        json_str = match.strip()
        if not json_str:
            continue
        try:
            schema = json.loads(json_str)
            # 校验最小必要字段
            if "name" in schema:
                schemas.append(schema)
            else:
                print(f"[WARN] 跳过无 name 字段的 schema in {filepath}", file=sys.stderr)
        except json.JSONDecodeError as e:
            print(f"[WARN] JSON 解析失败 in {filepath}: {e}", file=sys.stderr)

    return schemas


def scan_directory(skills_dir: str) -> Dict[str, List[Dict[str, Any]]]:
    """
    扫描技能目录，返回 {module_name: [schema1, schema2, ...]} 映射。
    排除 __ 开头的文件。
    """
    modules: Dict[str, List[Dict[str, Any]]] = {}

    for filename in sorted(os.listdir(skills_dir)):
        if not filename.endswith(".py"):
            continue
        if filename.startswith("__"):
            continue

        module_name = filename[:-3]
        filepath = os.path.join(skills_dir, filename)

        schemas = extract_schemas_from_file(filepath)
        if schemas:
            modules[module_name] = schemas

    return modules


def schema_to_markdown(module_name: str, schema: Dict[str, Any]) -> str:
    """将单个 API Schema 转为 Markdown 契约块"""
    lines = []

    func_name = schema.get("name", "unknown")
    description = schema.get("description", "")
    parameters = schema.get("parameters", {})
    return_spec = schema.get("return", {})

    lines.append(f"### `{func_name}`")
    lines.append("")
    lines.append(f"- **模块**: `skills.{module_name}`")
    lines.append(f"- **导入**: `from skills.{module_name} import {func_name}`")
    if description:
        lines.append(f"- **说明**: {description}")
    lines.append("")

    # 参数表
    if parameters:
        lines.append("**参数**:")
        lines.append("")
        lines.append("| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |")
        lines.append("|--------|------|------|--------|------|------|")
        for pname, pspec in parameters.items():
            ptype = pspec.get("type", "any")
            required = pspec.get("required", True)
            default = pspec.get("default", "—")
            desc = pspec.get("description", "")

            # 约束列：minimum/maximum/enum/pattern
            constraints = []
            if "minimum" in pspec:
                constraints.append(f"min={pspec['minimum']}")
            if "maximum" in pspec:
                constraints.append(f"max={pspec['maximum']}")
            if "enum" in pspec:
                constraints.append(f"enum={pspec['enum']}")
            if "pattern" in pspec:
                constraints.append(f"pattern={pspec['pattern']}")
            constraint_str = ", ".join(constraints) if constraints else "—"

            req_str = "是" if required else "否"
            default_str = str(default) if default != "—" else "—"

            lines.append(f"| `{pname}` | `{ptype}` | {req_str} | `{default_str}` | {constraint_str} | {desc} |")
        lines.append("")

    # 返回值
    if return_spec:
        ret_type = return_spec.get("type", "any")
        ret_desc = return_spec.get("description", "")
        lines.append("**返回值**:")
        lines.append(f"- 类型: `{ret_type}`")
        if ret_desc:
            lines.append(f"- 说明: {ret_desc}")

        # 返回值 schema（如果有）
        ret_schema = return_spec.get("schema")
        if ret_schema:
            lines.append("")
            lines.append("**返回值结构**:")
            lines.append("```json")
            lines.append(json.dumps(ret_schema, ensure_ascii=False, indent=2))
            lines.append("```")

    lines.append("")
    lines.append("---")
    lines.append("")
    return "\n".join(lines)


def generate_manifest(modules: Dict[str, List[Dict[str, Any]]]) -> str:
    """生成完整的 MANIFEST.md 内容"""
    parts = [PREAMBLE]

    # 统计
    total_funcs = sum(len(schemas) for schemas in modules.values())
    parts.append(f"**已注册模块**: {len(modules)} | **已注册函数**: {total_funcs}")
    parts.append("")
    parts.append("---")
    parts.append("")

    # 快速索引
    parts.append("## 快速索引")
    parts.append("")
    for module_name, schemas in modules.items():
        for schema in schemas:
            func_name = schema.get("name", "?")
            desc = schema.get("description", "").split(".")[0]  # 首句
            parts.append(f"- `from skills.{module_name} import {func_name}` — {desc}")
    parts.append("")
    parts.append("---")
    parts.append("")

    # 各模块详细契约
    for module_name, schemas in modules.items():
        parts.append(f"## skills.{module_name}")
        parts.append("")
        for schema in schemas:
            parts.append(schema_to_markdown(module_name, schema))

    return "\n".join(parts)


def main():
    parser = argparse.ArgumentParser(description="AIOS 技能索引器 — 自动生成 MANIFEST.md")
    parser.add_argument(
        "--dir",
        default=None,
        help="技能目录路径（默认为本脚本所在目录）",
    )
    args = parser.parse_args()

    skills_dir = args.dir or os.path.dirname(os.path.abspath(__file__))

    if not os.path.isdir(skills_dir):
        print(f"[ERROR] 目录不存在: {skills_dir}", file=sys.stderr)
        sys.exit(1)

    # 扫描
    modules = scan_directory(skills_dir)

    if not modules:
        print("[WARN] 未发现任何含 [API_SCHEMA_START] 的技能文件", file=sys.stderr)
        sys.exit(0)

    # 生成
    manifest = generate_manifest(modules)

    # 写入
    output_path = os.path.join(skills_dir, MANIFEST_FILENAME)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(manifest)

    # 统计
    total_funcs = sum(len(s) for s in modules.values())
    print(f"[OK] {MANIFEST_FILENAME} 已生成: {output_path}")
    print(f"     模块数: {len(modules)} | 函数数: {total_funcs}")
    for m, schemas in modules.items():
        names = [s["name"] for s in schemas]
        print(f"     - skills.{m}: {', '.join(names)}")


if __name__ == "__main__":
    main()
