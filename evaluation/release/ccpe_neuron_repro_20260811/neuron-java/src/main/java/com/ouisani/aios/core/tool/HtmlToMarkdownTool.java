package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.*;

/**
 * HTML→Markdown 转换工具 — 借鉴 Firecrawl 的 HTML 转 Markdown 管道。
 * <p>
 * 三级降级策略（借鉴 Firecrawl 的远程服务→Go FFI→Turndown.js）：
 * <ol>
 *   <li>级别1：Python html2text 库（高质量，需 pip install）</li>
 *   <li>级别2：Python BeautifulSoup 文本提取（标准库，中等质量）</li>
 *   <li>级别3：Java 正则表达式基础转换（兜底，低质量但零依赖）</li>
 * </ol>
 * <p>
 * OS 类比：Linux 的编码转换管道 — iconv 优先，失败则逐字节映射。
 */
public class HtmlToMarkdownTool implements Tool<HtmlToMarkdownTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(HtmlToMarkdownTool.class);
    private static final int MAX_HTML_LENGTH = 200000;

    public record Input(
            String html,
            boolean onlyMainContent
    ) implements ToolInput {
        public Input {
            if (html == null || html.isBlank()) throw new IllegalArgumentException("html required");
            if (html.length() > MAX_HTML_LENGTH) {
                html = html.substring(0, MAX_HTML_LENGTH);
            }
        }

        public Input(String html) { this(html, true); }

        @Override public String toJson() {
            return "{\"html\":\"" + html.replace("\"", "\\\"").replace("\n", "\\n").substring(0, Math.min(html.length(), 500)) + "...\"}";
        }
    }

    @Override public String name() { return "html_to_markdown"; }

    @Override public String description() {
        return "Converts HTML content to clean Markdown. Removes scripts, styles, and non-main content. Use before feeding web content to LLM.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"html\":{\"type\":\"string\",\"description\":\"HTML content to convert\"},\"onlyMainContent\":{\"type\":\"boolean\",\"description\":\"Extract only main content, remove nav/footer/sidebar (default: true)\"}},\"required\":[\"html\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.debug("[HtmlToMarkdown] 开始转换, htmlLen={}", input.html().length());

        // 级别1：Python html2text
        String result = tryHtml2Text(input, context);
        if (result != null) {
            log.debug("[HtmlToMarkdown] html2text 转换成功");
            return ToolOutput.ok(result);
        }

        // 级别2：Python BeautifulSoup
        result = tryBeautifulSoup(input, context);
        if (result != null) {
            log.debug("[HtmlToMarkdown] BeautifulSoup 转换成功");
            return ToolOutput.ok(result);
        }

        // 级别3：Java 正则兜底
        result = tryRegexFallback(input);
        log.debug("[HtmlToMarkdown] 正则兜底转换");
        return ToolOutput.ok(result);
    }

    // ── 级别1：Python html2text ──

    private String tryHtml2Text(Input input, ToolContext context) {
        String script = """
            import sys, html2text
            h = html2text.HTML2Text()
            h.ignore_links = False
            h.ignore_images = False
            h.body_width = 0
            html = sys.stdin.read()
            print(h.handle(html))
            """;

        BashTool bashTool = new BashTool();
        String command = "echo '" + input.html().replace("'", "'\\''").substring(0, Math.min(input.html().length(), MAX_HTML_LENGTH))
                + "' | python3 -u -c \"" + script.replace("\"", "\\\"") + "\"";
        BashTool.Input bashInput = new BashTool.Input(command, 15);
        ToolOutput result = bashTool.call(bashInput, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

        if (result.success() && result.toText().length() > 10 && !result.toText().contains("ImportError")
                && !result.toText().contains("ModuleNotFoundError")) {
            return postProcess(result.toText());
        }
        return null;
    }

    // ── 级别2：Python BeautifulSoup ──

    private String tryBeautifulSoup(Input input, ToolContext context) {
        String onlyMain = input.onlyMainContent() ? "True" : "False";
        String script = """
            import sys
            from html.parser import HTMLParser

            class TextExtractor(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.result = []
                    self.skip_tags = {'script','style','noscript','meta','head','nav','footer','aside','header'}
                    %s
                    self.in_skip = 0
                    self.in_p = False
                    self.in_h = 0

                def handle_starttag(self, tag, attrs):
                    tag = tag.lower()
                    if tag in self.skip_tags:
                        self.in_skip += 1
                    elif tag in ('h1','h2','h3','h4','h5','h6'):
                        self.in_h = int(tag[1])
                        self.result.append('\\n')
                    elif tag == 'p':
                        self.in_p = True
                        self.result.append('\\n')
                    elif tag == 'br':
                        self.result.append('\\n')
                    elif tag == 'li':
                        self.result.append('\\n- ')
                    elif tag == 'a':
                        for name, value in attrs:
                            if name == 'href':
                                self.result.append('[')
                    elif tag == 'img':
                        alt = ''
                        src = ''
                        for name, value in attrs:
                            if name == 'alt': alt = value
                            if name == 'src': src = value
                        if alt or src:
                            self.result.append(f'![{alt}]({src})')
                    elif tag == 'code':
                        self.result.append('`')
                    elif tag == 'strong' or tag == 'b':
                        self.result.append('**')
                    elif tag == 'em' or tag == 'i':
                        self.result.append('*')

                def handle_endtag(self, tag):
                    tag = tag.lower()
                    if tag in self.skip_tags:
                        self.in_skip = max(0, self.in_skip - 1)
                    elif tag in ('h1','h2','h3','h4','h5','h6'):
                        self.result.append('\\n')
                        self.in_h = 0
                    elif tag == 'p':
                        self.in_p = False
                        self.result.append('\\n')
                    elif tag == 'a':
                        self.result.append(']')
                    elif tag == 'code':
                        self.result.append('`')
                    elif tag == 'strong' or tag == 'b':
                        self.result.append('**')
                    elif tag == 'em' or tag == 'i':
                        self.result.append('*')

                def handle_data(self, data):
                    if self.in_skip > 0:
                        return
                    text = data
                    if self.in_h > 0:
                        prefix = '#' * self.in_h + ' '
                        text = prefix + text
                    self.result.append(text)

                def get_text(self):
                    return ''.join(self.result)

            html = sys.stdin.read()
            extractor = TextExtractor()
            extractor.feed(html)
            print(extractor.get_text())
            """.formatted(onlyMain.equals("True") ? "self.skip_tags.update({'sidebar','ad','cookie-banner','modal'})" : "");

        BashTool bashTool = new BashTool();
        String command = "echo '" + input.html().replace("'", "'\\''").substring(0, Math.min(input.html().length(), MAX_HTML_LENGTH))
                + "' | python3 -u -c \"" + script.replace("\"", "\\\"") + "\"";
        BashTool.Input bashInput = new BashTool.Input(command, 15);
        ToolOutput result = bashTool.call(bashInput, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

        if (result.success() && result.toText().length() > 10) {
            return postProcess(result.toText());
        }
        return null;
    }

    // ── 级别3：Java 正则兜底 ──

    private String tryRegexFallback(Input input) {
        String html = input.html();

        // 移除不需要的标签
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "");
        html = html.replaceAll("(?is)<head[^>]*>.*?</head>", "");

        if (input.onlyMainContent()) {
            html = html.replaceAll("(?is)<nav[^>]*>.*?</nav>", "");
            html = html.replaceAll("(?is)<footer[^>]*>.*?</footer>", "");
            html = html.replaceAll("(?is)<aside[^>]*>.*?</aside>", "");
            html = html.replaceAll("(?is)<header[^>]*>.*?</header>", "");
        }

        // 转换标题
        html = html.replaceAll("(?i)<h1[^>]*>(.*?)</h1>", "\n# $1\n");
        html = html.replaceAll("(?i)<h2[^>]*>(.*?)</h2>", "\n## $1\n");
        html = html.replaceAll("(?i)<h3[^>]*>(.*?)</h3>", "\n### $1\n");
        html = html.replaceAll("(?i)<h4[^>]*>(.*?)</h4>", "\n#### $1\n");

        // 转换段落和换行
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)<p[^>]*>", "\n");
        html = html.replaceAll("(?i)</p>", "\n");
        html = html.replaceAll("(?i)<li[^>]*>", "\n- ");
        html = html.replaceAll("(?i)<hr\\s*/?>", "\n---\n");

        // 转换粗体/斜体
        html = html.replaceAll("(?i)<strong[^>]*>(.*?)</strong>", "**$1**");
        html = html.replaceAll("(?i)<b[^>]*>(.*?)</b>", "**$1**");
        html = html.replaceAll("(?i)<em[^>]*>(.*?)</em>", "*$1*");
        html = html.replaceAll("(?i)<i[^>]*>(.*?)</i>", "*$1*");

        // 转换链接
        html = html.replaceAll("(?i)<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", "[$2]($1)");

        // 转换图片
        html = html.replaceAll("(?i)<img[^>]*alt=\"([^\"]*)\"[^>]*src=\"([^\"]*)\"[^>]*/?>", "![$1]($2)");
        html = html.replaceAll("(?i)<img[^>]*src=\"([^\"]*)\"[^>]*/?>", "![]($1)");

        // 转换代码
        html = html.replaceAll("(?i)<code[^>]*>(.*?)</code>", "`$1`");

        // 移除所有剩余 HTML 标签
        html = html.replaceAll("<[^>]+>", "");

        // 解码 HTML 实体
        html = html.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");

        return postProcess(html);
    }

    // ── 后处理 ──

    private String postProcess(String markdown) {
        // 压缩多余空行
        markdown = markdown.replaceAll("\n{3,}", "\n\n");
        // 移除行首尾空白
        String[] lines = markdown.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use html_to_markdown to convert HTML to clean Markdown before feeding web content to LLM. Removes scripts, styles, nav, footer.";
    }
}
