package com.ulpf.dataplane.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventIngestionServiceDriftTest {

    private CredentialRepository credentialRepository;
    private MappingRepository mappingRepository;
    private ClickHouseIngestionRepository clickHouseIngestionRepository;
    private SensorTelemetryEvaluator sensorTelemetryEvaluator;
    private SchemaDriftNotificationService driftNotificationService;
    private EventIngestionService service;

    private final String validApiKey = "ulpf_test_api_key_drift";
    private final String sourceId = "src_drift_test";
    private final String vendorId = "ven_drift_test";

    @BeforeEach
    void setUp() {
        credentialRepository = mock(CredentialRepository.class);
        mappingRepository = mock(MappingRepository.class);
        clickHouseIngestionRepository = mock(ClickHouseIngestionRepository.class);
        sensorTelemetryEvaluator = mock(SensorTelemetryEvaluator.class);
        driftNotificationService = mock(SchemaDriftNotificationService.class);

        service = new EventIngestionService(
                credentialRepository,
                mappingRepository,
                clickHouseIngestionRepository,
                sensorTelemetryEvaluator,
                driftNotificationService
        );

        String keyHash = com.ulpf.controlplane.service.OnboardingService.hashSha256(validApiKey);
        CredentialRecord cred = new CredentialRecord("c_drift", sourceId, vendorId, keyHash, "ACTIVE", LocalDateTime.now());
        when(credentialRepository.findActiveByKeyHash(keyHash)).thenReturn(Optional.of(cred));
    }

    private void stubActiveMapping(String mappingsJson) {
        String fullMappingJson = """
            {
                "target_table": "events_drift_tbl",
                "mappings": %s
            }
        """.formatted(mappingsJson);

        MappingVersionRecord record = new MappingVersionRecord(
                "map_1", sourceId, 1, fullMappingJson, "ACTIVE", LocalDateTime.now()
        );
        when(mappingRepository.findActiveBySourceId(sourceId)).thenReturn(Optional.of(record));
    }

    @Test
    void testTenKeysTwoMapped_CallsCheckAndNotifyDriftWithEightUnmappedKeysAndNotTotalDrift() {
        // Mapped: k1, k2
        stubActiveMapping("""
            {
                "k1": "canonical_k1",
                "k2": "canonical_k2"
            }
        """);

        Map<String, Object> payload = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            payload.put("k" + i, "val" + i);
        }

        service.ingest(validApiKey, payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> unmappedKeysCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Integer> totalKeysCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> isTotalDriftCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(driftNotificationService, times(1)).checkAndNotifyDrift(
                eq(sourceId),
                unmappedKeysCaptor.capture(),
                totalKeysCaptor.capture(),
                isTotalDriftCaptor.capture()
        );

        Set<String> unmapped = unmappedKeysCaptor.getValue();
        assertEquals(8, unmapped.size());
        assertFalse(unmapped.contains("k1"));
        assertFalse(unmapped.contains("k2"));
        for (int i = 3; i <= 10; i++) {
            assertTrue(unmapped.contains("k" + i));
        }

        assertEquals(10, totalKeysCaptor.getValue());
        assertFalse(isTotalDriftCaptor.getValue());
    }

    @Test
    void testAllKeysUnmapped_CallsCheckAndNotifyDriftWithTotalDriftTrue() {
        // Mapping exists but does not map any payload keys
        stubActiveMapping("""
            {
                "unrelated_a": "canonical_a",
                "unrelated_b": "canonical_b"
            }
        """);

        Map<String, Object> payload = Map.of(
                "key_alpha", "1",
                "key_beta", "2",
                "key_gamma", "3"
        );

        service.ingest(validApiKey, payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> unmappedKeysCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Integer> totalKeysCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> isTotalDriftCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(driftNotificationService, times(1)).checkAndNotifyDrift(
                eq(sourceId),
                unmappedKeysCaptor.capture(),
                totalKeysCaptor.capture(),
                isTotalDriftCaptor.capture()
        );

        assertEquals(Set.of("key_alpha", "key_beta", "key_gamma"), unmappedKeysCaptor.getValue());
        assertEquals(3, totalKeysCaptor.getValue());
        assertTrue(isTotalDriftCaptor.getValue());
    }

    @Test
    void testAllKeysMapped_DriftServiceNeverCalled() {
        stubActiveMapping("""
            {
                "host": "canonical_host",
                "ip": "canonical_ip",
                "status": "canonical_status"
            }
        """);

        Map<String, Object> payload = Map.of(
                "host", "web-01",
                "ip", "192.168.1.1",
                "status", "OK"
        );

        service.ingest(validApiKey, payload);

        verify(driftNotificationService, never()).checkAndNotifyDrift(anyString(), any(), anyInt(), anyBoolean());
    }

    @Test
    void testExactlyThirtyPercentUnmapped_CallsCheckAndNotifyDrift() {
        // 10 keys: 7 mapped (k1..k7), 3 unmapped (k8..k10) = 30% drift
        stubActiveMapping("""
            {
                "k1": "c1", "k2": "c2", "k3": "c3",
                "k4": "c4", "k5": "c5", "k6": "c6", "k7": "c7"
            }
        """);

        Map<String, Object> payload = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            payload.put("k" + i, "val" + i);
        }

        service.ingest(validApiKey, payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> unmappedKeysCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Integer> totalKeysCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> isTotalDriftCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(driftNotificationService, times(1)).checkAndNotifyDrift(
                eq(sourceId),
                unmappedKeysCaptor.capture(),
                totalKeysCaptor.capture(),
                isTotalDriftCaptor.capture()
        );

        assertEquals(Set.of("k8", "k9", "k10"), unmappedKeysCaptor.getValue());
        assertEquals(10, totalKeysCaptor.getValue());
        assertFalse(isTotalDriftCaptor.getValue());
    }

    @Test
    void testNestedPayload_TopLevelUnmappedResultsInTotalDrift() {
        // Mapping might define a deep key like "network.src_ip", but the top-level payload object has only "event"
        stubActiveMapping("""
            {
                "network.src_ip": "src_ip"
            }
        """);

        Map<String, Object> payload = Map.of(
                "event", Map.of(
                        "network", Map.of("src_ip", "10.0.0.1")
                )
        );

        service.ingest(validApiKey, payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> unmappedKeysCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Integer> totalKeysCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> isTotalDriftCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(driftNotificationService, times(1)).checkAndNotifyDrift(
                eq(sourceId),
                unmappedKeysCaptor.capture(),
                totalKeysCaptor.capture(),
                isTotalDriftCaptor.capture()
        );

        assertEquals(Set.of("event"), unmappedKeysCaptor.getValue());
        assertEquals(1, totalKeysCaptor.getValue());
        assertTrue(isTotalDriftCaptor.getValue());
    }

    @Test
    void testNoActiveMapping_DriftServiceNeverCalled() {
        // Source has NO active mapping in mappingRepository
        when(mappingRepository.findActiveBySourceId(sourceId)).thenReturn(Optional.empty());

        Map<String, Object> payload = Map.of(
                "unmapped_col1", "val1",
                "unmapped_col2", "val2"
        );

        service.ingest(validApiKey, payload);

        // Drift service must NOT be called when no mapping exists to prevent duplicate alerts
        verify(driftNotificationService, never()).checkAndNotifyDrift(anyString(), any(), anyInt(), anyBoolean());
    }
}
