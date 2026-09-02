package com.ulpf.mapping.model;

import java.util.List;

/**
 * Represents a field name after preprocessing/normalization.
 * Immutable value object containing the raw text, cleaned text, and token list.
 */
public class NormalizedField {
    
    private final String rawText;
    private final String cleanedText;
    private final List<String> tokens;
    
    public NormalizedField(String rawText, String cleanedText, List<String> tokens) {
        this.rawText = rawText;
        this.cleanedText = cleanedText;
        this.tokens = List.copyOf(tokens); // Defensive copy for immutability
    }
    
    public String getRawText() {
        return rawText;
    }
    
    public String getCleanedText() {
        return cleanedText;
    }
    
    public List<String> getTokens() {
        return tokens;
    }
}
