# aios_skills/web_scraper.py
"""
AutoGPT-style 原子工具：高可用网页文本提取器。
核心特性：
  - 指数退避重试（3 轮，base=2s）
  - 双引擎 fallback：requests → urllib3
  - 智能编码检测（chardet / header charset）
  - 防反爬 UA 轮换
  - 结构化错误返回，绝不静默失败
"""

import time
import random
import re
import logging
from typing import Optional

logger = logging.getLogger("aios.skills.web_scraper")

# ── UA 轮换池 ──
_UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/119.0.0.0",
    "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/121.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AIOS/2.0 SkillEngine",
]

# ── 重试配置 ──
_MAX_RETRIES = 3
_BASE_DELAY = 2.0  # 秒
_TIMEOUT = 20  # 秒


def _pick_ua() -> str:
    return random.choice(_UA_POOL)


def _detect_encoding(response) -> str:
    """从 HTTP header 或内容中检测编码，优先级：header > meta > chardet > utf-8"""
    # 1. HTTP Content-Type header
    content_type = response.headers.get("Content-Type", "")
    charset_match = re.search(r"charset=([\w-]+)", content_type, re.IGNORECASE)
    if charset_match:
        return charset_match.group(1).strip()

    # 2. HTML meta 标签
    meta_match = re.search(
        rb'<meta[^>]+charset=["\']?([\w-]+)', response.content, re.IGNORECASE
    )
    if meta_match:
        return meta_match.group(1).decode("ascii", errors="ignore").strip()

    # 3. chardet（如果安装了的话）
    try:
        import chardet
        detected = chardet.detect(response.content)
        if detected and detected.get("encoding"):
            return detected["encoding"]
    except ImportError:
        pass

    # 4. 兜底 UTF-8
    return "utf-8"


def _clean_html(html: str) -> str:
    """从 HTML 中提取纯文本，移除脚本/样式/注释"""
    try:
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(html, "html.parser")
        for tag in soup(["script", "style", "noscript", "iframe", "svg"]):
            tag.extract()
        text = soup.get_text(separator="\n", strip=True)
    except ImportError:
        # BeautifulSoup 不可用时，正则兜底
        html = re.sub(r"<script[^>]*>.*?</script>", "", html, flags=re.DOTALL | re.IGNORECASE)
        html = re.sub(r"<style[^>]*>.*?</style>", "", html, flags=re.DOTALL | re.IGNORECASE)
        html = re.sub(r"<[^>]+>", " ", html)
        html = re.sub(r"&\w+;", " ", html)
        html = re.sub(r"\s+", " ", html)
        text = html.strip()

    # 清理多余空行
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return "\n".join(lines)


def _fetch_with_requests(url: str, timeout: int) -> str:
    """引擎 1：requests + 自动编码检测"""
    import requests
    headers = {"User-Agent": _pick_ua(), "Accept": "text/html,application/xhtml+xml,*/*"}
    resp = requests.get(url, headers=headers, timeout=timeout, allow_redirects=True)
    resp.raise_for_status()
    encoding = _detect_encoding(resp)
    resp.encoding = encoding
    return _clean_html(resp.text)


def _fetch_with_urllib(url: str, timeout: int) -> str:
    """引擎 2：urllib fallback（当 requests 不可用时）"""
    import urllib.request
    import urllib.error
    req = urllib.request.Request(url, headers={"User-Agent": _pick_ua()})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        # 简单编码检测
        charset = resp.headers.get_content_charset() or "utf-8"
        html = raw.decode(charset, errors="replace")
    return _clean_html(html)


def fetch_and_clean_text(url: str, max_length: int = 1000, timeout: int = _TIMEOUT) -> str:
    """
    通用网页纯文本安全提取 — AutoGPT 级别的健壮性。

    [API_SCHEMA_START]
    {
        "name": "fetch_and_clean_text",
        "description": "通用网页纯文本安全提取，自带 3 轮指数退避重试、双引擎 fallback、UA 轮换、智能编码检测。",
        "parameters": {
            "url": {"type": "string", "required": true, "description": "目标网页 URL，必须以 http:// 或 https:// 开头", "pattern": "^https?://.+"},
            "max_length": {"type": "integer", "required": false, "default": 1000, "minimum": 100, "maximum": 100000, "description": "返回文本最大字符数"},
            "timeout": {"type": "integer", "required": false, "default": 20, "minimum": 5, "maximum": 120, "description": "单次 HTTP 请求超时秒数"}
        },
        "return": {
            "type": "string",
            "description": "清理后的纯文本字符串。失败时返回 [SKILL_ERROR] 前缀的结构化错误信息。"
        }
    }
    [API_SCHEMA_END]

    Args:
        url:        目标网页 URL
        max_length: 返回文本最大字符数（防止 Token 爆炸）
        timeout:    单次请求超时秒数

    Returns:
        清理后的纯文本字符串。失败时返回 '[SKILL_ERROR] ...' 前缀的结构化错误信息。
    """
    # URL 基本校验
    if not url or not re.match(r"^https?://", url):
        return f"[SKILL_ERROR] 无效 URL: {url}"

    last_error = None

    for attempt in range(1, _MAX_RETRIES + 1):
        # 选择引擎：先 requests，失败后 urllib fallback
        engines = [_fetch_with_requests, _fetch_with_urllib]

        for engine in engines:
            try:
                text = engine(url, timeout)
                if text and len(text.strip()) > 0:
                    truncated = text[:max_length]
                    if len(text) > max_length:
                        truncated += f"\n... [截断: 原文 {len(text)} 字符，已截取前 {max_length} 字符]"
                    logger.info(f"[web_scraper] 成功抓取: {url} (attempt={attempt}, engine={engine.__name__})")
                    return truncated
            except Exception as e:
                last_error = e
                logger.warning(f"[web_scraper] {engine.__name__} 失败 (attempt={attempt}): {e}")
                continue

        # 所有引擎都失败，指数退避等待
        if attempt < _MAX_RETRIES:
            delay = _BASE_DELAY * (2 ** (attempt - 1)) + random.uniform(0, 1)
            logger.info(f"[web_scraper] 所有引擎失败，{delay:.1f}s 后重试 (attempt={attempt}/{_MAX_RETRIES})")
            time.sleep(delay)

    return f"[SKILL_ERROR] 网页抓取失败（已重试 {_MAX_RETRIES} 次）: url={url}, last_error={last_error}"


def fetch_json(url: str, timeout: int = _TIMEOUT) -> str:
    """
    专门抓取 JSON API 端点，返回格式化后的 JSON 字符串。

    [API_SCHEMA_START]
    {
        "name": "fetch_json",
        "description": "抓取 JSON API 端点，返回格式化后的 JSON 字符串。",
        "parameters": {
            "url": {"type": "string", "required": true, "description": "JSON API 端点 URL", "pattern": "^https?://.+"},
            "timeout": {"type": "integer", "required": false, "default": 20, "minimum": 5, "maximum": 120, "description": "单次 HTTP 请求超时秒数"}
        },
        "return": {
            "type": "string",
            "description": "格式化的 JSON 字符串。失败时返回 [SKILL_ERROR] 前缀错误信息。"
        }
    }
    [API_SCHEMA_END]

    Args:
        url:     JSON API 的 URL
        timeout: 超时秒数

    Returns:
        格式化的 JSON 字符串，或 '[SKILL_ERROR] ...' 错误信息
    """
    import json

    if not url or not re.match(r"^https?://", url):
        return f"[SKILL_ERROR] 无效 URL: {url}"

    last_error = None
    for attempt in range(1, _MAX_RETRIES + 1):
        try:
            import requests
            headers = {"User-Agent": _pick_ua(), "Accept": "application/json"}
            resp = requests.get(url, headers=headers, timeout=timeout)
            resp.raise_for_status()
            data = resp.json()
            return json.dumps(data, ensure_ascii=False, indent=2)
        except ImportError:
            try:
                import urllib.request
                req = urllib.request.Request(url, headers={"User-Agent": _pick_ua(), "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    data = json.loads(resp.read().decode("utf-8", errors="replace"))
                return json.dumps(data, ensure_ascii=False, indent=2)
            except Exception as e:
                last_error = e
        except Exception as e:
            last_error = e

        if attempt < _MAX_RETRIES:
            time.sleep(_BASE_DELAY * (2 ** (attempt - 1)))

    return f"[SKILL_ERROR] JSON 抓取失败（已重试 {_MAX_RETRIES} 次）: url={url}, last_error={last_error}"
