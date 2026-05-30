#!/usr/bin/env python3
"""AIOS APT — User-space package manager for WASM tool modules.

Usage:
    python aios_apt.py install <tool_name>
    python aios_apt.py list
    python aios_apt.py remove <tool_name>
"""

import os
import sys
import json
import urllib.request
import urllib.error

LIB_DIR = "./usr_lib_wasm"
MOCK_REGISTRY = {
    "math_tool": {
        "url": "https://raw.githubusercontent.com/aios-wasm/tools/main/math_tool.wasm",
        "version": "1.0.0",
        "description": "High-performance math operations (fibonacci, prime, factorial)",
    },
    "crypto_hash": {
        "url": "https://raw.githubusercontent.com/aios-wasm/tools/main/crypto_hash.wasm",
        "version": "1.2.0",
        "description": "SHA-256 / MD5 hashing utilities",
    },
    "libc_crypto": {
        "url": "https://raw.githubusercontent.com/aios-wasm/tools/main/libc_crypto.wasm",
        "version": "2.0.1",
        "description": "Full-featured cryptographic library",
    },
    "string_utils": {
        "url": "https://raw.githubusercontent.com/aios-wasm/tools/main/string_utils.wasm",
        "version": "0.9.0",
        "description": "String manipulation and regex operations",
    },
    "image_filter": {
        "url": "https://raw.githubusercontent.com/aios-wasm/tools/main/image_filter.wasm",
        "version": "1.1.0",
        "description": "Image processing filters (grayscale, blur, edge detection)",
    },
}


def ensure_lib_dir():
    os.makedirs(LIB_DIR, exist_ok=True)


def install(tool_name: str):
    if tool_name not in MOCK_REGISTRY:
        print(f"❌ Package '{tool_name}' not found in registry.")
        print(f"   Available packages: {', '.join(MOCK_REGISTRY.keys())}")
        sys.exit(1)

    meta = MOCK_REGISTRY[tool_name]
    dest = os.path.join(LIB_DIR, f"{tool_name}.wasm")

    if os.path.exists(dest):
        print(f"⚠️  {tool_name} is already installed at {dest}")
        print(f"   Use 'python aios_apt.py remove {tool_name}' first to reinstall.")
        return

    ensure_lib_dir()

    print(f"📦 Installing {tool_name} v{meta['version']}...")
    print(f"   Description: {meta['description']}")
    print(f"   Source: {meta['url']}")

    try:
        print(f"   ⬇️  Downloading...", end=" ", flush=True)
        req = urllib.request.Request(
            meta["url"],
            headers={"User-Agent": "aios-apt/1.0"},
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = resp.read()
        print(f"OK ({len(data)} bytes)")

        with open(dest, "wb") as f:
            f.write(data)

        print(f"   ✅ Written to {dest}")

    except (urllib.error.URLError, urllib.error.HTTPError) as e:
        print(f"FAILED")
        print(f"   🌐 Download failed: {e}")
        print(f"   📝 Generating mock WASM binary instead...")

        mock_wasm = _generate_mock_wasm(tool_name)
        with open(dest, "wb") as f:
            f.write(mock_wasm)
        print(f"   ✅ Mock module written to {dest}")

    manifest_path = os.path.join(LIB_DIR, f"{tool_name}.json")
    with open(manifest_path, "w") as f:
        json.dump({
            "name": tool_name,
            "version": meta["version"],
            "description": meta["description"],
            "source": meta["url"],
            "installed_at": __import__("time").strftime("%Y-%m-%d %H:%M:%S"),
        }, f, indent=2)

    print(f"\n🎉 {tool_name} v{meta['version']} installed successfully!")
    print(f"   The AIOS kernel will hot-load this tool on next TOOL_INSTALL syscall.")


def _generate_mock_wasm(tool_name: str) -> bytes:
    magic = b"\x00asm"
    version = b"\x01\x00\x00\x00"
    name_bytes = tool_name.encode("utf-8")
    name_len = bytes([len(name_bytes)])
    custom_section_id = b"\x00"
    section_content = name_len + name_bytes + b"\x00"
    section_len = bytes([len(section_content)])
    return magic + version + custom_section_id + section_len + section_content


def list_installed():
    ensure_lib_dir()
    print("📦 Installed WASM packages:")
    print("-" * 60)

    found = False
    for filename in sorted(os.listdir(LIB_DIR)):
        if filename.endswith(".wasm"):
            tool_name = filename[:-5]
            manifest_path = os.path.join(LIB_DIR, f"{tool_name}.json")
            version = "?"
            desc = ""
            if os.path.exists(manifest_path):
                try:
                    with open(manifest_path) as f:
                        meta = json.load(f)
                    version = meta.get("version", "?")
                    desc = meta.get("description", "")
                except Exception:
                    pass
            wasm_path = os.path.join(LIB_DIR, filename)
            size = os.path.getsize(wasm_path)
            print(f"  {tool_name:20s} v{version:8s} ({size:>6d} bytes)  {desc}")
            found = True

    if not found:
        print("  (no packages installed)")
    print("-" * 60)


def remove(tool_name: str):
    wasm_path = os.path.join(LIB_DIR, f"{tool_name}.wasm")
    manifest_path = os.path.join(LIB_DIR, f"{tool_name}.json")

    if not os.path.exists(wasm_path):
        print(f"❌ Package '{tool_name}' is not installed.")
        sys.exit(1)

    os.remove(wasm_path)
    print(f"   🗑️  Removed {wasm_path}")

    if os.path.exists(manifest_path):
        os.remove(manifest_path)
        print(f"   🗑️  Removed {manifest_path}")

    print(f"🎉 {tool_name} removed successfully.")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)

    cmd = sys.argv[1]

    if cmd == "install":
        if len(sys.argv) < 3:
            print("Usage: python aios_apt.py install <tool_name>")
            sys.exit(1)
        install(sys.argv[2])
    elif cmd == "list":
        list_installed()
    elif cmd == "remove":
        if len(sys.argv) < 3:
            print("Usage: python aios_apt.py remove <tool_name>")
            sys.exit(1)
        remove(sys.argv[2])
    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
