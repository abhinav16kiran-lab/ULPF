package com.ulpf.dataplane.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EventIngestionServiceTest {

    private CredentialRepository credentialRepository;
    private MappingRepository mappingRepository;
    private ClickHouseIngestionRepository clickHouseIngestionRepository;
    private SensorTelemetryEvaluator sensorTelemetryEvaluator;
    private EventIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(CredentialRepository.class);
        mappingRepository = mock(MappingRepository.class);
        clickHouseIngestionRepository = mock(ClickHouseIngestionRepository.class);
        sensorTelemetryEvaluator = new SensorTelemetryEvaluator();

        ingestionService = new EventIngestionService(
                credentialRepository,
                mappingRepository,
                clickHouseIngestionRepository,
                sensorTelemetryEvaluator
        );
    }

    @Test
    void testIngestSeparatesMappedAndRawUnmappedFields() {
        String apiKey = "ulpf_live_testkey123";
        CredentialRecord cred = new CredentialRecord("cred_1", "src_001", "ven_001", "hash_123", "ACTIVE", LocalDateTime.now());
        when(credentialRepository.findActiveByKeyHash(anyString())).thenReturn(Optional.of(cred));

        String mappingJson = """
            {
              "vendor_src_ip": { "canonicalField": "source_ip", "confidence": 0.95 },
              "vendor_user": { "canonicalField": "user_id", "confidence": 0.90 }
            }
            """;
        MappingVersionRecord mappingVer = new MappingVersionRecord(
                "map_1", "src_001", 1, mappingJson, "ACTIVE", LocalDateTime.now()
        );
        when(mappingRepository.findActiveBySourceId("src_001")).thenReturn(Optional.of(mappingVer));

        Map<String, Object> payload = Map.of(
                "vendor_src_ip", "192.168.1.100",
                "vendor_user", "admin",
                "custom_vendor_metric", 99.4,
                "overflow_debug_tag", "DEBUG_ENABLED"
        );

        var result = ingestionService.ingest(apiKey, payload);

        assertNotNull(result);
        assertEquals("ACCEPTED", result.status());

        ArgumentCaptor<CanonicalEventRecord> canonicalCaptor = ArgumentCaptor.forClass(CanonicalEventRecord.class);
        verify(clickHouseIngestionRepository, times(1)).enqueueCanonical(canonicalCaptor.capture());

        CanonicalEventRecord capturedCanonical = canonicalCaptor.getValue();
        assertNotNull(capturedCanonical);
        assertNotNull(capturedCanonical.rawUnmapped());
        
        String rawUnmapped = capturedCanonical.rawUnmapped();
        assertTrue(rawUnmapped.contains("custom_vendor_metric"), "raw_unmapped should contain custom_vendor_metric");
        assertTrue(rawUnmapped.contains("overflow_debug_tag"), "raw_unmapped should contain overflow_debug_tag");
        assertFalse(rawUnmapped.contains("vendor_src_ip"), "raw_unmapped should NOT contain vendor_src_ip because it is mapped");
        assertFalse(rawUnmapped.contains("vendor_user"), "raw_unmapped should NOT contain vendor_user because it is mapped");
    }

    @Test
    void testIngestWithNoMappingPacksAllPayloadKeysIntoRawUnmapped() {
        String apiKey = "ulpf_live_testkey456";
        CredentialRecord cred = new CredentialRecord("cred_2", "src_002", "ven_001", "hash_456", "ACTIVE", LocalDateTime.now());
        when(credentialRepository.findActiveByKeyHash(anyString())).thenReturn(Optional.of(cred));
        when(mappingRepository.findActiveBySourceId("src_002")).thenReturn(Optional.empty());

        Map<String, Object> payload = Map.of(
                "unknown_field_1", "value_1",
                "unknown_field_2", 123
        );

        var result = ingestionService.ingest(apiKey, payload);

        assertNotNull(result);
        assertEquals("ACCEPTED", result.status());

        ArgumentCaptor<CanonicalEventRecord> canonicalCaptor = ArgumentCaptor.forClass(CanonicalEventRecord.class);
        verify(clickHouseIngestionRepository, times(1)).enqueueCanonical(canonicalCaptor.capture());

        CanonicalEventRecord capturedCanonical = canonicalCaptor.getValue();
        assertNotNull(capturedCanonical.rawUnmapped());
        
        String rawUnmapped = capturedCanonical.rawUnmapped();
        assertTrue(rawUnmapped.contains("unknown_field_1"));
        assertTrue(rawUnmapped.contains("unknown_field_2"));
    }
}
