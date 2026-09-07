package com.ulpf.mapping.service;

import com.ulpf.mapping.repository.AliasRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for learning from human corrections during mapping review.
 * Extracts corrections and saves them to the mapping_aliases table for future use.
 */
@Service
public class MappingLearningService {
    
    private static final Logger log = LoggerFactory.getLogger(MappingLearningService.class);
    
    private final CorrectionExtractor correctionExtractor;
    private final FieldPreprocessor fieldPreprocessor;
    private final AliasRepository aliasRepository;
    
    public MappingLearningService(
            CorrectionExtractor correctionExtractor,
            FieldPreprocessor fieldPreprocessor,
            AliasRepository aliasRepository) {
        this.correctionExtractor = correctionExtractor;
        this.fieldPreprocessor = fieldPreprocessor;
        this.aliasRepository = aliasRepository;
    }
    
    /**
     * Learn from human corrections by comparing old (AI) vs new (human-edited) mapping.
     * For each correction, normalize the vendor field name and save to mapping_aliases.
     * 
     * @param oldMappingJson the original AI-proposed mapping JSON
     * @param newMappingJson the human-edited mapping JSON
     * @return number of new aliases learned
     */
    public int learnFromCorrections(String oldMappingJson, String newMappingJson) {
        // Step 1: Extract all corrections
        List<CorrectionExtractor.Correction> corrections = 
            correctionExtractor.extractCorrections(oldMappingJson, newMappingJson);
        
        if (corrections.isEmpty()) {
            log.debug("No corrections found - AI mapping was fully accepted");
            return 0;
        }
        
        log.info("Learning from {} human corrections", corrections.size());
        
        // Step 2: For each correction, normalize and save to aliases table
        int aliasesLearned = 0;
        
        for (CorrectionExtractor.Correction correction : corrections) {
            try {
                // Only learn if human corrected to a valid canonical field
                if (correction.humanCorrected() != null && !correction.humanCorrected().isBlank()) {
                    // Normalize the vendor field name (same logic as Layer 1 lookup)
                    String normalizedKey = normalizeForAliasLookup(correction.vendorFieldRaw());
                    
                    // Save to mapping_aliases with source='human_correction'
                    aliasRepository.insertAlias(
                        correction.humanCorrected(),  // Canonical field (e.g., "src_ip")
                        normalizedKey,                // Normalized vendor field (e.g., "sourceaddress")
                        "human_correction"            // Source = learned from human
                    );
                    
                    aliasesLearned++;
                    
                    log.info("Learned: '{}' (normalized: '{}') → '{}' [was: '{}']", 
                            correction.vendorFieldRaw(), 
                            normalizedKey,
                            correction.humanCorrected(), 
                            correction.aiProposed());
                }
            } catch (Exception e) {
                log.warn("Failed to learn from correction for field '{}': {}", 
                        correction.vendorFieldRaw(), e.getMessage());
            }
        }
        
        log.info("Successfully learned {} new aliases from human corrections", aliasesLearned);
        return aliasesLearned;
    }
    
    /**
     * Normalize vendor field name for alias lookup (same logic as Layer 1).
     * Must match the normalization in AliasLookupService.lookup().
     * 
     * Example: "SourceAddress" → "sourceaddress" (lowercase, no spaces)
     */
    private String normalizeForAliasLookup(String vendorFieldRaw) {
        // Step 1: Use FieldPreprocessor to get cleaned text
        var normalized = fieldPreprocessor.process(vendorFieldRaw);
        
        // Step 2: Remove all spaces (Layer 1 lookup does this)
        return normalized.getCleanedText().replace(" ", "");
    }
}
