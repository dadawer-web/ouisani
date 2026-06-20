#!/usr/bin/env python3
"""
CMake 项目构建文件生成器
节点: create_cmake_project
功能: 创建 C++ 项目的 CMake 构建文件
"""

import os
import sys
import json
from typing import Dict, List, Any, Optional
from pathlib import Path

# 获取工作目录
WORKSPACE_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUTS_DIR = os.path.join(WORKSPACE_DIR, "outputs")

# 检查是否在 AIOS 环境中，尝试导入 BaseAgent
try:
    from agents.base import BaseAgent
except ImportError:
    # 如果导入失败，创建一个简单的替代类用于测试
    class BaseAgent:
        def __init__(self, name: str = "BaseAgent"):
            self.name = name

        def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
            """处理数据的方法，需要子类重写"""
            raise NotImplementedError("Subclasses must implement process_data")

        def run(self, data: Dict[str, Any]) -> Dict[str, Any]:
            """运行代理处理数据"""
            print(f"Agent {self.name} starting to process data...", flush=True)
            try:
                result = self.process_data(data)
                print(f"Agent {self.name} completed successfully.", flush=True)
                return result
            except Exception as e:
                print(f"Agent {self.name} failed: {e}", flush=True)
                return {"status": "error", "error": str(e)}


def ensure_dir(path: str) -> None:
    """确保目录存在"""
    os.makedirs(path, exist_ok=True)


def read_previous_output() -> Dict[str, Any]:
    """读取前置节点的输出（如果有）"""
    # 尝试读取 agent_1_output.json 或其他前置节点输出
    possible_files = [
        os.path.join(WORKSPACE_DIR, "outputs", "agent_1_output.json"),
        os.path.join(WORKSPACE_DIR, "outputs", "create_core_source_files_output.json"),
        os.path.join(WORKSPACE_DIR, "outputs", "project_info.json"),
    ]
    for fpath in possible_files:
        if os.path.exists(fpath):
            try:
                with open(fpath, "r", encoding="utf-8") as f:
                    data = json.load(f)
                print(f"[create_cmake_project] 读取到前置输出: {fpath}", flush=True)
                return data
            except Exception as e:
                print(f"[create_cmake_project] 读取 {fpath} 失败: {e}", flush=True)
    return {}


def detect_source_files(project_dir: str) -> Dict[str, List[str]]:
    """检测项目目录中的源文件"""
    cpp_files = []
    h_files = []
    header_dirs = set()

    for root, dirs, files in os.walk(project_dir):
        # 跳过隐藏目录和构建目录
        dirs[:] = [d for d in dirs if not d.startswith('.') and d not in ('build', 'cmake-build')]
        for fname in files:
            fpath = os.path.join(root, fname)
            rel_path = os.path.relpath(fpath, project_dir)
            if fname.endswith(('.cpp', '.cc', '.cxx', '.c')):
                cpp_files.append(rel_path)
            elif fname.endswith(('.h', '.hpp', '.hxx')):
                h_files.append(rel_path)
                header_dirs.add(os.path.dirname(rel_path))

    return {
        "cpp_files": sorted(cpp_files),
        "h_files": sorted(h_files),
        "header_dirs": sorted(header_dirs),
    }


def generate_cmake_content(
    project_name: str,
    cpp_standard: int,
    source_files: List[str],
    header_dirs: List[str],
    libraries: List[str],
    executable_name: str,
    build_type: str,
) -> str:
    """生成 CMakeLists.txt 内容"""
    lines = []
    lines.append(f"cmake_minimum_required(VERSION 3.14)")
    lines.append(f"project({project_name} LANGUAGES CXX)")
    lines.append("")

    # C++ 标准设置
    lines.append(f"set(CMAKE_CXX_STANDARD {cpp_standard})")
    lines.append(f"set(CMAKE_CXX_STANDARD_REQUIRED ON)")
    lines.append(f"set(CMAKE_CXX_EXTENSIONS OFF)")
    lines.append("")

    # 构建类型
    lines.append(f'if(NOT CMAKE_BUILD_TYPE)')
    lines.append(f'  set(CMAKE_BUILD_TYPE "{build_type}")')
    lines.append(f"endif()")
    lines.append("")

    # 编译器警告选项
    lines.append("# Compiler warnings")
    lines.append("if(CMAKE_CXX_COMPILER_ID MATCHES \"GNU|Clang\")")
    lines.append("  add_compile_options(-Wall -Wextra -Wpedantic -Wno-unused-parameter)")
    lines.append("elseif(CMAKE_CXX_COMPILER_ID STREQUAL \"MSVC\")")
    lines.append("  add_compile_options(/W4)")
    lines.append("endif()")
    lines.append("")

    # 收集源文件
    if source_files:
        lines.append("# Source files")
        lines.append(f"set(SOURCES")
        for sf in source_files:
            lines.append(f"    {sf}")
        lines.append(")")
        lines.append("")

    # 头文件目录
    if header_dirs:
        lines.append("# Include directories")
        lines.append("include_directories(")
        for hd in header_dirs:
            if hd == '.' or hd == '':
                lines.append("    ${CMAKE_CURRENT_SOURCE_DIR}")
            else:
                lines.append(f"    {hd}")
        lines.append(")")
        lines.append("")

    # 可执行文件
    lines.append("# Create executable")
    if source_files:
        lines.append(f"add_executable({executable_name} ${{SOURCES}})")
    else:
        lines.append(f"add_executable({executable_name} src/main.cpp)")
    lines.append("")

    # 链接库
    if libraries:
        lines.append("# Link libraries")
        lines.append(f"target_link_libraries({executable_name} PRIVATE")
        for lib in libraries:
            lines.append(f"    {lib}")
        lines.append(")")
        lines.append("")

    # 安装目标
    lines.append("# Install target")
    lines.append(f"install(TARGETS {executable_name} DESTINATION bin)")
    lines.append("")

    return "\n".join(lines)


def generate_build_script(project_dir: str, build_type: str) -> str:
    """生成构建脚本 build.sh"""
    script = f"""#!/bin/bash
# C++ 项目构建脚本
# 由 create_cmake_project 自动生成

set -e

SCRIPT_DIR="$(cd "$(dirname "${{BASH_SOURCE[0]}}")" && pwd)"
BUILD_DIR="${{SCRIPT_DIR}}/build"
BUILD_TYPE="{build_type}"

echo "=== CMake 构建开始 ==="
echo "构建类型: ${{BUILD_TYPE}}"
echo "构建目录: ${{BUILD_DIR}}"

# 创建构建目录
mkdir -p "${{BUILD_DIR}}"

# 运行 CMake 配置
echo "--- CMake 配置 ---"
cmake -B "${{BUILD_DIR}}" -S "${{SCRIPT_DIR}}" -DCMAKE_BUILD_TYPE=${{BUILD_TYPE}}

# 编译
echo "--- 编译 ---"
cmake --build "${{BUILD_DIR}}" --config ${{BUILD_TYPE}} -j$(nproc 2>/dev/null || echo 4)

echo "=== 构建完成 ==="
echo "可执行文件位置: ${{BUILD_DIR}}/"
"""
    return script


class CreateCMakeProject(BaseAgent):
    """
    CMake 项目构建文件生成器
    功能: 为 C++ 项目创建 CMakeLists.txt 和构建脚本
    """

    def __init__(self, name: str = "CreateCMakeProject"):
        super().__init__(name=name)
        self.name = name

    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理数据：根据项目信息生成 CMake 构建文件

        输入参数 (data dict):
            - project_name: 项目名称 (默认: "MyCppProject")
            - project_dir: 项目目录路径 (默认: 当前目录下的 cpp_project)
            - cpp_standard: C++ 标准版本 (默认: 17)
            - libraries: 需要链接的库列表 (默认: [])
            - executable_name: 可执行文件名 (默认: 与 project_name 相同)
            - build_type: 构建类型 Debug/Release (默认: "Debug")
            - use_previous_output: 是否使用前置节点的输出 (默认: False)

        输出:
            - status: "success" / "error"
            - project_dir: 项目目录
            - cmake_file: CMakeLists.txt 路径
            - build_script: build.sh 路径
            - detected_files: 检测到的源文件信息
        """
        print("=" * 60, flush=True)
        print("[create_cmake_project] CMake 构建文件生成器启动", flush=True)
        print("=" * 60, flush=True)

        # 1. 读取参数
        project_name = data.get("project_name", "MyCppProject")
        cpp_standard = int(data.get("cpp_standard", 17))
        libraries = data.get("libraries", [])
        executable_name = data.get("executable_name", project_name)
        build_type = data.get("build_type", "Debug")
        use_previous = data.get("use_previous_output", False)

        # 确定项目目录
        project_dir = data.get("project_dir", "")
        if not project_dir:
            project_dir = os.path.join(WORKSPACE_DIR, "cpp_project")

        # 2. 如果需要使用前置节点输出，读取之
        if use_previous:
            prev_output = read_previous_output()
            if prev_output:
                # 尝试从前置输出中提取信息
                if "project_name" in prev_output:
                    project_name = prev_output["project_name"]
                if "project_dir" in prev_output:
                    project_dir = prev_output["project_dir"]
                if "source_files" in prev_output:
                    data["source_files_override"] = prev_output["source_files"]
                print(f"[create_cmake_project] 使用前置节点输出: project_name={project_name}", flush=True)

        print(f"[create_cmake_project] 项目名称: {project_name}", flush=True)
        print(f"[create_cmake_project] 项目目录: {project_dir}", flush=True)
        print(f"[create_cmake_project] C++ 标准: {cpp_standard}", flush=True)
        print(f"[create_cmake_project] 构建类型: {build_type}", flush=True)
        print(f"[create_cmake_project] 可执行文件: {executable_name}", flush=True)

        # 3. 确保输出目录存在
        ensure_dir(OUTPUTS_DIR)
        ensure_dir(project_dir)

        # 4. 检测源文件
        source_files_override = data.get("source_files_override", [])
        if source_files_override:
            detected = {
                "cpp_files": [f for f in source_files_override if f.endswith(('.cpp', '.cc', '.cxx', '.c'))],
                "h_files": [f for f in source_files_override if f.endswith(('.h', '.hpp', '.hxx'))],
                "header_dirs": sorted(set(
                    os.path.dirname(f) for f in source_files_override if f.endswith(('.h', '.hpp', '.hxx'))
                )),
            }
        else:
            detected = detect_source_files(project_dir)

        cpp_files = detected["cpp_files"]
        header_dirs = detected["header_dirs"]

        print(f"[create_cmake_project] 检测到 {len(cpp_files)} 个源文件, {len(detected['h_files'])} 个头文件", flush=True)
        for sf in cpp_files:
            print(f"  - {sf}", flush=True)

        # 5. 如果没有检测到任何源文件，创建一个默认的 src/main.cpp 占位
        if not cpp_files:
            main_cpp_path = os.path.join(project_dir, "src", "main.cpp")
            if not os.path.exists(main_cpp_path):
                ensure_dir(os.path.dirname(main_cpp_path))
                with open(main_cpp_path, "w", encoding="utf-8") as f:
                    f.write(f"""#include <iostream>

int main() {{
    std::cout << "Hello from {project_name}!" << std::endl;
    return 0;
}}
""")
                cpp_files = ["src/main.cpp"]
                header_dirs = ["."]
                print(f"[create_cmake_project] 创建默认入口文件: src/main.cpp", flush=True)

        # 6. 生成 CMakeLists.txt
        cmake_content = generate_cmake_content(
            project_name=project_name,
            cpp_standard=cpp_standard,
            source_files=cpp_files,
            header_dirs=header_dirs,
            libraries=libraries,
            executable_name=executable_name,
            build_type=build_type,
        )

        cmake_path = os.path.join(project_dir, "CMakeLists.txt")
        with open(cmake_path, "w", encoding="utf-8") as f:
            f.write(cmake_content)
        print(f"[create_cmake_project] 已生成 CMakeLists.txt: {cmake_path}", flush=True)

        # 7. 生成构建脚本
        build_script_content = generate_build_script(project_dir, build_type)
        build_script_path = os.path.join(project_dir, "build.sh")
        with open(build_script_path, "w", encoding="utf-8") as f:
            f.write(build_script_content)
        os.chmod(build_script_path, 0o755)
        print(f"[create_cmake_project] 已生成构建脚本: {build_script_path}", flush=True)

        # 8. 写出结果到输出文件
        result = {
            "status": "success",
            "node": "create_cmake_project",
            "project_name": project_name,
            "project_dir": project_dir,
            "cmake_file": cmake_path,
            "build_script": build_script_path,
            "cpp_standard": cpp_standard,
            "build_type": build_type,
            "executable_name": executable_name,
            "detected_files": {
                "cpp_count": len(cpp_files),
                "h_count": len(detected["h_files"]),
                "cpp_files": cpp_files,
                "h_files": detected["h_files"],
                "header_dirs": header_dirs,
            },
            "libraries": libraries,
        }

        output_path = os.path.join(OUTPUTS_DIR, "create_cmake_project_output.json")
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"[create_cmake_project] 结果已写入: {output_path}", flush=True)

        print("=" * 60, flush=True)
        print("[create_cmake_project] CMake 构建文件生成完成!", flush=True)
        print("=" * 60, flush=True)

        return result


if __name__ == "__main__":
    print("NODE_VERIFIED_AND_READY: create_cmake_project 启动测试", flush=True)

    # 默认测试参数
    test_data = {
        "project_name": "MyCppProject",
        "project_dir": os.path.join(WORKSPACE_DIR, "cpp_project"),
        "cpp_standard": 17,
        "libraries": [],
        "executable_name": "my_app",
        "build_type": "Debug",
        "use_previous_output": False,
    }

    # 如果命令行传入了 JSON 文件，使用它作为参数
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        with open(sys.argv[1], "r", encoding="utf-8") as f:
            test_data.update(json.load(f))

    agent = CreateCMakeProject()
    result = agent.run(test_data)

    if result.get("status") == "success":
        print("\n✅ 测试成功! CMake 项目已创建。", flush=True)
        print(f"  CMakeLists.txt: {result.get('cmake_file')}", flush=True)
        print(f"  Build Script: {result.get('build_script')}", flush=True)
    else:
        print(f"\n❌ 测试失败: {result.get('error', 'Unknown error')}", flush=True)
        sys.exit(1)