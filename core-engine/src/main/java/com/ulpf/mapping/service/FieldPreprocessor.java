package com.ulpf.mapping.service;

import com.ulpf.mapping.model.NormalizedField;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Preprocesses raw field names into normalized form for matching.
 * Implements the three-step regex normalization from the design doc.
 */
@Service
public class FieldPreprocessor {
    
    /**
     * Process a raw field name into normalized form.
     * 
     * @param rawFieldName the raw field name from vendor schema
     * @return NormalizedField with cleaned text and tokens
     */
    public NormalizedField process(String rawFieldName) {
        // Step 1: Split acronym boundaries (e.g., "HTTPMethod" -> "HTTP Method")
        String step1 = rawFieldName.replaceAll("(.)([A-Z][a-z]+)", "$1 $2");
        
        // Step 2: Split ordinary camelCase and digit boundaries
        String step2 = step1.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        
        // Step 3: Normalize separators, lowercase, and trim
        String cleanedText = step2.replaceAll("[_\\-.]+", " ")
                                  .toLowerCase()
                                  .trim()
                                  .replaceAll("\\s+", " ");
        
        List<String> tokens = Arrays.asList(cleanedText.split(" "));
        
        return new NormalizedField(rawFieldName, cleanedText, tokens);
    }
}
