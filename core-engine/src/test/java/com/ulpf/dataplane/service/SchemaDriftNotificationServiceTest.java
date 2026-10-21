package com.ulpf.dataplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SchemaDriftNotificationServiceTest {

    private SourceRepository sourceRepository;
    private VendorRepository vendorRepository;
    private OnboardingRepository onboardingRepository;
    private SchemaDriftNotificationService driftNotificationService;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SourceRepository.class);
        vendorRepository = mock(VendorRepository.class);
        onboardingRepository = mock(OnboardingRepository.class);

        driftNotificationService = new SchemaDriftNotificationService(
                sourceRepository,
                vendorRepository,
                onboardingRepository,
                null
        );
    }

    @Test
    void testSchemaDriftTriggersRoleTailoredNotifications() {
        String sourceId = "src_fw_100";
        String vendorId = "ven_100";
        String ownerUserId = "usr_vendor_01";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Source", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Palo Alto", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));

        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("quic_connection_id", "tls_cipher_suite"));

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

        // 1. Vendor Alert (Informative Read-Only)
        assertEquals(ownerUserId, users.get(0));
        assertTrue(titles.get(0).contains("Schema Drift Alert"));
        assertTrue(messages.get(0).contains("contact your ULPF Administrator"));

        // 2. Admin Alert (Actionable Governance)
        assertEquals("admin", users.get(1));
        assertTrue(titles.get(1).contains("[ADMIN ACTION REQUIRED]"));
        assertTrue(messages.get(1).contains("/admin"));
    }

    @Test
    void testDuplicateSchemaDriftSuppressedWithinCooldownWindow() {
        String sourceId = "src_fw_200";

        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        Set<String> unmappedKeys = Set.of("new_flag_1", "new_flag_2");

        // 1st occurrence -> Notification sent
        driftNotificationService.checkAndNotifyDrift(sourceId, unmappedKeys);

        // 2nd occurrence -> Cooldown active, notification SUPPRESSED!
        driftNotificationService.checkAndNotifyDrift(sourceId, unmappedKeys);

        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testDistinctUnmappedKeysTriggerSeparateAlerts() {
        String sourceId = "src_fw_300";
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        // 1st key set -> Notification 1 sent
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("key_alpha"));

        // 2nd distinct key set -> Suppressed within 30-min window, accumulated for digest
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("key_beta"));

        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        SchemaDriftNotificationService.DriftState state = driftNotificationService.getDriftStateCache().get(sourceId + ":SCHEMA_DRIFT");
        assertNotNull(state);
        assertTrue(state.accumulatedKeys().contains("key_alpha"));
        assertTrue(state.accumulatedKeys().contains("key_beta"));
        assertEquals(2, state.eventCount().get());
    }

    @Test
    void testNotifyHistoricalFallbackTriggersVendorAlertAndSuppressesDuplicates() {
        String sourceId = "src_fw_400";
        String vendorId = "ven_400";
        String ownerUserId = "usr_vendor_400";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Source 400", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Vendor 400", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));

        // 1st fallback event -> Notification dispatched to vendor owner
        driftNotificationService.notifyHistoricalFallback(sourceId, 1, Set.of("legacy_ip"));

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(1)).saveNotification(
                userCaptor.capture(),
                titleCaptor.capture(),
                msgCaptor.capture()
        );

        assertEquals(ownerUserId, userCaptor.getValue());
        assertTrue(titleCaptor.getValue().contains("Schema Version Fallback Alert"));
        assertTrue(msgCaptor.getValue().contains("fell back to historical mapping version v1"));

        // 2nd fallback event within cooldown window -> SUPPRESSED!
        driftNotificationService.notifyHistoricalFallback(sourceId, 1, Set.of("legacy_ip"));

        // Still only 1 total notification persisted!
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testNotifyVersionUpgradeResetsFallbackAlertAndSendsConfirmation() {
        String sourceId = "src_fw_500";
        String vendorId = "ven_500";
        String ownerUserId = "usr_vendor_500";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Source 500", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Vendor 500", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));

        // 1. Fallback alert triggered -> sets fallback alert lock
        driftNotificationService.notifyHistoricalFallback(sourceId, 1, Set.of("old_key"));
        assertTrue(driftNotificationService.hasFallbackAlert(sourceId));

        // 2. Stream resumes 100% active v2 compliance -> triggers upgrade notification & clears lock
        driftNotificationService.notifyVersionUpgrade(sourceId, 2);
        assertFalse(driftNotificationService.hasFallbackAlert(sourceId));

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(2)).saveNotification(
                userCaptor.capture(),
                titleCaptor.capture(),
                msgCaptor.capture()
        );

        List<String> titles = titleCaptor.getAllValues();
        List<String> messages = msgCaptor.getAllValues();

        assertEquals("Schema Version Fallback Alert: " + sourceId, titles.get(0));
        assertEquals("Schema Version Resumed: " + sourceId, titles.get(1));
        assertTrue(messages.get(1).contains("resumed using active mapping version v2"));
    }

    @Test
    void testTotalSchemaDriftDispatchesCriticalAlert() {
        String sourceId = "src_fw_total";
        String vendorId = "ven_total";
        String ownerUserId = "usr_vendor_total";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Total", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Vendor Total", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));

        // 5 keys in payload, all 5 unmapped -> isTotalDrift = true
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("k1", "k2", "k3", "k4", "k5"), 5, true);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        verify(onboardingRepository, times(2)).saveNotification(
                anyString(),
                titleCaptor.capture(),
                msgCaptor.capture()
        );

        List<String> titles = titleCaptor.getAllValues();
        List<String> messages = msgCaptor.getAllValues();

        assertTrue(titles.get(0).contains("[CRITICAL] Total Schema Drift"));
        assertTrue(messages.get(0).contains("All payload fields"));
        assertTrue(messages.get(0).contains("/replay"));

        assertTrue(titles.get(1).contains("[CRITICAL ACTION REQUIRED] Total Schema Drift"));
        assertTrue(messages.get(1).contains("100% of payload fields"));
    }

    @Test
    void testHighSchemaDriftRatioAlert() {
        String sourceId = "src_fw_high_ratio";
        String vendorId = "ven_high";
        String ownerUserId = "usr_vendor_high";

        SourceRecord source = new SourceRecord(sourceId, vendorId, "Firewall Ratio", "FIREWALL", "ACTIVE", LocalDateTime.now());
        VendorRecord vendor = new VendorRecord(vendorId, ownerUserId, "Vendor High", "ACTIVE", LocalDateTime.now());

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));

        // 4 unmapped out of 10 total keys = 40% drift (exceeds 30% threshold)
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("f1", "f2", "f3", "f4"), 10, false);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(onboardingRepository, times(2)).saveNotification(anyString(), titleCaptor.capture(), anyString());

        assertTrue(titleCaptor.getAllValues().get(0).contains("Schema Drift Alert (40%)"));
        assertTrue(titleCaptor.getAllValues().get(1).contains("[ADMIN ACTION REQUIRED] High Schema Drift (40%)"));
    }

    @Test
    void testRoutingThroughNotificationService() {
        com.ulpf.controlplane.service.NotificationService notificationService =
                mock(com.ulpf.controlplane.service.NotificationService.class);
        driftNotificationService.setNotificationService(notificationService);

        String sourceId = "src_fw_routing";
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("unmapped_col"));

        // Verify createNotification on NotificationService was called
        verify(notificationService, atLeastOnce()).createNotification(anyString(), anyString(), anyString());

        // Verify fallback onboardingRepository.saveNotification was NOT called
        verify(onboardingRepository, never()).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void test10000CallsRapidlyTriggerExactlyOneAlert() {
        String sourceId = "src_high_volume";
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        for (int i = 0; i < 10000; i++) {
            driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("unmapped_field_" + (i % 20)));
        }

        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());
        SchemaDriftNotificationService.DriftState state = driftNotificationService.getDriftStateCache().get(sourceId + ":SCHEMA_DRIFT");
        assertNotNull(state);
        assertEquals(10000, state.eventCount().get());
        assertTrue(state.accumulatedKeys().size() <= 50);
    }

    @Test
    void testDifferentSourcesHaveIndependentAlerts() {
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        driftNotificationService.checkAndNotifyDrift("source_A", Set.of("k1"));
        driftNotificationService.checkAndNotifyDrift("source_B", Set.of("k2"));

        verify(onboardingRepository, times(2)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testDriftThenTotalDriftTriggersEscalationAlert() {
        String sourceId = "src_escalation";
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        // Normal drift
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("extra_field"), 10, false);
        // Total drift on same source -> Escalates immediately!
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("field1", "field2"), 2, true);

        verify(onboardingRepository, times(2)).saveNotification(anyString(), anyString(), anyString());
    }

    @Test
    void testWindowExpiryWithNoNewEventsScheduledFlushSendsDigest() throws InterruptedException {
        driftNotificationService.setCooldownMs(50L); // 50ms for test
        String sourceId = "src_quiet_drift";
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        // First event
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("k_alpha"));
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Second event (suppressed)
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("k_beta"));
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        Thread.sleep(70);

        // Scheduled flush runs
        driftNotificationService.flushExpiredDigests();

        // 1 initial alert + 1 digest = 2 notifications total
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(onboardingRepository, times(2)).saveNotification(anyString(), titleCaptor.capture(), msgCaptor.capture());

        assertTrue(titleCaptor.getAllValues().get(1).contains("[DIGEST] Schema Drift Summary"));
        assertTrue(msgCaptor.getAllValues().get(1).contains("k_alpha"));
        assertTrue(msgCaptor.getAllValues().get(1).contains("k_beta"));
        assertTrue(msgCaptor.getAllValues().get(1).contains("2 drifted events"));
    }

    @Test
    void testBoundaryDriftThreshold3Of10NormalVersus4Of10HighDrift() {
        when(sourceRepository.findById(anyString())).thenReturn(Optional.empty());

        // 3 of 10 = 30% ratio -> strictly <= 30%, normal drift alert
        driftNotificationService.checkAndNotifyDrift("src_normal_drift", Set.of("a", "b", "c"), 10, false);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(onboardingRepository, times(1)).saveNotification(anyString(), titleCaptor.capture(), anyString());
        assertEquals("[ADMIN ACTION REQUIRED] Schema Drift: src_normal_drift", titleCaptor.getValue());

        // 4 of 10 = 40% ratio -> > 30%, HIGH drift alert
        driftNotificationService.checkAndNotifyDrift("src_high_drift", Set.of("a", "b", "c", "d"), 10, false);

        ArgumentCaptor<String> highTitleCaptor = ArgumentCaptor.forClass(String.class);
        verify(onboardingRepository, times(2)).saveNotification(anyString(), highTitleCaptor.capture(), anyString());
        assertTrue(highTitleCaptor.getValue().contains("[ADMIN ACTION REQUIRED] High Schema Drift (40%)"));
    }
}

