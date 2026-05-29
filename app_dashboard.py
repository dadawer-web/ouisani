import socket
import json
import threading
from flask import Flask, jsonify, render_template_string
import logging

log = logging.getLogger('werkzeug')
log.setLevel(logging.ERROR)

app = Flask(__name__)

def fetch_kernel_events():
    req = {
        "syscall": "VFS_CALL",
        "action": "READ",
        "path": "/proc/events",
        "caller_id": 0
    }
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(3)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))
        res = client.recv(81920).decode('utf-8')
        client.close()
        parsed = json.loads(res)
        return parsed.get("events", [])
    except Exception as e:
        return [{"type": "ERROR", "source": "Dashboard", "message": str(e)}]

def fetch_kernel_stat():
    req = {
        "syscall": "VFS_CALL",
        "action": "READ",
        "path": "/proc/agent_top",
        "caller_id": 0
    }
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(3)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))
        res = client.recv(81920).decode('utf-8')
        client.close()
        parsed = json.loads(res)
        data = parsed.get("data", {})
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except:
                data = {"content": data}
        return data
    except Exception:
        return {}

@app.route('/api/status')
def api_status():
    events = fetch_kernel_events()
    stat = fetch_kernel_stat()
    content = stat.get("content", "{}")
    if isinstance(content, str):
        try:
            content = json.loads(content)
        except:
            content = {}
    return jsonify({
        "status": "ok",
        "events": events,
        "stat": content
    })

HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8">
    <title>OUISANI AIOS KERNEL COMMAND CENTER</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root {
            --neon-green: #00ff41;
            --neon-cyan: #00e5ff;
            --neon-yellow: #ffeb3b;
            --neon-red: #ff003c;
            --neon-purple: #b388ff;
            --dark-bg: #0a0a0a;
            --panel-bg: #111;
            --grid-color: rgba(0, 255, 65, 0.08);
        }
        * { box-sizing: border-box; }
        body {
            background-color: var(--dark-bg);
            color: var(--neon-green);
            font-family: 'Courier New', Courier, monospace;
            margin: 0;
            padding: 20px;
            background-image:
                linear-gradient(var(--grid-color) 1px, transparent 1px),
                linear-gradient(90deg, var(--grid-color) 1px, transparent 1px);
            background-size: 20px 20px;
            min-height: 100vh;
        }
        h1 {
            text-align: center;
            text-shadow: 0 0 20px var(--neon-green), 0 0 40px var(--neon-green);
            letter-spacing: 8px;
            margin-bottom: 5px;
            font-size: 2rem;
        }
        .subtitle {
            text-align: center;
            color: #666;
            letter-spacing: 3px;
            margin-bottom: 25px;
            font-size: 0.85rem;
        }
        .container {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            max-width: 1500px;
            margin: 0 auto;
        }
        .panel {
            background-color: var(--panel-bg);
            border: 1px solid var(--neon-green);
            box-shadow: 0 0 15px rgba(0, 255, 65, 0.15), inset 0 0 30px rgba(0, 255, 65, 0.03);
            padding: 20px;
            border-radius: 3px;
        }
        .panel h2 {
            border-bottom: 1px solid rgba(0, 255, 65, 0.4);
            padding-bottom: 10px;
            margin-top: 0;
            font-size: 1rem;
            text-shadow: 0 0 5px var(--neon-green);
            letter-spacing: 2px;
        }
        .stats-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 12px;
            margin-bottom: 20px;
        }
        .stat-box {
            border: 1px dashed rgba(0, 255, 65, 0.3);
            padding: 15px 10px;
            text-align: center;
        }
        .stat-label {
            font-size: 0.7rem;
            color: #666;
            letter-spacing: 1px;
            margin-bottom: 8px;
        }
        .stat-value {
            font-size: 2.2rem;
            font-weight: bold;
            text-shadow: 0 0 10px var(--neon-green);
        }
        .stat-value.cyan { color: var(--neon-cyan); text-shadow: 0 0 10px var(--neon-cyan); }
        .stat-value.yellow { color: var(--neon-yellow); text-shadow: 0 0 10px var(--neon-yellow); }
        .stat-value.red { color: var(--neon-red); text-shadow: 0 0 10px var(--neon-red); }
        .stat-value.purple { color: var(--neon-purple); text-shadow: 0 0 10px var(--neon-purple); }
        .terminal {
            height: 420px;
            overflow-y: auto;
            background-color: #000;
            padding: 12px;
            border: 1px solid #222;
            font-size: 0.82rem;
            line-height: 1.6;
        }
        .terminal p { margin: 3px 0; }
        .log-llm_start { color: var(--neon-cyan); }
        .log-llm_end { color: var(--neon-green); }
        .log-wasm_start { color: var(--neon-yellow); }
        .log-wasm_trap { color: var(--neon-red); text-shadow: 0 0 5px var(--neon-red); }
        .log-vfs_write { color: var(--neon-purple); }
        .log-agent_spawn { color: var(--neon-purple); }
        .log-error { color: var(--neon-red); }
        .log-default { color: #888; }
        .blink {
            animation: blink 1s infinite;
        }
        @keyframes blink {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.3; }
        }
        .footer {
            text-align: center;
            margin-top: 20px;
            color: #333;
            font-size: 0.75rem;
            letter-spacing: 2px;
        }
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: #000; }
        ::-webkit-scrollbar-thumb { background: var(--neon-green); border-radius: 3px; }
    </style>
</head>
<body>

    <h1>▶ OUISANI AIOS MICRO-KERNEL</h1>
    <div class="subtitle">RING 0 TELEMETRY COMMAND CENTER &mdash; REAL-TIME MONITORING</div>

    <div class="container">
        <div class="panel">
            <h2>◈ SYSTEM TELEMETRY</h2>
            <div class="stats-grid">
                <div class="stat-box">
                    <div class="stat-label">LLM QUEUE DEPTH</div>
                    <div class="stat-value cyan" id="llm-queue">0</div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">WASM SANDBOXES</div>
                    <div class="stat-value yellow" id="wasm-active">0</div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">EVENTS / SEC</div>
                    <div class="stat-value" id="events-sec">0</div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">KERNEL UPTIME</div>
                    <div class="stat-value purple" id="uptime">0s</div>
                </div>
            </div>
            <div>
                <canvas id="loadChart" height="140"></canvas>
            </div>
        </div>

        <div class="panel">
            <h2>◈ /PROC/EVENTS STREAM <span class="blink" style="color:var(--neon-red)">●</span></h2>
            <div class="terminal" id="terminal">
                <p style="color:#333">[BOOT] Ouisani Kernel Dashboard v1.0</p>
                <p style="color:#333">[BOOT] Waiting for Ring 0 telemetry...</p>
            </div>
        </div>
    </div>

    <div class="footer">OUISANI AIOS &copy; 2026 &mdash; ALL YOUR BASE ARE BELONG TO US</div>

    <script>
        const ctx = document.getElementById('loadChart').getContext('2d');
        const maxPoints = 30;
        const loadData = Array(maxPoints).fill(0);
        const labels = Array(maxPoints).fill('');

        const loadChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Event Flux',
                    data: loadData,
                    borderColor: '#00ff41',
                    backgroundColor: 'rgba(0, 255, 65, 0.08)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4,
                    pointRadius: 0
                }]
            },
            options: {
                responsive: true,
                animation: { duration: 300 },
                scales: {
                    x: { display: false },
                    y: {
                        beginAtZero: true,
                        grid: { color: 'rgba(0,255,65,0.08)' },
                        ticks: { color: '#00ff41', font: { family: 'Courier New' } }
                    }
                },
                plugins: {
                    legend: { display: false }
                }
            }
        });

        const terminal = document.getElementById('terminal');
        let lastEventCount = 0;
        let startTime = Date.now();

        function getLogClass(type) {
            const map = {
                'LLM_REQ_START': 'log-llm_start',
                'LLM_REQ_END': 'log-llm_end',
                'WASM_EXEC_START': 'log-wasm_start',
                'WASM_TRAP': 'log-wasm_trap',
                'VFS_WRITE': 'log-vfs_write',
                'AGENT_SPAWN': 'log-agent_spawn',
                'ERROR': 'log-error'
            };
            return map[type] || 'log-default';
        }

        function formatTime(ts) {
            const d = new Date(ts);
            return d.toLocaleTimeString('en-US', { hour12: false });
        }

        async function poll() {
            try {
                const resp = await fetch('/api/status');
                const data = await resp.json();

                const events = data.events || [];
                const stat = data.stat || {};

                const llmQ = stat.llm_queue || 0;
                const wasmActive = stat.wasm_active || 0;
                document.getElementById('llm-queue').textContent = llmQ;
                document.getElementById('wasm-active').textContent = wasmActive;

                const newEvents = events.length;
                document.getElementById('events-sec').textContent = newEvents;

                const elapsed = Math.floor((Date.now() - startTime) / 1000);
                const m = Math.floor(elapsed / 60);
                const s = elapsed % 60;
                document.getElementById('uptime').textContent = m > 0 ? `${m}m${s}s` : `${s}s`;

                loadData.push(newEvents);
                loadData.shift();
                loadChart.data.datasets[0].data = loadData;
                loadChart.update();

                if (events.length > 0) {
                    for (const ev of events) {
                        const type = ev.type || '?';
                        const source = ev.source || '?';
                        const message = ev.message || '';
                        const time = ev.ts ? formatTime(ev.ts) : '--:--:--';
                        const cls = getLogClass(type);
                        const p = document.createElement('p');
                        p.className = cls;
                        p.textContent = `[${time}] [${type}] ${source}: ${message}`;
                        terminal.appendChild(p);
                    }
                    terminal.scrollTop = terminal.scrollHeight;

                    while (terminal.children.length > 200) {
                        terminal.removeChild(terminal.firstChild);
                    }
                }
            } catch (e) {
                const p = document.createElement('p');
                p.className = 'log-error';
                p.textContent = `[${new Date().toLocaleTimeString()}] KERNEL OFFLINE - Retrying...`;
                terminal.appendChild(p);
                terminal.scrollTop = terminal.scrollHeight;
            }
        }

        setInterval(poll, 1000);
        poll();
    </script>
</body>
</html>
"""

@app.route('/')
def index():
    return render_template_string(HTML_TEMPLATE)

if __name__ == '__main__':
    print("=" * 60)
    print("  🖥️  OUISANI AIOS KERNEL COMMAND CENTER")
    print("  📡 Dashboard: http://127.0.0.1:5000")
    print("=" * 60)
    app.run(host='0.0.0.0', port=5000, debug=False)
