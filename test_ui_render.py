"""AIOS UI Render End-to-End Test.

Starts an Agent that pushes simulated system-monitoring dashboard
frames to /dev/fb0 every 3 seconds.  Open app_monitor.html in a
browser while this script is running to see the live dashboard.

Usage:
    python test_ui_render.py
"""

import json
import random
import time
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))

from ouisani_sdk.kernel import Kernel
from ouisani_sdk.agent import Agent


def make_dashboard(tick: int) -> dict:
    cpu = random.uniform(15, 95)
    mem = random.uniform(30, 85)
    disk = random.uniform(40, 70)
    net_in = random.uniform(5, 200)
    net_out = random.uniform(2, 80)
    tasks = random.randint(1, 24)
    uptime = tick * 3

    hours = uptime // 3600
    mins = (uptime % 3600) // 60
    secs = uptime % 60

    return {
        "type": "dashboard",
        "widgets": [
            {
                "id": "cpu",
                "type": "gauge",
                "label": "CPU Usage",
                "value": round(cpu, 1),
            },
            {
                "id": "mem",
                "type": "gauge",
                "label": "Memory Usage",
                "value": round(mem, 1),
            },
            {
                "id": "disk",
                "type": "gauge",
                "label": "Disk I/O",
                "value": round(disk, 1),
            },
            {
                "id": "net-in",
                "type": "progress",
                "label": "Net In (MB/s)",
                "value": round(min(net_in / 200 * 100, 100), 1),
            },
            {
                "id": "net-out",
                "type": "progress",
                "label": "Net Out (MB/s)",
                "value": round(min(net_out / 80 * 100, 100), 1),
            },
            {
                "id": "tasks",
                "type": "text",
                "label": "Active Tasks",
                "value": f"{tasks} running",
            },
            {
                "id": "uptime",
                "type": "text",
                "label": "Uptime",
                "value": f"{hours:02d}:{mins:02d}:{secs:02d}",
            },
            {
                "id": "status",
                "type": "text",
                "label": "Kernel Status",
                "value": "HEALTHY" if cpu < 85 else "HIGH LOAD",
            },
        ],
    }


def main():
    print("=" * 60)
    print("  AIOS UI Render - End-to-End Test")
    print("=" * 60)

    kernel = Kernel()
    agent = Agent(kernel, agent_id=200)

    print(f"[Test] Agent {agent.agent_id} created")
    print("[Test] Pushing dashboard frames to /dev/fb0 every 3s...")
    print("[Test] Open app_monitor.html in your browser to see the live dashboard")
    print("[Test] Press Ctrl+C to stop\n")

    tick = 0
    try:
        while True:
            tick += 1
            frame = make_dashboard(tick)

            resp = agent.ui.render(frame)
            status = resp.get("status", "unknown")

            cpu_val = frame["widgets"][0]["value"]
            mem_val = frame["widgets"][1]["value"]
            print(f"[Tick {tick:03d}] render → {status} | CPU={cpu_val}% | MEM={mem_val}%")

            time.sleep(3)
    except KeyboardInterrupt:
        print(f"\n[Test] Stopped after {tick} frames")


if __name__ == "__main__":
    main()
