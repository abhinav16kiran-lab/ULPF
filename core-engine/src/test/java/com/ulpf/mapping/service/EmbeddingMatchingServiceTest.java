package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.CanonicalEmbedding;
import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.repository.EmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmbeddingMatchingService.
 * Tests blending formula, scaling, and clamping logic with mocked embeddings.
 */
class EmbeddingMatchingServiceTest {
    
    private EmbeddingMatchingService service;
    private EmbeddingClient embeddingClient;
    private EmbeddingRepository embeddingRepository;
    private MappingConfig config;
    
    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        embeddingRepository = mock(EmbeddingRepository.class);
        config = mock(MappingConfig.class);
        
        when(config.getHybridAcceptanceThreshold()).thenReturn(0.50);
        
        service = new EmbeddingMatchingService(embeddingClient, embeddingRepository, config);
    }
    
    @Test
    void testBlendingWithZeroPriorScore() {
        // Mock embeddings
        double[] fieldEmbedding = {0.5, 0.5, 0.5};
        double[] canonicalEmbedding = {0.5, 0.5, 0.5}; // Cosine similarity = 1.0
        
        when(embeddingClient.getEmbedding(anyString())).thenReturn(fieldEmbedding);
        when(embeddingRepository.findAllCanonicalEmbeddings()).thenReturn(List.of(
            new CanonicalEmbedding("src_ip", canonicalEmbedding)
        ));
        
        MappingCandidate result = service.matchWithFallback("test", 0.0);
        
        // With bestPriorScore = 0.0, should use semantic score alone
        // Raw similarity = 1.0, scaled = (1.0 - 0.12) / 0.30 = 2.93, clamped to 1.0
        assertEquals("src_ip", result.getCanonicalField());
        assertEquals(1.0, result.getScore(), 0.01);
        assertEquals("L4_HYBRID", result.getProducedByLayer());
    }
    
    @Test
    void testBlendingWithPriorScore() {
        // Mock embeddings with moderate similarity
        double[] fieldEmbedding = {1.0, 0.0, 0.0};
        double[] canonicalEmbedding = {0.5, 0.5, 0.0}; // Cosine similarity ~0.71
        
        when(embeddingClient.getEmbedding(anyString())).thenReturn(fieldEmbedding);
        when(embeddingRepository.findAllCanonicalEmbeddings()).thenReturn(List.of(
            new CanonicalEmbedding("dest_ip", canonicalEmbedding)
        ));
        
        MappingCandidate result = service.matchWithFallback("test", 0.60);
        
        // Blend: 0.3 * 0.60 + 0.7 * scaledSimilarity
        // Raw similarity ~0.71, scaled = (0.71 - 0.12) / 0.30 = 1.97, clamped to 1.0
        // Combined = 0.3 * 0.60 + 0.7 * 1.0 = 0.18 + 0.70 = 0.88
        assertTrue(result.getScore() >= 0.85 && result.getScore() <= 0.90);
    }
    
    @Test
    void testScalingClampLowEnd() {
        // Mock embeddings with low similarity (below 0.12 threshold)
        double[] fieldEmbedding = {1.0, 0.0, 0.0};
        double[] canonicalEmbedding = {0.0, 1.0, 0.0}; // Cosine similarity = 0.0
        
        when(embeddingClient.getEmbedding(anyString())).thenReturn(fieldEmbedding);
        when(embeddingRepository.findAllCanonicalEmbeddings()).thenReturn(List.of(
            new CanonicalEmbedding("url", canonicalEmbedding)
        ));
        
        MappingCandidate result = service.matchWithFallback("test", 0.0);
        
        // Raw similarity = 0.0, scaled = (0.0 - 0.12) / 0.30 = -0.4, clamped to 0.0
        assertEquals(0.0, result.getScore(), 0.01);
    }
    
    @Test
    void testScalingClampHighEnd() {
        // Already tested in testBlendingWithZeroPriorScore
        // When raw similarity >= 0.42, scaled similarity gets clamped to 1.0
    }
    
    @Test
    void testBestMatchSelection() {
        // Mock embeddings with multiple candidates
        double[] fieldEmbedding = {1.0, 0.0, 0.0};
        double[] embedding1 = {0.9, 0.1, 0.0}; // High similarity
        double[] embedding2 = {0.0, 1.0, 0.0}; // Low similarity
        double[] embedding3 = {0.8, 0.2, 0.0}; // Medium similarity
        
        when(embeddingClient.getEmbedding(anyString())).thenReturn(fieldEmbedding);
        when(embeddingRepository.findAllCanonicalEmbeddings()).thenReturn(List.of(
            new CanonicalEmbedding("src_ip", embedding1),
            new CanonicalEmbedding("dest_ip", embedding2),
            new CanonicalEmbedding("url", embedding3)
        ));
        
        MappingCandidate result = service.matchWithFallback("test", 0.0);
        
        // Should select src_ip (highest similarity)
        assertEquals("src_ip", result.getCanonicalField());
    }
    
    @Test
    void testProducedByLayerLabel() {
        double[] fieldEmbedding = {0.5, 0.5, 0.5};
        double[] canonicalEmbedding = {0.5, 0.5, 0.5};
        
        when(embeddingClient.getEmbedding(anyString())).thenReturn(fieldEmbedding);
        when(embeddingRepository.findAllCanonicalEmbeddings()).thenReturn(List.of(
            new CanonicalEmbedding("src_ip", canonicalEmbedding)
        ));
        
        MappingCandidate result = service.matchWithFallback("test", 0.0);
        
        assertEquals("L4_HYBRID", result.getProducedByLayer());
    }
}
