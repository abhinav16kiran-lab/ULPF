package com.ulpf.mapping.service;

import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.model.NormalizedField;
import com.ulpf.mapping.repository.AliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TypoMatchingService.
 * Tests edit distance thresholds for short and long words.
 */
class TypoMatchingServiceTest {
    
    private TypoMatchingService service;
    private AliasRepository repository;
    private Map<String, String> testAliasMap;
    
    @BeforeEach
    void setUp() {
        repository = mock(AliasRepository.class);
        testAliasMap = new HashMap<>();
        
        // Short words (4 chars or less)
        testAliasMap.put("sip", "src_ip");
        testAliasMap.put("dip", "dest_ip");
        
        // Longer words
        testAliasMap.put("clientaddr", "src_ip");
        testAliasMap.put("clientadd", "src_ip");  // 1 char different
        
        when(repository.getAliasMap()).thenReturn(testAliasMap);
        
        service = new TypoMatchingService(repository);
    }
    
    @Test
    void testShortWordOneEditAccepted() {
        // "si" vs "sip" - 1 edit, maxLen=3, should accept (1 <= 1)
        NormalizedField field = new NormalizedField("si", "si", List.of("si"));
        List<MappingCandidate> results = service.match(field);
        
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(c -> c.getCanonicalField().equals("src_ip")));
    }
    
    @Test
    void testShortWordTwoEditsRejected() {
        // "s" vs "sip" - 2 edits, maxLen=3, should reject (2 > 1)
        NormalizedField field = new NormalizedField("s", "s", List.of("s"));
        List<MappingCandidate> results = service.match(field);
        
        // Should not match any short word with 2+ edits
        assertTrue(results.isEmpty() || 
                   results.stream().noneMatch(c -> c.getCanonicalField().equals("src_ip")));
    }
    
    @Test
    void testLongWordWithin30Percent() {
        // "clientadr" vs "clientaddr" - 1 edit, maxLen=11, 1/11 = 0.09 <= 0.30, should accept
        NormalizedField field = new NormalizedField("clientadr", "clientadr", List.of("clientadr"));
        List<MappingCandidate> results = service.match(field);
        
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(c -> c.getCanonicalField().equals("src_ip")));
    }
    
    @Test
    void testLongWordExceeds30Percent() {
        // "client" vs "clientaddr" - 4 edits, maxLen=10, 4/10 = 0.40 > 0.30, should reject
        NormalizedField field = new NormalizedField("client", "client", List.of("client"));
        List<MappingCandidate> results = service.match(field);
        
        // Should not match if distance exceeds 30% of max length
        assertTrue(results.isEmpty() || 
                   results.stream().noneMatch(c -> c.getCanonicalField().equals("src_ip")));
    }
    
    @Test
    void testBoundaryCase4CharsOneEdit() {
        // At maxLen=4 boundary, 1 edit should be accepted
        testAliasMap.put("test", "test_field");
        
        NormalizedField field = new NormalizedField("tes", "tes", List.of("tes"));
        List<MappingCandidate> results = service.match(field);
        
        assertTrue(results.stream().anyMatch(c -> c.getCanonicalField().equals("test_field")));
    }
    
    @Test
    void testResultsSortedByScore() {
        // Add multiple matches with different distances
        testAliasMap.put("sourceip", "src_ip");
        testAliasMap.put("sourceipp", "src_ip");
        
        NormalizedField field = new NormalizedField("sourceip", "sourceip", List.of("sourceip"));
        List<MappingCandidate> results = service.match(field);
        
        // First result should have highest score (exact match = distance 0)
        assertFalse(results.isEmpty());
        assertEquals(1.0, results.get(0).getScore(), 0.01);
    }
    
    @Test
    void testProducedByLayerLabel() {
        testAliasMap.put("srcip", "src_ip");
        
        NormalizedField field = new NormalizedField("srcip", "srcip", List.of("srcip"));
        List<MappingCandidate> results = service.match(field);
        
        assertFalse(results.isEmpty());
        assertEquals("TYPO_MATCH", results.get(0).getProducedByLayer());
    }
}
