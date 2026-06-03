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

    float[] embed(String text);

    default float[] mockEmbed(String text) {
        int dimensions = 1536;
        float[] vector = new float[dimensions];
        int hash = text != null ? text.hashCode() : 0;
        long seed = hash != 0 ? Math.abs(hash) : 42;
        for (int i = 0; i < dimensions; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            vector[i] = ((float) ((seed >>> 33) & 0x7FFFFFFF) / 0x7FFFFFFF - 0.5f) * 0.1f;
        }
        return vector;
    }

    boolean isAvailable();
}
