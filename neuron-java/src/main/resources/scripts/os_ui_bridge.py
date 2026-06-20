"""
AIOS UI Bridge — 跨平台 UI 控件探针

通过操作系统无障碍 API (AT-SPI / UIAutomation) 查找桌面软件的 UI 控件坐标，
为 ComputerUseTool 提供比像素截图更精准的元素定位能力。

调用方式: python3 os_ui_bridge.py <app_name> <element_name>
返回格式: JSON {"found": bool, "x": int, "y": int, "error": str}

真实环境下可按需启用:
- Linux:  pip install pyatspi dogtail
- Windows: pip install pywinauto
- macOS:  使用 AppleScript / Accessibility API
"""

import sys
import json
import platform

# ── 延迟导入：仅在对应平台上加载 ──
# import pywinauto  # Windows: UIAutomation backend
# import pyatspi    # Linux: AT-SPI accessibility
# import subprocess # macOS: AppleScript bridge


def find_ui_element(app_name, element_name):
    """
    尝试通过操作系统无障碍 API 查找控件的物理坐标。
    返回格式: {"found": bool, "x": int, "y": int, "width": int, "height": int, "error": str}
    """
    os_name = platform.system()

    try:
        if os_name == "Linux":
            return _find_linux(app_name, element_name)
        elif os_name == "Windows":
            return _find_windows(app_name, element_name)
        elif os_name == "Darwin":
            return _find_macos(app_name, element_name)
        else:
            return {"found": False, "error": f"Unsupported OS: {os_name}"}

    except Exception as e:
        return {"found": False, "error": str(e)}


def _find_linux(app_name, element_name):
    """
    Linux: 尝试通过 AT-SPI / dogtail / xdotool 查找 UI 元素。
    """
    # ── 方案 1: dogtail (基于 AT-SPI) ──
    try:
        # from dogtail.tree import root
        # app = root.application(app_name)
        # element = app.child(element_name)
        # bbox = element.position
        # size = element.size
        # return {"found": True, "x": bbox[0], "y": bbox[1],
        #         "width": size[0], "height": size[1]}
        pass
    except Exception:
        pass

    # ── 方案 2: xdotool + wmctrl (降级方案) ──
    try:
        import subprocess
        # 查找窗口 ID
        result = subprocess.run(
            ["xdotool", "search", "--name", app_name],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode != 0 or not result.stdout.strip():
            return {"found": False, "error": f"Window not found: {app_name}"}

        window_id = result.stdout.strip().split('\n')[0]

        # 获取窗口几何信息
        geo_result = subprocess.run(
            ["xdotool", "getwindowgeometry", "--shell", window_id],
            capture_output=True, text=True, timeout=5
        )

        geo = {}
        for line in geo_result.stdout.strip().split('\n'):
            if '=' in line:
                k, v = line.split('=', 1)
                geo[k] = int(v)

        # 激活窗口
        subprocess.run(["xdotool", "windowactivate", window_id], timeout=5)

        return {
            "found": True,
            "x": geo.get("X", 0),
            "y": geo.get("Y", 0),
            "width": geo.get("WIDTH", 0),
            "height": geo.get("HEIGHT", 0),
            "window_id": window_id,
            "method": "xdotool"
        }
    except FileNotFoundError:
        return {"found": False, "error": "xdotool not installed. Run: sudo apt install xdotool"}
    except Exception as e:
        return {"found": False, "error": str(e)}


def _find_windows(app_name, element_name):
    """
    Windows: 通过 pywinauto UIAutomation 查找 UI 元素。
    """
    try:
        # from pywinauto import Desktop
        # desktop = Desktop(backend="uia")
        # app_window = desktop.window(title_re=app_name)
        # element = app_window.child_window(title=element_name)
        # rect = element.rectangle()
        # return {"found": True,
        #         "x": rect.mid_point().x,
        #         "y": rect.mid_point().y,
        #         "width": rect.width(),
        #         "height": rect.height(),
        #         "method": "pywinauto"}
        return {"found": False, "error": "pywinauto not enabled. Uncomment import and install: pip install pywinauto"}
    except Exception as e:
        return {"found": False, "error": str(e)}


def _find_macos(app_name, element_name):
    """
    macOS: 通过 AppleScript / Accessibility API 查找 UI 元素。
    """
    try:
        import subprocess
        # 使用 AppleScript 获取窗口位置
        script = f'''
        tell application "{app_name}"
            activate
            set winBounds to bounds of front window
            return winBounds
        end tell
        '''
        result = subprocess.run(
            ["osascript", "-e", script],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode != 0:
            return {"found": False, "error": f"AppleScript failed: {result.stderr}"}

        # 解析 bounds: left, top, right, bottom
        parts = result.stdout.strip().split(', ')
        if len(parts) == 4:
            left, top, right, bottom = [int(p.strip()) for p in parts]
            return {
                "found": True,
                "x": left,
                "y": top,
                "width": right - left,
                "height": bottom - top,
                "method": "applescript"
            }
        return {"found": False, "error": f"Unexpected bounds format: {result.stdout}"}
    except FileNotFoundError:
        return {"found": False, "error": "osascript not available"}
    except Exception as e:
        return {"found": False, "error": str(e)}


def list_ui_elements(app_name):
    """
    列出指定应用的所有可访问 UI 元素（调试用）。
    """
    os_name = platform.system()
    try:
        if os_name == "Linux":
            # from dogtail.tree import root
            # app = root.application(app_name)
            # elements = []
            # for child in app.getChildren():
            #     elements.append({"name": child.name, "role": child.roleName})
            # return {"app": app_name, "elements": elements}
            return {"app": app_name, "elements": [], "error": "dogtail not enabled"}
        else:
            return {"app": app_name, "elements": [], "error": f"Not implemented for {os_name}"}
    except Exception as e:
        return {"app": app_name, "elements": [], "error": str(e)}


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(json.dumps({"found": False, "error": "Usage: python3 os_ui_bridge.py <command> <app_name> [element_name]"}))
        print(json.dumps({"found": False, "error": "Commands: find <app_name> <element_name> | list <app_name>"}))
        sys.exit(1)

    command = sys.argv[1]

    if command == "find":
        if len(sys.argv) < 4:
            print(json.dumps({"found": False, "error": "Usage: find <app_name> <element_name>"}))
            sys.exit(1)
        target_app = sys.argv[2]
        target_element = sys.argv[3]
        result = find_ui_element(target_app, target_element)
        print(json.dumps(result))

    elif command == "list":
        target_app = sys.argv[2]
        result = list_ui_elements(target_app)
        print(json.dumps(result))

    else:
        print(json.dumps({"found": False, "error": f"Unknown command: {command}. Use 'find' or 'list'."}))
        sys.exit(1)
