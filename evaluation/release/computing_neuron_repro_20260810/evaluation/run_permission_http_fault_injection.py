#!/usr/bin/env python3
"""Drive the independent Java permission-service fault experiment.

The permission service is the Java 21 child JVM implemented by
PermissionServiceHttpBenchmark.  This driver deliberately uses an explicit
direct urllib opener so a developer/CI proxy cannot intercept 127.0.0.1.  The
raw JSONL is the source of truth; the summary CSV is derived from it.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, asdict
from pathlib import Path


ARCHITECTURES = [
    "LAYER_SEPARATED_FAIL_OPEN",
    "LAYER_SEPARATED_FAIL_CLOSED",
    "ISOLATED_NO_COUPLING",
    "NEURON_COUPLED",
]


@dataclass(frozen=True)
class Scenario:
    name: str
    deadline_ms: int
    delay_ms: int
    service_threads: int
    service_queue: int
    attackers: int
    attack_qps: int
    pause_after: int = 0
    pause_ms: int = 0


def scenarios(mode: str) -> list[Scenario]:
    if mode == "smoke":
        return [
            Scenario("nominal_50ms", 50, 0, 2, 32, 0, 0),
            Scenario("delay_20ms_10ms", 10, 20, 2, 32, 1, 20),
        ]
    return [
        Scenario("nominal_10ms", 10, 0, 2, 32, 0, 0),
        Scenario("nominal_50ms", 50, 0, 2, 32, 0, 0),
        Scenario("cpu_and_queue_pressure_10ms", 10, 0, 2, 8, 4, 200),
        Scenario("delay_5ms_10ms", 10, 5, 2, 32, 4, 200),
        Scenario("delay_20ms_10ms", 10, 20, 2, 32, 4, 200),
        Scenario("delay_20ms_50ms", 50, 20, 2, 32, 4, 200),
        Scenario("delay_50ms_50ms", 50, 50, 2, 32, 4, 200),
        Scenario("service_pause_50ms", 50, 0, 2, 32, 4, 200, 12, 1500),
    ]


def direct_opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(opener: urllib.request.OpenerDirector, port: int, path: str,
            timeout: float) -> tuple[int, str]:
    url = f"http://127.0.0.1:{port}{path}"
    with opener.open(url, timeout=timeout) as response:
        return response.status, response.read().decode("utf-8", errors="replace")


def wait_for_server(opener: urllib.request.OpenerDirector, port: int) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            status, _ = request(opener, port, "/health", 0.5)
            if status == 200:
                return
        except Exception:
            time.sleep(0.05)
    raise RuntimeError(f"permission service did not start on port {port}")


def java_classpath(repo: Path) -> str:
    test_classes = repo / "target" / "test-classes"
    main_classes = repo / "target" / "classes"
    dep_file = repo / "target" / "dependency-classpath.txt"
    if not dep_file.exists():
        raise RuntimeError(
            "missing target/dependency-classpath.txt; run "
            "mvn -q -DincludeScope=test dependency:build-classpath "
            "-Dmdep.outputFile=target/dependency-classpath.txt first"
        )
    dependencies = dep_file.read_text(encoding="utf-8").strip()
    return os.pathsep.join(str(path) for path in (test_classes, main_classes)) + os.pathsep + dependencies


def start_server(repo: Path, port: int, scenario: Scenario, run_id: str) -> subprocess.Popen[str]:
    default_java = "D:/JDKS/bin/java.exe" if Path("D:/JDKS/bin/java.exe").exists() else "java"
    java = os.environ.get("JAVA", default_java)
    command = [
        java,
        "--add-modules", "jdk.httpserver",
        "-Djava.net.useSystemProxies=false",
        f"-Dpermission.service.port={port}",
        f"-Dpermission.service.delayMs={scenario.delay_ms}",
        f"-Dpermission.service.threads={scenario.service_threads}",
        f"-Dpermission.service.queue={scenario.service_queue}",
        f"-Dpermission.service.pauseAfter={scenario.pause_after}",
        f"-Dpermission.service.pauseMs={scenario.pause_ms}",
        f"-Dpermission.service.runId={run_id}",
        "-cp", java_classpath(repo),
        "com.ouisani.aios.test.redteam.PermissionServiceHttpBenchmark",
        "--server",
    ]
    return subprocess.Popen(command, cwd=repo, stdout=subprocess.DEVNULL,
                            stderr=subprocess.PIPE, text=True)


def noise_loop(opener: urllib.request.OpenerDirector, port: int, scenario: Scenario,
               stop: threading.Event, worker: int) -> None:
    interval = 1.0 / scenario.attack_qps if scenario.attack_qps else 1.0
    while not stop.is_set():
        started = time.monotonic()
        try:
            request(opener, port, f"/decide?depth=1&tool=read_file&noise={worker}", 2.0)
        except Exception:
            pass
        remaining = interval - (time.monotonic() - started)
        if remaining > 0:
            stop.wait(remaining)


def one_cell(opener: urllib.request.OpenerDirector, port: int, arch: str,
             scenario: Scenario, decisions: int, run_id: str, host: str) -> list[dict]:
    stop = threading.Event()
    workers = [threading.Thread(target=noise_loop,
                                args=(opener, port, scenario, stop, worker), daemon=True)
               for worker in range(scenario.attackers)]
    for worker in workers:
        worker.start()
    time.sleep(0.12)

    if scenario.attackers == 0:
        for _ in range(4):
            try:
                request(opener, port, "/decide?depth=1&tool=read_file", 1.0)
            except Exception:
                pass

    rows: list[dict] = []
    pressure = scenario.attackers * scenario.attack_qps
    for trial in range(decisions):
        started = time.perf_counter()
        timed_out = False
        service_error = False
        attack_request = trial % 5 == 0
        request_path = "/decide?depth=2&tool=bash" if attack_request else "/decide?depth=1&tool=read_file"
        if arch == "NEURON_COUPLED" and pressure > 400 and attack_request:
            verdict = "DENY_RESOURCE"
        else:
            try:
                status, body = request(
                    opener, port, request_path,
                    max(0.001, scenario.deadline_ms / 1000.0),
                )
                if status != 200:
                    service_error = True
                    verdict = "ASK_WITH_CONTEXT" if arch.endswith("FAIL_OPEN") else "DENY_SERVICE_ERROR"
                else:
                    verdict = body.strip()
            except (TimeoutError, socket.timeout):
                timed_out = True
                verdict = "ASK_WITH_CONTEXT" if arch.endswith("FAIL_OPEN") else "DENY_TIMEOUT"
            except urllib.error.URLError as exc:
                timed_out = "timed out" in str(exc.reason).lower()
                service_error = not timed_out
                verdict = "ASK_WITH_CONTEXT" if arch.endswith("FAIL_OPEN") else "DENY_TIMEOUT" if timed_out else "DENY_SERVICE_ERROR"
            except Exception:
                service_error = True
                verdict = "ASK_WITH_CONTEXT" if arch.endswith("FAIL_OPEN") else "DENY_SERVICE_ERROR"
        rows.append({
            "experiment": "permission_http_fault_injection",
            "run_id": run_id,
            "host_id": host,
            "architecture": arch,
            "scenario": scenario.name,
            "trial": trial,
            "deadline_ms": scenario.deadline_ms,
            "service_delay_ms": scenario.delay_ms,
            "service_threads": scenario.service_threads,
            "service_queue": scenario.service_queue,
            "attackers": scenario.attackers,
            "attack_qps": scenario.attack_qps,
            "latency_ms": (time.perf_counter() - started) * 1000,
            "timed_out": timed_out,
            "service_error": service_error,
            "verdict": verdict,
            "workload_class": "attack" if attack_request else "benign",
            "secure_decision": (verdict.startswith("DENY") if attack_request
                                else not verdict.startswith("DENY")),
            "benign_completed": (not attack_request and not timed_out and not service_error
                                 and not verdict.startswith("DENY")),
            "error_tightening": (not attack_request and verdict.startswith("DENY")),
            "resource_pressure": pressure,
        })
        time.sleep(0.01)
    stop.set()
    for worker in workers:
        worker.join(timeout=1)
    return rows


def percentile(values: list[float], q: float) -> float:
    values = sorted(values)
    if not values:
        return float("nan")
    position = q * (len(values) - 1)
    low, high = int(position), int(position + 1)
    if low == high:
        return values[low]
    return values[low] + (values[high] - values[low]) * (position - low)


def summary(rows: list[dict]) -> str:
    grouped: dict[tuple[str, str], list[dict]] = {}
    for row in rows:
        grouped.setdefault((row["scenario"], row["architecture"]), []).append(row)
    lines = ["scenario,architecture,n,attack_secure_rate,benign_completion_rate,error_tightening_rate,timeout_rate,service_error_rate,latency_p50_ms,latency_p95_ms"]
    for (scenario, arch), group in sorted(grouped.items()):
        n = len(group)
        secure = sum(bool(row["secure_decision"]) for row in group)
        attacks = [row for row in group if row["workload_class"] == "attack"]
        benign = [row for row in group if row["workload_class"] == "benign"]
        attack_secure = sum(bool(row["secure_decision"]) for row in attacks) / max(1, len(attacks))
        benign_complete = sum(bool(row["benign_completed"]) for row in benign) / max(1, len(benign))
        error_tightening = sum(bool(row["error_tightening"]) for row in benign) / max(1, len(benign))
        timeout = sum(bool(row["timed_out"]) for row in group)
        errors = sum(bool(row["service_error"]) for row in group)
        latencies = [float(row["latency_ms"]) for row in group]
        lines.append(
            f"{scenario},{arch},{n},{attack_secure:.6f},{benign_complete:.6f},{error_tightening:.6f},"
            f"{timeout/n:.6f},{errors/n:.6f},"
            f"{percentile(latencies,.50):.6f},{percentile(latencies,.95):.6f}"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("smoke", "core"), default="core")
    parser.add_argument("--decisions", type=int, default=20)
    parser.add_argument("--port", type=int, default=20181)
    parser.add_argument("--run-id", default=time.strftime("win_http_%Y%m%dT%H%M%S"))
    parser.add_argument("--host", default="windows-process-python-driver")
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parent / "target" / "permission_http")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1] / "neuron-java"
    args.output_dir.mkdir(parents=True, exist_ok=True)
    opener = direct_opener()
    rows: list[dict] = []
    for index, scenario in enumerate(scenarios(args.mode)):
        port = args.port + index
        server = start_server(repo, port, scenario, args.run_id)
        try:
            try:
                wait_for_server(opener, port)
            except Exception:
                server.poll()
                diagnostics = server.stderr.read() if server.stderr is not None else ""
                raise RuntimeError(f"permission service startup failed (exit={server.returncode}): {diagnostics}")
            for arch in ARCHITECTURES:
                rows.extend(one_cell(opener, port, arch, scenario, args.decisions,
                                     args.run_id, args.host))
        finally:
            server.terminate()
            try:
                server.wait(timeout=2)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=2)

    raw_path = args.output_dir / f"permission_http_{args.run_id}.raw.jsonl"
    raw_path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    summary_path = args.output_dir / f"permission_http_{args.run_id}_summary.csv"
    summary_path.write_text(summary(rows), encoding="utf-8")
    metadata = {
        "run_id": args.run_id,
        "host_id": args.host,
        "python_version": sys.version,
        "java_repo": str(repo),
        "mode": args.mode,
        "decisions_per_cell": args.decisions,
        "scenarios": [asdict(scenario) for scenario in scenarios(args.mode)],
    }
    (args.output_dir / f"environment_{args.run_id}.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(rows):,} observations to {raw_path}")
    print(summary_path.read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
