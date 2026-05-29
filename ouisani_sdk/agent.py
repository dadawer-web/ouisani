"""AIOS Agent high-level abstraction.

Provides a Pythonic interface for an Agent to interact with the AIOS kernel,
including sandbox execution, vector memory, semantic VFS, and LLM inference.
"""

from __future__ import annotations

import json
from typing import Any

from .kernel import Kernel


class _SandboxMixin:
    """Sandbox execution sub-module."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id

    def run_c_code(self, code: str) -> dict[str, Any]:
        """Compile and execute C code in the WasmEdge sandbox.

        The code is compiled with ``clang`` to ``.wasm``, then executed
        inside WasmEdge with Gas metering and memory isolation.

        Args:
            code: C source code to compile and execute.

        Returns:
            Kernel response containing sandbox stdout and exit code.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="COMPILE_AND_EXECUTE",
            payload=json.dumps({"code": code}),
            caller_id=self._agent_id,
        )


class _MemoryMixin:
    """Vector memory sub-module (OS-level RAG)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id
        self._path = f"/dev/vec_mem_{agent_id}"

    def remember(self, text: str) -> dict[str, Any]:
        """Write a memory entry into the agent's vector memory.

        The text is automatically embedded (real API or mock fallback)
        and stored in the VFS VectorNode for later semantic retrieval.

        Args:
            text: Text to remember.

        Returns:
            Kernel response.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="WRITE",
            path=self._path,
            payload=text,
            caller_id=self._agent_id,
        )

    def recall(self, query: str, top_k: int = 3) -> list[dict[str, Any]]:
        """Semantic search over the agent's vector memory.

        Uses cosine similarity on embeddings to find the most relevant
        memories for the given query.

        Args:
            query: Natural language query.
            top_k: Maximum number of results to return.

        Returns:
            List of ``{"text": ..., "score": ...}`` dicts, sorted by
            relevance (highest first).
        """
        resp = self._kernel.syscall(
            "VFS_CALL",
            action="SEARCH",
            path=self._path,
            payload=query,
            caller_id=self._agent_id,
            top_k=top_k,
        )
        return resp.get("results", [])


class _SemanticMixin:
    """Semantic VFS sub-module (natural language → VFS operations)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id

    def execute_intent(self, intent: str) -> dict[str, Any]:
        """Execute a natural language intent through the Semantic VFS.

        The kernel routes the intent through ``/dev/semantic``, which
        uses LLM to translate it into VFS operations (READ/WRITE/
        COMPILE_AND_EXECUTE) and replays them automatically.

        Args:
            intent: Natural language command (e.g. ``"帮我算第42个斐波那契数"``).

        Returns:
            Kernel response with the execution result.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="WRITE",
            path="/dev/semantic",
            payload=intent,
            caller_id=self._agent_id,
        )


class _ToolsMixin:
    """Dynamic tool linking sub-module (OS-level dlopen)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id

    def list(self) -> list[dict[str, Any]]:
        """Discover available pre-compiled WASM tools (``dlopen`` semantics).

        Scans the kernel's ``./usr_lib_wasm/`` directory and returns
        metadata for every registered ``.wasm`` tool.

        Returns:
            List of ``{"name": ..., "path": ...}`` dicts.
        """
        resp = self._kernel.syscall(
            "TOOL_DISCOVER",
            caller_id=self._agent_id,
        )
        return resp.get("data", [])

    def call(self, tool_name: str, args: dict[str, Any]) -> str:
        """Invoke a pre-compiled WASM tool by name (``dlsym`` + call).

        The kernel locates the tool, instantiates a WasmNode, passes
        the arguments via stdin, and returns the captured stdout.

        Args:
            tool_name: Registered tool name (e.g. ``"math_fast"``).
            args: Arguments dict passed as JSON to the WASM module.

        Returns:
            The tool's stdout output as a string.
        """
        resp = self._kernel.syscall(
            "TOOL_CALL",
            tool_name=tool_name,
            args=json.dumps(args),
            caller_id=self._agent_id,
        )
        return resp.get("stdout", resp.get("message", str(resp)))


class Agent:
    """High-level Agent abstraction for the AIOS kernel.

    An Agent owns a sandbox, vector memory, and semantic interface.
    It can think (LLM inference), remember (RAG), and act (sandbox).

    Args:
        kernel: Connected ``Kernel`` instance.
        agent_id: Unique agent identifier.

    Example::

        kernel = Kernel()
        agent = Agent(kernel, agent_id=101)

        # Think with LLM
        result = agent.think("What is the meaning of life?")

        # Remember and recall
        agent.memory.remember("I love programming in C++")
        hits = agent.memory.recall("programming languages")

        # Execute C code in sandbox
        output = agent.sandbox.run_c_code(r'''
        #include <stdio.h>
        int main() { printf("Hello AIOS!\\n"); return 0; }
        ''')

        # Natural language intent
        result = agent.semantic.execute_intent("帮我算第10个斐波那契数")
    """

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self.kernel = kernel
        self.agent_id = agent_id

        self.sandbox = _SandboxMixin(kernel, agent_id)
        self.memory = _MemoryMixin(kernel, agent_id)
        self.semantic = _SemanticMixin(kernel, agent_id)
        self.tools = _ToolsMixin(kernel, agent_id)

    def think(self, prompt: str, priority: int = 0) -> dict[str, Any]:
        """Invoke LLM inference through the kernel scheduler.

        The request is queued in the priority-driven LLM scheduler.
        Higher priority values preempt lower ones.

        Args:
            prompt: Prompt text for the LLM.
            priority: Task priority (0=normal, 99=critical).

        Returns:
            Kernel response containing the LLM output.
        """
        return self.kernel.syscall(
            "LLM_INFERENCE",
            payload=prompt,
            priority=priority,
            caller_id=self.agent_id,
        )

    def spawn(self, role: str, initial_prompt: str = "") -> Agent:
        """Fork a child agent process (Unix ``fork()`` semantics).

        Creates a new Agent with a kernel-allocated PID, establishing a
        parent-child process tree relationship.

        Args:
            role: Role description for the child agent (e.g. ``"researcher"``).
            initial_prompt: If non-empty, automatically fire a non-blocking
                ``think()`` call to the child so it starts working immediately.

        Returns:
            The newly spawned child ``Agent`` object.
        """
        resp = self.kernel.syscall(
            "AGENT_SPAWN",
            payload=role,
            caller_id=self.agent_id,
        )
        child_id = resp.get("child_id", -1)
        child = Agent(self.kernel, child_id)

        if initial_prompt and child_id > 0:
            child.think(initial_prompt)

        return child

    def wait(self, child_agent: Agent) -> str:
        """Block until a child agent exits (Unix ``waitpid()`` semantics).

        The calling agent is marked as ``BLOCKED`` in the kernel process
        table until the child calls :meth:`exit`.  The underlying TCP
        call blocks until the kernel resolves the child's ``promise``.

        Args:
            child_agent: The child ``Agent`` to wait on.

        Returns:
            The child's exit result string.
        """
        resp = self.kernel.syscall(
            "AGENT_WAIT",
            child_id=child_agent.agent_id,
            caller_id=self.agent_id,
        )
        return resp.get("data", "")

    def exit(self, result: str) -> dict[str, Any]:
        """Terminate this agent's process (Unix ``_exit()`` semantics).

        Sets the exit result and resolves the kernel ``promise``, which
        unblocks any parent that is ``wait()``-ing on this agent.

        Args:
            result: Exit result string delivered to the waiting parent.

        Returns:
            Kernel response.
        """
        return self.kernel.syscall(
            "AGENT_EXIT",
            payload=result,
            caller_id=self.agent_id,
        )

    def __repr__(self) -> str:
        return f"Agent(id={self.agent_id})"
