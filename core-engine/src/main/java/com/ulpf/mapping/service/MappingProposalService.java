package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.mapping.model.MappingProposal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for persisting AI schema mapping proposals as versioned CANDIDATE records
 * in SQLite mapping_versions using MappingRepository.
 */
@Service
public class MappingProposalService {

    private final MappingRepository mappingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MappingProposalService(MappingRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    /**
     * Save a batch of mapping proposals as one CANDIDATE version in mapping_versions.
     * Computes the next incremental version number dynamically.
     * 
     * @param sourceId the source ID
     * @param proposals list of all mapping proposals for this batch
     * @return the saved MappingVersionRecord
     */
    public MappingVersionRecord saveMappingVersion(String sourceId, List<MappingProposal> proposals) {
        try {
            String mappingJson = buildMappingJson(proposals);
            int version = mappingRepository.getNextVersionNumber(sourceId);

            MappingVersionRecord record = new MappingVersionRecord(
                    null, sourceId, version, mappingJson, "CANDIDATE", null
            );

            return mappingRepository.saveMappingVersion(record);
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

            if (proposals != null) {
                for (MappingProposal p : proposals) {
                    Map<String, Object> fieldMapping = new LinkedHashMap<>();
                    fieldMapping.put("canonicalField", p.getCanonicalField());
                    fieldMapping.put("confidence", p.getConfidence());
                    fieldMapping.put("source", p.getSource());

                    byField.put(p.getVendorFieldRaw(), fieldMapping);
                }
            }

            return objectMapper.writeValueAsString(byField);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize mapping JSON", e);
        }
    }
}
