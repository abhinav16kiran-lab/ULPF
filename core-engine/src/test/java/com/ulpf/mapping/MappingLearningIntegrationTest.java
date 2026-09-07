package com.ulpf.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.mapping.repository.AliasRepository;
import com.ulpf.mapping.service.AliasLookupService;
import com.ulpf.mapping.service.MappingLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the complete mapping learning flow:
 * 1. Admin corrects an AI-proposed mapping
 * 2. Correction is learned and saved to mapping_aliases
 * 3. Next lookup for same field returns learned alias
 */
@SpringBootTest
@ActiveProfiles("test")
class MappingLearningIntegrationTest {
    
    @Autowired
    private MappingLearningService mappingLearningService;
    
    @Autowired
    private AliasLookupService aliasLookupService;
    
    @Autowired
    private AliasRepository aliasRepository;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        // Clean up test aliases
        jdbcTemplate.update("DELETE FROM mapping_aliases WHERE source = 'human_correction'");
        aliasRepository.reloadAliases();
    }
    
    @Test
    void testCompletelearningFlow() {
        // Step 1: AI proposes wrong mapping
        String oldMappingJson = """
            {
                "SourceAddress": {
                    "canonicalField": "url",
                    "confidence": 0.55,
                    "source": "TFIDF"
                }
            }
            """;
        
        // Step 2: Admin corrects it
        String newMappingJson = """
            {
                "SourceAddress": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                }
            }
            """;
        
        // Step 3: Learn from the correction
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldMappingJson, newMappingJson);
        assertEquals(1, aliasesLearned);
        
        // Step 4: Verify alias was saved to database
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE source = 'human_correction' AND canonical_field = 'src_ip'",
            Integer.class
        );
        assertEquals(1, count);
        
        // Step 5: Verify next lookup finds the learned alias
        var normalizedField = new com.ulpf.mapping.model.NormalizedField(
            "SourceAddress", 
            "source address", 
            java.util.List.of("source", "address")
        );
        
        Optional<String> result = aliasLookupService.lookup(normalizedField);
        assertTrue(result.isPresent());
        assertEquals("src_ip", result.get());
    }
    
    @Test
    void testMultipleCorrectionsLearned() {
        // Given - multiple corrections
        String oldMappingJson = """
            {
                "SrcAddr": {
                    "canonicalField": "url",
                    "confidence": 0.50,
                    "source": "TFIDF"
                },
                "DestAddr": {
                    "canonicalField": "timestamp",
                    "confidence": 0.45,
                    "source": "L4_HYBRID"
                },
                "RequestURL": {
                    "canonicalField": "url",
                    "confidence": 0.80,
                    "source": "TFIDF"
                }
            }
            """;
        
        String newMappingJson = """
            {
                "SrcAddr": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                },
                "DestAddr": {
                    "canonicalField": "dest_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                },
                "RequestURL": {
                    "canonicalField": "url",
                    "confidence": 0.80,
                    "source": "TFIDF"
                }
            }
            """;
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldMappingJson, newMappingJson);
        
        // Then - 2 corrections (RequestURL unchanged)
        assertEquals(2, aliasesLearned);
        
        // Verify both in database
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE source = 'human_correction'",
            Integer.class
        );
        assertTrue(count >= 2);
    }
    
    @Test
    void testInMemoryCacheUpdatedImmediately() {
        // Given - field not in cache
        var normalizedField = new com.ulpf.mapping.model.NormalizedField(
            "ClientAddr", 
            "client addr", 
            java.util.List.of("client", "addr")
        );
        
        Optional<String> beforeLearning = aliasLookupService.lookup(normalizedField);
        assertFalse(beforeLearning.isPresent()); // Not found yet
        
        // When - learn correction
        String oldJson = """
            {
                "ClientAddr": {
                    "canonicalField": null,
                    "confidence": 0.0,
                    "source": "NONE"
                }
            }
            """;
        
        String newJson = """
            {
                "ClientAddr": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                }
            }
            """;
        
        mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then - immediately available in cache
        Optional<String> afterLearning = aliasLookupService.lookup(normalizedField);
        assertTrue(afterLearning.isPresent());
        assertEquals("src_ip", afterLearning.get());
    }
    
    @Test
    void testNoCorrectionsNoLearning() {
        // Given - AI mapping fully accepted
        String oldJson = """
            {
                "src_ip": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "ALIAS_LOOKUP"
                }
            }
            """;
        
        String newJson = """
            {
                "src_ip": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "ALIAS_LOOKUP"
                }
            }
            """;
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then
        assertEquals(0, aliasesLearned);
        
        // Verify nothing added to database
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE source = 'human_correction'",
            Integer.class
        );
        assertEquals(0, count);
    }
    
    @Test
    void testMetadataIgnoredDuringLearning() {
        // Given - mapping with metadata
        String oldJson = """
            {
                "SrcAddr": {
                    "canonicalField": "url",
                    "confidence": 0.50,
                    "source": "TFIDF"
                },
                "metadata": {
                    "log_type": "REG_LOG",
                    "delta": null,
                    "max_interval_ms": 60000
                }
            }
            """;
        
        String newJson = """
            {
                "SrcAddr": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                },
                "metadata": {
                    "log_type": "REG_LOG",
                    "delta": 0.5,
                    "max_interval_ms": 60000
                }
            }
            """;
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then - only field correction learned, not metadata
        assertEquals(1, aliasesLearned);
        
        // Verify no "metadata" alias in database
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE alias_key LIKE '%metadata%'",
            Integer.class
        );
        assertEquals(0, count);
    }
}
