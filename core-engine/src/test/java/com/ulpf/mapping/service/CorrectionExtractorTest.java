package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorrectionExtractorTest {
    
    private CorrectionExtractor correctionExtractor;
    
    @BeforeEach
    void setUp() {
        correctionExtractor = new CorrectionExtractor(new ObjectMapper());
    }
    
    @Test
    void testExtractSingleCorrection() {
        // Given - AI proposed "url", human corrected to "src_ip"
        String oldJson = """
            {
                "SourceAddress": {
                    "canonicalField": "url",
                    "confidence": 0.55,
                    "source": "TFIDF"
                }
            }
            """;
        
        String newJson = """
            {
                "SourceAddress": {
                    "canonicalField": "src_ip",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                }
            }
            """;
        
        // When
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then
        assertEquals(1, corrections.size());
        CorrectionExtractor.Correction correction = corrections.get(0);
        assertEquals("SourceAddress", correction.vendorFieldRaw());
        assertEquals("url", correction.aiProposed());
        assertEquals("src_ip", correction.humanCorrected());
    }
    
    @Test
    void testExtractMultipleCorrections() {
        // Given - multiple fields corrected
        String oldJson = """
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
        
        String newJson = """
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
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then - only 2 corrections (RequestURL was not changed)
        assertEquals(2, corrections.size());
        assertTrue(corrections.stream().anyMatch(c -> c.vendorFieldRaw().equals("SrcAddr")));
        assertTrue(corrections.stream().anyMatch(c -> c.vendorFieldRaw().equals("DestAddr")));
    }
    
    @Test
    void testNoCorrections() {
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
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then
        assertEquals(0, corrections.size());
    }
    
    @Test
    void testHandleNullCanonicalFields() {
        // Given - AI couldn't map, human provides mapping
        String oldJson = """
            {
                "UnknownField": {
                    "canonicalField": null,
                    "confidence": 0.0,
                    "source": "NONE"
                }
            }
            """;
        
        String newJson = """
            {
                "UnknownField": {
                    "canonicalField": "user_id",
                    "confidence": 1.0,
                    "source": "HUMAN_CORRECTED"
                }
            }
            """;
        
        // When
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then
        assertEquals(1, corrections.size());
        CorrectionExtractor.Correction correction = corrections.get(0);
        assertNull(correction.aiProposed());
        assertEquals("user_id", correction.humanCorrected());
    }
    
    @Test
    void testIgnoreMetadataBlock() {
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
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then - only 1 correction (metadata ignored)
        assertEquals(1, corrections.size());
        assertEquals("SrcAddr", corrections.get(0).vendorFieldRaw());
    }
    
    @Test
    void testHandleMalformedJson() {
        // Given - invalid JSON
        String oldJson = "{ invalid json }";
        String newJson = "{ also invalid }";
        
        // When
        List<CorrectionExtractor.Correction> corrections = correctionExtractor.extractCorrections(oldJson, newJson);
        
        // Then - returns empty list, no crash
        assertEquals(0, corrections.size());
    }
}
