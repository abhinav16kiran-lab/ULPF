package com.ulpf.common.db;

import com.ulpf.common.db.VendorWebhookRepository.VendorWebhookRecord;
import com.ulpf.common.db.VendorWebhookRepository.WebhookDeliveryLogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VendorWebhookRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private VendorWebhookRepository repository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);

        // Minimal tables needed for vendor_webhooks
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS vendors (
                vendor_id TEXT PRIMARY KEY,
                owner_user_id TEXT NOT NULL,
                vendor_name TEXT NOT NULL,
                status TEXT NOT NULL
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS vendor_webhooks (
                webhook_id          TEXT PRIMARY KEY,
                vendor_id           TEXT NOT NULL UNIQUE,
                webhook_url         TEXT NOT NULL,
                secret_key          TEXT,
                is_active           BOOLEAN NOT NULL DEFAULT 1,
                status              TEXT NOT NULL DEFAULT 'UNTESTED' CHECK (status IN ('UNTESTED', 'HEALTHY', 'FAILING')),
                last_status_code    INTEGER,
                last_attempt_at     TIMESTAMP,
                consecutive_fails   INTEGER NOT NULL DEFAULT 0,
                created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vendor_id) REFERENCES vendors(vendor_id)
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS webhook_delivery_logs (
                delivery_id         TEXT PRIMARY KEY,
                vendor_id           TEXT,
                target_url          TEXT NOT NULL,
                alert_type          TEXT NOT NULL,
                status_code         INTEGER,
                success             BOOLEAN NOT NULL,
                error_message       TEXT,
                latency_ms          INTEGER,
                attempted_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);

        jdbcTemplate.execute("INSERT INTO vendors VALUES ('ven_1', 'user_1', 'Vendor One', 'ACTIVE')");
        repository = new VendorWebhookRepository(jdbcTemplate);
    }

    @Test
    void testSaveAndLookupByVendorId() {
        VendorWebhookRecord initial = new VendorWebhookRecord(
                null, "ven_1", "https://hooks.slack.com/1", "initial_secret",
                true, "UNTESTED", null, null, 0, null, null
        );

        VendorWebhookRecord saved = repository.saveOrUpdate(initial);
        assertNotNull(saved.webhookId());
        assertEquals("https://hooks.slack.com/1", saved.webhookUrl());
        assertEquals("initial_secret", saved.secretKey());
        assertEquals("UNTESTED", saved.status());

        Optional<VendorWebhookRecord> lookup = repository.findByVendorId("ven_1");
        assertTrue(lookup.isPresent());
        assertEquals("initial_secret", lookup.get().secretKey());
    }

    @Test
    void testUpdateWebhookUrl_PreservesExistingSecret_WhenNewSecretIsNull() {
        // Step 1: Save with initial secret
        VendorWebhookRecord initial = new VendorWebhookRecord(
                null, "ven_1", "https://hooks.slack.com/old", "whsec_super_secret",
                true, "HEALTHY", 200, LocalDateTime.now(), 0, null, null
        );
        repository.saveOrUpdate(initial);

        // Step 2: Update URL only, passing null for secretKey
        VendorWebhookRecord update = new VendorWebhookRecord(
                null, "ven_1", "https://hooks.slack.com/new", null,
                true, "UNTESTED", null, null, 0, null, null
        );
        VendorWebhookRecord updated = repository.saveOrUpdate(update);

        // Assert: URL changed, but secret key is NOT wiped out
        assertEquals("https://hooks.slack.com/new", updated.webhookUrl());
        assertEquals("whsec_super_secret", updated.secretKey(), "Existing secret key must be preserved by COALESCE");

        Optional<VendorWebhookRecord> reloaded = repository.findByVendorId("ven_1");
        assertTrue(reloaded.isPresent());
        assertEquals("whsec_super_secret", reloaded.get().secretKey());
    }

    @Test
    void testDeliveryStatusUpdateAndLogs() {
        VendorWebhookRecord initial = new VendorWebhookRecord(
                null, "ven_1", "https://hooks.slack.com/1", "secret",
                true, "UNTESTED", null, null, 0, null, null
        );
        repository.saveOrUpdate(initial);

        // 1st transient failure (< 3): status remains UNTESTED
        repository.updateDeliveryStatus("ven_1", false, 500, 1);
        VendorWebhookRecord wh1 = repository.findByVendorId("ven_1").orElseThrow();
        assertEquals("UNTESTED", wh1.status());
        assertEquals(500, wh1.lastStatusCode());
        assertEquals(1, wh1.consecutiveFails());

        // 3rd consecutive failure (>= 3): status flips to FAILING
        repository.updateDeliveryStatus("ven_1", false, 500, 3);
        VendorWebhookRecord wh3 = repository.findByVendorId("ven_1").orElseThrow();
        assertEquals("FAILING", wh3.status());
        assertEquals(3, wh3.consecutiveFails());

        // Subsequent success: resets consecutive fails to 0 and marks HEALTHY
        repository.updateDeliveryStatus("ven_1", true, 200, 0);
        VendorWebhookRecord whHealthy = repository.findByVendorId("ven_1").orElseThrow();
        assertEquals("HEALTHY", whHealthy.status());
        assertEquals(200, whHealthy.lastStatusCode());
        assertEquals(0, whHealthy.consecutiveFails());

        repository.logDelivery(new WebhookDeliveryLogRecord(
                null, "ven_1", "https://hooks.slack.com/1", "SCHEMA_DRIFT",
                500, false, "Connection refused", 120, null
        ));

        List<WebhookDeliveryLogRecord> logs = repository.findRecentLogsByVendorId("ven_1", 5);
        assertEquals(1, logs.size());
        assertEquals("SCHEMA_DRIFT", logs.get(0).alertType());
        assertFalse(logs.get(0).success());
    }
}
