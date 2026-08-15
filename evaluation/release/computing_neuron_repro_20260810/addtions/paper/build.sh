#!/bin/bash
# =============================================================================
#  Neuron 论文构建脚本
#  使用用户空间安装的 TeX Live 2023 工具链编译 main.pdf
# =============================================================================
set -e

# ── 工具链环境（用户空间 TeX Live，无需 root）────────────────────────────────
export PATH="/home/xmy/.local/share/texlive/usr/bin:$PATH"
export TEXMFCNF="/home/xmy/.local/share/texlive/usr/share/texlive/texmf-dist/web2c:"
# 指向可写目录：已含 mf.base / pdflatex.fmt，且在沙箱白名单内
export TEXMFVAR="/home/xmy/.local/share/texlive/var/texmf"
export TEXMFCONFIG="/home/xmy/.local/share/texlive/var/texmf"
export VARTEXFONTS="/home/xmy/.local/share/texlive/var/texmf/fonts"

cd "$(dirname "$0")"

echo "==> Pass 1: pdflatex"
pdflatex -interaction=nonstopmode main.tex

echo "==> Pass 2: bibtex"
bibtex main

echo "==> Pass 3: pdflatex"
pdflatex -interaction=nonstopmode main.tex

echo "==> Pass 4: pdflatex (resolve cross-refs)"
pdflatex -interaction=nonstopmode main.tex

echo ""
echo "==> Done. PDF info:"
pdfinfo main.pdf 2>/dev/null | grep -E "Pages|File size"
