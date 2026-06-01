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


class _GraphMixin:
    """Knowledge graph sub-module (GraphFS)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id
        self._path = "/dev/graph0"

    def ingest(self, text: str) -> dict[str, Any]:
        """Write text into the graph, auto-extracting knowledge triples.

        The kernel calls LLM to extract [Subject|Relation|Object] triples
        from the text and inserts them into the GraphNode.

        Args:
            text: Text containing knowledge to extract.

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

    def query(self, entity: str, depth: int = 2) -> dict[str, Any]:
        """Query the knowledge graph for a subgraph around an entity.

        Performs BFS from the given entity and returns all entities
        and edges within the specified depth.

        Args:
            entity: Starting entity name.
            depth: BFS traversal depth (default 2).

        Returns:
            Kernel response with subgraph data.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="GRAPH_QUERY",
            path=self._path,
            payload=entity,
            caller_id=self._agent_id,
            depth=depth,
        )

    def debug(self) -> dict[str, Any]:
        """Export the full knowledge graph as JSON for visualization.

        Returns:
            Kernel response with all entities and edges.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="DEBUG_GRAPH",
            path=self._path,
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

    def install_tool(self, tool_name: str) -> dict[str, Any]:
        """Hot-load a newly installed WASM tool into the kernel.

        After using ``aios_apt.py install <tool>`` to place a ``.wasm``
        file in ``./usr_lib_wasm/``, call this method to tell the
        kernel to rescan the directory and register the new tool
        without restarting.

        Args:
            tool_name: Name of the tool to hot-load (e.g. ``"math_tool"``).

        Returns:
            Kernel response with the updated tool list.
        """
        resp = self._kernel.syscall(
            "TOOL_INSTALL",
            payload=tool_name,
            caller_id=self._agent_id,
        )
        return resp


class _DisplayMixin:
    """UI display sub-module (framebuffer render via /dev/fb0)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id

    def render(self, ui_json: dict[str, Any]) -> dict[str, Any]:
        """Render a UI frame to the display bus (``write()`` on ``/dev/fb0``).

        The JSON payload is written into the kernel's DisplayNode ring
        buffer.  Any connected SSE client (``GET /ui/stream``) will
        receive the frame as a ``data: ...`` event in real time.

        Args:
            ui_json: Arbitrary JSON-serialisable dict representing a
                UI frame (e.g. dashboard widgets, text cards, progress
                bars, etc.).

        Returns:
            Kernel response.
        """
        return self._kernel.syscall(
            "VFS_CALL",
            action="WRITE",
            path="/dev/fb0",
            payload=json.dumps(ui_json),
            caller_id=self._agent_id,
        )


class _InterruptMixin:
    """External interrupt (IRQ) sub-module (webhook-driven events)."""

    def __init__(self, kernel: Kernel, agent_id: int) -> None:
        self._kernel = kernel
        self._agent_id = agent_id

    def wait_for_webhook(self) -> str:
        """Block until an external webhook IRQ arrives (Unix ``read()`` on IRQ device).

        Reads from ``/dev/irq/webhook0`` in the kernel VFS.  The call
        blocks the current thread until an external HTTP POST to
        ``/webhook/trigger`` injects a payload into the WebhookNode's
        event queue, which wakes up this reader via ``condition_variable``.

        Returns:
            The payload string that was injected by the webhook.
        """
        resp = self._kernel.syscall(
            "VFS_CALL",
            action="READ",
            path="/dev/irq/webhook0",
            caller_id=self._agent_id,
        )
        return resp.get("data", resp.get("message", str(resp)))


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
        self.graph = _GraphMixin(kernel, agent_id)
        self.tools = _ToolsMixin(kernel, agent_id)
        self.irq = _InterruptMixin(kernel, agent_id)
        self.ui = _DisplayMixin(kernel, agent_id)

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

    def spawn(
        self,
        role: str,
        initial_prompt: str = "",
        stdin: str = "",
        stdout: str = "",
    ) -> Agent:
        """Fork a child agent process (Unix ``fork()`` semantics).

        Creates a new Agent with a kernel-allocated PID, establishing a
        parent-child process tree relationship.

        The ``stdin`` and ``stdout`` parameters configure the child's
        I/O redirection, analogous to Unix ``dup2``.  When set, the
        child's LLM inference results will automatically read input
        from ``stdin`` and/or write output to ``stdout`` via the VFS.

        Args:
            role: Role description for the child agent (e.g. ``"researcher"``).
            initial_prompt: If non-empty, automatically fire a non-blocking
                ``think()`` call to the child so it starts working immediately.
            stdin: VFS path to read input from (e.g. ``"/tmp/pipes/agent_101_to_102"``).
                When the child calls ``think()``, the kernel reads data from
                this VFS path and prepends it to the LLM prompt.
            stdout: VFS path to write output to (e.g. ``"/tmp/pipes/agent_102_to_101"``).
                When the child's LLM inference completes, the kernel writes
                the result to this VFS path in addition to returning it.

        Returns:
            The newly spawned child ``Agent`` object.
        """
        resp = self.kernel.syscall(
            "AGENT_SPAWN",
            payload=role,
            caller_id=self.agent_id,
            stdin=stdin,
            stdout=stdout,
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

    def migrate(
        self,
        target_host: str = "127.0.0.1",
        target_port: int = 9090,
    ) -> dict[str, Any]:
        """Live-migrate this agent to a remote AIOS node.

        Suspends the agent on the local kernel, serializes its PCB and
        memory pages into a snapshot, then ships the snapshot to the
        target node where the agent is resurrected.

        The default ``target_port`` (9090) corresponds to the **syscall**
        port of the destination node.  The actual cluster RPC port is
        derived as ``target_port + 3`` (i.e. 8083 for port 8080, 9093
        for port 9090), matching the kernel's ``syscall_port + 3``
        convention for the HTTP webhook server.

        Args:
            target_host: IP address of the destination AIOS node.
            target_port: Syscall port of the destination node
                (default 9090).  The cluster RPC port is automatically
                computed as ``target_port + 3``.

        Returns:
            Response dict from the remote node, including
            ``migrated_from`` and ``migrated_to`` fields.

        Raises:
            RuntimeError: If the local AGENT_EXPORT step fails.
            ConnectionError: If the remote node is unreachable.
        """
        cluster_port = target_port + 3
        return self.kernel.migrate_agent(
            agent_id=self.agent_id,
            target_ip=target_host,
            target_port=cluster_port,
        )

    def __repr__(self) -> str:
        return f"Agent(id={self.agent_id})"
