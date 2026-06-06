"""
AIOS Python SDK — User-space syscall interface for sandboxed agents.

This SDK enables Python scripts running inside AIOS sandboxes to
communicate with the Java kernel via stdin/stdout JSON IPC.

Usage:
    import aios

    # Think with LLM
    answer = aios.think("What is the meaning of life?")

    # Read / Write VFS files
    content = aios.read_file("/devhouse/prd.txt")
    aios.write_file("/devhouse/output.txt", "Hello from Python!")

    # Raw syscall
    result = aios.syscall("bin.ps", {})
"""

import sys
import json


# ── Internal IPC ──────────────────────────────────────────────

def _send_request(action: str, params: dict = None) -> dict:
    """
    Send a syscall request to the AIOS Java kernel via stdin/stdout IPC.

    Protocol:
        1. Write JSON line to stdout  →  kernel picks it up
        2. Read JSON line from stdin  ←  kernel sends response back

    Args:
        action: The syscall action name (e.g. "llm.think", "vfs.read")
        params: Optional dict of parameters

    Returns:
        dict: The kernel's response as a parsed JSON dict
    """
    request = {
        "action": action,
        "params": params or {}
    }

    # Send request
    payload = json.dumps(request, ensure_ascii=False)
    sys.stdout.write(payload + "\n")
    sys.stdout.flush()

    # Block until kernel responds
    response_line = sys.stdin.readline()
    if not response_line:
        return {"success": False, "error": "Kernel connection closed"}

    try:
        return json.loads(response_line.strip())
    except json.JSONDecodeError as e:
        return {"success": False, "error": f"Invalid JSON from kernel: {e}"}


# ── Public API ────────────────────────────────────────────────

def syscall(action: str, params: dict = None) -> dict:
    """
    Issue a raw syscall to the AIOS kernel.

    Args:
        action: Syscall action (e.g. "bin.ps", "vfs.mount")
        params: Optional parameters dict

    Returns:
        dict with at least {"success": bool, ...}
    """
    return _send_request(action, params)


def think(prompt: str) -> str:
    """
    Invoke the LLM to think about a prompt.

    Args:
        prompt: The prompt string to send to the LLM

    Returns:
        str: The LLM's response text
    """
    result = _send_request("llm.think", {"prompt": prompt})
    if result.get("success"):
        return result.get("data", result.get("payload", ""))
    return f"[LLM ERROR] {result.get('error', 'Unknown error')}"


def read_file(path: str) -> str:
    """
    Read a file from the AIOS Virtual File System.

    Args:
        path: VFS path (e.g. "/devhouse/prd.txt")

    Returns:
        str: File contents
    """
    result = _send_request("vfs.read", {"path": path})
    if result.get("success"):
        return result.get("data", result.get("payload", ""))
    return f"[VFS ERROR] {result.get('error', 'File not found')}"


def write_file(path: str, data: str) -> bool:
    """
    Write data to a file in the AIOS Virtual File System.

    Args:
        path: VFS path (e.g. "/devhouse/output.txt")
        data: Content to write

    Returns:
        bool: True if successful
    """
    result = _send_request("vfs.write", {"path": path, "data": data})
    return result.get("success", False)


# ── Boot log ──────────────────────────────────────────────────

print("[Python SDK] Generated successfully. Ready for third-party developers.")
