import urllib.request

paths = [
    "programming_runs/generators/parse.py",
    "programming_runs/generators/generator_types.py",
]
for path in paths:
    try:
        url = f"https://raw.githubusercontent.com/noahshinn/reflexion/218cf0ef1df84b05ce379dd4a8e47f17766733a0/{path}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        content = urllib.request.urlopen(req, timeout=30).read().decode("utf-8")
        out = f"e:/ouisani/evaluation/_reflexion_{path.replace('/', '_')}"
        open(out, "w", encoding="utf-8").write(content)
        print(f"OK {path} -> {out} ({len(content)} bytes)")
    except Exception as e:
        print(f"FAIL {path}: {e}")
