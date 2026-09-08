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

        // 2nd distinct key set -> Notification 2 sent!
        driftNotificationService.checkAndNotifyDrift(sourceId, Set.of("key_beta"));

        verify(onboardingRepository, times(2)).saveNotification(anyString(), anyString(), anyString());
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
}
