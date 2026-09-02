package com.ulpf.mapping.service;

import com.ulpf.mapping.model.NormalizedField;
import com.ulpf.mapping.repository.AliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AliasLookupService.
 * Tests exact-match behavior with no fuzzy matching.
 */
class AliasLookupServiceTest {
    
    private AliasLookupService service;
    private AliasRepository repository;
    private Map<String, String> testAliasMap;
    
    @BeforeEach
    void setUp() {
        repository = mock(AliasRepository.class);
        testAliasMap = new HashMap<>();
        testAliasMap.put("sourceip", "src_ip");
        testAliasMap.put("srcaddress", "src_ip");
        testAliasMap.put("destip", "dest_ip");
        
        when(repository.getAliasMap()).thenReturn(testAliasMap);
        
        service = new AliasLookupService(repository);
    }
    
    @Test
    void testExactMatchHit() {
        NormalizedField field = new NormalizedField("sourceip", "source ip", List.of("source", "ip"));
        Optional<String> result = service.lookup(field);
        
        assertTrue(result.isPresent());
        assertEquals("src_ip", result.get());
    }
    
    @Test
    void testExactMatchMiss() {
        NormalizedField field = new NormalizedField("unknown", "unknown field", List.of("unknown", "field"));
        Optional<String> result = service.lookup(field);
        
        assertFalse(result.isPresent());
    }
    
    @Test
    void testSpacesRemovedFromKey() {
        // Cleaned text has spaces, but lookup key should have them removed
        NormalizedField field = new NormalizedField("SrcAddress", "src address", List.of("src", "address"));
        Optional<String> result = service.lookup(field);
        
        assertTrue(result.isPresent());
        assertEquals("src_ip", result.get());
    }
    
    @Test
    void testNoFuzzyMatching() {
        // "srcip" is not in the map, only "sourceip" is
        NormalizedField field = new NormalizedField("srcip", "src ip", List.of("src", "ip"));
        Optional<String> result = service.lookup(field);
        
        assertFalse(result.isPresent());
    }
}
