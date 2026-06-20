package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级条件表达式引擎 — 支持 AND/OR/NOT 复合逻辑。
 * <p>
 * 替代原来基于字符串分割的 evaluateCondition，支持：
 * <ul>
 *   <li>比较运算：==, !=, >, >=, <, <=, contains, exists</li>
 *   <li>逻辑运算：AND, OR, NOT（大小写不敏感）</li>
 *   <li>括号分组：(condition AND condition) OR condition</li>
 *   <li>变量引用：{{node_id.key}}</li>
 * </ul>
 * <p>
 * 示例：
 * <pre>
 *   {{search_node.result_type}} == 'success' AND {{search_node.count}} > 0
 *   ({{build_node.tests_passed}} == 'true' OR {{build_node.skip_tests}} == 'true') AND NOT {{build_node.has_error}} exists
 * </pre>
 * <p>
 * 实现方式：递归下降解析器，不依赖外部库。
 */
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([\\w.]+)}}");

    /**
     * 评估条件表达式。
     *
     * @param condition 条件表达式
     * @param nodeMap   节点映射（用于变量解析）
     * @param context   工作流上下文
     * @return true=条件满足, false=条件不满足
     */
    public static boolean evaluate(String condition, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        if (condition == null || condition.isBlank()) {
            return true; // 无条件默认放行
        }

        try {
            // 1. 解析 {{node_id.key}} 占位符
            String resolved = resolveVariables(condition, nodeMap, context);

            // 2. 递归下降解析求值
            Parser parser = new Parser(resolved);
            boolean result = parser.parseExpression();
            parser.expectEnd();
            return result;
        } catch (Exception e) {
            log.warn("[ConditionEvaluator] 条件求值异常，默认放行: condition='{}', error={}", condition, e.getMessage());
            return true; // 条件求值失败时默认放行，不阻塞流程
        }
    }

    /**
     * 解析 {{node_id.key}} 占位符为实际值。
     */
    private static String resolveVariables(String condition, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        Matcher matcher = VAR_PATTERN.matcher(condition);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varRef = matcher.group(1);
            String[] parts = varRef.split("\\.", 2);
            String nodeId = parts[0];
            String key = parts.length > 1 ? parts[1] : "result";

            Object value = null;
            WorkflowNode sourceNode = nodeMap.get(nodeId);
            if (sourceNode != null) {
                value = sourceNode.getOutputData().get(key);
            }
            if (value == null && context != null) {
                Object ctxVal = context.resolveValue("{{" + varRef + "}}");
                if (ctxVal != null) value = ctxVal;
            }

            String strValue = value != null ? value.toString() : "null";
            // 如果值是布尔或数字，不加引号；否则加引号
            if ("true".equalsIgnoreCase(strValue) || "false".equalsIgnoreCase(strValue) ||
                strValue.matches("-?\\d+(\\.\\d+)?") || "null".equals(strValue)) {
                matcher.appendReplacement(result, strValue);
            } else {
                matcher.appendReplacement(result, "'" + Matcher.quoteReplacement(strValue) + "'");
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  递归下降解析器
    // ════════════════════════════════════════════════════════════════

    /**
     * 简单的递归下降解析器，支持 AND/OR/NOT 和括号分组。
     * <p>
     * 文法：
     * <pre>
     *   expression := orExpr
     *   orExpr     := andExpr (OR andExpr)*
     *   andExpr    := notExpr (AND notExpr)*
     *   notExpr    := NOT notExpr | primary
     *   primary    := '(' expression ')' | comparison
     *   comparison := operand (op operand)?
     *   op         := == | != | >= | <= | > | < | contains | exists
     *   operand    := quoted_string | number | boolean | null
     * </pre>
     */
    private static class Parser {
        private final String input;
        private int pos = 0;

        Parser(String input) {
            this.input = input.trim();
        }

        boolean parseExpression() {
            return parseOr();
        }

        void expectEnd() {
            skipWhitespace();
            if (pos < input.length()) {
                throw new RuntimeException("Unexpected token at position " + pos + ": " + input.substring(pos));
            }
        }

        private boolean parseOr() {
            boolean result = parseAnd();
            while (true) {
                skipWhitespace();
                if (matchKeyword("OR")) {
                    boolean right = parseAnd();
                    result = result || right;
                } else {
                    break;
                }
            }
            return result;
        }

        private boolean parseAnd() {
            boolean result = parseNot();
            while (true) {
                skipWhitespace();
                if (matchKeyword("AND")) {
                    boolean right = parseNot();
                    result = result && right;
                } else {
                    break;
                }
            }
            return result;
        }

        private boolean parseNot() {
            skipWhitespace();
            if (matchKeyword("NOT")) {
                return !parseNot();
            }
            return parsePrimary();
        }

        private boolean parsePrimary() {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++; // 跳过 '('
                boolean result = parseExpression();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ')') {
                    pos++; // 跳过 ')'
                } else {
                    throw new RuntimeException("Expected ')' at position " + pos);
                }
                return result;
            }
            return parseComparison();
        }

        private boolean parseComparison() {
            String left = parseOperand();
            skipWhitespace();

            // 检查是否有运算符
            if (pos >= input.length()) {
                // 无运算符：非空即真
                return isTruthy(left);
            }

            // 检查是否是逻辑关键字（说明这个 primary 已经结束）
            String remaining = input.substring(pos).toUpperCase();
            if (remaining.startsWith(" AND") || remaining.startsWith(" OR") ||
                remaining.startsWith(" NOT") || remaining.startsWith(")")) {
                return isTruthy(left);
            }

            // 解析运算符
            String op = parseOperator();
            skipWhitespace();
            String right = parseOperand();

            return applyOperator(left, op, right);
        }

        private String parseOperand() {
            skipWhitespace();
            if (pos >= input.length()) return "null";

            char c = input.charAt(pos);

            // 带引号的字符串
            if (c == '\'' || c == '"') {
                char quote = c;
                pos++; // 跳过引号
                int start = pos;
                while (pos < input.length() && input.charAt(pos) != quote) {
                    pos++;
                }
                String value = input.substring(start, pos);
                if (pos < input.length()) pos++; // 跳过结束引号
                return value;
            }

            // 数字、布尔、null 或无引号字符串
            int start = pos;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isWhitespace(ch) || ch == ')' || ch == '(') break;
                // 检查是否遇到运算符
                if (isOperatorStart(input, pos)) break;
                pos++;
            }
            return input.substring(start, pos).trim();
        }

        private String parseOperator() {
            skipWhitespace();
            // 检查关键字运算符
            if (matchKeyword("contains")) return "contains";
            if (matchKeyword("exists")) return "exists";

            // 检查符号运算符
            if (pos + 1 < input.length()) {
                String two = input.substring(pos, pos + 2);
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")) {
                    pos += 2;
                    return two;
                }
            }
            if (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '>' || c == '<') {
                    pos++;
                    return String.valueOf(c);
                }
            }
            throw new RuntimeException("Expected operator at position " + pos);
        }

        private boolean isOperatorStart(String s, int p) {
            if (p >= s.length()) return false;
            char c = s.charAt(p);
            if (c == '=' || c == '!' || c == '>' || c == '<') return true;
            // 检查 contains/exists 关键字
            String sub = s.substring(p).toUpperCase();
            return sub.startsWith("CONTAINS") || sub.startsWith("EXISTS") ||
                   sub.startsWith("AND") || sub.startsWith("OR") || sub.startsWith("NOT");
        }

        private boolean applyOperator(String left, String op, String right) {
            return switch (op) {
                case "==" -> left.equals(right);
                case "!=" -> !left.equals(right);
                case ">=" -> {
                    try { yield Double.parseDouble(left) >= Double.parseDouble(right); }
                    catch (NumberFormatException e) { yield false; }
                }
                case "<=" -> {
                    try { yield Double.parseDouble(left) <= Double.parseDouble(right); }
                    catch (NumberFormatException e) { yield false; }
                }
                case ">" -> {
                    try { yield Double.parseDouble(left) > Double.parseDouble(right); }
                    catch (NumberFormatException e) { yield false; }
                }
                case "<" -> {
                    try { yield Double.parseDouble(left) < Double.parseDouble(right); }
                    catch (NumberFormatException e) { yield false; }
                }
                case "contains" -> left.contains(right);
                case "exists" -> !"null".equals(left) && !left.isEmpty();
                default -> false;
            };
        }

        private boolean isTruthy(String value) {
            return !"null".equals(value) && !value.isEmpty() && !"false".equalsIgnoreCase(value);
        }

        private boolean matchKeyword(String keyword) {
            skipWhitespace();
            String remaining = input.substring(pos);
            if (remaining.toUpperCase().startsWith(keyword)) {
                // 确保是完整的关键字（后面是空格或括号或结束）
                int afterKeyword = pos + keyword.length();
                if (afterKeyword >= input.length() ||
                    Character.isWhitespace(input.charAt(afterKeyword)) ||
                    input.charAt(afterKeyword) == '(' || input.charAt(afterKeyword) == ')') {
                    pos = afterKeyword;
                    return true;
                }
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
