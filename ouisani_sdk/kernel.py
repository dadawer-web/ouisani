"""AIOS Kernel communication bridge.

Encapsulates raw TCP socket + JSON protocol into a clean Python API.
"""

from __future__ import annotations

import json
import socket
from typing import Any


class Kernel:
    """Low-level communication bridge to the AIOS microkernel.

    Connects to the kernel's Syscall port (8080) and MCP port (8081)
    over TCP, handling JSON serialization/deserialization transparently.

    Args:
        host: Kernel host address.
        syscall_port: Port for the Syscall server (epoll + eventfd).
        mcp_port: Port for the MCP server (JSON-RPC 2.0).
        timeout: Socket timeout in seconds.
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        syscall_port: int = 8080,
        mcp_port: int = 8081,
        timeout: float = 60,
    ) -> None:
        self.host = host
        self.syscall_port = syscall_port
        self.mcp_port = mcp_port
        self.timeout = timeout

    def _send_tcp(self, port: int, payload: dict[str, Any]) -> dict[str, Any]:
        """Send a JSON payload over TCP and return the parsed response.

        Args:
            port: Target TCP port.
            payload: Dict to be JSON-serialized and sent.

        Returns:
            Parsed JSON response from the kernel.

        Raises:
            ConnectionError: If the kernel is unreachable.
            TimeoutError: If the kernel does not respond within timeout.
            ValueError: If the response cannot be parsed as JSON.
        """
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(self.timeout)
        try:
            client.connect((self.host, port))
            client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
            raw = client.recv(131072).decode("utf-8").strip()
            if not raw:
                return {"status": "error", "message": "empty response from kernel"}
            return json.loads(raw)
        except socket.timeout as exc:
            raise TimeoutError(
                f"Kernel did not respond within {self.timeout}s on port {port}"
            ) from exc
        except ConnectionRefusedError as exc:
            raise ConnectionError(
                f"Kernel not reachable at {self.host}:{port}. Is aios_core running?"
            ) from exc
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON from kernel: {raw[:200]}") from exc
        finally:
            client.close()

    def syscall(
        self,
        syscall_name: str,
        *,
        action: str = "",
        path: str = "",
        payload: str = "",
        caller_id: int = 0,
        priority: int = 0,
        top_k: int = 3,
    ) -> dict[str, Any]:
        """Issue a syscall to the AIOS kernel.

        Args:
            syscall_name: Syscall type (e.g. ``"VFS_CALL"``, ``"LLM_INFERENCE"``).
            action: VFS action (``"READ"``, ``"WRITE"``, ``"SEARCH"``, etc.).
            path: VFS path (e.g. ``"/dev/vec_mem_101"``).
            payload: Payload string for the syscall.
            caller_id: Agent ID of the caller.
            priority: Task priority (0=low, 99=critical).
            top_k: Number of results for SEARCH.

        Returns:
            Kernel response as a dict.
        """
        req: dict[str, Any] = {
            "syscall": syscall_name,
            "caller_id": caller_id,
            "priority": priority,
            "payload": payload,
        }
        if action:
            req["action"] = action
        if path:
            req["path"] = path
        if action == "SEARCH":
            req["top_k"] = top_k
        return self._send_tcp(self.syscall_port, req)

    def mcp_call(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        req_id: int = 1,
    ) -> dict[str, Any]:
        """Issue a JSON-RPC 2.0 call to the MCP server.

        Args:
            method: MCP method name (e.g. ``"tools/list"``).
            params: Method parameters.
            req_id: JSON-RPC request ID.

        Returns:
            MCP response as a dict.
        """
        req: dict[str, Any] = {
            "jsonrpc": "2.0",
            "method": method,
            "id": req_id,
        }
        if params is not None:
            req["params"] = params
        return self._send_tcp(self.mcp_port, req)

    def events(self) -> list[dict[str, Any]]:
        """Poll the kernel event stream (``/proc/events``).

        Returns a list of events and clears the kernel-side buffer.
        Each event has ``ts``, ``type``, ``source``, ``message`` fields.
        """
        resp = self.syscall("VFS_CALL", action="READ", path="/proc/events")
        return resp.get("events", [])

    def ping(self) -> bool:
        """Check if the kernel is alive.

        Returns:
            ``True`` if the kernel responds, ``False`` otherwise.
        """
        try:
            resp = self._send_tcp(self.syscall_port, {"syscall": "PING"})
            return resp.get("status") == "ok"
        except (ConnectionError, TimeoutError, OSError):
            return False
