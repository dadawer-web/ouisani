#!/bin/bash

# 番茄钟应用启动脚本
echo "🍅 番茄钟应用"
echo "================"
echo ""

# 检查是否安装了Python 3
if command -v python3 &> /dev/null; then
    echo "正在启动HTTP服务器..."
    echo "请在浏览器中访问: http://localhost:8000"
    echo ""
    echo "按 Ctrl+C 停止服务器"
    echo ""
    python3 -m http.server 8000
else
    echo "错误: 未找到Python 3"
    echo ""
    echo "请手动在浏览器中打开 index.html 文件"
    echo "或安装Python 3后重试"
fi