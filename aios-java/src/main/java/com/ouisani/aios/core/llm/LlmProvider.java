package com.ouisani.aios.core.llm;

import java.util.List;

public interface LlmProvider {

    String name();

    String think(String prompt, String systemPrompt);

    default String think(String prompt) {
        return think(prompt, "");
    }

    record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    String thinkWithHistory(List<ChatMessage> messages, String systemPrompt);

    boolean isAvailable();
}
