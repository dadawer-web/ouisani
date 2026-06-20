#!/bin/bash
# C++ 项目构建脚本
# 由 create_cmake_project 自动生成

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"
BUILD_TYPE="Debug"

echo "=== CMake 构建开始 ==="
echo "构建类型: ${BUILD_TYPE}"
echo "构建目录: ${BUILD_DIR}"

# 创建构建目录
mkdir -p "${BUILD_DIR}"

# 运行 CMake 配置
echo "--- CMake 配置 ---"
cmake -B "${BUILD_DIR}" -S "${SCRIPT_DIR}" -DCMAKE_BUILD_TYPE=${BUILD_TYPE}

# 编译
echo "--- 编译 ---"
cmake --build "${BUILD_DIR}" --config ${BUILD_TYPE} -j$(nproc 2>/dev/null || echo 4)

echo "=== 构建完成 ==="
echo "可执行文件位置: ${BUILD_DIR}/"
