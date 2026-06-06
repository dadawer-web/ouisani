#!/usr/bin/env python3
"""
AIOS Python SDK — Example Agent

Demonstrates how to use the aios Python SDK to build
an AI agent that runs inside the AIOS sandbox.
"""

import aios


def main():
    print("=" * 60)
    print("  AIOS Python SDK — Example Agent")
    print("=" * 60)

    # 1. Read a task from the VFS
    print("\n[Example Agent] Reading task from /devhouse/prd.txt ...")
    prd = aios.read_file("/devhouse/prd.txt")
    print(f"[Example Agent] PRD content:\n{prd[:200]}...\n")

    # 2. Think with LLM
    print("[Example Agent] Asking LLM to analyze the PRD ...")
    analysis = aios.think(
        "请用一句话总结以下产品需求的核心价值：" + prd[:500]
    )
    print(f"[Example Agent] LLM Analysis:\n{analysis}\n")

    # 3. Write the result back to VFS
    print("[Example Agent] Writing analysis to /devhouse/analysis.txt ...")
    success = aios.write_file("/devhouse/analysis.txt", analysis)
    print(f"[Example Agent] Write result: {'OK' if success else 'FAILED'}")

    # 4. Raw syscall example — list all processes
    print("\n[Example Agent] Listing all processes via raw syscall ...")
    ps_result = aios.syscall("bin.ps", {})
    print(f"[Example Agent] Process list: {ps_result}")

    print("\n[Example Agent] Demo completed. Exiting.")


if __name__ == "__main__":
    main()
