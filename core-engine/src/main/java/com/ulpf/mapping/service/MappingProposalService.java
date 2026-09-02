package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.mapping.model.MappingProposal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for persisting mapping proposals to the database.
 * 
 * TEMPORARY/PLACEHOLDER: This implementation should be swapped for a call into
 * Adarsh's shared save function once its real signature exists. See design doc
 * for open questions about mapping_json shape and version numbering.
 */
@Service
public class MappingProposalService {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public MappingProposalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Save a batch of mapping proposals as one row in mapping_versions.
     * Called once per onboarding batch, not once per field.
     * 
     * @param sourceId the source ID
     * @param proposals list of all mapping proposals for this batch
     */
    public void saveMappingVersion(String sourceId, List<MappingProposal> proposals) {
        try {
            String mappingJson = buildMappingJson(proposals);
            
            String sql = """
                INSERT INTO mapping_versions (mapping_id, source_id, version, mapping_json, status)
                VALUES (?, ?, ?, ?, 'CANDIDATE')
                """;
            
            // Generate a simple mapping_id (in practice, should use UUID or similar)
            String mappingId = "map_" + System.currentTimeMillis();
            
            // Version computation is left to Adarsh's eventual save function
            // For now, use a placeholder version of 1
            int version = 1;
            
            jdbcTemplate.update(sql, mappingId, sourceId, version, mappingJson);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to save mapping version for source: " + sourceId, e);
        }
    }
    
    /**
     * Build the mapping_json blob from proposals.
     * Shape: { "vendorField1": { "canonicalField": "...", "confidence": 0.9, "source": "..." }, ... }
     */
    private String buildMappingJson(List<MappingProposal> proposals) {
        try {
            Map<String, Object> byField = new LinkedHashMap<>();
            
            for (MappingProposal p : proposals) {
                Map<String, Object> fieldMapping = new LinkedHashMap<>();
                fieldMapping.put("canonicalField", p.getCanonicalField());
                fieldMapping.put("confidence", p.getConfidence());
                fieldMapping.put("source", p.getSource());
                
                byField.put(p.getVendorFieldRaw(), fieldMapping);
            }
            
            return objectMapper.writeValueAsString(byField);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize mapping JSON", e);
        }
    }
}
