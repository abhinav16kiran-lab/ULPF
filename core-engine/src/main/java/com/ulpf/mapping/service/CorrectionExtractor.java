package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility for extracting human corrections from mapping JSON changes.
 * Compares AI-proposed mappings vs human-edited mappings to identify learning opportunities.
 */
@Component
public class CorrectionExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(CorrectionExtractor.class);
    private final ObjectMapper objectMapper;
    
    public CorrectionExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Represents a single correction made by a human reviewer.
     */
    public record Correction(
        String vendorFieldRaw,      // Original vendor field name (e.g., "SourceAddress")
        String aiProposed,          // What AI suggested (e.g., "url" or null)
        String humanCorrected       // What human changed it to (e.g., "src_ip")
    ) {}
    
    /**
     * Extract all corrections by comparing old (AI) vs new (human-edited) mapping JSON.
     * 
     * @param oldMappingJson the AI-proposed mapping JSON
     * @param newMappingJson the human-edited mapping JSON
     * @return list of corrections where human changed the AI's proposal
     */
    public List<Correction> extractCorrections(String oldMappingJson, String newMappingJson) {
        List<Correction> corrections = new ArrayList<>();
        
        try {
            JsonNode oldRoot = objectMapper.readTree(oldMappingJson);
            JsonNode newRoot = objectMapper.readTree(newMappingJson);
            
            // Iterate through all fields in the new mapping
            Iterator<Map.Entry<String, JsonNode>> fields = newRoot.fields();
            
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String vendorFieldRaw = entry.getKey();
                
                // Skip metadata block (not a field mapping)
                if ("metadata".equals(vendorFieldRaw)) {
                    continue;
                }
                
                JsonNode newFieldNode = entry.getValue();
                JsonNode oldFieldNode = oldRoot.get(vendorFieldRaw);
                
                // If field doesn't exist in old mapping, skip (newly added field)
                if (oldFieldNode == null) {
                    continue;
                }
                
                // Extract canonical field values (handle null gracefully)
                String oldCanonical = extractCanonicalField(oldFieldNode);
                String newCanonical = extractCanonicalField(newFieldNode);
                
                // If human changed the mapping, record it as a correction
                if (!equals(oldCanonical, newCanonical)) {
                    corrections.add(new Correction(vendorFieldRaw, oldCanonical, newCanonical));
                    log.debug("Correction detected: '{}' - AI proposed '{}', human corrected to '{}'", 
                             vendorFieldRaw, oldCanonical, newCanonical);
                }
            }
            
            log.info("Extracted {} corrections from mapping changes", corrections.size());
            
        } catch (Exception e) {
            log.warn("Failed to extract corrections from mapping JSON: {}", e.getMessage());
        }
        
        return corrections;
    }
    
    /**
     * Extract canonicalField value from a field node, handling null/missing gracefully.
     */
    private String extractCanonicalField(JsonNode fieldNode) {
        if (fieldNode == null || !fieldNode.has("canonicalField")) {
            return null;
        }
        
        JsonNode canonical = fieldNode.get("canonicalField");
        return canonical.isNull() ? null : canonical.asText();
    }
    
    /**
     * Null-safe equality check.
     */
    private boolean equals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
