package com.ulpf.common.db;

import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClickHouseIngestionRepositoryTest {

    private ClickHouseIngestionRepository ingestionRepository;
    private JdbcTemplate clickhouseJdbcTemplate;
    private com.ulpf.dataplane.service.TableIngestionFailureNotificationService failureNotificationService;

    @BeforeEach
    void setUp() {
        clickhouseJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        failureNotificationService = Mockito.mock(com.ulpf.dataplane.service.TableIngestionFailureNotificationService.class);
        ingestionRepository = new ClickHouseIngestionRepository(clickhouseJdbcTemplate);
        ingestionRepository.setTableFailureNotificationService(failureNotificationService);
    }

    @Test
    void testEnqueueAndFlush() {
        RawEventRecord event = new RawEventRecord(
                "evt-100",
                "lin-100",
                "vendor1",
                "source1",
                1,
                LocalDateTime.now(),
                "{\"raw\":\"payload\"}"
        );

        ingestionRepository.enqueue(event);
        assertEquals(1, ingestionRepository.getQueueSize());

        ingestionRepository.flush();

        assertEquals(0, ingestionRepository.getQueueSize());
        verify(clickhouseJdbcTemplate, atLeastOnce()).batchUpdate(anyString(), any(), anyInt(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushPreservesOriginalReceivedAtTimestamps() {
        LocalDateTime time1 = LocalDateTime.of(2026, 10, 18, 10, 0, 0, 100_000_000);
        LocalDateTime time2 = LocalDateTime.of(2026, 10, 18, 10, 0, 0, 500_000_000);

        RawEventRecord event1 = new RawEventRecord("e1", "l1", "v1", "s1", 1, time1, "{\"msg\":\"one\"}");
        RawEventRecord event2 = new RawEventRecord("e2", "l2", "v2", "s2", 1, time2, "{\"msg\":\"two\"}");

        ingestionRepository.enqueue(event1);
        ingestionRepository.enqueue(event2);
        assertEquals(2, ingestionRepository.getQueueSize());

        ingestionRepository.flush();
        assertEquals(0, ingestionRepository.getQueueSize());

        org.mockito.ArgumentCaptor<List<RawEventRecord>> batchCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(clickhouseJdbcTemplate).batchUpdate(anyString(), batchCaptor.capture(), anyInt(), any());

        List<RawEventRecord> capturedBatch = batchCaptor.getValue();
        assertEquals(2, capturedBatch.size());
        assertEquals("e1", capturedBatch.get(0).eventId());
        assertEquals(time1, capturedBatch.get(0).receivedAt());
        assertEquals("e2", capturedBatch.get(1).eventId());
        assertEquals(time2, capturedBatch.get(1).receivedAt());
    }

    @Test
    void testFlushRecoversWhenBatchAlreadyCommittedOnTimeout() {
        com.ulpf.integrity.service.BatchIntegrityService integrityService =
                Mockito.mock(com.ulpf.integrity.service.BatchIntegrityService.class);
        ingestionRepository.setBatchIntegrityService(integrityService);

        RawEventRecord event = new RawEventRecord(
                "e-timeout", "lin-1", "v1", "s1", 1, LocalDateTime.now(), "{\"msg\":\"data\"}"
        );
        ingestionRepository.enqueue(event);

        // batchUpdate simulates socket timeout / connection drop after ClickHouse wrote
        Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("Read timed out"))
                .when(clickhouseJdbcTemplate).batchUpdate(anyString(), any(), anyInt(), any());

        // Probe query verifies ClickHouse already has the event
        Mockito.when(clickhouseJdbcTemplate.queryForObject(
                Mockito.contains("count()"), Mockito.eq(Integer.class), Mockito.eq("e-timeout")
        )).thenReturn(1);

        ingestionRepository.flush();

        // Queue size should be 0 because it was not re-enqueued
        assertEquals(0, ingestionRepository.getQueueSize());
        // Integrity block processing should still occur
        Mockito.verify(integrityService, Mockito.times(1)).processRawBatch(any());
    }

    @Test
    void testFlushReEnqueuesWhenGenuinelyFailed() {
        com.ulpf.integrity.service.BatchIntegrityService integrityService =
                Mockito.mock(com.ulpf.integrity.service.BatchIntegrityService.class);
        ingestionRepository.setBatchIntegrityService(integrityService);

        RawEventRecord event = new RawEventRecord(
                "e-fail", "lin-2", "v1", "s1", 1, LocalDateTime.now(), "{\"msg\":\"data\"}"
        );
        ingestionRepository.enqueue(event);

        // batchUpdate fails
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Connection refused"))
                .when(clickhouseJdbcTemplate).batchUpdate(anyString(), any(), anyInt(), any());

        // Probe query returns 0 (not in ClickHouse)
        Mockito.when(clickhouseJdbcTemplate.queryForObject(
                Mockito.contains("count()"), Mockito.eq(Integer.class), Mockito.eq("e-fail")
        )).thenReturn(0);

        ingestionRepository.flush();

        // Queue size should be 1 because it was re-enqueued for retry
        assertEquals(1, ingestionRepository.getQueueSize());
        // Integrity block should NOT be processed
        Mockito.verify(integrityService, Mockito.never()).processRawBatch(any());
    }

    @Test
    void testFlushCanonical_MissingTargetTable_TriggersNotifyNoTargetTable() {
        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        null, "evt-1", "lin-1", "ven-1", "src-missing-target", 1,
                        LocalDateTime.now(), 1.0, "{}", "{}", java.util.Collections.emptyMap()
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        verify(failureNotificationService).notifyNoTargetTable("src-missing-target", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_MissingTable_TriggersNotifyTableMissing() {
        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_missing_tbl")
        )).thenReturn(java.util.Collections.emptyList());

        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_missing_tbl", "evt-2", "lin-2", "ven-2", "src-missing-tbl", 1,
                        LocalDateTime.now(), 2.0, "{}", "{}", java.util.Collections.emptyMap()
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        verify(failureNotificationService).notifyTableMissing("src-missing-tbl", "events_missing_tbl", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_BatchInsertException_TriggersNotifyInsertFailure() {
        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_failing_tbl")
        )).thenReturn(List.of("event_id", "timestamp"));

        Mockito.doThrow(new org.springframework.dao.DataRetrievalFailureException("ClickHouse write timeout"))
                .when(clickhouseJdbcTemplate)
                .batchUpdate(Mockito.anyString(), Mockito.any(), Mockito.anyInt(), Mockito.any());

        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_failing_tbl", "evt-3", "lin-3", "ven-3", "src-failing-tbl", 1,
                        LocalDateTime.now(), 3.0, "{}", "{}", java.util.Collections.emptyMap()
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        verify(failureNotificationService).notifyInsertFailure(
                Mockito.eq("src-failing-tbl"),
                Mockito.eq("events_failing_tbl"),
                Mockito.eq(1),
                Mockito.contains("ClickHouse write timeout")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_Success_NoFailureNotification() {
        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_success_tbl")
        )).thenReturn(List.of("event_id", "timestamp"));

        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_success_tbl", "evt-4", "lin-4", "ven-4", "src-success-tbl", 1,
                        LocalDateTime.now(), 4.0, "{}", "{}", java.util.Collections.emptyMap()
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        verify(clickhouseJdbcTemplate).batchUpdate(
                Mockito.contains("INSERT INTO ulpf_events.events_success_tbl"),
                Mockito.any(),
                Mockito.eq(1),
                Mockito.any()
        );
        Mockito.verify(failureNotificationService).markRecovered("src-success-tbl", "events_success_tbl");
        Mockito.verify(failureNotificationService, Mockito.never()).notifyInsertFailure(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
        Mockito.verify(failureNotificationService, Mockito.never()).notifyTableMissing(Mockito.any(), Mockito.any(), Mockito.anyInt());
        Mockito.verify(failureNotificationService, Mockito.never()).notifyNoTargetTable(Mockito.any(), Mockito.anyInt());
        Mockito.verify(failureNotificationService, Mockito.never()).notifyMissingColumns(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt());
    }

    @Test
    void testFlushCanonical_MissingMapping_TriggersNotifyMissingMapping() {
        // Event has mappingVersion = null and targetTable = null (Scenario 5)
        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        null, "evt-5", "lin-5", "ven-5", "src-no-mapping", null,
                        LocalDateTime.now(), 5.0, "{}", "{}", java.util.Collections.emptyMap()
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        verify(failureNotificationService).notifyMissingMapping("src-no-mapping", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_MissingColumns_ReconcilesViaDynamicSchemaProvisioningService() {
        ingestionRepository.clearFailedAlterCache();
        com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaService =
                Mockito.mock(com.ulpf.mapping.service.DynamicSchemaProvisioningService.class);
        Mockito.when(dynamicSchemaService.addColumnIfNotExists(anyString(), anyString(), anyString())).thenReturn(true);
        ingestionRepository.setDynamicSchemaProvisioningService(dynamicSchemaService);

        // ClickHouse table currently only has event_id and timestamp
        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_evolution_tbl")
        )).thenReturn(List.of("event_id", "timestamp"), List.of("event_id", "timestamp", "client_ip"));

        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_evolution_tbl", "evt-6", "lin-6", "ven-6", "src-evolution", 1,
                        LocalDateTime.now(), 6.0, "{}", "{}", java.util.Map.of("client_ip", "192.168.1.1")
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        // Verify dynamic column addition was triggered for client_ip and succeeded
        verify(dynamicSchemaService).addColumnIfNotExists("events_evolution_tbl", "client_ip", "Nullable(String)");
        // ALTER returned true -> no notifyMissingColumns alert sent!
        verify(failureNotificationService, never()).notifyMissingColumns(anyString(), anyString(), anySet(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_MissingColumns_AlterFails_SendsAlert() {
        ingestionRepository.clearFailedAlterCache();
        com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaService =
                Mockito.mock(com.ulpf.mapping.service.DynamicSchemaProvisioningService.class);
        Mockito.when(dynamicSchemaService.addColumnIfNotExists(anyString(), anyString(), anyString())).thenReturn(false);
        ingestionRepository.setDynamicSchemaProvisioningService(dynamicSchemaService);

        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_failed_alter_tbl")
        )).thenReturn(List.of("event_id", "timestamp"));

        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_failed_alter_tbl", "evt-7", "lin-7", "ven-7", "src-fail-alter", 1,
                        LocalDateTime.now(), 7.0, "{}", "{}", java.util.Map.of("broken_col", "some_value")
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        // ALTER returned false -> notifyMissingColumns alert sent!
        verify(failureNotificationService).notifyMissingColumns("src-fail-alter", "events_failed_alter_tbl", Set.of("broken_col"), 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_MissingColumns_InvalidColumnName_RejectsWithoutAlter() {
        ingestionRepository.clearFailedAlterCache();
        com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaService =
                Mockito.mock(com.ulpf.mapping.service.DynamicSchemaProvisioningService.class);
        ingestionRepository.setDynamicSchemaProvisioningService(dynamicSchemaService);

        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_invalid_col_tbl")
        )).thenReturn(List.of("event_id", "timestamp"));

        // Column name with invalid character (hyphen/special char)
        ClickHouseIngestionRepository.CanonicalEventRecord record =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_invalid_col_tbl", "evt-8", "lin-8", "ven-8", "src-invalid-col", 1,
                        LocalDateTime.now(), 8.0, "{}", "{}", java.util.Map.of("invalid-col!", "value")
                );

        ingestionRepository.enqueueCanonical(record);
        ingestionRepository.flushCanonical();

        // Dynamic column addition is rejected prior to SQL injection risk -> no addColumnIfNotExists call!
        verify(dynamicSchemaService, never()).addColumnIfNotExists(anyString(), anyString(), anyString());
        // Alert is still dispatched for missing columns
        verify(failureNotificationService).notifyMissingColumns("src-invalid-col", "events_invalid_col_tbl", Set.of("invalid-col!"), 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlushCanonical_MissingColumns_SameFailingColumnTwiceIn10Min_TriesAlterOnce() {
        ingestionRepository.clearFailedAlterCache();
        com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaService =
                Mockito.mock(com.ulpf.mapping.service.DynamicSchemaProvisioningService.class);
        Mockito.when(dynamicSchemaService.addColumnIfNotExists(anyString(), anyString(), anyString())).thenReturn(false);
        ingestionRepository.setDynamicSchemaProvisioningService(dynamicSchemaService);

        Mockito.when(clickhouseJdbcTemplate.query(
                Mockito.contains("system.columns"),
                Mockito.any(org.springframework.jdbc.core.RowMapper.class),
                Mockito.eq("events_cooldown_col_tbl")
        )).thenReturn(List.of("event_id", "timestamp"));

        ClickHouseIngestionRepository.CanonicalEventRecord record1 =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_cooldown_col_tbl", "evt-9a", "lin-9", "ven-9", "src-cooldown-col", 1,
                        LocalDateTime.now(), 9.0, "{}", "{}", java.util.Map.of("flaky_col", "val1")
                );
        ClickHouseIngestionRepository.CanonicalEventRecord record2 =
                new ClickHouseIngestionRepository.CanonicalEventRecord(
                        "events_cooldown_col_tbl", "evt-9b", "lin-9", "ven-9", "src-cooldown-col", 1,
                        LocalDateTime.now(), 9.1, "{}", "{}", java.util.Map.of("flaky_col", "val2")
                );

        // First flush
        ingestionRepository.enqueueCanonical(record1);
        ingestionRepository.flushCanonical();

        // Second flush immediately after
        ingestionRepository.enqueueCanonical(record2);
        ingestionRepository.flushCanonical();

        // Even though flushed twice, addColumnIfNotExists should only have been tried ONCE within the 10 min window!
        verify(dynamicSchemaService, times(1)).addColumnIfNotExists("events_cooldown_col_tbl", "flaky_col", "Nullable(String)");
    }
}


