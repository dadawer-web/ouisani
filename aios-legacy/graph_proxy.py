#!/usr/bin/env python3
"""Lightweight HTTP proxy for AIOS GraphFS Visualizer.

Bridges browser HTTP requests to the AIOS kernel TCP syscall port.
Serves the visualizer HTML and proxies /debug/graph requests.

Usage: python3 graph_proxy.py
"""

import http.server
import json
import os
import socket
import threading

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
HTML_FILE = os.path.join(PROJECT_ROOT, "app_graph_visualizer.html")
KERNEL_HOST = "127.0.0.1"
KERNEL_PORT = 8080
PROXY_PORT = 8090


def kernel_syscall(req: dict, timeout: float = 10):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect((KERNEL_HOST, KERNEL_PORT))
    client.sendall((json.dumps(req) + "\n").encode("utf-8"))
    raw = client.recv(262144).decode("utf-8").strip()
    client.close()
    return json.loads(raw)


class GraphProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/debug/graph":
            self._proxy_debug_graph()
        elif self.path == "/" or self.path == "/index.html":
            self._serve_html()
        else:
            self.send_error(404)

    def _proxy_debug_graph(self):
        try:
            resp = kernel_syscall({
                "syscall": "VFS_CALL",
                "action": "DEBUG_GRAPH",
                "path": "/dev/graph0",
                "caller_id": 0,
            }, timeout=10)
            body = json.dumps(resp).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", len(body))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            err = json.dumps({"status": "error", "message": str(e)}).encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(err)

    def _serve_html(self):
        try:
            with open(HTML_FILE, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", len(body))
            self.end_headers()
            self.wfile.write(body)
        except FileNotFoundError:
            self.send_error(404, "app_graph_visualizer.html not found")

    def log_message(self, format, *args):
        print(f"[GraphProxy] {args[0]}")


def main():
    server = http.server.HTTPServer(("0.0.0.0", PROXY_PORT), GraphProxyHandler)
    print(f"[GraphProxy] Serving on http://127.0.0.1:{PROXY_PORT}")
    print(f"[GraphProxy] Visualizer: http://127.0.0.1:{PROXY_PORT}/")
    print(f"[GraphProxy] API:        http://127.0.0.1:{PROXY_PORT}/debug/graph")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[GraphProxy] Stopped")
        server.server_close()


if __name__ == "__main__":
    main()
