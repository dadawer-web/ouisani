package com.ouisani.aios.core.tool;

/**
 * 标准数据类型常量 — 强类型 I/O 契约的类型字典。
 * <p>
 * 工具在声明 inputPorts / outputPorts 时，应优先使用这些标准类型，
 * 确保流水线上下游类型可匹配。工具也可自定义类型字符串，但标准类型
 * 能让 GraphValidator 做更精确的兼容性检查。
 * <p>
 * 类比：相当于 Unix 管道中的 MIME 类型 — 让上下游知道彼此的数据格式。
 */
public final class DataTypes {

    private DataTypes() {}

    // ── 文本类 ──
    /** 纯文本 */
    public static final String PLAIN_TEXT = "PlainText";
    /** Markdown 格式文本 */
    public static final String MARKDOWN_TEXT = "MarkdownText";
    /** HTML 格式文本 */
    public static final String HTML_TEXT = "HtmlText";
    /** 代码片段（带语言标识） */
    public static final String CODE_SNIPPET = "CodeSnippet";

    // ── 结构化数据类 ──
    /** JSON 对象或数组 */
    public static final String JSON_DATA = "JsonData";
    /** YAML 格式数据 */
    public static final String YAML_DATA = "YamlData";
    /** CSV 表格数据 */
    public static final String CSV_DATA = "CsvData";
    /** 键值对映射 */
    public static final String KEY_VALUE_MAP = "KeyValueMap";

    // ── 列表类 ──
    /** URL 列表 */
    public static final String URL_LIST = "UrlList";
    /** 文件路径列表（VFS 路径） */
    public static final String FILE_PATH_LIST = "FilePathList";
    /** 字符串列表 */
    public static final String STRING_LIST = "StringList";
    /** 搜索结果列表 */
    public static final String SEARCH_RESULT_LIST = "SearchResultList";

    // ── 文件系统类 ──
    /** VFS 文件路径 */
    public static final String FILE_PATH = "FilePath";
    /** VFS 目录路径 */
    public static final String DIRECTORY_PATH = "DirectoryPath";
    /** 文件内容（完整文件内容字符串） */
    public static final String FILE_CONTENT = "FileContent";

    // ── 命令执行类 ──
    /** Shell 命令字符串 */
    public static final String SHELL_COMMAND = "ShellCommand";
    /** 命令执行结果（stdout + stderr） */
    public static final String COMMAND_OUTPUT = "CommandOutput";

    // ── 网络类 ──
    /** URL 地址 */
    public static final String URL = "Url";
    /** HTTP 响应内容 */
    public static final String HTTP_RESPONSE = "HttpResponse";
    /** 网页抓取结果 */
    public static final String WEB_PAGE_CONTENT = "WebPageContent";

    // ── 智能体类 ──
    /** 智能体任务描述 */
    public static final String TASK_DESCRIPTION = "TaskDescription";
    /** 智能体执行结果 */
    public static final String AGENT_RESULT = "AgentResult";
    /** 消息内容（EventBus IPC） */
    public static final String MESSAGE = "Message";

    // ── 通配 ──
    /** 任意类型 — 兼容一切（向后兼容旧工具） */
    public static final String ANY = "any";

    /**
     * 检查两个数据类型是否兼容。
     * <p>
     * 兼容规则（按优先级）：
     * <ul>
     *   <li>ANY 兼容一切</li>
     *   <li>类型完全相同则兼容</li>
     *   <li><b>文本族</b>：MarkdownText / HtmlText / CodeSnippet / YamlData / CsvData
     *       均可降级为 PlainText（文本是所有文本格式的公共超集）</li>
     *   <li><b>结构化数据族</b>：JsonData ↔ YamlData ↔ KeyValueMap 互转；
     *       CsvData / YamlData 可降级为 PlainText</li>
     *   <li><b>列表族</b>：UrlList / FilePathList / StringList / SearchResultList
     *       互转；列表可降级为 PlainText（序列化）</li>
     *   <li><b>路径族</b>：FilePath ↔ DirectoryPath 互转；
     *       FilePathList 可降级为 FilePath（取首个）或 PlainText</li>
     *   <li><b>网络族</b>：WebPageContent / HttpResponse / HtmlText 互转；
     *       均可降级为 PlainText / MarkdownText</li>
     *   <li><b>命令族</b>：CommandOutput 可降级为 PlainText</li>
     *   <li><b>智能体族</b>：AgentResult / TaskDescription / Message
     *       均可降级为 PlainText / MarkdownText</li>
     *   <li><b>文件族</b>：FileContent 可降级为 PlainText / MarkdownText</li>
     * </ul>
     *
     * @param upstream 上游输出类型
     * @param downstream 下游输入类型
     * @return true 表示类型兼容
     */
    public static boolean isCompatible(String upstream, String downstream) {
        if (upstream == null || downstream == null) return true;
        if (ANY.equals(upstream) || ANY.equals(downstream)) return true;
        if (upstream.equals(downstream)) return true;

        // ── 文本族：所有文本格式均可降级为 PlainText ──
        if (PLAIN_TEXT.equals(downstream)) {
            return isTextType(upstream);
        }
        // MarkdownText 是富文本的公共超集，所有文本格式均可降级为 MarkdownText
        if (MARKDOWN_TEXT.equals(downstream)) {
            return isTextType(upstream);
        }
        // PlainText 可升级为任意文本格式（下游自行解析）
        if (PLAIN_TEXT.equals(upstream) || MARKDOWN_TEXT.equals(upstream)) {
            return isTextType(downstream);
        }

        // ── 结构化数据族：JsonData ↔ YamlData ↔ KeyValueMap 互转 ──
        if (isStructuredType(upstream) && isStructuredType(downstream)) return true;

        // ── 列表族：UrlList / FilePathList / StringList / SearchResultList 互转 ──
        if (isListType(upstream) && isListType(downstream)) return true;
        // 列表可降级为 PlainText（JSON 序列化）
        if (isListType(upstream) && PLAIN_TEXT.equals(downstream)) return true;

        // ── 路径族：FilePath ↔ DirectoryPath ──
        if (FILE_PATH.equals(upstream) && DIRECTORY_PATH.equals(downstream)) return true;
        if (DIRECTORY_PATH.equals(upstream) && FILE_PATH.equals(downstream)) return true;
        // FilePathList 可降级为 FilePath（取首个元素）
        if (FILE_PATH_LIST.equals(upstream) && FILE_PATH.equals(downstream)) return true;

        // ── 网络族：WebPageContent / HttpResponse / HtmlText 互转 ──
        if (isWebType(upstream) && isWebType(downstream)) return true;

        // ── 文件内容可降级为 PlainText / MarkdownText ──
        if (FILE_CONTENT.equals(upstream) && isTextType(downstream)) return true;

        // ── 智能体族：AgentResult / TaskDescription / Message 可降级为文本 ──
        if (isAgentType(upstream) && isTextType(downstream)) return true;

        // ── 命令输出可降级为 PlainText ──
        if (COMMAND_OUTPUT.equals(upstream) && PLAIN_TEXT.equals(downstream)) return true;

        return false;
    }

    /** 判断是否为文本族类型（可序列化为纯文本） */
    private static boolean isTextType(String type) {
        return PLAIN_TEXT.equals(type) || MARKDOWN_TEXT.equals(type) || HTML_TEXT.equals(type)
                || CODE_SNIPPET.equals(type) || YAML_DATA.equals(type) || CSV_DATA.equals(type)
                || FILE_CONTENT.equals(type);
    }

    /** 判断是否为结构化数据族（可互转） */
    private static boolean isStructuredType(String type) {
        return JSON_DATA.equals(type) || YAML_DATA.equals(type) || KEY_VALUE_MAP.equals(type)
                || CSV_DATA.equals(type);
    }

    /** 判断是否为列表族（可互转） */
    private static boolean isListType(String type) {
        return URL_LIST.equals(type) || FILE_PATH_LIST.equals(type) || STRING_LIST.equals(type)
                || SEARCH_RESULT_LIST.equals(type);
    }

    /** 判断是否为网络族（可互转） */
    private static boolean isWebType(String type) {
        return WEB_PAGE_CONTENT.equals(type) || HTTP_RESPONSE.equals(type) || HTML_TEXT.equals(type);
    }

    /** 判断是否为智能体族（可降级为文本） */
    private static boolean isAgentType(String type) {
        return AGENT_RESULT.equals(type) || TASK_DESCRIPTION.equals(type) || MESSAGE.equals(type);
    }
}
