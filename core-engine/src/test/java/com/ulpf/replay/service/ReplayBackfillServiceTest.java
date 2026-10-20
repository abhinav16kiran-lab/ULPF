package com.ulpf.replay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.service.NotificationService;
import com.ulpf.mapping.service.DynamicSchemaProvisioningService;
import com.ulpf.mapping.service.MappingTransformer;
import com.ulpf.replay.model.ReplayJobStatus;
import com.ulpf.replay.model.ReplayRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplayBackfillServiceTest {

    private ClickHouseIngestionRepository clickHouseRepo;
    private MappingRepository mappingRepo;
    private SourceRepository sourceRepo;
    private DynamicSchemaProvisioningService schemaService;
    private MappingTransformer transformer;
    private NotificationService notificationService;
    private VendorRepository vendorRepo;
    private ReplayBackfillService replayService;

    // Direct synchronous executor for deterministic test execution
    private ExecutorService directExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clickHouseRepo = mock(ClickHouseIngestionRepository.class);
        mappingRepo = mock(MappingRepository.class);
        sourceRepo = mock(SourceRepository.class);
        schemaService = mock(DynamicSchemaProvisioningService.class);
        transformer = new MappingTransformer(objectMapper);
        notificationService = mock(NotificationService.class);
        vendorRepo = mock(VendorRepository.class);

        directExecutor = new AbstractExecutorService() {
            private boolean shutdown = false;
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };

        replayService = new ReplayBackfillService(
                clickHouseRepo,
                mappingRepo,
                sourceRepo,
                schemaService,
                transformer,
                notificationService,
                vendorRepo,
                directExecutor
        );
    }

    private SourceRecord createMockSource(String sourceId, String vendorId, String sourceName) {
        return new SourceRecord(sourceId, vendorId, sourceName, "LOG", "ACTIVE", LocalDateTime.now());
    }

    private MappingVersionRecord createMockMapping(String mappingId, String sourceId, int version, String json) {
        return new MappingVersionRecord(mappingId, sourceId, version, json, "ACTIVE", LocalDateTime.now());
    }

    @Test
    void testReplayHappyPath() {
        String sourceId = "src_happy";
        SourceRecord source = createMockSource(sourceId, "ven_1", "firewall_alpha");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = """
            {
              "metadata": { "target_table": "events_firewall_alpha" },
              "src_ip": { "canonicalField": "source_ip" },
              "action": { "canonicalField": "action" }
            }
            """;
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));

        when(schemaService.tableExists("ulpf_events", "events_firewall_alpha")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(100L);

        List<RawEventRecord> rawEvents = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 100; i++) {
            rawEvents.add(new RawEventRecord(
                    "evt_" + i, "lin_" + i, "ven_1", sourceId, null,
                    now.plusSeconds(i), "{\"src_ip\":\"192.168.1." + i + "\",\"action\":\"ALLOW\"}"
            ));
        }

        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(rawEvents)
                .thenReturn(Collections.emptyList());

        when(clickHouseRepo.findExistingEventIds(eq("events_firewall_alpha"), anyCollection()))
                .thenReturn(Collections.emptySet());
        when(clickHouseRepo.batchInsertDynamicRecords(eq("events_firewall_alpha"), anyList()))
                .thenReturn(100);

        when(vendorRepo.findById("ven_1")).thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", now)));

        ReplayJobStatus status = replayService.triggerReplayAsync(sourceId, new ReplayRequest(null, null, 100));

        assertNotNull(status);
        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.status());
        assertEquals(100, finalStatus.processed());
        assertEquals(100, finalStatus.inserted());
        assertEquals(0, finalStatus.skipped());
        assertEquals(0, finalStatus.failed());
        assertEquals(100, finalStatus.progressPercent());

        verify(clickHouseRepo, times(1)).batchInsertDynamicRecords(eq("events_firewall_alpha"), argThat(list -> list.size() == 100));
        verify(notificationService, times(1)).createNotification(eq("user_1"), anyString(), contains("Total: 100"));
    }

    @Test
    void testReplayDeduplication() {
        String sourceId = "src_dedup";
        SourceRecord source = createMockSource(sourceId, "ven_1", "web_app");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = """
            {
              "metadata": { "target_table": "events_web_app" },
              "url": { "canonicalField": "request_url" }
            }
            """;
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_web_app")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(10L);

        List<RawEventRecord> rawEvents = new ArrayList<>();
        Set<String> existingIds = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            String id = "evt_dedup_" + i;
            rawEvents.add(new RawEventRecord(id, "lin_" + i, "ven_1", sourceId, null, now.plusSeconds(i), "{\"url\":\"/home\"}"));
            existingIds.add(id);
        }

        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(rawEvents)
                .thenReturn(Collections.emptyList());

        // All events already exist in dynamic table
        when(clickHouseRepo.findExistingEventIds(eq("events_web_app"), anyCollection())).thenReturn(existingIds);

        replayService.triggerReplayAsync(sourceId, new ReplayRequest(null, null, 10));

        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.status());
        assertEquals(10, finalStatus.processed());
        assertEquals(0, finalStatus.inserted());
        assertEquals(10, finalStatus.skipped());
        assertEquals(0, finalStatus.failed());

        verify(clickHouseRepo, never()).batchInsertDynamicRecords(anyString(), anyList());
    }

    @Test
    void testReplayChunkingPagination() {
        String sourceId = "src_chunking";
        SourceRecord source = createMockSource(sourceId, "ven_1", "db_logs");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_db_logs\"},\"query\":{\"canonicalField\":\"sql_query\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_db_logs")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(2500L);

        LocalDateTime baseTime = LocalDateTime.of(2026, 10, 1, 0, 0);

        List<RawEventRecord> chunk1 = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            chunk1.add(new RawEventRecord("evt_" + i, "lin_" + i, "ven_1", sourceId, null, baseTime.plusSeconds(i), "{\"query\":\"SELECT 1\"}"));
        }
        List<RawEventRecord> chunk2 = new ArrayList<>();
        for (int i = 1000; i < 2000; i++) {
            chunk2.add(new RawEventRecord("evt_" + i, "lin_" + i, "ven_1", sourceId, null, baseTime.plusSeconds(i), "{\"query\":\"SELECT 2\"}"));
        }
        List<RawEventRecord> chunk3 = new ArrayList<>();
        for (int i = 2000; i < 2500; i++) {
            chunk3.add(new RawEventRecord("evt_" + i, "lin_" + i, "ven_1", sourceId, null, baseTime.plusSeconds(i), "{\"query\":\"SELECT 3\"}"));
        }

        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(chunk1)
                .thenReturn(chunk2)
                .thenReturn(chunk3)
                .thenReturn(Collections.emptyList());

        when(clickHouseRepo.findExistingEventIds(eq("events_db_logs"), anyCollection())).thenReturn(Collections.emptySet());
        when(clickHouseRepo.batchInsertDynamicRecords(eq("events_db_logs"), anyList())).thenAnswer(invocation -> {
            List<?> list = invocation.getArgument(1);
            return list.size();
        });

        replayService.triggerReplayAsync(sourceId, new ReplayRequest(null, null, 1000));

        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.status());
        assertEquals(2500, finalStatus.processed());
        assertEquals(2500, finalStatus.inserted());
        assertEquals(0, finalStatus.skipped());

        verify(clickHouseRepo, times(3)).batchInsertDynamicRecords(eq("events_db_logs"), anyList());
    }

    @Test
    void testReplayEmptyRawEvents() {
        String sourceId = "src_empty";
        SourceRecord source = createMockSource(sourceId, "ven_1", "empty_source");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_empty\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_empty")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(0L);

        ReplayJobStatus status = replayService.triggerReplayAsync(sourceId, null);

        assertNotNull(status);
        assertEquals("COMPLETED", status.status());
        assertEquals(0, status.totalEstimate());
        assertEquals(0, status.processed());
        assertEquals(0, status.inserted());
        verify(clickHouseRepo, never()).readRawEventsChunk(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void testReplayMissingDynamicTableAutoProvision() {
        String sourceId = "src_missing_tbl";
        SourceRecord source = createMockSource(sourceId, "ven_1", "new_source");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_new_source\"},\"host\":{\"canonicalField\":\"hostname\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));

        // Table does NOT exist initially
        when(schemaService.tableExists("ulpf_events", "events_new_source")).thenReturn(false);
        when(schemaService.provisionSchemaForSource(eq(sourceId), eq("new_source"), eq(mappingJson)))
                .thenReturn("events_new_source");

        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(1L);
        RawEventRecord raw = new RawEventRecord("evt_1", "lin_1", "ven_1", sourceId, null, LocalDateTime.now(), "{\"host\":\"srv1\"}");
        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(raw))
                .thenReturn(Collections.emptyList());
        when(clickHouseRepo.findExistingEventIds(eq("events_new_source"), anyCollection())).thenReturn(Collections.emptySet());
        when(clickHouseRepo.batchInsertDynamicRecords(eq("events_new_source"), anyList())).thenReturn(1);

        replayService.triggerReplayAsync(sourceId, null);

        verify(schemaService, times(1)).provisionSchemaForSource(eq(sourceId), eq("new_source"), eq(mappingJson));
        verify(clickHouseRepo, times(1)).batchInsertDynamicRecords(eq("events_new_source"), anyList());
        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.status());
        assertEquals(1, finalStatus.inserted());
    }

    @Test
    void testReplayCancellation() {
        String sourceId = "src_cancel";
        SourceRecord source = createMockSource(sourceId, "ven_1", "cancel_source");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_cancel\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_cancel")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(50L);

        AtomicBoolean token = new AtomicBoolean(true); // Token already set to cancelled

        // Directly call executeReplay with cancelled token
        replayService.executeReplay("job_cancel", source, mapping, "events_cancel", new ReplayRequest(null, null, 10), token);

        ReplayJobStatus status = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(status);
        assertEquals("CANCELLED", status.status());
        verify(clickHouseRepo, never()).readRawEventsChunk(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void testReplayMalformedJson() {
        String sourceId = "src_malformed";
        SourceRecord source = createMockSource(sourceId, "ven_1", "malformed_source");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_malformed\"},\"field\":{\"canonicalField\":\"norm_field\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_malformed")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(3L);

        LocalDateTime now = LocalDateTime.now();
        List<RawEventRecord> rawEvents = List.of(
                new RawEventRecord("evt_1", "lin_1", "ven_1", sourceId, null, now, "{\"field\":\"val1\"}"),
                new RawEventRecord("evt_2", "lin_2", "ven_1", sourceId, null, now.plusSeconds(1), "MALFORMED_JSON_STRING"),
                new RawEventRecord("evt_3", "lin_3", "ven_1", sourceId, null, now.plusSeconds(2), "{\"field\":\"val3\"}")
        );

        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(rawEvents)
                .thenReturn(Collections.emptyList());
        when(clickHouseRepo.findExistingEventIds(eq("events_malformed"), anyCollection())).thenReturn(Collections.emptySet());
        when(clickHouseRepo.batchInsertDynamicRecords(eq("events_malformed"), anyList())).thenReturn(2);

        replayService.triggerReplayAsync(sourceId, null);

        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("COMPLETED", finalStatus.status());
        assertEquals(3, finalStatus.processed());
        assertEquals(2, finalStatus.inserted());
        assertEquals(1, finalStatus.failed());
        assertEquals(0, finalStatus.skipped());

        verify(clickHouseRepo, times(1)).batchInsertDynamicRecords(eq("events_malformed"), argThat(list -> list.size() == 2));
    }

    @Test
    void testReplayTargetTableMetadataFallback() {
        String sourceId = "src_fallback";
        SourceRecord source = createMockSource(sourceId, "ven_1", "Auth Server Alpha");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        // Mapping JSON has NO target_table in metadata
        String mappingJson = "{\"user\":{\"canonicalField\":\"username\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));

        when(schemaService.constructTableName("Auth Server Alpha")).thenReturn("events_auth_server_alpha");
        when(schemaService.tableExists("ulpf_events", "events_auth_server_alpha")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(1L);

        RawEventRecord raw = new RawEventRecord("evt_1", "lin_1", "ven_1", sourceId, null, LocalDateTime.now(), "{\"user\":\"alice\"}");
        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(raw))
                .thenReturn(Collections.emptyList());
        when(clickHouseRepo.findExistingEventIds(eq("events_auth_server_alpha"), anyCollection())).thenReturn(Collections.emptySet());
        when(clickHouseRepo.batchInsertDynamicRecords(eq("events_auth_server_alpha"), anyList())).thenReturn(1);

        replayService.triggerReplayAsync(sourceId, null);

        verify(schemaService, times(1)).constructTableName("Auth Server Alpha");
        verify(clickHouseRepo, times(1)).batchInsertDynamicRecords(eq("events_auth_server_alpha"), anyList());
        ReplayJobStatus finalStatus = replayService.getJobStatus(sourceId).orElse(null);
        assertNotNull(finalStatus);
        assertEquals("events_auth_server_alpha", finalStatus.targetTable());
    }

    @Test
    void testReplaySensorTelemetryValueExtraction() {
        String sourceId = "src_sensor";
        SourceRecord source = createMockSource(sourceId, "ven_1", "Temperature Sensor");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = """
            {
              "metadata": {
                "target_table": "events_sensor_readings",
                "log_type": "SEN_TEL",
                "sensor_field": "reading_celsius"
              },
              "reading_celsius": { "canonicalField": "temperature" }
            }
            """;
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(mappingRepo.findAllValidVersionsBySourceId(sourceId)).thenReturn(List.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_sensor_readings")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(1L);

        RawEventRecord raw = new RawEventRecord(
                "evt_sen_1", "lin_sen_1", "ven_1", sourceId, null,
                LocalDateTime.now(), "{\"reading_celsius\": 36.6, \"status\": \"OK\"}"
        );
        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(raw))
                .thenReturn(Collections.emptyList());
        when(clickHouseRepo.findExistingEventIds(eq("events_sensor_readings"), anyCollection())).thenReturn(Collections.emptySet());

        replayService.triggerReplayAsync(sourceId, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CanonicalEventRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(clickHouseRepo, times(1)).batchInsertDynamicRecords(eq("events_sensor_readings"), captor.capture());

        List<CanonicalEventRecord> records = captor.getValue();
        assertEquals(1, records.size());
        CanonicalEventRecord rec = records.get(0);
        assertNotNull(rec.numericValue());
        assertEquals(36.6, rec.numericValue(), 0.001);
        assertEquals("36.6", rec.mappedFields().get("temperature"));
    }

    @Test
    void testReplayConcurrentTriggerBlocked() {
        String sourceId = "src_concurrent";
        SourceRecord source = createMockSource(sourceId, "ven_1", "Concurrent Source");
        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));

        String mappingJson = "{\"metadata\":{\"target_table\":\"events_concurrent\"}}";
        MappingVersionRecord mapping = createMockMapping("map_1", sourceId, 1, mappingJson);
        when(mappingRepo.findActiveBySourceId(sourceId)).thenReturn(Optional.of(mapping));
        when(schemaService.tableExists("ulpf_events", "events_concurrent")).thenReturn(true);
        when(clickHouseRepo.countRawEventsForSource(eq(sourceId), any(), any())).thenReturn(100L);

        // First trigger with an executor that pauses
        CountDownLatch pauseLatch = new CountDownLatch(1);
        ExecutorService pausingExecutor = Executors.newSingleThreadExecutor();
        ReplayBackfillService pausingService = new ReplayBackfillService(
                clickHouseRepo, mappingRepo, sourceRepo, schemaService, transformer, notificationService, vendorRepo, pausingExecutor
        );

        when(clickHouseRepo.readRawEventsChunk(eq(sourceId), any(), any(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    pauseLatch.await(2, TimeUnit.SECONDS);
                    return Collections.emptyList();
                });

        try {
            pausingService.triggerReplayAsync(sourceId, null);

            // Second concurrent trigger should immediately fail
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                pausingService.triggerReplayAsync(sourceId, null);
            });
            assertTrue(ex.getMessage().contains("already running"));
        } finally {
            pauseLatch.countDown();
            pausingExecutor.shutdown();
        }
    }
}
