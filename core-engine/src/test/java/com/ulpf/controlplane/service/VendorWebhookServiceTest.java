package com.ulpf.controlplane.service;

import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.common.db.VendorWebhookRepository;
import com.ulpf.common.db.VendorWebhookRepository.VendorWebhookRecord;
import com.ulpf.common.db.VendorWebhookRepository.WebhookDeliveryLogRecord;
import com.ulpf.controlplane.service.VendorWebhookService.VendorWebhookResponse;
import com.ulpf.controlplane.service.VendorWebhookService.WebhookConfigRequest;
import com.ulpf.dataplane.service.AlertDispatcher;
import com.ulpf.dataplane.service.AlertDispatcher.DeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VendorWebhookServiceTest {

    private VendorWebhookRepository webhookRepo;
    private VendorRepository vendorRepo;
    private AlertDispatcher alertDispatcher;
    private VendorWebhookService service;

    @BeforeEach
    void setUp() {
        webhookRepo = mock(VendorWebhookRepository.class);
        vendorRepo = mock(VendorRepository.class);
        alertDispatcher = mock(AlertDispatcher.class);
        service = new VendorWebhookService(webhookRepo, vendorRepo, alertDispatcher);
    }

    // ── Secret Masking ──────────────────────────────────────────────

    @Test
    void testSaveAndMaskSecret() {
        // Arrange: vendor lookup
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        VendorWebhookRecord saved = new VendorWebhookRecord(
                "wh_1", "ven_1", "https://hooks.slack.com/services/T00/B00/XXXXX",
                "my-shared-hmac-secret", true, "UNTESTED", null, null, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(webhookRepo.saveOrUpdate(any())).thenReturn(saved);
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.of(saved));
        when(webhookRepo.findRecentLogsByVendorId("ven_1", 5)).thenReturn(List.of());

        // Act
        VendorWebhookResponse response = service.saveOrUpdateWebhook(
                "user_1",
                new WebhookConfigRequest("https://hooks.slack.com/services/T00/B00/XXXXX", "my-shared-hmac-secret")
        );

        // Assert: secret is masked, not exposed in plaintext
        assertTrue(response.configured());
        assertNotNull(response.maskedSecret());
        assertNotEquals("my-shared-hmac-secret", response.maskedSecret());
        assertTrue(response.maskedSecret().contains("••••"), "Masked secret should contain bullet characters");
        // Must not reveal the full secret
        assertFalse(response.maskedSecret().equals("my-shared-hmac-secret"));
    }

    @Test
    void testSaveOrUpdateWebhook_PreservesExistingSecret_WhenOmitted() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        VendorWebhookRecord existing = new VendorWebhookRecord(
                "wh_1", "ven_1", "https://old-hooks.slack.com/services/1",
                "whsec_existing_secret_12345", true, "HEALTHY", 200, LocalDateTime.now(), 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        VendorWebhookRecord updated = new VendorWebhookRecord(
                "wh_1", "ven_1", "https://new-hooks.slack.com/services/2",
                "whsec_existing_secret_12345", true, "UNTESTED", null, null, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.of(existing), Optional.of(updated));

        // When saved, verify the repository was invoked with the existing secret preserved
        when(webhookRepo.saveOrUpdate(argThat(rec ->
                "whsec_existing_secret_12345".equals(rec.secretKey()) &&
                "https://new-hooks.slack.com/services/2".equals(rec.webhookUrl())
        ))).thenReturn(updated);

        when(webhookRepo.findRecentLogsByVendorId("ven_1", 5)).thenReturn(List.of());

        // Act: update URL with secretKey left null (leave blank to keep existing)
        VendorWebhookResponse response = service.saveOrUpdateWebhook(
                "user_1",
                new WebhookConfigRequest("https://new-hooks.slack.com/services/2", null)
        );

        // Assert: existing secret is preserved in the response
        assertTrue(response.configured());
        assertEquals("https://new-hooks.slack.com/services/2", response.webhookUrl());
        assertNotNull(response.maskedSecret());
        assertTrue(response.maskedSecret().contains("••••"));
    }

    @Test
    void testMaskSecret_ShortSecret() {
        // Very short secrets should be fully masked
        String masked = VendorWebhookService.maskSecret("abc");
        assertEquals("••••", masked);
    }

    @Test
    void testMaskSecret_NullSecret() {
        assertNull(VendorWebhookService.maskSecret(null));
    }

    @Test
    void testMaskSecret_BlankSecret() {
        assertNull(VendorWebhookService.maskSecret("   "));
    }

    // ── Test Ping ───────────────────────────────────────────────────

    @Test
    void testTestPingSuccess() throws Exception {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        VendorWebhookRecord webhook = new VendorWebhookRecord(
                "wh_1", "ven_1", "https://hooks.example.com/test",
                "secret", true, "UNTESTED", null, null, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.of(webhook));

        // Mock dispatcher to return successful delivery
        DeliveryResult successResult = new DeliveryResult(true, 200, null, 145);
        when(alertDispatcher.sendWebhook(any(URI.class), eq("secret"), any(), eq("ven_1")))
                .thenReturn(CompletableFuture.completedFuture(successResult));

        DeliveryResult result = service.sendTestPing("user_1");

        assertTrue(result.success());
        assertEquals(200, result.statusCode());
        assertEquals(145, result.latencyMs());
        assertNull(result.errorMessage());
    }

    @Test
    void testTestPingFailure() throws Exception {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        VendorWebhookRecord webhook = new VendorWebhookRecord(
                "wh_1", "ven_1", "https://hooks.example.com/test",
                "secret", true, "UNTESTED", null, null, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.of(webhook));

        DeliveryResult failResult = new DeliveryResult(false, 500, "Internal Server Error", 320);
        when(alertDispatcher.sendWebhook(any(URI.class), eq("secret"), any(), eq("ven_1")))
                .thenReturn(CompletableFuture.completedFuture(failResult));

        DeliveryResult result = service.sendTestPing("user_1");

        assertFalse(result.success());
        assertEquals(500, result.statusCode());
    }

    @Test
    void testTestPingWithNoWebhookConfigured() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.sendTestPing("user_1"),
                "Should throw when no webhook is configured");
    }

    // ── Role / Vendor Validation ────────────────────────────────────

    @Test
    void testUserRoleForbidden_NoVendorFound() {
        // User with no associated vendor organization
        when(vendorRepo.findByOwnerUserId("regular_user_99"))
                .thenReturn(Optional.empty());

        // getWebhook should throw since user has no vendor
        assertThrows(IllegalArgumentException.class,
                () -> service.getWebhook("regular_user_99"),
                "Non-vendor user should be rejected");
    }

    @Test
    void testSaveWebhookWithInvalidUrl() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateWebhook("user_1", new WebhookConfigRequest("ftp://not-http.com/hook", null)),
                "Non HTTP/HTTPS URLs should be rejected");
    }

    @Test
    void testSaveWebhookWithBlankUrl() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateWebhook("user_1", new WebhookConfigRequest("", null)),
                "Blank URL should be rejected");
    }

    // ── Delete ──────────────────────────────────────────────────────

    @Test
    void testDeleteWebhook() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        assertDoesNotThrow(() -> service.deleteWebhook("user_1"));
        verify(webhookRepo).deleteByVendorId("ven_1");
    }

    // ── Get Webhook Not Configured ──────────────────────────────────

    @Test
    void testGetWebhookWhenNotConfigured() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));
        when(webhookRepo.findByVendorId("ven_1")).thenReturn(Optional.empty());

        VendorWebhookResponse resp = service.getWebhook("user_1");
        assertFalse(resp.configured());
        assertNull(resp.webhookUrl());
        assertEquals(0, resp.consecutiveFails());
    }

    @Test
    void testSaveWebhookWithLoopbackAddressRejected() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateWebhook("user_1", new WebhookConfigRequest("http://localhost:8080/internal", null)));
        assertTrue(ex1.getMessage().contains("restricted internal or link-local address"));

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateWebhook("user_1", new WebhookConfigRequest("http://127.0.0.1:9090/admin", null)));
        assertTrue(ex2.getMessage().contains("restricted internal or link-local address"));
    }

    @Test
    void testSaveWebhookWithCloudMetadataRejected() {
        when(vendorRepo.findByOwnerUserId("user_1"))
                .thenReturn(Optional.of(new VendorRecord("ven_1", "user_1", "Vendor One", "ACTIVE", LocalDateTime.now())));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateWebhook("user_1", new WebhookConfigRequest("http://169.254.169.254/latest/meta-data", null)));
        assertTrue(ex.getMessage().contains("restricted internal or link-local address"));
    }
}
