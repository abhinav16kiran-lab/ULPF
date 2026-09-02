package com.ulpf.mapping.service;

import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.model.NormalizedField;
import com.ulpf.mapping.repository.AliasRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 3: Typo / edit-distance matching service.
 * Catches misspellings and truncations using Levenshtein distance.
 */
@Service
public class TypoMatchingService {
    
    private final AliasRepository aliasRepository;
    
    public TypoMatchingService(AliasRepository aliasRepository) {
        this.aliasRepository = aliasRepository;
    }
    
    /**
     * Match a normalized field using edit distance against known aliases.
     * 
     * @param field the normalized field
     * @return list of candidates within threshold, sorted by similarity descending
     */
    public List<MappingCandidate> match(NormalizedField field) {
        List<MappingCandidate> results = new ArrayList<>();
        String queryKey = field.getCleanedText().replace(" ", "");
        
        Map<String, String> aliasMap = aliasRepository.getAliasMap();
        
        for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
            String aliasKey = entry.getKey();
            String canonicalField = entry.getValue();
            
            int distance = levenshteinDistance(queryKey, aliasKey);
            int maxLen = Math.max(queryKey.length(), aliasKey.length());
            
            // Threshold scales with word length
            boolean withinThreshold = (maxLen <= 4) 
                ? distance <= 1 
                : ((double) distance / maxLen) <= 0.30;
            
            if (withinThreshold) {
                // Convert distance to 0.0-1.0 similarity score
                double similarity = 1.0 - ((double) distance / maxLen);
                results.add(new MappingCandidate(canonicalField, similarity, "TYPO_MATCH"));
            }
        }
        
        return results.stream()
            .sorted(Comparator.comparingDouble(MappingCandidate::getScore).reversed())
            .collect(Collectors.toList());
    }
    
    /**
     * Compute Levenshtein distance between two strings.
     * 
     * @param s1 first string
     * @param s2 second string
     * @return edit distance
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(
                        dp[i - 1][j],      // deletion
                        Math.min(
                            dp[i][j - 1],  // insertion
                            dp[i - 1][j - 1] // substitution
                        )
                    );
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
}
