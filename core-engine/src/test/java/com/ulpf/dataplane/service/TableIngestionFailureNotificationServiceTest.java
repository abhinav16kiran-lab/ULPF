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
        service.setCooldownMs(50L); // 50ms cooldown for fast test
        String sourceId = "src_quiet_recovery";
        String tableName = "events_quiet_recovery";

        when(sourceRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                new User("admin_1", "admin", "Admin", "hash", Role.ADMIN, LocalDateTime.now())
        ));

        // Initial alert
        service.notifyTableMissing(sourceId, tableName, 10);
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Suppressed event
        service.notifyTableMissing(sourceId, tableName, 40);
        verify(onboardingRepository, times(1)).saveNotification(anyString(), anyString(), anyString());

        // Wait for cooldown to expire without any subsequent events arriving
        try {
            Thread.sleep(70);
        } catch (InterruptedException ignored) {}

        // Scheduled worker runs
        service.flushExpiredDigests();

        // Digest MUST be sent for the 40 suppressed events!
        verify(onboardingRepository, times(2)).saveNotification(anyString(), anyString(), anyString());
    }
}

