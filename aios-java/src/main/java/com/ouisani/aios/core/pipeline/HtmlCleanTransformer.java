package com.ouisani.aios.core.pipeline;

/**
 * HTML 清洗转换器 — 借鉴 Firecrawl 的 removeUnwantedElements。
 * 移除 script/style/nav/footer/aside/header 等非主体内容。
 */
public class HtmlCleanTransformer implements ContentTransformer {

    @Override
    public String transform(String content, TransformContext context) {
        if (content == null || content.isBlank()) return content;

        String html = content;
        boolean onlyMain = !"false".equals(context.getOption("onlyMainContent", "true"));

        // 移除 script/style/noscript
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "");
        html = html.replaceAll("(?is)<meta[^>]*>", "");
        html = html.replaceAll("(?is)<link[^>]*>", "");

        if (onlyMain) {
            html = html.replaceAll("(?is)<nav[^>]*>.*?</nav>", "");
            html = html.replaceAll("(?is)<footer[^>]*>.*?</footer>", "");
            html = html.replaceAll("(?is)<aside[^>]*>.*?</aside>", "");
            html = html.replaceAll("(?is)<header[^>]*>.*?</header>", "");
            html = html.replaceAll("(?is)<div[^>]*class=\"[^\"]*(?:sidebar|ad|cookie|banner|modal|popup)[^\"]*\"[^>]*>.*?</div>", "");
        }

        // 移除 HTML 注释
        html = html.replaceAll("<!--.*?-->", "");

        return html;
    }

    @Override public String name() { return "html_clean"; }
    @Override public boolean required() { return true; }
}
