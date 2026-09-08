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

    @Test
    void testHistoricalVersionMatchingFallbackResolvesLegacyKeys() {
        String apiKey = "ulpf_live_testkey789";
        CredentialRecord cred = new CredentialRecord("cred_3", "src_003", "ven_001", "hash_789", "ACTIVE", LocalDateTime.now());
        when(credentialRepository.findActiveByKeyHash(anyString())).thenReturn(Optional.of(cred));

        // Active Version 2 mapping JSON
        String activeV2MappingJson = """
            {
              "vendor_src_ip_v2": { "canonicalField": "source_ip", "confidence": 0.98 }
            }
            """;
        MappingVersionRecord activeV2 = new MappingVersionRecord(
                "map_v2", "src_003", 2, activeV2MappingJson, "ACTIVE", LocalDateTime.now()
        );
        when(mappingRepository.findActiveBySourceId("src_003")).thenReturn(Optional.of(activeV2));

        // Retired Version 1 mapping JSON
        String retiredV1MappingJson = """
            {
              "legacy_client_ip": { "canonicalField": "source_ip", "confidence": 0.90 }
            }
            """;
        MappingVersionRecord retiredV1 = new MappingVersionRecord(
                "map_v1", "src_003", 1, retiredV1MappingJson, "RETIRED", LocalDateTime.now().minusDays(10)
        );

        when(mappingRepository.findAllValidVersionsBySourceId("src_003"))
                .thenReturn(java.util.List.of(activeV2, retiredV1));

        // Payload with active key, legacy v1 key, and a brand new unknown key
        Map<String, Object> payload = Map.of(
                "vendor_src_ip_v2", "10.0.0.1",
                "legacy_client_ip", "192.168.1.50",
                "brand_new_unmapped_field", "PROPRIETARY_DATA"
        );

        var result = ingestionService.ingest(apiKey, payload);

        assertNotNull(result);
        assertEquals("ACCEPTED", result.status());

        ArgumentCaptor<CanonicalEventRecord> canonicalCaptor = ArgumentCaptor.forClass(CanonicalEventRecord.class);
        verify(clickHouseIngestionRepository, times(1)).enqueueCanonical(canonicalCaptor.capture());

        CanonicalEventRecord capturedCanonical = canonicalCaptor.getValue();
        assertNotNull(capturedCanonical.rawUnmapped());

        String rawUnmapped = capturedCanonical.rawUnmapped();
        // Active key -> mapped
        assertFalse(rawUnmapped.contains("vendor_src_ip_v2"), "Active v2 key should NOT be in raw_unmapped");
        // Legacy v1 key -> resolved via historical fallback matching
        assertFalse(rawUnmapped.contains("legacy_client_ip"), "Legacy v1 key should NOT be in raw_unmapped due to historical fallback");
        // Completely unknown key -> lands in raw_unmapped
        assertTrue(rawUnmapped.contains("brand_new_unmapped_field"), "Unknown key should land in raw_unmapped");
    }

    @Test
    void testIngestTriggersHistoricalFallbackNotification() {
        SchemaDriftNotificationService mockNotificationService = mock(SchemaDriftNotificationService.class);
        EventIngestionService serviceWithNotification = new EventIngestionService(
                credentialRepository,
                mappingRepository,
                clickHouseIngestionRepository,
                sensorTelemetryEvaluator,
                mockNotificationService
        );

        String apiKey = "ulpf_live_testkey_fallback";
        CredentialRecord cred = new CredentialRecord("cred_fb", "src_fb", "ven_fb", "hash_fb", "ACTIVE", LocalDateTime.now());
        when(credentialRepository.findActiveByKeyHash(anyString())).thenReturn(Optional.of(cred));

        String activeV2Json = """
            {
              "active_key": { "canonicalField": "user_id", "confidence": 0.99 }
            }
            """;
        MappingVersionRecord activeV2 = new MappingVersionRecord("map_v2", "src_fb", 2, activeV2Json, "ACTIVE", LocalDateTime.now());
        when(mappingRepository.findActiveBySourceId("src_fb")).thenReturn(Optional.of(activeV2));

        String retiredV1Json = """
            {
              "old_user_id": { "canonicalField": "user_id", "confidence": 0.90 }
            }
            """;
        MappingVersionRecord retiredV1 = new MappingVersionRecord("map_v1", "src_fb", 1, retiredV1Json, "RETIRED", LocalDateTime.now().minusDays(5));
        when(mappingRepository.findAllValidVersionsBySourceId("src_fb")).thenReturn(java.util.List.of(activeV2, retiredV1));

        Map<String, Object> payload = Map.of(
                "active_key", "usr_123",
                "old_user_id", "usr_legacy_123"
        );

        serviceWithNotification.ingest(apiKey, payload);

        verify(mockNotificationService, times(1)).notifyHistoricalFallback(
                eq("src_fb"),
                eq(1),
                eq(java.util.Set.of("old_user_id"))
        );
    }
}
