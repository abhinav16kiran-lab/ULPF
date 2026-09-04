package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.CanonicalEmbedding;
import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.repository.EmbeddingRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Layer 4: Semantic matching using MiniLM embeddings.
 * Embeds the raw field text and compares against canonical field descriptions.
 */
@Service
public class EmbeddingMatchingService {
    
    private final EmbeddingClient embeddingClient;
    private final EmbeddingRepository embeddingRepository;
    private final MappingConfig config;
    
    public EmbeddingMatchingService(
            EmbeddingClient embeddingClient,
            EmbeddingRepository embeddingRepository,
            MappingConfig config) {
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
        this.config = config;
    }
    
    /**
     * Match a raw field name using semantic similarity with fallback blending.
     * 
     * @param rawFieldName the raw vendor field name
     * @param bestPriorScore the best score from Layer 2/3, or 0.0 if neither found anything
     * @return mapping candidate with blended score
     */
    public MappingCandidate matchWithFallback(String rawFieldName, double bestPriorScore) {
        try {
            // Embed the raw field name (not cleaned/preprocessed per design doc)
            double[] fieldEmbedding = embeddingClient.getEmbedding(rawFieldName);
            
            // Find best matching canonical field by cosine similarity
            List<CanonicalEmbedding> canonicalEmbeddings = embeddingRepository.findAllCanonicalEmbeddings();
            
            if (canonicalEmbeddings == null || canonicalEmbeddings.isEmpty()) {
                // No embeddings available yet - return with prior score
                return new MappingCandidate(null, bestPriorScore, "L4_HYBRID");
            }
            
            CanonicalEmbedding bestMatch = null;
            double bestRawSimilarity = -1.0;
            
            for (CanonicalEmbedding candidate : canonicalEmbeddings) {
                double similarity = cosineSimilarity(fieldEmbedding, candidate.getEmbeddingVector());
                if (similarity > bestRawSimilarity) {
                    bestRawSimilarity = similarity;
                    bestMatch = candidate;
                }
            }
            
            if (bestMatch == null) {
                // No embeddings available
                return new MappingCandidate(null, 0.0, "L4_HYBRID");
            }
            
            // Scale raw similarity to 0.0-1.0 range
            // Constants from design doc: stretch narrow band to full range
            double scaledSimilarity = clamp((bestRawSimilarity - 0.12) / 0.30, 0.0, 1.0);
            
            // Blend with prior layer scores
            double combined;
            if (bestPriorScore == 0.0) {
                // No prior signal, use semantic score alone
                combined = scaledSimilarity;
            } else {
                // Blend: 30% prior layers, 70% semantic
                combined = (0.3 * bestPriorScore) + (0.7 * scaledSimilarity);
            }
            
            return new MappingCandidate(bestMatch.getCanonicalField(), combined, "L4_HYBRID");
            
        } catch (Exception e) {
            // If embedding fails, return empty candidate
            throw new RuntimeException("Embedding matching failed for field: " + rawFieldName, e);
        }
    }
    
    /**
     * Compute cosine similarity between two vectors.
     */
    private double cosineSimilarity(double[] v1, double[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        
        norm1 = Math.sqrt(norm1);
        norm2 = Math.sqrt(norm2);
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (norm1 * norm2);
    }
    
    /**
     * Clamp a value between min and max.
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clear cached canonical embeddings from memory to release RAM immediately.
     */
    public void clearCache() {
        embeddingRepository.clearCache();
    }
}
