package com.ouisani.aios.core.llm;

public class VectorMath {

    public static float cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            throw new IllegalArgumentException("Vectors must be non-null, same length, and non-empty");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f;
        }

        return (float) (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    public static float euclideanDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            throw new IllegalArgumentException("Vectors must be non-null and same length");
        }

        float sum = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    public static float[] normalize(float[] v) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException("Vector must be non-null and non-empty");
        }

        float norm = 0.0f;
        for (float val : v) {
            norm += val * val;
        }
        norm = (float) Math.sqrt(norm);

        if (norm == 0.0f) {
            return new float[v.length];
        }

        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] / norm;
        }
        return result;
    }
}
