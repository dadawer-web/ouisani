# aios_skills/search_tool.py
"""
AutoGPT-style 原子工具：DuckDuckGo 搜索引擎。
核心特性：
  - 双引擎 fallback：duckduckgo-search 库 → HTML 爬虫兜底
  - 指数退避重试（3 轮）
  - 结果结构化输出（title + href + body）
  - 内嵌 API_SCHEMA 契约（Dify 规范）
"""

import time
import random
import re
import json
import logging
from typing import Optional

logger = logging.getLogger("aios.skills.search_tool")

# ── 重试配置 ──
_MAX_RETRIES = 3
_BASE_DELAY = 2.0
_TIMEOUT = 15


def duckduckgo_search(query: str, max_results: int = 3) -> str:
    """
    [API_SCHEMA_START]
    {
        "name": "duckduckgo_search",
        "description": "Perform a DuckDuckGo web search to find up-to-date factual information. Returns structured search results with title, URL, and snippet.",
        "parameters": {
            "query": {
                "type": "string",
                "required": true,
                "description": "The search query string"
            },
            "max_results": {
                "type": "integer",
                "required": false,
                "default": 3,
                "minimum": 1,
                "maximum": 10,
                "description": "Maximum number of search results to return"
            }
        },
        "return": {
            "type": "string",
            "description": "JSON string containing structured search results. Each result has 'title', 'href', and 'body' fields. On failure, returns '[SKILL_ERROR] ...' prefixed message.",
            "schema": {
                "results": [
                    {
                        "title": "string - Page title",
                        "href": "string - Page URL",
                        "body": "string - Snippet text"
                    }
                ],
                "query": "string - Original search query",
                "count": "integer - Number of results returned"
            }
        }
    }
    [API_SCHEMA_END]
    """
    if not query or not query.strip():
        return json.dumps({"results": [], "query": query, "count": 0, "error": "Empty query"})

    last_error = None

    for attempt in range(1, _MAX_RETRIES + 1):
        # 引擎 1：duckduckgo-search 库
        try:
            result = _search_with_ddgs(query, max_results)
            if result is not None:
                logger.info(f"[search_tool] DDGS search success: query='{query}', results={len(result)}")
                return _format_results(query, result)
        except Exception as e:
            last_error = e
            logger.warning(f"[search_tool] DDGS failed (attempt={attempt}): {e}")

        # 引擎 2：HTML 爬虫兜底
        try:
            result = _search_with_html(query, max_results)
            if result is not None:
                logger.info(f"[search_tool] HTML fallback success: query='{query}', results={len(result)}")
                return _format_results(query, result)
        except Exception as e:
            last_error = e
            logger.warning(f"[search_tool] HTML fallback failed (attempt={attempt}): {e}")

        # 指数退避
        if attempt < _MAX_RETRIES:
            delay = _BASE_DELAY * (2 ** (attempt - 1)) + random.uniform(0, 1)
            logger.info(f"[search_tool] All engines failed, retrying in {delay:.1f}s (attempt={attempt}/{_MAX_RETRIES})")
            time.sleep(delay)

    return f"[SKILL_ERROR] 搜索失败（已重试 {_MAX_RETRIES} 次）: query='{query}', last_error={last_error}"


def _search_with_ddgs(query: str, max_results: int) -> Optional[list]:
    """引擎 1：使用 duckduckgo-search 库（推荐，最稳定）"""
    from duckduckgo_search import DDGS

    with DDGS() as ddgs:
        results = list(ddgs.text(query, max_results=max_results))
        # ddgs.text 返回 [{'title': ..., 'href': ..., 'body': ...}, ...]
        return results if results else None


def _search_with_html(query: str, max_results: int) -> Optional[list]:
    """引擎 2：DuckDuckGo HTML 爬虫兜底（当 ddgs 库不可用时）"""
    import urllib.parse
    import urllib.request

    url = f"https://html.duckduckgo.com/html/?q={urllib.parse.quote_plus(query)}"
    headers = {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
    }

    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        html = resp.read().decode("utf-8", errors="replace")

    # 解析搜索结果
    results = []
    # DuckDuckGo HTML 版的结果块
    result_blocks = re.findall(
        r'<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?'
        r'<a[^>]+class="result__snippet"[^>]*>(.*?)</a>',
        html, re.DOTALL
    )

    for href, title, body in result_blocks[:max_results]:
        # 清理 HTML 标签
        title = re.sub(r"<[^>]+>", "", title).strip()
        body = re.sub(r"<[^>]+>", "", body).strip()
        results.append({"title": title, "href": href, "body": body})

    return results if results else None


def _format_results(query: str, results: list) -> str:
    """将搜索结果格式化为结构化 JSON 字符串"""
    output = {
        "results": results,
        "query": query,
        "count": len(results),
    }
    return json.dumps(output, ensure_ascii=False, indent=2)
