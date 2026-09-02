package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.MappingCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConfidenceEvaluator.
 * Tests confidence checks and meaningful token validation.
 */
class ConfidenceEvaluatorTest {
    
    private ConfidenceEvaluator evaluator;
    private MappingConfig config;
    
    @BeforeEach
    void setUp() {
        config = mock(MappingConfig.class);
        when(config.getConfidenceThreshold()).thenReturn(0.60);
        when(config.getGapThreshold()).thenReturn(0.20);
        
        evaluator = new ConfidenceEvaluator(config);
    }
    
    @Test
    void testEmptyCandidateList() {
        assertFalse(evaluator.isConfident(new ArrayList<>()));
    }
    
    @Test
    void testNullCandidateList() {
        assertFalse(evaluator.isConfident(null));
    }
    
    @Test
    void testTopScorePassesButNoGap() {
        // Top score is 0.70 (passes threshold) but gap is only 0.05 (fails gap)
        List<MappingCandidate> candidates = List.of(
            new MappingCandidate("src_ip", 0.70, "TFIDF"),
            new MappingCandidate("dest_ip", 0.65, "TFIDF")
        );
        
        assertFalse(evaluator.isConfident(candidates));
    }
    
    @Test
    void testGapPassesButLowTopScore() {
        // Gap is 0.40 (passes) but top score is 0.50 (fails threshold)
        List<MappingCandidate> candidates = List.of(
            new MappingCandidate("src_ip", 0.50, "TFIDF"),
            new MappingCandidate("dest_ip", 0.10, "TFIDF")
        );
        
        assertFalse(evaluator.isConfident(candidates));
    }
    
    @Test
    void testBothPass() {
        // Top score is 0.80 (passes) and gap is 0.30 (passes)
        List<MappingCandidate> candidates = List.of(
            new MappingCandidate("src_ip", 0.80, "TFIDF"),
            new MappingCandidate("dest_ip", 0.50, "TFIDF")
        );
        
        assertTrue(evaluator.isConfident(candidates));
    }
    
    @Test
    void testSingleCandidateWithHighScore() {
        // Only one candidate, so gap is automatically large (against 0.0)
        List<MappingCandidate> candidates = List.of(
            new MappingCandidate("src_ip", 0.80, "TFIDF")
        );
        
        assertTrue(evaluator.isConfident(candidates));
    }
    
    @Test
    void testMeaningfulTokenValid() {
        assertTrue(evaluator.isMeaningfulToken("SrcIP"));
        assertTrue(evaluator.isMeaningfulToken("ab"));
        assertTrue(evaluator.isMeaningfulToken("a1"));
    }
    
    @Test
    void testMeaningfulTokenInvalid() {
        assertFalse(evaluator.isMeaningfulToken(""));
        assertFalse(evaluator.isMeaningfulToken("a"));
        assertFalse(evaluator.isMeaningfulToken("_"));
        assertFalse(evaluator.isMeaningfulToken("__"));
        assertFalse(evaluator.isMeaningfulToken(null));
    }
    
    @Test
    void testMeaningfulTokenSymbolsOnly() {
        assertFalse(evaluator.isMeaningfulToken("___"));
        assertFalse(evaluator.isMeaningfulToken("..."));
        assertFalse(evaluator.isMeaningfulToken("---"));
    }
}
