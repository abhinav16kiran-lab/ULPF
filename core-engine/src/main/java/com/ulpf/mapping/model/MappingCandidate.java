package com.ulpf.mapping.model;

/**
 * Represents a single candidate mapping from a scoring layer.
 * Immutable value object with a canonical field name, normalized score (0.0-1.0),
 * and the layer that produced it.
 */
public class MappingCandidate {
    
    private final String canonicalField;
    private final double score;
    private final String producedByLayer;
    
    public MappingCandidate(String canonicalField, double score, String producedByLayer) {
        this.canonicalField = canonicalField;
        this.score = score;
        this.producedByLayer = producedByLayer;
    }
    
    public String getCanonicalField() {
        return canonicalField;
    }
    
    public double getScore() {
        return score;
    }
    
    public String getProducedByLayer() {
        return producedByLayer;
    }
}
