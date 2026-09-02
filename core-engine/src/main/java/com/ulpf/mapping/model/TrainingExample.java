package com.ulpf.mapping.model;

/**
 * Represents a labeled training example for TF-IDF matching.
 */
public class TrainingExample {
    
    private final String text;
    private final String canonicalField;
    
    public TrainingExample(String text, String canonicalField) {
        this.text = text;
        this.canonicalField = canonicalField;
    }
    
    public String getText() {
        return text;
    }
    
    public String getCanonicalField() {
        return canonicalField;
    }
}
