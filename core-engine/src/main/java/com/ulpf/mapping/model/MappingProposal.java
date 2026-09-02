package com.ulpf.mapping.model;

/**
 * Final output of the mapping pipeline for one field.
 * Immutable value object representing the mapping decision.
 */
public class MappingProposal {
    
    private final String vendorFieldRaw;
    private final String canonicalField;  // null if UNKNOWN
    private final double confidence;
    private final String source;  // ALIAS_LOOKUP, TFIDF, TYPO_MATCH, L4_HYBRID, NONE
    
    public MappingProposal(String vendorFieldRaw, String canonicalField, double confidence, String source) {
        this.vendorFieldRaw = vendorFieldRaw;
        this.canonicalField = canonicalField;
        this.confidence = confidence;
        this.source = source;
    }
    
    public String getVendorFieldRaw() {
        return vendorFieldRaw;
    }
    
    public String getCanonicalField() {
        return canonicalField;
    }
    
    public double getConfidence() {
        return confidence;
    }
    
    public String getSource() {
        return source;
    }
}
