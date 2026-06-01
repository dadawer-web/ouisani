"""
AIOS Shell (aiosh) — Interactive REPL with pipeline and NL2Shell support.

Usage:
    python3 app_aiosh.py

Built-in commands:
    read <path>          Read from VFS path and print to stdout
    write <path> <data>  Write data to VFS path
    ls <path>            List VFS directory
    tree <path>          Show VFS tree
    ps                   Show /proc/agents
    events               Show kernel events
    help                 Show this help
    exit / quit          Exit the shell

Pipeline syntax:
    aios> agent 翻译官 | agent 摘要师 | agent 审校员
    aios> read /dev/camera0 | agent 安保检查 | write /dev/vec_mem_101

    Segments can be: agent <prompt>, read <path>, write <path>
    Adjacent segments are connected by VFS pipes automatically.

NL2Shell syntax:
    aios> #帮我读取摄像头的数据，让安保Agent检查一下，然后把结果存到101的记忆里
    aios> 自然语言:读取摄像头数据并让Agent分析后写入向量记忆

    Lines starting with '#' or '自然语言:' are compiled by the
    NL2Shell translator (a Root Agent) into pipeline commands,
    then automatically executed.
"""

from __future__ import annotations

import json
import shlex
import sys
import time
import urllib.request
import urllib.error

from ouisani_sdk import Kernel, Agent


SHELL_BANNER = r"""
  ___  _    _    ___   ___
 / _ \| |  | |  / __| / __|
| (_) | |__| |_| \__ \| (__
 \___/|____|\___/|___/ \___|

  AIOS Interactive Shell v2.0  (NL2Shell enabled)
  Type 'help' for built-in commands.
  Use '|' to pipe agents together.
  Prefix with '#' or '自然语言:' for NL compilation.
"""

PIPE_BASE = "/tmp/pipes"

NL2SHELL_SYSTEM_PROMPT = """\
You are the NL2Shell compiler for the AIOS operating system.
Your job is to translate the user's natural language intent into a single \
aiosh pipeline command string. No explanation, no markdown, just the command.

## AIOS VFS Device Map

The following virtual devices exist in the kernel VFS:

| VFS Path | Type | Description |
|---|---|---|
| /dev/camera0 | Camera | Read to capture a frame (returns image description) |
| /dev/fb0 | Display | Write JSON to render a UI frame |
| /dev/vec_mem_{id} | Vector Memory | Read/write semantic memory for agent {id} (e.g. /dev/vec_mem_101) |
| /dev/irq/webhook0 | Webhook IRQ | Read blocks until an external webhook arrives |
| /dev/semantic | Semantic VFS | Write a natural language intent to execute via LLM |
| /dev/net/http | HTTP Device | Network access |
| /proc/agents | ProcFS | Read to list all running agents |
| /proc/events | ProcFS | Read to list kernel events |
| /proc/kmsg | ProcFS | Read kernel log |
| /proc/version | ProcFS | Read kernel version |
| /tmp/pipes/* | Pipe | Named pipes for inter-agent IPC |

## aiosh Pipeline Syntax

A pipeline consists of segments separated by '|'. Each segment is one of:

1. `agent <prompt>` — Spawn an LLM agent with the given prompt.
   The agent reads from its stdin pipe (if any) and writes to its stdout pipe.
   Example: `agent 请翻译以下内容为英文`

2. `read <vfs_path>` — Read data from a VFS path.
   In a pipeline, the read output becomes the stdin of the next segment.
   Example: `read /dev/camera0`

3. `write <vfs_path>` — Write data to a VFS path.
   In a pipeline, this consumes the stdout of the previous segment.
   Example: `write /dev/vec_mem_101`

## Pipeline Rules

- Adjacent segments are automatically connected via kernel PipeNodes.
- The first segment's stdout goes to the pipe feeding the second segment's stdin.
- `read` at the start of a pipeline: reads VFS data, outputs it to the next segment.
- `write` at the end of a pipeline: receives the previous segment's output, writes to VFS.
- `agent` in the middle: receives piped input as context, sends LLM output to next pipe.

## Examples

User: 读取摄像头数据并让Agent分析
Output: read /dev/camera0 | agent 分析摄像头画面中的内容

User: 把摄像头数据给安保Agent检查，结果存到101的记忆
Output: read /dev/camera0 | agent 你是安保检查员，检查画面是否有异常 | write /dev/vec_mem_101

User: 让Agent A写一首诗，Agent B翻译成英文
Output: agent 写一首关于春天的中文诗 | agent 将以下内容翻译成英文

User: 读取101的记忆并让Agent总结
Output: read /dev/vec_mem_101 | agent 请总结以下内容

## Important

- Output ONLY the pipeline command string. No explanation, no quotes, no backticks.
- Use Chinese prompts for agents when the user speaks Chinese.
- Always use the correct VFS paths from the device map above.
- If the user mentions a specific agent ID for memory, use /dev/vec_mem_{id}.
"""


class AiosShell:
    def __init__(self, host: str = "127.0.0.1", port: int = 8080) -> None:
        self.kernel = Kernel(host=host, syscall_port=port)
        self.agent_counter = 0
        self.running = True
        self._nl_translator_id: int | None = None

    def _next_agent_id(self) -> int:
        self.agent_counter += 1
        return 100 + self.agent_counter

    def _get_nl_translator(self) -> Agent:
        if self._nl_translator_id is None:
            resp = self.kernel.syscall(
                "AGENT_SPAWN",
                payload="NL2Shell_Translator",
                caller_id=0,
            )
            self._nl_translator_id = resp.get("child_id", -1)
            if self._nl_translator_id < 0:
                self._nl_translator_id = 0
        return Agent(kernel=self.kernel, agent_id=self._nl_translator_id)

    def _create_pipe(self, pipe_name: str) -> str:
        pipe_path = f"{PIPE_BASE}/{pipe_name}"
        resp = self.kernel.create_pipe(pipe_path)
        if resp.get("status") != "ok":
            print(f"  [warning] create_pipe({pipe_path}): {resp.get('message', resp)}")
        return pipe_path

    def _vfs_read(self, path: str) -> str:
        resp = self.kernel.vfs_read(path)
        if resp.get("status") == "ok":
            data = resp.get("data", "")
            if isinstance(data, dict):
                return data.get("content", json.dumps(data, ensure_ascii=False))
            return str(data)
        return f"[error] {resp.get('message', resp)}"

    def _vfs_write(self, path: str, data: str) -> None:
        resp = self.kernel.vfs_write(path, data)
        if resp.get("status") != "ok":
            print(f"  [error] write failed: {resp.get('message', resp)}")

    def cmd_read(self, args: list[str]) -> None:
        if not args:
            print("  Usage: read <vfs_path>")
            return
        data = self._vfs_read(args[0])
        print(data)

    def cmd_write(self, args: list[str]) -> None:
        if len(args) < 2:
            print("  Usage: write <vfs_path> <data>")
            return
        self._vfs_write(args[0], " ".join(args[1:]))

    def cmd_ls(self, args: list[str]) -> None:
        path = args[0] if args else "/"
        resp = self.kernel.syscall("VFS_CALL", action="LIST", path=path)
        if resp.get("status") == "ok":
            print(resp.get("data", ""))
        else:
            print(f"  [error] {resp.get('message', resp)}")

    def cmd_tree(self, args: list[str]) -> None:
        path = args[0] if args else "/"
        resp = self.kernel.syscall("VFS_CALL", action="TREE", path=path)
        if resp.get("status") == "ok":
            print(resp.get("data", ""))
        else:
            print(f"  [error] {resp.get('message', resp)}")

    def cmd_ps(self, _args: list[str]) -> None:
        data = self._vfs_read("/proc/agents")
        print(data)

    def cmd_events(self, _args: list[str]) -> None:
        events = self.kernel.events()
        if not events:
            print("  (no events)")
            return
        for ev in events[-20:]:
            ts = ev.get("ts", "?")
            etype = ev.get("type", "?")
            src = ev.get("source", "?")
            msg = ev.get("message", "")
            print(f"  [{ts}] {etype}/{src}: {msg}")

    def cmd_help(self, _args: list[str]) -> None:
        print(__doc__)

    def _parse_pipeline_segment(self, seg: str) -> dict:
        seg = seg.strip()
        if seg.lower().startswith("agent "):
            return {"type": "agent", "prompt": seg[6:].strip()}
        if seg.lower().startswith("read "):
            return {"type": "read", "path": seg[5:].strip()}
        if seg.lower().startswith("write "):
            parts = seg[6:].strip().split(None, 1)
            return {"type": "write", "path": parts[0], "data": parts[1] if len(parts) > 1 else ""}
        return {"type": "agent", "prompt": seg}

    def execute_pipeline(self, segments_raw: list[str]) -> None:
        segments = [self._parse_pipeline_segment(s) for s in segments_raw]
        n = len(segments)
        if n == 0:
            return

        pipe_paths: list[str | None] = []
        for i in range(n - 1):
            pipe_paths.append(self._create_pipe(f"pipe_{i}_{i + 1}"))

        agents: list[Agent] = []
        parent = Agent(kernel=self.kernel, agent_id=0)

        for i, seg in enumerate(segments):
            stdin_path = pipe_paths[i - 1] if i > 0 else ""
            stdout_path = pipe_paths[i] if i < n - 1 else ""

            if seg["type"] == "read":
                data = self._vfs_read(seg["path"])
                if stdout_path and data:
                    self._vfs_write(stdout_path, data)
                    print(f"  [{i}] read {seg['path']} → {stdout_path} ({len(data)} bytes)")
                else:
                    print(f"  [{i}] read {seg['path']} → {data[:100]}")
                continue

            if seg["type"] == "write":
                if stdin_path:
                    data = self._vfs_read(stdin_path)
                    if data:
                        self._vfs_write(seg["path"], data)
                        print(f"  [{i}] {stdin_path} → write {seg['path']} ({len(data)} bytes)")
                    else:
                        print(f"  [{i}] write {seg['path']}: no data from stdin pipe")
                elif seg.get("data"):
                    self._vfs_write(seg["path"], seg["data"])
                    print(f"  [{i}] write {seg['path']}: inline data")
                continue

            agent_id = self._next_agent_id()
            role = f"pipeline_stage_{i}"

            resp = self.kernel.syscall(
                "AGENT_SPAWN",
                payload=role,
                caller_id=0,
                stdin=stdin_path,
                stdout=stdout_path,
            )
            child_id = resp.get("child_id", -1)
            if child_id < 0:
                print(f"  [error] failed to spawn agent for stage {i}")
                return

            agent = Agent(kernel=self.kernel, agent_id=child_id)
            agents.append(agent)

            print(f"  [{i}] Agent {child_id} | stdin={stdin_path or 'none'} | stdout={stdout_path or 'none'} | prompt=\"{seg['prompt'][:60]}\"")

            think_resp = agent.think(seg["prompt"], priority=50)
            llm_text = self._extract_llm_text(think_resp)
            agent.exit(result=llm_text or "")

        if not agents:
            return

        last = agents[-1]
        wait_resp = self.kernel.syscall("AGENT_WAIT", child_id=last.agent_id, caller_id=0)
        last_result = wait_resp.get("data", "") if wait_resp.get("status") == "ok" else ""

        if last_result:
            print(f"\n  ┌─── Pipeline Output (Agent {last.agent_id}) ───")
            for line in last_result.strip().split("\n"):
                print(f"  │ {line}")
            print(f"  └─── End ───")
        else:
            print(f"  Agent {last.agent_id} completed with no output.")

        for agent in agents[:-1]:
            try:
                self.kernel.syscall("AGENT_WAIT", child_id=agent.agent_id, caller_id=0)
            except Exception:
                pass

    def execute_single(self, prompt: str) -> None:
        agent_id = self._next_agent_id()
        role = "shell_agent"

        resp = self.kernel.syscall(
            "AGENT_SPAWN",
            payload=role,
            caller_id=0,
        )
        child_id = resp.get("child_id", -1)
        if child_id < 0:
            print("  [error] failed to spawn agent")
            return

        agent = Agent(kernel=self.kernel, agent_id=child_id)

        print(f"  Agent {child_id} thinking...")
        think_resp = agent.think(prompt, priority=50)

        llm_text = self._extract_llm_text(think_resp)
        agent.exit(result=llm_text or "")

        if llm_text:
            print(f"\n  ┌─── Agent {child_id} Output ───")
            for line in llm_text.strip().split("\n"):
                print(f"  │ {line}")
            print(f"  └─── End ───")
        else:
            print(f"  Agent {child_id} completed with no output.")

    def nl_compile(self, natural_language: str) -> str | None:
        print(f"  🔮 NL2Shell compiling...")

        compiled = self._call_llm_direct(NL2SHELL_SYSTEM_PROMPT, natural_language)
        if compiled is None:
            print(f"  [error] NL2Shell translation failed")
            return None

        compiled = compiled.strip()
        if compiled.startswith("```"):
            lines = compiled.split("\n")
            lines = lines[1:]
            if lines and lines[-1].strip().startswith("```"):
                lines = lines[:-1]
            compiled = "\n".join(lines).strip()

        return compiled

    def _call_llm_direct(self, system_prompt: str, user_input: str) -> str | None:
        openai_port = self.kernel.syscall_port + 2
        url = f"http://127.0.0.1:{openai_port}/v1/chat/completions"

        body = json.dumps({
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_input},
            ],
            "temperature": 0.1,
        }).encode("utf-8")

        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                result = json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            print(f"  [error] LLM direct call failed: {e}")
            return None

        choices = result.get("choices", [])
        if not choices:
            return None

        return choices[0].get("message", {}).get("content", None)

    @staticmethod
    def _extract_llm_text(resp: dict) -> str | None:
        data = resp.get("data")
        if data is None:
            return resp.get("message")

        if isinstance(data, str):
            try:
                data = json.loads(data)
            except (json.JSONDecodeError, TypeError):
                return data

        if isinstance(data, dict):
            return data.get("response") or data.get("data") or data.get("message") or str(data)

        return str(data)

    def parse_line(self, line: str) -> None:
        line = line.strip()
        if not line:
            return

        if line.startswith("#") or line.startswith("自然语言:"):
            nl_input = line[1:] if line.startswith("#") else line[len("自然语言:"):]
            nl_input = nl_input.strip()
            if not nl_input:
                return
            compiled = self.nl_compile(nl_input)
            if compiled is None:
                return
            print(f"  ✅ Compiled: {compiled}")
            print(f"  ─── Executing ───")
            self._execute_compiled(compiled)
            return

        self._execute_compiled(line)

    def _execute_compiled(self, line: str) -> None:
        if "|" in line:
            segments = [s.strip() for s in line.split("|")]
            valid = []
            for seg in segments:
                if seg:
                    valid.append(seg)
                else:
                    print("  [error] empty segment in pipeline")
                    return
            if valid:
                self.execute_pipeline(valid)
            return

        try:
            tokens = shlex.split(line)
        except ValueError as e:
            print(f"  [error] parse error: {e}")
            return

        if not tokens:
            return

        cmd = tokens[0].lower()
        args = tokens[1:]

        builtin = {
            "read": self.cmd_read,
            "write": self.cmd_write,
            "ls": self.cmd_ls,
            "tree": self.cmd_tree,
            "ps": self.cmd_ps,
            "events": self.cmd_events,
            "help": self.cmd_help,
            "exit": lambda _: self._quit(),
            "quit": lambda _: self._quit(),
        }

        if cmd in builtin:
            builtin[cmd](args)
        elif cmd == "agent":
            if args:
                self.execute_single(" ".join(args))
            else:
                print("  Usage: agent <prompt>")
        else:
            self.execute_single(line)

    def _quit(self) -> None:
        self.running = False
        print("  Bye!")

    def run(self) -> None:
        print(SHELL_BANNER)

        print(f"  Connecting to AIOS kernel at {self.kernel.host}:{self.kernel.syscall_port}...")
        try:
            resp = self.kernel.syscall("VFS_CALL", action="READ", path="/proc/version")
            if resp.get("status") == "ok":
                print(f"  ✅ Connected! Kernel: {resp.get('data', 'unknown version')}")
            else:
                print(f"  ⚠️  Kernel responded but with: {resp.get('message', resp)}")
        except (ConnectionError, TimeoutError) as e:
            print(f"  [error] Cannot connect to AIOS kernel: {e}")
            print(f"  Make sure aios_core is running on {self.kernel.host}:{self.kernel.syscall_port}.")
            sys.exit(1)

        print()

        while self.running:
            try:
                line = input("aios> ")
            except (EOFError, KeyboardInterrupt):
                print()
                self._quit()
                break

            self.parse_line(line)


def run_nl2shell_test(shell: AiosShell) -> None:
    print("=" * 60)
    print("  NL2Shell End-to-End Test")
    print("=" * 60)

    test_input = "#帮我读取摄像头的数据，让安保Agent检查一下，然后把检查结果存到101的向量记忆里"
    print(f"\n  📝 Test Input:\n    {test_input}\n")

    compiled = shell.nl_compile(test_input)

    if compiled is None:
        print("  ❌ NL2Shell compilation failed!")
        return

    print(f"  ✅ Compiled Command:\n    {compiled}\n")

    expected_patterns = ["read", "/dev/camera0", "agent", "write", "/dev/vec_mem_101"]
    found = [p for p in expected_patterns if p.lower() in compiled.lower()]
    missing = [p for p in expected_patterns if p.lower() not in compiled.lower()]

    print(f"  📋 Pattern Check:")
    for p in expected_patterns:
        status = "✅" if p.lower() in compiled.lower() else "❌"
        print(f"    {status} '{p}'")

    if missing:
        print(f"\n  ⚠️  Missing patterns: {missing}")
        print(f"  The compiled command may not fully match the expected pipeline.")
    else:
        print(f"\n  ✅ All expected patterns found in compiled command!")

    print(f"\n  ─── Executing compiled pipeline ───")
    shell._execute_compiled(compiled)

    print(f"\n{'=' * 60}")
    print(f"  NL2Shell Test Complete")
    print(f"{'=' * 60}\n")


def main() -> None:
    host = "127.0.0.1"
    port = 8080
    test_mode = False

    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg == "--host" and i + 1 < len(sys.argv):
            host = sys.argv[i + 1]
            i += 2
        elif arg == "--port" and i + 1 < len(sys.argv):
            port = int(sys.argv[i + 1])
            i += 2
        elif arg == "--test-nl":
            test_mode = True
            i += 1
        else:
            i += 1

    shell = AiosShell(host=host, port=port)

    if test_mode:
        try:
            shell.kernel.syscall("VFS_CALL", action="READ", path="/proc/version")
        except (ConnectionError, TimeoutError) as e:
            print(f"  [error] Cannot connect to AIOS kernel: {e}")
            sys.exit(1)
        run_nl2shell_test(shell)
    else:
        shell.run()


if __name__ == "__main__":
    main()
