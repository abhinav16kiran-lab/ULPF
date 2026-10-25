package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MappingTransformerTest {

    private MappingTransformer transformer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        transformer = new MappingTransformer(objectMapper);
    }

    @Test
    void testResolveCanonicalFieldVariations() throws Exception {
        String json = """
            {
              "f1": { "canonicalField": "source_ip" },
              "f2": { "canonical_field": "dest_port" },
              "f3": "protocol",
              "f4": "unmapped",
              "f5": { "canonicalField": "unmapped" }
            }
            """;
        JsonNode root = objectMapper.readTree(json);

        assertEquals("source_ip", transformer.resolveCanonicalField("f1", root));
        assertEquals("dest_port", transformer.resolveCanonicalField("f2", root));
        assertEquals("protocol", transformer.resolveCanonicalField("f3", root));
        assertNull(transformer.resolveCanonicalField("f4", root));
        assertNull(transformer.resolveCanonicalField("f5", root));
        assertNull(transformer.resolveCanonicalField("non_existent", root));
    }

    @Test
    void testNormalizeColumnName() {
        assertEquals("source_ip", transformer.normalizeColumnName("source.ip"));
        assertEquals("network_dest_port", transformer.normalizeColumnName("network-dest-port"));
        assertEquals("user_name", transformer.normalizeColumnName(" user.name "));
        assertEquals("", transformer.normalizeColumnName(null));
    }

    @Test
    void testExtractNumericValue() throws Exception {
        JsonNode node1 = objectMapper.readTree("{\"cpu_usage\": 85.5, \"message\": \"ok\"}");
        assertEquals(85.5, transformer.extractNumericValue(node1, "cpu_usage"));

        JsonNode node2 = objectMapper.readTree("{\"cpu_usage\": \"92.3\"}");
        assertEquals(92.3, transformer.extractNumericValue(node2, "cpu_usage"));

        JsonNode node3 = objectMapper.readTree("{\"temp\": 38.0}");
        assertEquals(38.0, transformer.extractNumericValue(node3, null));
    }

    @Test
    void testTransformSeparatesMappedAndUnmapped() throws Exception {
        String mappingJson = """
            {
              "src_host": { "canonicalField": "host_name" },
              "remote_ip": { "canonicalField": "network.client_ip" }
            }
            """;
        JsonNode mappingRoot = objectMapper.readTree(mappingJson);

        String rawJson = "{\"src_host\": \"srv-01\", \"remote_ip\": \"10.0.0.1\", \"custom_tag\": \"beta\", \"counter\": 123}";
        MappingTransformer.TransformedResult result = transformer.transform(rawJson, mappingRoot, null, null);

        assertNotNull(result);
        assertEquals("srv-01", result.mappedFields().get("host_name"));
        assertEquals("10.0.0.1", result.mappedFields().get("network_client_ip"));

        assertTrue(result.rawUnmappedJson().contains("custom_tag"));
        assertTrue(result.rawUnmappedJson().contains("counter"));
        assertFalse(result.rawUnmappedJson().contains("src_host"));
    }

    @Test
    void testTransformWithHistoricalFallback() throws Exception {
        String activeMappingJson = """
            {
              "current_field": { "canonicalField": "target_col" }
            }
            """;
        String historicalMappingJson = """
            {
              "old_vendor_field": { "canonicalField": "target_col" }
            }
            """;

        JsonNode activeRoot = objectMapper.readTree(activeMappingJson);
        JsonNode historicalRoot = objectMapper.readTree(historicalMappingJson);

        String rawJson = "{\"old_vendor_field\": \"legacy_value\", \"extra\": \"val\"}";
        MappingTransformer.TransformedResult result = transformer.transform(rawJson, activeRoot, List.of(historicalRoot), null);

        assertNotNull(result);
        assertEquals("legacy_value", result.mappedFields().get("target_col"));
        assertTrue(result.rawUnmappedJson().contains("extra"));
    }

    @Test
    void testTransformMalformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            transformer.transform("THIS IS NOT JSON", null, null, null);
        });
    }
}
