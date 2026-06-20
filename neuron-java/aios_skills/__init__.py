# aios_skills — AIOS Global Skills Registry
"""
AIOS 全局技能库。所有模块均可通过 `from skills.xxx import yyy` 直接调用。

可用模块：
  - web_scraper:  高可用网页文本提取（重试+双引擎+编码检测）
  - file_ops:     原子文件读写（编码检测+路径安全+JSON支持）
  - code_executor: 安全代码执行（子进程隔离+超时+危险代码拦截）
"""

from skills.web_scraper import fetch_and_clean_text, fetch_json
from skills.file_ops import read_file, write_file, read_json, write_json, list_files
from skills.code_executor import execute_python, execute_script

__all__ = [
    "fetch_and_clean_text",
    "fetch_json",
    "read_file",
    "write_file",
    "read_json",
    "write_json",
    "list_files",
    "execute_python",
    "execute_script",
]
