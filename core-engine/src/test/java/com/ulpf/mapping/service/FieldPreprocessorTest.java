package com.ulpf.mapping.service;

import com.ulpf.mapping.model.NormalizedField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FieldPreprocessor.
 * Tests acronym boundaries, camelCase, and separator normalization.
 */
class FieldPreprocessorTest {
    
    private FieldPreprocessor preprocessor;
    
    @BeforeEach
    void setUp() {
        preprocessor = new FieldPreprocessor();
    }
    
    @Test
    void testAcronymBoundaries() {
        NormalizedField result = preprocessor.process("HTTPMethod");
        assertEquals("http method", result.getCleanedText());
    }
    
    @Test
    void testCamelCase() {
        NormalizedField result = preprocessor.process("SrcAddress");
        assertEquals("src address", result.getCleanedText());
    }
    
    @Test
    void testUnderscores() {
        NormalizedField result = preprocessor.process("source_ip_address");
        assertEquals("source ip address", result.getCleanedText());
    }
    
    @Test
    void testHyphens() {
        NormalizedField result = preprocessor.process("client-addr");
        assertEquals("client addr", result.getCleanedText());
    }
    
    @Test
    void testDots() {
        NormalizedField result = preprocessor.process("src.ip.addr");
        assertEquals("src ip addr", result.getCleanedText());
    }
    
    @Test
    void testAlreadyLowercase() {
        NormalizedField result = preprocessor.process("sourceip");
        assertEquals("sourceip", result.getCleanedText());
    }
    
    @Test
    void testMixedSeparators() {
        NormalizedField result = preprocessor.process("Client_Addr-IP.Value");
        assertEquals("client addr ip value", result.getCleanedText());
    }
    
    @Test
    void testRawTextPreserved() {
        String raw = "HTTPMethod";
        NormalizedField result = preprocessor.process(raw);
        assertEquals(raw, result.getRawText());
    }
    
    @Test
    void testTokensCreated() {
        NormalizedField result = preprocessor.process("SrcAddress");
        assertEquals(2, result.getTokens().size());
        assertEquals("src", result.getTokens().get(0));
        assertEquals("address", result.getTokens().get(1));
    }
}
