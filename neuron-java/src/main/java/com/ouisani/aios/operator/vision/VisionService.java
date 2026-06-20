package com.ouisani.aios.operator.vision;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmProvider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 视觉服务 — 将截图发送给多模态模型，获取屏幕内容描述。
 * <p>
 * 这是 OperatorAgent 的"眼睛"：ComputerUseTool 截图后，VisionService
 * 将 Base64 图片发送给 mimo-v2.5 等多模态模型，获取屏幕上的 UI 元素、
 * 文字、按钮位置等结构化描述，供操作员决策下一步动作。
 * <p>
 * 调用链路：ComputerUseTool.screenshot → HostRpaManager.takeScreenshotBase64
 * → VisionService.analyzeScreenshot → multimodalProvider.thinkWithHistory → 屏幕描述
 *
 * @see com.ouisani.aios.operator.tools.ComputerUseTool
 */
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    /** 多模态 LLM Provider（mimo-v2.5 等） */
    private final LlmProvider multimodalProvider;

    /** 视觉分析 System Prompt */
    private static final String VISION_SYSTEM_PROMPT =
            "You are a screen analysis assistant for a computer use agent. "
            + "Your job is to describe what you see on the screen in a structured way that helps the agent decide its next action.\n\n"
            + "For each screenshot, provide:\n"
            + "1. **Active Window**: Which application/window is currently focused\n"
            + "2. **UI Elements**: Buttons, input fields, menus, dialogs with their approximate positions (describe as top-left, center, bottom-right etc.)\n"
            + "3. **Text Content**: Any visible text, especially in input fields, labels, and messages\n"
            + "4. **State**: What state is the application in? (loading, ready, error, dialog open, etc.)\n"
            + "5. **Suggested Action**: What the agent should do next based on the current screen state\n\n"
            + "Be concise but precise. Use pixel coordinates when possible (estimate from the image). "
            + "Format coordinates as (x, y) where (0,0) is top-left corner.";

    public VisionService(LlmProvider multimodalProvider) {
        this.multimodalProvider = multimodalProvider;
    }

    /**
     * 分析截图，返回屏幕内容描述。
     *
     * @param screenshotBase64 截图的 Base64 编码（JPEG）
     * @param screenWidth      屏幕宽度（用于坐标估算）
     * @param screenHeight     屏幕高度（用于坐标估算）
     * @return 多模态模型返回的屏幕描述文本
     */
    public String analyzeScreenshot(String screenshotBase64, int screenWidth, int screenHeight) {
        if (multimodalProvider == null || !multimodalProvider.isAvailable()) {
            return "[VisionService] Multimodal provider not available — cannot analyze screenshot. "
                    + "Screen resolution: " + screenWidth + "x" + screenHeight;
        }

        String userPrompt = "Analyze this screenshot. Screen resolution: " + screenWidth + "x" + screenHeight
                + ". Describe all visible UI elements, text, and suggest the next action for the operator agent.";

        ChatMessage visionMessage = ChatMessage.userWithImage(userPrompt, screenshotBase64, null);

        try {
            long startMs = System.currentTimeMillis();
            String result = multimodalProvider.thinkWithHistory(
                    List.of(visionMessage), VISION_SYSTEM_PROMPT);
            long elapsedMs = System.currentTimeMillis() - startMs;

            log.info("[VisionService] 截图已分析，耗时 {}ms，响应长度: {}",
                    elapsedMs, result != null ? result.length() : 0);

            if (result == null || result.isBlank()) {
                return "[VisionService] Multimodal model returned empty response. Screen: "
                        + screenWidth + "x" + screenHeight;
            }

            return result;
        } catch (Exception e) {
            log.error("[VisionService] Failed to analyze screenshot: {}", e.getMessage());
            return "[VisionService] Error analyzing screenshot: " + e.getMessage()
                    + ". Screen resolution: " + screenWidth + "x" + screenHeight;
        }
    }

    /**
     * 分析截图（全屏），返回屏幕内容描述。
     */
    public String analyzeScreenshot(String screenshotBase64) {
        return analyzeScreenshot(screenshotBase64, 0, 0);
    }

    /** 检查多模态 Provider 是否可用 */
    public boolean isAvailable() {
        return multimodalProvider != null && multimodalProvider.isAvailable();
    }

    /**
     * 分析截图，定位指定 UI 元素的坐标 — 供 DesktopGuiTool 引擎二调用。
     * <p>
     * 向多模态模型发送截图 + 目标元素描述，要求模型返回 "x,y" 格式的坐标。
     * 坐标为截图图像空间（缩放后），与 ComputerUseTool 的坐标体系一致。
     *
     * @param screenshotBase64 截图的 Base64 编码（JPEG）
     * @param screenWidth      截图宽度
     * @param screenHeight     截图高度
     * @param elementName      目标元素描述（如 "Send button"、"地址栏"）
     * @return "x,y" 格式的坐标字符串，或 null 表示未找到
     */
    public String analyzeScreenshotForElement(String screenshotBase64, int screenWidth, int screenHeight, String elementName) {
        if (multimodalProvider == null || !multimodalProvider.isAvailable()) {
            return null;
        }

        String userPrompt = "Find the UI element described as: \"" + elementName + "\"\n"
                + "Screen resolution: " + screenWidth + "x" + screenHeight + "\n"
                + "Return ONLY the center coordinates of this element as: x,y\n"
                + "If the element is not visible, return: NOT_FOUND\n"
                + "Do not include any other text, explanation, or formatting.";

        ChatMessage visionMessage = ChatMessage.userWithImage(userPrompt, screenshotBase64, null);

        try {
            String result = multimodalProvider.thinkWithHistory(
                    List.of(visionMessage), VISION_COORD_SYSTEM_PROMPT);

            if (result == null || result.isBlank()) {
                return null;
            }

            // 清理模型输出：去除 Markdown、空格、换行
            String cleaned = result.trim()
                    .replaceAll("```.*?```", "")  // 去除代码块
                    .replaceAll("[^0-9,\\-]", "")  // 只保留数字和逗号
                    .trim();

            if (cleaned.isEmpty() || "NOT_FOUND".equalsIgnoreCase(result.trim())) {
                return null;
            }

            // 验证格式：x,y
            String[] parts = cleaned.split(",");
            if (parts.length >= 2) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                // 边界检查
                if (x >= 0 && y >= 0 && x <= screenWidth && y <= screenHeight) {
                    return x + "," + y;
                }
            }

            return null;

        } catch (Exception e) {
            log.error("[VisionService] Failed to locate element '{}': {}", elementName, e.getMessage());
            return null;
        }
    }

    /** 视觉坐标定位 System Prompt — 要求模型只返回坐标 */
    private static final String VISION_COORD_SYSTEM_PROMPT =
            "You are a precise UI element locator. Given a screenshot and an element description, "
            + "find the element and return its center pixel coordinates.\n"
            + "IMPORTANT: Return ONLY the coordinates in format: x,y\n"
            + "Where (0,0) is the top-left corner of the image.\n"
            + "If the element is not found, return: NOT_FOUND\n"
            + "Do NOT include any explanation, markdown, or extra text.";
}
