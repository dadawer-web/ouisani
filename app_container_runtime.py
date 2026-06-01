#!/usr/bin/env python3
"""AIOS Container Runtime — Agent 容器构建引擎

解析 Agentfile 语法，构建并运行隔离容器：

  ┌──────────────────────────────────────────────────────────────────────┐
  │  Agentfile 语法                                                     │
  │                                                                      │
  │  FROM aios/base_c_wasm        # 基础运行环境                        │
  │  LIMIT_TOKENS 50000           # Cgroup Token 限制                   │
  │  MOUNT /tmp/host_data /data   # VFS 目录映射                        │
  │  COPY ./my_tool.c /src/       # 拷贝源码                            │
  │  BUILD aios_gcc /src/my_tool.c -o /bin/agent.wasm  # 自动编译      │
  │  ENTRYPOINT /bin/agent.wasm   # 入口点                              │
  │                                                                      │
  ├──────────────────────────────────────────────────────────────────────┤
  │  构建流程                                                           │
  │                                                                      │
  │  app_container_runtime.py build <name>                               │
  │    ├── 解析 Agentfile                                                │
  │    ├── 创建构建上下文 (.aios_containers/<name>/)                     │
  │    ├── COPY: 拷贝源码到构建上下文                                    │
  │    ├── BUILD: Clang → WASM 编译                                      │
  │    └── 保存镜像元数据 (image.json)                                   │
  │                                                                      │
  ├──────────────────────────────────────────────────────────────────────┤
  │  运行流程                                                           │
  │                                                                      │
  │  app_container_runtime.py run <name>                                 │
  │    ├── 加载镜像元数据                                                │
  │    ├── CGROUP_CREATE → 创建 Cgroup 限制                              │
  │    ├── AGENT_SPAWN (CLONE_NEWNS) → 隔离 VFS 命名空间                │
  │    ├── CGROUP_ATTACH → 绑定 Cgroup                                   │
  │    ├── MOUNT → VFS 目录映射                                          │
  │    └── VFS_CALL EXECUTE_MODULE → 执行 ENTRYPOINT                    │
  │                                                                      │
  └──────────────────────────────────────────────────────────────────────┘
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import time

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
CONTAINER_DIR = os.path.join(PROJECT_ROOT, ".aios_containers")
WASI_SDK_PATH = "/opt/wasi-sdk"
WASI_SYSROOT = f"{WASI_SDK_PATH}/share/wasi-sysroot"

SYSCALL_PORT = 8080
HTTP_PORT = 8083

AIOS_CLONE_NEWNS = 0x00020000

BANNER = r"""
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   🐳  AIOS Container Runtime  🐳                                            ║
║                                                                              ║
║   "Build once, run isolated — just like real containers, but for Agents."    ║
║                                                                              ║
║   Agentfile ──► Build ──► Image ──► Run ──► Isolated Agent                  ║
║       │                      │                   │                            ║
║   FROM/LIMIT/COPY       Clang→WASM      CLONE_NEWNS + Cgroup                ║
║   MOUNT/BUILD           .wasm binary     VFS chroot + Token limit            ║
║   ENTRYPOINT                                                             ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
"""


def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"  [{ts}] [{tag}] {msg}")


def send_syscall(payload: dict, timeout: float = 30) -> dict:
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(("127.0.0.1", SYSCALL_PORT))
    client.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


def check_kernel_online() -> bool:
    try:
        resp = send_syscall({"syscall": "VFS_CALL", "action": "LIST", "path": "/", "caller_id": 0}, timeout=5)
        return resp.get("status") == "ok"
    except Exception:
        return False


class AgentfileParser:
    def __init__(self, filepath: str):
        self.filepath = filepath
        self.base_image = ""
        self.limit_tokens = 0
        self.cpu_quota = 100.0
        self.mounts = []
        self.copies = []
        self.builds = []
        self.entrypoint = ""

    def parse(self) -> bool:
        if not os.path.exists(self.filepath):
            log("ERROR", f"Agentfile not found: {self.filepath}")
            return False

        with open(self.filepath, "r") as f:
            lines = f.readlines()

        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                continue

            parts = line.split()
            directive = parts[0].upper()

            if directive == "FROM":
                self.base_image = parts[1] if len(parts) > 1 else ""
            elif directive == "LIMIT_TOKENS":
                self.limit_tokens = int(parts[1]) if len(parts) > 1 else 0
            elif directive == "LIMIT_CPU":
                self.cpu_quota = float(parts[1]) if len(parts) > 1 else 100.0
            elif directive == "MOUNT":
                if len(parts) >= 3:
                    self.mounts.append({"host": parts[1], "container": parts[2]})
            elif directive == "COPY":
                if len(parts) >= 3:
                    self.copies.append({"src": parts[1], "dst": parts[2]})
                elif len(parts) >= 2:
                    self.copies.append({"src": parts[1], "dst": parts[1]})
            elif directive == "BUILD":
                if len(parts) >= 2:
                    self.builds.append({
                        "compiler": parts[1] if len(parts) > 1 else "aios_gcc",
                        "args": parts[2:] if len(parts) > 2 else [],
                    })
            elif directive == "ENTRYPOINT":
                self.entrypoint = " ".join(parts[1:])

        log("Parse", f"FROM:       {self.base_image}")
        log("Parse", f"LIMIT_TPM:  {self.limit_tokens}")
        log("Parse", f"CPU_QUOTA:  {self.cpu_quota}%")
        log("Parse", f"MOUNTS:     {len(self.mounts)}")
        log("Parse", f"COPIES:     {len(self.copies)}")
        log("Parse", f"BUILDS:     {len(self.builds)}")
        log("Parse", f"ENTRYPOINT: {self.entrypoint}")

        return True


class ContainerBuilder:
    def __init__(self, name: str, parser: AgentfileParser):
        self.name = name
        self.parser = parser
        self.context_dir = os.path.join(CONTAINER_DIR, name)
        self.image_dir = os.path.join(self.context_dir, "image")

    def build(self) -> bool:
        log("Build", f"Building container image: {self.name}")

        os.makedirs(self.image_dir, exist_ok=True)
        os.makedirs(os.path.join(self.image_dir, "bin"), exist_ok=True)
        os.makedirs(os.path.join(self.image_dir, "src"), exist_ok=True)

        for copy_item in self.parser.copies:
            if not self._copy_file(copy_item):
                return False

        for build_item in self.parser.builds:
            if not self._compile(build_item):
                return False

        if not self._save_image_metadata():
            return False

        log("Build", f"✅ Container image '{self.name}' built successfully!")
        return True

    def _copy_file(self, copy_item: dict) -> bool:
        src = os.path.join(PROJECT_ROOT, copy_item["src"])
        dst = os.path.join(self.image_dir, copy_item["dst"].lstrip("/"))

        if os.path.isdir(src):
            if os.path.exists(dst):
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
            log("Copy", f"📁 {copy_item['src']} → {copy_item['dst']} (directory)")
        elif os.path.isfile(src):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            log("Copy", f"📄 {copy_item['src']} → {copy_item['dst']}")
        else:
            log("ERROR", f"COPY source not found: {src}")
            return False

        return True

    def _compile(self, build_item: dict) -> bool:
        args = build_item["args"]
        if not args:
            log("ERROR", "BUILD directive requires arguments")
            return False

        src_file = None
        out_file = None
        i = 0
        while i < len(args):
            if args[i] == "-o" and i + 1 < len(args):
                out_file = args[i + 1]
                i += 2
            else:
                if src_file is None:
                    src_file = args[i]
                i += 1

        if not src_file:
            log("ERROR", "BUILD: no source file specified")
            return False

        src_path = os.path.join(self.image_dir, src_file.lstrip("/"))
        if not os.path.exists(src_path):
            src_path = os.path.join(PROJECT_ROOT, src_file.lstrip("/"))

        if not os.path.exists(src_path):
            log("ERROR", f"BUILD source not found: {src_file}")
            return False

        if not out_file:
            base = os.path.splitext(os.path.basename(src_file))[0]
            out_file = f"/bin/{base}.wasm"

        out_path = os.path.join(self.image_dir, out_file.lstrip("/"))
        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        clang = os.path.join(WASI_SDK_PATH, "bin", "clang")
        if not os.path.exists(clang):
            log("ERROR", f"WASI SDK clang not found at {clang}")
            return False

        cmd = [
            clang,
            "--target=wasm32-wasi",
            f"--sysroot={WASI_SYSROOT}",
            "-O2",
            "-mexec-model=reactor",
            "-Wl,--allow-undefined",
            "-Wl,--initial-memory=524288",
            "-Wl,--max-memory=4194304",
            "-o", out_path,
            src_path,
        ]

        log("Build", f"🔨 Compiling: {src_file} → {out_file}")
        log("Build", f"   clang --target=wasm32-wasi -mexec-model=reactor -O2")

        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            log("ERROR", f"Compilation failed:\n{result.stderr}")
            return False

        size = os.path.getsize(out_path)
        log("Build", f"✅ Compiled: {out_file} ({size:,} bytes)")
        return True

    def _save_image_metadata(self) -> bool:
        metadata = {
            "name": self.name,
            "base_image": self.parser.base_image,
            "limit_tokens": self.parser.limit_tokens,
            "cpu_quota": self.parser.cpu_quota,
            "mounts": self.parser.mounts,
            "entrypoint": self.parser.entrypoint,
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

        meta_path = os.path.join(self.context_dir, "image.json")
        with open(meta_path, "w") as f:
            json.dump(metadata, f, indent=2, ensure_ascii=False)

        log("Build", f"📋 Image metadata saved: {meta_path}")
        return True


class ContainerRunner:
    def __init__(self, name: str):
        self.name = name
        self.context_dir = os.path.join(CONTAINER_DIR, name)
        self.image_dir = os.path.join(self.context_dir, "image")
        self.metadata = None
        self.agent_id = None
        self.cgroup_name = None

    def run(self) -> bool:
        log("Run", f"Starting container: {self.name}")

        if not self._load_image():
            return False

        if not self._create_cgroup():
            return False

        if not self._spawn_agent():
            return False

        if not self._attach_cgroup():
            return False

        if not self._setup_mounts():
            return False

        if not self._execute_entrypoint():
            return False

        log("Run", f"✅ Container '{self.name}' running! agent_id={self.agent_id}")
        log("Run", f"   Cgroup: {self.cgroup_name}")
        log("Run", f"   VFS Namespace: /containers/agent_{self.agent_id}")
        return True

    def _load_image(self) -> bool:
        meta_path = os.path.join(self.context_dir, "image.json")
        if not os.path.exists(meta_path):
            log("ERROR", f"Image not found: {meta_path}")
            log("ERROR", f"Run 'app_container_runtime.py build {self.name}' first")
            return False

        with open(meta_path, "r") as f:
            self.metadata = json.load(f)

        log("Run", f"📋 Image loaded: {self.metadata['name']}")
        log("Run", f"   Base: {self.metadata['base_image']}")
        log("Run", f"   LIMIT_TOKENS: {self.metadata['limit_tokens']}")
        log("Run", f"   ENTRYPOINT: {self.metadata['entrypoint']}")
        return True

    def _create_cgroup(self) -> bool:
        if self.metadata["limit_tokens"] <= 0:
            log("Run", "⚠️  No LIMIT_TOKENS set, skipping cgroup creation")
            return True

        self.cgroup_name = f"/agent_{self.name}"

        resp = send_syscall({
            "syscall": "CGROUP_CREATE",
            "name": self.cgroup_name,
            "max_tokens_per_minute": self.metadata["limit_tokens"],
            "cpu_quota": self.metadata.get("cpu_quota", 100.0),
            "parent": "/",
        })

        if resp.get("status") != "ok":
            log("ERROR", f"CGROUP_CREATE failed: {resp}")
            return False

        log("Run", f"🔒 Cgroup created: {self.cgroup_name} (max_tpm={self.metadata['limit_tokens']})")
        return True

    def _spawn_agent(self) -> bool:
        resp = send_syscall({
            "syscall": "AGENT_SPAWN",
            "caller_id": 0,
            "role": f"container:{self.name}",
            "clone_flags": AIOS_CLONE_NEWNS,
        })

        if resp.get("status") != "ok":
            log("ERROR", f"AGENT_SPAWN failed: {resp}")
            return False

        self.agent_id = resp["child_id"]
        root_dir = resp.get("root_dir", "unknown")
        has_ns = resp.get("mount_namespace", "")

        log("Run", f"🚀 Agent spawned: id={self.agent_id}")
        log("Run", f"   Mount Namespace: {has_ns}")
        log("Run", f"   Root Dir: {root_dir}")
        return True

    def _attach_cgroup(self) -> bool:
        if not self.cgroup_name or not self.agent_id:
            return True

        resp = send_syscall({
            "syscall": "CGROUP_ATTACH",
            "agent_id": self.agent_id,
            "cgroup_name": self.cgroup_name,
        })

        if resp.get("status") != "ok":
            log("ERROR", f"CGROUP_ATTACH failed: {resp}")
            return False

        log("Run", f"🔗 Agent {self.agent_id} attached to cgroup {self.cgroup_name}")
        return True

    def _setup_mounts(self) -> bool:
        for mount in self.metadata.get("mounts", []):
            host_path = mount["host"]
            container_path = mount["container"]

            resp = send_syscall({
                "syscall": "VFS_CALL",
                "action": "LIST",
                "path": host_path,
                "agent_id": self.agent_id,
                "caller_id": 0,
            })

            if resp.get("status") == "ok":
                log("Run", f"📂 MOUNT: {host_path} → {container_path} (host path accessible)")
            else:
                log("Run", f"⚠️  MOUNT: {host_path} not found in VFS, creating directory")
                send_syscall({
                    "syscall": "VFS_CALL",
                    "action": "WRITE",
                    "path": host_path,
                    "payload": "",
                    "agent_id": self.agent_id,
                    "caller_id": 0,
                })

        return True

    def _execute_entrypoint(self) -> bool:
        entrypoint = self.metadata.get("entrypoint", "")
        if not entrypoint:
            log("Run", "⚠️  No ENTRYPOINT defined, container running in background mode")
            return True

        wasm_path = os.path.join(self.image_dir, entrypoint.lstrip("/"))
        if os.path.exists(wasm_path):
            log("Run", f"🎯 Executing ENTRYPOINT: {entrypoint}")
            log("Run", f"   WASM binary: {wasm_path}")

            with open(wasm_path, "rb") as f:
                wasm_bytes = f.read()

            import requests as req_lib

            try:
                resp = req_lib.post(
                    f"http://127.0.0.1:{HTTP_PORT}/bpf/load",
                    headers={"X-AIOS-Ring": "0"},
                    files={
                        "wasm": (os.path.basename(wasm_path), wasm_bytes, "application/octet-stream"),
                    },
                    data={
                        "hook_point": f"container_{self.name}_{self.agent_id}",
                        "export_func": "bpf_filter",
                    },
                    timeout=10,
                )
                resp_data = resp.json()
                if resp_data.get("status") == "ok":
                    log("Run", f"✅ WASM module loaded into kernel")
                else:
                    log("Run", f"⚠️  WASM load response: {resp_data}")
            except Exception as e:
                log("Run", f"⚠️  WASM load via HTTP failed: {e}")

        resp = send_syscall({
            "syscall": "LLM_INFERENCE",
            "caller_id": self.agent_id,
            "payload": f"[Container:{self.name}] ENTRYPOINT {entrypoint}",
            "priority": 1,
        })

        status = resp.get("status", "unknown")
        log("Run", f"📡 ENTRYPOINT dispatched: status={status}")

        if status == "ok":
            data = resp.get("data", resp.get("result", ""))
            if isinstance(data, str) and len(data) > 0:
                preview = data[:200] if len(data) > 200 else data
                log("Run", f"📤 Output: {preview}")
        elif status == "error":
            msg = resp.get("message", "")
            if "EPERM" in msg or "Permission" in msg or "Security" in msg:
                log("Run", f"🛡️  Agent blocked by security: {msg}")
            else:
                log("Run", f"⚠️  Error: {msg}")

        return True


def cmd_build(name: str):
    agentfile_path = os.path.join(PROJECT_ROOT, "Agentfile")
    if not os.path.exists(agentfile_path):
        log("ERROR", f"Agentfile not found in {PROJECT_ROOT}")
        sys.exit(1)

    parser = AgentfileParser(agentfile_path)
    if not parser.parse():
        sys.exit(1)

    print()
    builder = ContainerBuilder(name, parser)
    if not builder.build():
        sys.exit(1)


def cmd_run(name: str):
    runner = ContainerRunner(name)
    if not runner.run():
        sys.exit(1)


def cmd_inspect(name: str):
    meta_path = os.path.join(CONTAINER_DIR, name, "image.json")
    if not os.path.exists(meta_path):
        log("ERROR", f"Container image not found: {name}")
        sys.exit(1)

    with open(meta_path, "r") as f:
        metadata = json.load(f)

    print(f"\n  📋 Container Image: {name}")
    print(f"  {'─' * 50}")
    for key, value in metadata.items():
        print(f"  {key:20s}: {value}")
    print()


def cmd_list():
    if not os.path.exists(CONTAINER_DIR):
        print("  No container images found.")
        return

    images = [d for d in os.listdir(CONTAINER_DIR)
              if os.path.exists(os.path.join(CONTAINER_DIR, d, "image.json"))]

    if not images:
        print("  No container images found.")
        return

    print(f"\n  📦 Container Images:")
    print(f"  {'─' * 50}")
    for name in images:
        meta_path = os.path.join(CONTAINER_DIR, name, "image.json")
        with open(meta_path, "r") as f:
            meta = json.load(f)
        print(f"  {name:20s} | base={meta.get('base_image', '?'):20s} | tpm={meta.get('limit_tokens', 0)}")
    print()


def cmd_cgroup_tree():
    resp = send_syscall({"syscall": "CGROUP_TREE", "caller_id": 0})
    if resp.get("status") == "ok":
        print(resp.get("tree", ""))
    else:
        log("ERROR", f"CGROUP_TREE failed: {resp}")


def main():
    print(BANNER)

    if len(sys.argv) < 2:
        print("  Usage:")
        print("    app_container_runtime.py build <name>   — Build container from Agentfile")
        print("    app_container_runtime.py run <name>     — Run container")
        print("    app_container_runtime.py inspect <name> — Inspect container image")
        print("    app_container_runtime.py list           — List container images")
        print("    app_container_runtime.py cgroup-tree    — Show Cgroup hierarchy")
        sys.exit(1)

    command = sys.argv[1].lower()

    if command == "build":
        if len(sys.argv) < 3:
            log("ERROR", "build requires a container name")
            sys.exit(1)
        name = sys.argv[2]

        log("Pre-flight", "Checking AIOS kernel connection...")
        if check_kernel_online():
            log("Pre-flight", "✅ Kernel online")
        else:
            log("WARNING", "Kernel not reachable — build will proceed (offline compile only)")

        cmd_build(name)

    elif command == "run":
        if len(sys.argv) < 3:
            log("ERROR", "run requires a container name")
            sys.exit(1)
        name = sys.argv[2]

        log("Pre-flight", "Checking AIOS kernel connection...")
        if not check_kernel_online():
            log("ERROR", "Cannot connect to AIOS kernel (TCP :8080)")
            log("ERROR", "Please start: ./build/aios_core")
            sys.exit(1)
        log("Pre-flight", "✅ Kernel online")

        cmd_run(name)

    elif command == "inspect":
        if len(sys.argv) < 3:
            log("ERROR", "inspect requires a container name")
            sys.exit(1)
        cmd_inspect(sys.argv[2])

    elif command == "list":
        cmd_list()

    elif command == "cgroup-tree":
        cmd_cgroup_tree()

    else:
        log("ERROR", f"Unknown command: {command}")
        sys.exit(1)


if __name__ == "__main__":
    main()
