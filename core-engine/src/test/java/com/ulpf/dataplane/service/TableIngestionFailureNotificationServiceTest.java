package com.ulpf.dataplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TableIngestionFailureNotificationServiceTest {

    private SourceRepository sourceRepository;
    private VendorRepository vendorRepository;
    private OnboardingRepository onboardingRepository;
    private UserRepository userRepository;
    private TableIngestionFailureNotificationService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SourceRepository.class);
        vendorRepository = mock(VendorRepository.class);
        onboardingRepository = mock(OnboardingRepository.class);
        userRepository = mock(UserRepository.class);

        service = new TableIngestionFailureNotificationService(
                sourceRepository,
                vendorRepository,
                onboardingRepository,
                userRepository
        );

        // Warm up Mockito bytecode/classloading so timing-dependent tests don't suffer cold-start jitter
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("warmup_admin", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));
        service.notifyTableMissing("src_warmup", "tbl_warmup", 1);
        service.clearCache();
        reset(onboardingRepository, userRepository, sourceRepository, vendorRepository);
    }

    @Test
    void testTableMissingDispatchesRoleTailoredAlerts() {
        String sourceId = "src_firewall_1";
        String vendorId = "ven_cisco";
        String ownerUserId = "usr_vendor_cisco";
        String tableName = "events_firewall_alpha";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Stream", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Cisco Systems", "ACTIVE", LocalDateTime.now());
        User adminUser = new User("admin_1", "admin", "Admin User", "hash", Role.ADMIN, LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(adminUser));

        service.notifyTableMissing(sourceId, tableName, 500);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(2)).saveNotification(
                userCaptor.capture(),
                titleCaptor.capture(),
                msgCaptor.capture()
        );

        List<String> users = userCaptor.getAllValues();
        List<String> titles = titleCaptor.getAllValues();
        List<String> messages = msgCaptor.getAllValues();

        // 1. Admin Notification
        assertEquals("admin_1", users.get(0));
        assertTrue(titles.get(0).contains("[CRITICAL] Dynamic Table Missing"));
        assertTrue(messages.get(0).contains(tableName));
        assertTrue(messages.get(0).contains("500 event(s) could not be loaded"));
        assertTrue(messages.get(0).contains("/replay"));

        // 2. Vendor Notification
        assertEquals(ownerUserId, users.get(1));
        assertTrue(titles.get(1).contains("Dynamic Table Missing"));
        assertTrue(messages.get(1).contains("Firewall Stream"));
    }

    @Test
    void testInsertFailureDispatchesAlertWithErrorMessage() {
        String sourceId = "src_db_logs";
        String vendorId = "ven_oracle";
        String ownerUserId = "usr_vendor_oracle";
        String tableName = "events_db_logs";
        String errorMsg = "Memory limit for query exceeded (8.20 GiB > 8.00 GiB)";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Database Stream", "DATABASE", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Oracle Corp", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_sys", "sysadmin", "System Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        service.notifyInsertFailure(sourceId, tableName, 250, errorMsg);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(2)).saveNotification(anyString(), titleCaptor.capture(), msgCaptor.capture());

        assertTrue(titleCaptor.getAllValues().get(0).contains("[INGESTION FAILURE] Dynamic Table Insert Failed"));
        assertTrue(msgCaptor.getAllValues().get(0).contains("250 event(s)"));
        assertTrue(msgCaptor.getAllValues().get(0).contains("Memory limit for query exceeded"));
        assertTrue(msgCaptor.getAllValues().get(0).contains("/replay"));
    }

    @Test
    void testNoTargetTableDispatchesConfigurationAlert() {
        String sourceId = "src_unmapped";
        SourceRecord source = new SourceRecord(sourceId, "ven_unk", "Unmapped Stream", "LOGS", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        service.notifyNoTargetTable(sourceId, 100);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(1)).saveNotification(anyString(), titleCaptor.capture(), msgCaptor.capture());

        assertTrue(titleCaptor.getValue().contains("[CONFIGURATION REQUIRED] No Target Table Defined"));
        assertTrue(msgCaptor.getValue().contains("100 event(s) bypassed dynamic table insertion"));
    }

    @Test
    void testAlertThrottlingSuppressesRapidDuplicateNotifications() {
        String sourceId = "src_fast_stream";
        String tableName = "events_fast_stream";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // 1st occurrence -> Trigger alert for 1,000 events
        service.notifyTableMissing(sourceId, tableName, 1000);

        // Subsequent rapid calls within cooldown window -> All SUPPRESSED!
        for (int i = 0; i < 999; i++) {
            service.notifyTableMissing(sourceId, tableName, 10);
        }

        // Exactly 1 alert dispatch (to fallback admin)
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Accumulated count should equal 1,000 + (999 * 10) = 10,990
        assertEquals(10990, service.getSuppressedCount(sourceId, "TABLE_MISSING"));
        assertTrue(service.isThrottled(sourceId, "TABLE_MISSING"));
    }

    @Test
    void testIndependentThrottlingPerSourceAndFailureType() {
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Source A missing table
        service.notifyTableMissing("source_A", "events_A", 100);

        // Source B missing table -> Should NOT be throttled by Source A
        service.notifyTableMissing("source_B", "events_B", 100);

        // Source A insert failure -> Different failureType, should NOT be throttled by TABLE_MISSING
        service.notifyInsertFailure("source_A", "events_A", 100, "Timeout");

        verify(onboardingRepository, times(3)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testDigestDispatchedWhenWindowExpires() {
        service.setCooldownMs(50); // 50ms test cooldown
        String sourceId = "src_cooldown_test";
        String tableName = "events_test";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Initial alert
        service.notifyTableMissing(sourceId, tableName, 100);
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Second event within 50ms -> Throttled
        service.notifyTableMissing(sourceId, tableName, 200);
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Wait for cooldown to expire
        try {
            Thread.sleep(70);
        } catch (InterruptedException ignored) {}

        // Event after cooldown -> Sends digest for previous window + new initial alert
        service.notifyTableMissing(sourceId, tableName, 50);

        // Total notifications: 1 initial + 1 digest + 1 new window initial = 3
        verify(onboardingRepository, times(3)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testNotifyMissingMapping_DispatchesExpectedAlerts() {
        String sourceId = "src_unmapped_stream";
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        service.notifyMissingMapping(sourceId, 50);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(1)).saveNotification(eq("admin_1"), titleCaptor.capture(), msgCaptor.capture());

        assertTrue(titleCaptor.getValue().contains("[ONBOARDING REQUIRED]"));
        assertTrue(msgCaptor.getValue().contains("no approved schema mapping"));
        assertTrue(msgCaptor.getValue().contains("POST /v1/admin/sources/src_unmapped_stream/replay"));
    }

    @Test
    void testNotifyMissingColumns_DispatchesExpectedAlerts() {
        String sourceId = "src_col_mismatch";
        String tableName = "events_col_mismatch";
        Set<String> missingCols = Set.of("client_ip", "geo_city");

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        service.notifyMissingColumns(sourceId, tableName, missingCols, 75);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(1)).saveNotification(eq("admin_1"), titleCaptor.capture(), msgCaptor.capture());

        assertTrue(titleCaptor.getValue().contains("[SCHEMA MISMATCH]"));
        assertTrue(msgCaptor.getValue().contains("client_ip"));
        assertTrue(msgCaptor.getValue().contains("geo_city"));
        assertTrue(msgCaptor.getValue().contains("75"));
    }

    @Test
    void testScheduledDigestDispatchesForExpiredWindowWithoutNewEvents() {
        service.setCooldownMs(100L); // 100ms cooldown for fast test
        String sourceId = "src_quiet_recovery";
        String tableName = "events_quiet_recovery";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Initial alert
        service.notifyTableMissing(sourceId, tableName, 10);

        // Suppressed event (immediately follows, well within 100ms)
        service.notifyTableMissing(sourceId, tableName, 40);

        // Exactly 1 alert was sent so far (the 40 events were suppressed)
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Wait for cooldown to expire without any subsequent events arriving
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {}

        // Scheduled worker runs
        service.flushExpiredDigests();

        // Digest MUST be sent for the 40 suppressed events -> 2 notifications total!
        verify(onboardingRepository, times(2)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testConcurrent32ThreadsSameSourceAndType_ExactlyOneAlertAndTotalCountPreserved() throws InterruptedException {
        String sourceId = "src_concurrency_1";
        String tableName = "events_concurrency_tbl";
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        int threadCount = 32;
        int callsPerThread = 1000;
        int countPerCall = 5;
        long totalExpectedEvents = (long) threadCount * callsPerThread * countPerCall;

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch endLatch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        service.notifyTableMissing(sourceId, tableName, countPerCall);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly 1 initial alert must have been dispatched
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Cache must hold the exact sum of all events
        TableIngestionFailureNotificationService.ThrottleEntry entry = service.getThrottleCache().get(sourceId + ":TABLE_MISSING");
        assertNotNull(entry);
        assertEquals(totalExpectedEvents, entry.accumulatedCount().get());
    }

    @Test
    void testTinyCooldownWithFlushLoop_NoLostCounts() throws InterruptedException {
        service.setCooldownMs(10L); // 10ms cooldown
        String sourceId = "src_flush_loop";
        String tableName = "events_flush_loop";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

        Thread flusher = new Thread(() -> {
            while (running.get()) {
                service.flushExpiredDigests();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {}
            }
            service.flushExpiredDigests();
        });
        flusher.start();

        for (int i = 0; i < 30; i++) {
            service.notifyTableMissing(sourceId, tableName, 10);
            Thread.sleep(3);
        }

        Thread.sleep(25);
        running.set(false);
        flusher.join(2000);

        // At least 1 alert/digest sent
        verify(onboardingRepository, atLeast(1)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testFailureThenSuccess_IncidentLifecycleAndRecoveryReplayMessage() {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);

        String sourceId = "src_recov_1";
        String vendorId = "ven_recov_1";
        String ownerUserId = "usr_vendor_recov";
        String tableName = "events_recov";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(
                new SourceRecord(sourceId, vendorId, "Recov Source", "SYSLOG", "ACTIVE", LocalDateTime.now())
        ));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(
                new VendorRecord(vendorId, ownerUserId, "Recov Vendor", "ACTIVE", LocalDateTime.now())
        ));
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // 1. Failure happens -> incident opened
        service.notifyTableMissing(sourceId, tableName, 100);

        ArgumentCaptor<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> incidentCaptor =
                ArgumentCaptor.forClass(com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord.class);
        verify(incidentRepo, times(1)).createIncident(incidentCaptor.capture());

        com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord opened = incidentCaptor.getValue();
        assertEquals(sourceId, opened.sourceId());
        assertEquals("TABLE_MISSING", opened.failureType());
        assertEquals(tableName, opened.tableName());
        assertEquals("OPEN", opened.status());
        assertEquals(100, opened.affectedCount());
        assertNotNull(opened.startedAt());

        assertEquals(1, service.getOpenIncidents().size());

        // 2. Recovery happens -> incident marked RESOLVED, replay notification dispatched
        service.markRecovered(sourceId, tableName);

        verify(incidentRepo, times(1)).resolveIncident(eq(opened.incidentId()), any(LocalDateTime.class));
        assertTrue(service.getOpenIncidents().isEmpty());

        // 2 initial alert notifications (admin + vendor) + 2 recovery notifications (admin + vendor) = 4 total
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(4)).saveNotification(
                userCaptor.capture(),
                titleCaptor.capture(),
                msgCaptor.capture()
        );

        List<String> titles = titleCaptor.getAllValues();
        List<String> messages = msgCaptor.getAllValues();

        // Recovery Admin Notification
        String recoveryAdminTitle = titles.get(2);
        String recoveryAdminMsg = messages.get(2);
        assertTrue(recoveryAdminTitle.contains("[RECOVERED]"));
        assertTrue(recoveryAdminMsg.contains("fromTime"));
        assertTrue(recoveryAdminMsg.contains("toTime"));
        assertTrue(recoveryAdminMsg.contains("/replay"));
        LocalDateTime expectedFromTime = opened.startedAt().minusMinutes(service.getReplayMarginMinutes());
        assertTrue(recoveryAdminMsg.contains(expectedFromTime.toString()));

        // Recovery Vendor Notification
        String recoveryVendorTitle = titles.get(3);
        String recoveryVendorMsg = messages.get(3);
        assertTrue(recoveryVendorTitle.contains("Ingestion Resumed"));
        assertTrue(recoveryVendorMsg.contains("Recov Source"));
    }

    @Test
    void testRecoveryReplayWindow_IncludesMarginMinutes() {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);
        service.setReplayMarginMinutes(10); // Custom 10-minute margin

        String sourceId = "src_margin_test";
        String tableName = "events_margin_test";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        service.notifyTableMissing(sourceId, tableName, 100);

        ArgumentCaptor<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> incidentCaptor =
                ArgumentCaptor.forClass(com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord.class);
        verify(incidentRepo).createIncident(incidentCaptor.capture());
        LocalDateTime startedAt = incidentCaptor.getValue().startedAt();
        LocalDateTime expectedFromTime = startedAt.minusMinutes(10);

        service.markRecovered(sourceId, tableName);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(onboardingRepository, times(2)).saveNotification(eq("admin_1"), anyString(), msgCaptor.capture());

        String recoveryMsg = msgCaptor.getAllValues().get(1);
        assertTrue(recoveryMsg.contains(expectedFromTime.toString()), "Replay fromTime must equal startedAt - 10 minutes");
    }

    @Test
    void testFailureRecoveryThenFailureInsideWindow_GivesTwoAlertsAndTwoIncidents() {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);

        String sourceId = "src_flapping";
        String tableName = "events_flapping";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // 1. Initial failure -> alert 1, incident 1
        service.notifyTableMissing(sourceId, tableName, 10);
        assertEquals(1, service.getOpenIncidents().size());

        // 2. Incident recovers -> throttle cache removed for key
        service.markRecovered(sourceId, tableName);
        assertTrue(service.getOpenIncidents().isEmpty());

        // 3. New failure immediately (inside the 30-min window, without calling clearCache()) -> alert 2, incident 2!
        service.notifyTableMissing(sourceId, tableName, 15);
        assertEquals(1, service.getOpenIncidents().size());

        // Verify two separate incidents were created
        ArgumentCaptor<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> captor =
                ArgumentCaptor.forClass(com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord.class);
        verify(incidentRepo, times(2)).createIncident(captor.capture());
        List<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> records = captor.getAllValues();
        assertNotEquals(records.get(0).incidentId(), records.get(1).incidentId());
        assertEquals(10, records.get(0).affectedCount());
        assertEquals(15, records.get(1).affectedCount());

        // Verify 2 failure alerts were dispatched to admin
        verify(onboardingRepository, times(3)).saveNotification(eq("admin_1"), anyString(), anyString());
        // (1st failure alert + 1 recovery alert + 2nd failure alert = 3 admin notifications)
    }

    @Test
    void testTwoWindowsThenRecovery_IncidentTotalIncludesUndigestedEvents() throws Exception {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);
        service.setCooldownMs(20); // 20ms cooldown

        String sourceId = "src_two_win_sum";
        String tableName = "events_two_win_sum";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Window 1: first failure (100 events)
        service.notifyTableMissing(sourceId, tableName, 100);
        // Window 1: second failure suppressed (20 events)
        service.notifyTableMissing(sourceId, tableName, 20);

        // Wait for window 1 to expire
        Thread.sleep(35);

        // Window 2: first failure of window 2 triggers digest for window 1 (adds 20 suppressed),
        // and adds 50 for the new alert -> total should be 100 + 20 + 50 = 170
        service.notifyTableMissing(sourceId, tableName, 50);

        // Window 2: second failure suppressed (30 events) -> un-digested in window 2
        service.notifyTableMissing(sourceId, tableName, 30);

        // Recover before window 2 expires -> should add un-digested 30 events -> 170 + 30 = 200 total!
        service.markRecovered(sourceId, tableName);

        ArgumentCaptor<Long> countCaptor = ArgumentCaptor.forClass(Long.class);
        verify(incidentRepo, atLeastOnce()).updateCountsAndLastSeen(anyString(), countCaptor.capture(), any());
        List<Long> capturedCounts = countCaptor.getAllValues();
        // The last update before resolution should be 200
        assertEquals(200L, capturedCounts.get(capturedCounts.size() - 1));
    }

    @Test
    void testSuccessWithNoOpenIncident_NothingSent() {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);

        service.markRecovered("src_clean", "events_clean");

        verifyNoInteractions(incidentRepo);
        verifyNoInteractions(onboardingRepository);
    }

    @Test
    void testCooldownLoadsFromProperty() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cooldownMs", 123456L);
        assertEquals(123456L, service.getCooldownMs());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "replayMarginMinutes", 15L);
        assertEquals(15L, service.getReplayMarginMinutes());
    }

    @Test
    void testTwoFailureWindows_CreatesTwoIncidents() {
        com.ulpf.common.db.IngestionIncidentRepository incidentRepo = mock(com.ulpf.common.db.IngestionIncidentRepository.class);
        service.setIncidentRepository(incidentRepo);

        String sourceId = "src_two_win";
        String tableName = "events_two_win";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Window 1 failure -> incident 1
        service.notifyTableMissing(sourceId, tableName, 50);
        assertEquals(1, service.getOpenIncidents().size());

        // Recover Window 1
        service.markRecovered(sourceId, tableName);
        assertTrue(service.getOpenIncidents().isEmpty());

        // Clear throttle cache to simulate new window
        service.clearCache();

        // Window 2 failure -> incident 2
        service.notifyTableMissing(sourceId, tableName, 80);
        assertEquals(1, service.getOpenIncidents().size());

        ArgumentCaptor<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> incidentCaptor =
                ArgumentCaptor.forClass(com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord.class);
        verify(incidentRepo, times(2)).createIncident(incidentCaptor.capture());

        List<com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord> records = incidentCaptor.getAllValues();
        assertEquals(2, records.size());
        assertNotEquals(records.get(0).incidentId(), records.get(1).incidentId());
    }
}

