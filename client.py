import json
import socket
import time


class AIOSClient:
    def __init__(self, host="127.0.0.1", port=8080):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((host, port))
        self._buf = b""

    def send_syscall(self, request: dict) -> dict:
        msg = json.dumps(request) + "\n"
        self.sock.sendall(msg.encode("utf-8"))
        while b"\n" not in self._buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Server closed connection")
            self._buf += chunk
        line, self._buf = self._buf.split(b"\n", 1)
        return json.loads(line.decode("utf-8"))

    def close(self):
        self.sock.close()


def main():
    print("=== AIOS Python Client - LLM Pipeline Integration Test ===\n")

    client = AIOSClient()
    print("[Client] Connected to AIOS Core at 127.0.0.1:8080\n")

    print("--- Step 1: Submit task to Agent 101 - 'Remember my lucky number is 7' ---")
    req = {
        "syscall": "EXECUTE_TOOL",
        "agent_id": 101,
        "priority": 3,
        "data": "Please remember that my lucky number is 7. Acknowledge this.",
    }
    print(f"[Client] >>> EXECUTE_TOOL: {req['data']}")
    resp = client.send_syscall(req)
    print(f"[Client] <<< {resp['status']}: {resp['message']}")
    print("[Client]     (Waiting for LLM pipeline to complete...)")
    time.sleep(15)
    print()

    print("--- Step 2: Check Agent 101 memory (should have user + assistant pages) ---")
    req = {"syscall": "READ_MEMORY", "agent_id": 101}
    resp = client.send_syscall(req)
    print(f"[Client] <<< {resp['status']}: {resp['message']}")
    if "data" in resp and isinstance(resp["data"], list):
        for p in resp["data"]:
            content = p["content"][:100] + "..." if len(p["content"]) > 100 else p["content"]
            print(f"           [{p['role']}] {content}")
    print()

    print("--- Step 3: Submit task to Agent 101 - 'What is my lucky number?' ---")
    req = {
        "syscall": "EXECUTE_TOOL",
        "agent_id": 101,
        "priority": 5,
        "data": "What is my lucky number? You should know from our previous conversation.",
    }
    print(f"[Client] >>> EXECUTE_TOOL: {req['data']}")
    resp = client.send_syscall(req)
    print(f"[Client] <<< {resp['status']}: {resp['message']}")
    print("[Client]     (Waiting for LLM pipeline to complete...)")
    time.sleep(15)
    print()

    print("--- Step 4: Check Agent 101 memory (should have more pages) ---")
    req = {"syscall": "READ_MEMORY", "agent_id": 101}
    resp = client.send_syscall(req)
    print(f"[Client] <<< {resp['status']}: {resp['message']}")
    if "data" in resp and isinstance(resp["data"], list):
        for p in resp["data"]:
            content = p["content"][:100] + "..." if len(p["content"]) > 100 else p["content"]
            print(f"           [{p['role']}] {content}")
    print()

    print("[Client] Pipeline integration test done. Closing connection.")
    client.close()


if __name__ == "__main__":
    main()
