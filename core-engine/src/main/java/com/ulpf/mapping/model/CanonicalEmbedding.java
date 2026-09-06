package com.ulpf.mapping.model;

/**
 * Represents a canonical field with its pre-computed embedding vector.
 * Used by Layer 4 semantic matching.
 */
public class CanonicalEmbedding {
    
    private final String canonicalField;
    private final double[] embeddingVector;
    
    public CanonicalEmbedding(String canonicalField, double[] embeddingVector) {
        this.canonicalField = canonicalField;
        this.embeddingVector = normalize(embeddingVector);
    }
    
    private static double[] normalize(double[] v) {
        if (v == null || v.length == 0) return v;
        double norm = 0.0;
        for (double val : v) {
            norm += val * val;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            double[] normalized = new double[v.length];
            for (int i = 0; i < v.length; i++) {
                normalized[i] = v[i] / norm;
            }
            return normalized;
        }
        return v;
    }
    
    public String getCanonicalField() {
        return canonicalField;
    }
    
    public double[] getEmbeddingVector() {
        return embeddingVector;
    }
}
