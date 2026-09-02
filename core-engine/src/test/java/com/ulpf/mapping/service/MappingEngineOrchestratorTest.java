package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.model.MappingProposal;
import com.ulpf.mapping.model.NormalizedField;
import com.ulpf.mapping.repository.AliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for MappingEngineOrchestrator.
 * Tests each short-circuit path through the pipeline.
 */
class MappingEngineOrchestratorTest {
    
    private MappingEngineOrchestrator orchestrator;
    private FieldPreprocessor preprocessor;
    private AliasLookupService aliasLookupService;
    private TfidfMatchingService tfidfMatchingService;
    private TypoMatchingService typoMatchingService;
    private ConfidenceEvaluator confidenceEvaluator;
    private MappingConfig config;
    private AliasRepository aliasRepository;
    
    @BeforeEach
    void setUp() {
        preprocessor = new FieldPreprocessor();
        
        // Mock alias repository
        aliasRepository = mock(AliasRepository.class);
        Map<String, String> aliasMap = new HashMap<>();
        aliasMap.put("sourceip", "src_ip");
        when(aliasRepository.getAliasMap()).thenReturn(aliasMap);
        
        aliasLookupService = new AliasLookupService(aliasRepository);
        tfidfMatchingService = mock(TfidfMatchingService.class);
        typoMatchingService = mock(TypoMatchingService.class);
        
        config = mock(MappingConfig.class);
        when(config.getConfidenceThreshold()).thenReturn(0.60);
        when(config.getGapThreshold()).thenReturn(0.20);
        when(config.getHybridAcceptanceThreshold()).thenReturn(0.50);
        
        confidenceEvaluator = new ConfidenceEvaluator(config);
        
        orchestrator = new MappingEngineOrchestrator(
            preprocessor,
            aliasLookupService,
            tfidfMatchingService,
            typoMatchingService,
            confidenceEvaluator,
            config
        );
    }
    
    @Test
    void testAliasHitShortCircuit() {
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("sourceip"), 
            false
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertEquals("sourceip", proposal.getVendorFieldRaw());
        assertEquals("src_ip", proposal.getCanonicalField());
        assertEquals(1.0, proposal.getConfidence());
        assertEquals("ALIAS_LOOKUP", proposal.getSource());
        
        // TF-IDF should never be called if alias hits
        verify(tfidfMatchingService, never()).match(any());
    }
    
    @Test
    void testTfidfConfidentShortCircuit() {
        // Mock confident TF-IDF result
        when(tfidfMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.85, "TFIDF"),
            new MappingCandidate("dest_ip", 0.40, "TFIDF")
        ));
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("unknownfield"), 
            false
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertEquals("src_ip", proposal.getCanonicalField());
        assertEquals(0.85, proposal.getConfidence());
        assertEquals("TFIDF", proposal.getSource());
        
        // Typo matching should not be called
        verify(typoMatchingService, never()).match(any());
    }
    
    @Test
    void testTypoConfidentShortCircuit() {
        // TF-IDF not confident
        when(tfidfMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.50, "TFIDF"),
            new MappingCandidate("dest_ip", 0.45, "TFIDF")
        ));
        
        // Typo matching confident
        when(typoMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("dest_ip", 0.90, "TYPO_MATCH"),
            new MappingCandidate("src_ip", 0.40, "TYPO_MATCH")
        ));
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("destipp"), 
            false
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertEquals("dest_ip", proposal.getCanonicalField());
        assertEquals(0.90, proposal.getConfidence());
        assertEquals("TYPO_MATCH", proposal.getSource());
    }
    
    @Test
    void testStrictVendorShortCircuit() {
        // Neither layer confident
        when(tfidfMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.50, "TFIDF")
        ));
        when(typoMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("dest_ip", 0.45, "TYPO_MATCH")
        ));
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("unknownfield"), 
            true  // STRICT vendor
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertNull(proposal.getCanonicalField());
        assertEquals(0.0, proposal.getConfidence());
        assertEquals("NONE", proposal.getSource());
    }
    
    @Test
    void testGibberishInputShortCircuit() {
        // Neither layer confident
        when(tfidfMatchingService.match(any())).thenReturn(List.of());
        when(typoMatchingService.match(any())).thenReturn(List.of());
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("_"), 
            false  // STANDARD vendor
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertNull(proposal.getCanonicalField());
        assertEquals(0.0, proposal.getConfidence());
        assertEquals("NONE", proposal.getSource());
    }
    
    @Test
    void testLayer4NotImplementedFallback() {
        // Neither layer confident, not strict, meaningful token
        when(tfidfMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.40, "TFIDF")
        ));
        when(typoMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("dest_ip", 0.35, "TYPO_MATCH")
        ));
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("meaningful"), 
            false
        );
        
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        // Should fall back to NONE with best prior score
        assertNull(proposal.getCanonicalField());
        assertEquals(0.40, proposal.getConfidence());  // max(0.40, 0.35)
        assertEquals("NONE", proposal.getSource());
    }
    
    @Test
    void testLayerExceptionHandledGracefully() {
        // TF-IDF throws exception
        when(tfidfMatchingService.match(any())).thenThrow(new RuntimeException("Test error"));
        
        // Typo matching confident
        when(typoMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.90, "TYPO_MATCH")
        ));
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("testfield"), 
            false
        );
        
        // Should still complete with typo result
        assertEquals(1, results.size());
        MappingProposal proposal = results.get(0);
        
        assertEquals("src_ip", proposal.getCanonicalField());
        assertEquals("TYPO_MATCH", proposal.getSource());
    }
    
    @Test
    void testBatchProcessing() {
        when(tfidfMatchingService.match(any())).thenReturn(List.of(
            new MappingCandidate("src_ip", 0.80, "TFIDF")
        ));
        when(typoMatchingService.match(any())).thenReturn(List.of());
        
        List<MappingProposal> results = orchestrator.mapFields(
            List.of("sourceip", "field1", "field2"), 
            false
        );
        
        assertEquals(3, results.size());
        assertEquals("ALIAS_LOOKUP", results.get(0).getSource());
        assertEquals("TFIDF", results.get(1).getSource());
        assertEquals("TFIDF", results.get(2).getSource());
    }
}
