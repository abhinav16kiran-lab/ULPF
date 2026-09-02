package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.MappingCandidate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shared confidence evaluation logic used by Layers 2 and 3.
 * Determines if a set of candidates is trustworthy enough to accept.
 */
@Service
public class ConfidenceEvaluator {
    
    private final MappingConfig config;
    
    public ConfidenceEvaluator(MappingConfig config) {
        this.config = config;
    }
    
    /**
     * Check if the top candidate meets confidence requirements.
     * Requires both a good top score AND a clear gap from the runner-up.
     * 
     * @param ranked list of candidates sorted by score descending
     * @return true if confident, false otherwise
     */
    public boolean isConfident(List<MappingCandidate> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return false;
        }
        
        double topScore = ranked.get(0).getScore();
        double secondScore = ranked.size() > 1 ? ranked.get(1).getScore() : 0.0;
        double gap = topScore - secondScore;
        
        return topScore >= config.getConfidenceThreshold() 
            && gap >= config.getGapThreshold();
    }
    
    /**
     * Check if a raw field name is meaningful (not blank/gibberish).
     * Used to filter out junk input before expensive Layer 4.
     * 
     * @param rawFieldName the raw field name
     * @return true if meaningful, false if gibberish/blank
     */
    public boolean isMeaningfulToken(String rawFieldName) {
        if (rawFieldName == null) {
            return false;
        }
        
        // Strip all non-alphanumeric characters
        String cleaned = rawFieldName.replaceAll("[^a-zA-Z0-9]", "");
        
        // Require at least 2 characters
        return cleaned.length() >= 2;
    }
}
