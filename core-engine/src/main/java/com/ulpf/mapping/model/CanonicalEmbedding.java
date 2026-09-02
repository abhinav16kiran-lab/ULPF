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
        this.embeddingVector = embeddingVector;
    }
    
    public String getCanonicalField() {
        return canonicalField;
    }
    
    public double[] getEmbeddingVector() {
        return embeddingVector;
    }
}
