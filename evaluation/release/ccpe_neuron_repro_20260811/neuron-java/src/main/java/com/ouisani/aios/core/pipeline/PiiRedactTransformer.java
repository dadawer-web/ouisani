package com.ouisani.aios.core.pipeline;

import java.util.regex.Pattern;

/**
 * PII 脱敏转换器 — 借鉴 Firecrawl 的 performRedactPII。
 * 检测并脱敏个人身份信息（邮箱、电话、身份证号等）。
 */
public class PiiRedactTransformer implements ContentTransformer {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{6}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    @Override
    public String transform(String content, TransformContext context) {
        if (content == null || content.isBlank()) return content;

        String redacted = content;
        redacted = EMAIL_PATTERN.matcher(redacted).replaceAll("[EMAIL_REDACTED]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[PHONE_REDACTED]");
        redacted = ID_CARD_PATTERN.matcher(redacted).replaceAll("[ID_REDACTED]");
        redacted = BANK_CARD_PATTERN.matcher(redacted).replaceAll("[BANK_REDACTED]");

        return redacted;
    }

    @Override public String name() { return "pii_redact"; }
}
