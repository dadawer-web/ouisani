# AIOS 技能舱 — 强类型 API 字典

> 这是 AIOS 技能舱的强类型 API 字典。请严格按照以下 JSON Schema 要求的参数类型，通过 `import skills.xxx` 调用。
> 本文件由 `__skill_indexer__.py` 自动生成，请勿手动编辑。


**已注册模块**: 4 | **已注册函数**: 10

---

## 快速索引

- `from skills.code_executor import execute_python` — 安全执行 Python 代码片段，子进程隔离 + 超时控制 + 危险代码拦截。
- `from skills.code_executor import execute_script` — 安全执行 Python 脚本文件，子进程隔离 + 超时控制 + 脚本内容安全扫描。
- `from skills.file_ops import read_file` — 安全读取文件内容，自动检测编码，防路径穿越。
- `from skills.file_ops import write_file` — 原子写入文件，先写临时文件再 rename，防半写损坏。
- `from skills.file_ops import read_json` — 读取 JSON 文件并解析为 Python 对象（dict 或 list）。
- `from skills.file_ops import write_json` — 将 Python 对象序列化为 JSON 并原子写入文件。
- `from skills.file_ops import list_files` — 安全列出目录下的文件，支持 glob 模式和递归搜索。
- `from skills.search_tool import duckduckgo_search` — Perform a DuckDuckGo web search to find up-to-date factual information
- `from skills.web_scraper import fetch_and_clean_text` — 通用网页纯文本安全提取，自带 3 轮指数退避重试、双引擎 fallback、UA 轮换、智能编码检测。
- `from skills.web_scraper import fetch_json` — 抓取 JSON API 端点，返回格式化后的 JSON 字符串。

---

## skills.code_executor

### `execute_python`

- **模块**: `skills.code_executor`
- **导入**: `from skills.code_executor import execute_python`
- **说明**: 安全执行 Python 代码片段，子进程隔离 + 超时控制 + 危险代码拦截。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `code` | `string` | 是 | `—` | — | 要执行的 Python 代码字符串 |
| `timeout` | `integer` | 否 | `30` | min=5, max=300 | 执行超时秒数 |
| `working_dir` | `string` | 否 | `None` | — | 工作目录，null 时使用当前目录 |
| `env_vars` | `object` | 否 | `None` | — | 额外环境变量键值对 |

**返回值**:
- 类型: `object`
- 说明: 结构化执行结果

**返回值结构**:
```json
{
  "success": "boolean - true 表示 returncode == 0",
  "stdout": "string - 标准输出",
  "stderr": "string - 标准错误",
  "returncode": "integer - 0=成功, -9=超时, -1=异常或安全拦截",
  "duration_ms": "integer - 执行耗时毫秒",
  "error": "string|null - 错误描述"
}
```

---

### `execute_script`

- **模块**: `skills.code_executor`
- **导入**: `from skills.code_executor import execute_script`
- **说明**: 安全执行 Python 脚本文件，子进程隔离 + 超时控制 + 脚本内容安全扫描。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `script_path` | `string` | 是 | `—` | — | Python 脚本文件路径 |
| `args` | `array` | 否 | `None` | — | 命令行参数列表 |
| `timeout` | `integer` | 否 | `30` | min=5, max=300 | 执行超时秒数 |
| `working_dir` | `string` | 否 | `None` | — | 工作目录，null 时使用脚本所在目录 |

**返回值**:
- 类型: `object`
- 说明: 同 execute_python 的结构化结果字典

---

## skills.file_ops

### `read_file`

- **模块**: `skills.file_ops`
- **导入**: `from skills.file_ops import read_file`
- **说明**: 安全读取文件内容，自动检测编码，防路径穿越。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `filepath` | `string` | 是 | `—` | — | 文件路径，必须在白名单目录内（/factory, /shared, /tmp, /home） |
| `max_length` | `integer` | 否 | `50000` | min=1000, max=1000000 | 最大读取字符数 |
| `encoding` | `string` | 否 | `None` | — | 指定编码，null 时自动检测 |

**返回值**:
- 类型: `string`
- 说明: 文件文本内容。失败时返回 [SKILL_ERROR] 前缀错误信息。

---

### `write_file`

- **模块**: `skills.file_ops`
- **导入**: `from skills.file_ops import write_file`
- **说明**: 原子写入文件，先写临时文件再 rename，防半写损坏。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `filepath` | `string` | 是 | `—` | — | 目标文件路径，必须在白名单目录下 |
| `content` | `string` | 是 | `—` | — | 要写入的文本内容 |
| `encoding` | `string` | 否 | `utf-8` | — | 文件编码 |

**返回值**:
- 类型: `string`
- 说明: 成功返回 [SKILL_OK] 前缀信息，失败返回 [SKILL_ERROR] 前缀错误信息。

---

### `read_json`

- **模块**: `skills.file_ops`
- **导入**: `from skills.file_ops import read_json`
- **说明**: 读取 JSON 文件并解析为 Python 对象（dict 或 list）。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `filepath` | `string` | 是 | `—` | — | JSON 文件路径 |

**返回值**:
- 类型: `object`
- 说明: 解析后的 Python 对象。失败时返回 [SKILL_ERROR] 前缀字符串，调用方必须先检查返回值类型。

---

### `write_json`

- **模块**: `skills.file_ops`
- **导入**: `from skills.file_ops import write_json`
- **说明**: 将 Python 对象序列化为 JSON 并原子写入文件。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `filepath` | `string` | 是 | `—` | — | 目标 JSON 文件路径 |
| `data` | `object` | 是 | `—` | — | 要序列化的 Python 对象（必须 JSON 可序列化） |
| `indent` | `integer` | 否 | `2` | min=0, max=8 | JSON 缩进空格数 |
| `ensure_ascii` | `boolean` | 否 | `False` | — | 是否转义非 ASCII 字符 |

**返回值**:
- 类型: `string`
- 说明: 成功返回 [SKILL_OK] 前缀信息，失败返回 [SKILL_ERROR] 前缀错误信息。

---

### `list_files`

- **模块**: `skills.file_ops`
- **导入**: `from skills.file_ops import list_files`
- **说明**: 安全列出目录下的文件，支持 glob 模式和递归搜索。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `directory` | `string` | 是 | `—` | — | 目录路径，必须在白名单下 |
| `pattern` | `string` | 否 | `*` | — | glob 匹配模式 |
| `recursive` | `boolean` | 否 | `False` | — | 是否递归搜索子目录 |

**返回值**:
- 类型: `array`
- 说明: 文件相对路径列表（已排序）。失败时返回 [SKILL_ERROR] 前缀字符串。

---

## skills.search_tool

### `duckduckgo_search`

- **模块**: `skills.search_tool`
- **导入**: `from skills.search_tool import duckduckgo_search`
- **说明**: Perform a DuckDuckGo web search to find up-to-date factual information. Returns structured search results with title, URL, and snippet.

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `query` | `string` | 是 | `—` | — | The search query string |
| `max_results` | `integer` | 否 | `3` | min=1, max=10 | Maximum number of search results to return |

**返回值**:
- 类型: `string`
- 说明: JSON string containing structured search results. Each result has 'title', 'href', and 'body' fields. On failure, returns '[SKILL_ERROR] ...' prefixed message.

**返回值结构**:
```json
{
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
```

---

## skills.web_scraper

### `fetch_and_clean_text`

- **模块**: `skills.web_scraper`
- **导入**: `from skills.web_scraper import fetch_and_clean_text`
- **说明**: 通用网页纯文本安全提取，自带 3 轮指数退避重试、双引擎 fallback、UA 轮换、智能编码检测。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `url` | `string` | 是 | `—` | pattern=^https?://.+ | 目标网页 URL，必须以 http:// 或 https:// 开头 |
| `max_length` | `integer` | 否 | `1000` | min=100, max=100000 | 返回文本最大字符数 |
| `timeout` | `integer` | 否 | `20` | min=5, max=120 | 单次 HTTP 请求超时秒数 |

**返回值**:
- 类型: `string`
- 说明: 清理后的纯文本字符串。失败时返回 [SKILL_ERROR] 前缀的结构化错误信息。

---

### `fetch_json`

- **模块**: `skills.web_scraper`
- **导入**: `from skills.web_scraper import fetch_json`
- **说明**: 抓取 JSON API 端点，返回格式化后的 JSON 字符串。

**参数**:

| 参数名 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|------|------|--------|------|------|
| `url` | `string` | 是 | `—` | pattern=^https?://.+ | JSON API 端点 URL |
| `timeout` | `integer` | 否 | `20` | min=5, max=120 | 单次 HTTP 请求超时秒数 |

**返回值**:
- 类型: `string`
- 说明: 格式化的 JSON 字符串。失败时返回 [SKILL_ERROR] 前缀错误信息。

---
