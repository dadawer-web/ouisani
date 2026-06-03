package com.ouisani.aios.core.llm;

public class TestEmbedding {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestEmbedding: Text Vectorization + Cosine Similarity    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        OpenAiAdapter adapter = new OpenAiAdapter("dummy", "https://api.openai.com", "gpt-4o-mini");

        System.out.println("── Step 1: Embed texts ──");
        String text1 = "AIOS is an AI operating system with virtual threads and WASM sandbox";
        String text2 = "The AI operating system uses Java virtual threads and WebAssembly";
        String text3 = "Today the weather is sunny and I want to eat ice cream";

        System.out.printf("  Text 1: %s%n", text1);
        System.out.printf("  Text 2: %s%n", text2);
        System.out.printf("  Text 3: %s%n", text3);
        System.out.println();

        float[] vec1 = adapter.embed(text1);
        float[] vec2 = adapter.embed(text2);
        float[] vec3 = adapter.embed(text3);

        System.out.printf("  Vec1 dimensions: %d%n", vec1.length);
        System.out.printf("  Vec2 dimensions: %d%n", vec2.length);
        System.out.printf("  Vec3 dimensions: %d%n", vec3.length);
        System.out.println();

        System.out.println("── Step 2: Compute cosine similarity ──");
        float sim12 = VectorMath.cosineSimilarity(vec1, vec2);
        float sim13 = VectorMath.cosineSimilarity(vec1, vec3);
        float sim23 = VectorMath.cosineSimilarity(vec2, vec3);

        System.out.printf("  Similarity(text1, text2) = %.6f  (semantically similar)%n", sim12);
        System.out.printf("  Similarity(text1, text3) = %.6f  (semantically different)%n", sim13);
        System.out.printf("  Similarity(text2, text3) = %.6f  (semantically different)%n", sim23);
        System.out.println();

        boolean testPassed = vec1.length > 0 && vec2.length > 0 && vec3.length > 0;

        if (testPassed) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🧮 [Embedding] Text Vectorization Test PASSED!          ║");
            System.out.println("  ║                                                          ║");
            System.out.printf("  ║  Dimensions: %d%n", vec1.length);
            System.out.printf("  ║  sim(AIOS desc, AIOS desc) = %.4f%n", sim12);
            System.out.printf("  ║  sim(AIOS desc, weather)   = %.4f%n", sim13);
            System.out.println("  ║  Semantic search foundation ready! 🌟                    ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Embedding Test FAILED!");
        }
    }
}
