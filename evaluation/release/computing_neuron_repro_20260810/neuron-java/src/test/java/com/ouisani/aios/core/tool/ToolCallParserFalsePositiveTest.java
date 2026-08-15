package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.tool.QueryEngine.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolCallParser false-positive 过滤测试 —— 同包以访问 package-private {@link ToolCallParser}。
 * <p>
 * 验证借鉴自 OpenWorker {@code openai_provider.py:444-463} 的 {@code _call_from_dict}：
 * 用 requested tool schemas（这里即 registeredToolNames）识别真实工具名形式，
 * 过滤掉任何名字不是真实工具的标签/JSON（no false positive）。
 * <p>
 * <b>重要语义</b>：registeredToolNames 只能过滤"非真实工具名"（如 {@code <example>}、
 * {@code <thought>}、虚构的 {@code faketool}）。它<b>无法</b>区分"真实调用"与"散文中的示例"——
 * 若 LLM 在散文里写 {@code <glob>*.java</glob>} 且 glob 已注册，仍会被解析为调用。
 * 这与 OpenWorker 行为一致（name 匹配即视为调用，prose-vs-call 歧义需上层提示词解决）。
 */
class ToolCallParserFalsePositiveTest {

    private static final Set<String> REGISTERED = Set.of("glob", "bash", "file_read");

    private static List<ToolCall> parse(String response) {
        return ToolCallParser.parseToolCalls(response, REGISTERED);
    }

    // ── 非真实工具名：必须被过滤（no false positive）──

    @Test
    void nonRegisteredDirectTagFiltered() {
        // LLM 输出格式示例标签 <example>…</example>，example 非注册工具 → 不触发
        List<ToolCall> calls = parse("你可以这样调用：<example>{\"path\":\"/tmp\"}</example>");
        assertTrue(calls.isEmpty(), "非注册工具名标签必须被过滤");
    }

    @Test
    void multipleExampleTagsAllFiltered() {
        // 散文中多种非注册标签混合 → 全部过滤
        List<ToolCall> calls = parse("<thought>用户想搜索</thought> 用 <example>glob *.java</example> 演示");
        assertTrue(calls.isEmpty(), "非注册标签（thought/example）必须全部被过滤");
    }

    @Test
    void nonRegisteredNameInToolCallJsonFiltered() {
        // <tool_call> 块内 JSON name 是虚构工具 → 过滤
        List<ToolCall> calls = parse("<tool_call>{\"name\":\"faketool\",\"arguments\":{}}</tool_call>");
        assertTrue(calls.isEmpty(), "<tool_call> 内非注册工具名必须被过滤");
    }

    @Test
    void nonRegisteredFunctionBlockFiltered() {
        // <function=非注册> → 过滤
        List<ToolCall> calls = parse("<function=faketool><parameter=x>1</parameter></function>");
        assertTrue(calls.isEmpty(), "<function=非注册> 必须被过滤");
    }

    @Test
    void nonRegisteredFunctionBlockAtTopLevelFiltered() {
        // 顶层 <function=非注册>（无 <tool_call> 包裹）→ 过滤
        List<ToolCall> calls = parse("正文 <function=faketool>data</function> 结尾");
        assertTrue(calls.isEmpty(), "顶层 <function=非注册> 必须被过滤");
    }

    // ── 真实工具名：必须被解析（正向路径，记录固有行为）──

    @Test
    void registeredDirectTagParsed() {
        // 注册工具的直标签 → 解析（注：散文示例与真实调用无法区分，name 匹配即触发）
        List<ToolCall> calls = parse("调用 <glob>*.java</glob> 搜索");
        assertEquals(1, calls.size());
        assertEquals("glob", calls.get(0).toolName());
        assertEquals("*.java", calls.get(0).paramsJson());
    }

    @Test
    void registeredNameInToolCallJsonParsed() {
        List<ToolCall> calls = parse("<tool_call>{\"name\":\"glob\",\"arguments\":{\"pattern\":\"*.java\"}}</tool_call>");
        assertEquals(1, calls.size());
        assertEquals("glob", calls.get(0).toolName());
    }

    @Test
    void registeredFunctionBlockParsed() {
        List<ToolCall> calls = parse("<function=glob><parameter=pattern>*.java</parameter></function>");
        assertEquals(1, calls.size());
        assertEquals("glob", calls.get(0).toolName());
        assertTrue(calls.get(0).paramsJson().contains("pattern"));
    }

    @Test
    void registeredAndUnregisteredMixed_onlyRegisteredKept() {
        // 混合：一个注册、一个非注册 → 只保留注册的
        List<ToolCall> calls = parse("<glob>*.ts</glob> <example>not a tool</example>");
        assertEquals(1, calls.size(), "只应保留注册工具调用");
        assertEquals("glob", calls.get(0).toolName());
    }
}
