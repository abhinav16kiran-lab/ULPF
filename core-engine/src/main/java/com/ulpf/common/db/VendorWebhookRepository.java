package com.ulpf.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for vendor webhook configurations and delivery audit logs.
 * Manages the vendor_webhooks and webhook_delivery_logs SQLite tables.
 */
@Repository
public class VendorWebhookRepository {

    private final JdbcTemplate jdbcTemplate;

    public VendorWebhookRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Model Records ────────────────────────────────────────────────

    public record VendorWebhookRecord(
        String webhookId,
        String vendorId,
        String webhookUrl,
        String secretKey,
        boolean isActive,
        String status,           // UNTESTED, HEALTHY, FAILING
        Integer lastStatusCode,
        LocalDateTime lastAttemptAt,
        int consecutiveFails,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record WebhookDeliveryLogRecord(
        String deliveryId,
        String vendorId,
        String targetUrl,
        String alertType,
        Integer statusCode,
        boolean success,
        String errorMessage,
        Integer latencyMs,
        LocalDateTime attemptedAt
    ) {}

    // ── Row Mappers ──────────────────────────────────────────────────

    private static final RowMapper<VendorWebhookRecord> WEBHOOK_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp lastAttempt = rs.getTimestamp("last_attempt_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");

        return new VendorWebhookRecord(
            rs.getString("webhook_id"),
            rs.getString("vendor_id"),
            rs.getString("webhook_url"),
            rs.getString("secret_key"),
            rs.getBoolean("is_active"),
            rs.getString("status"),
            rs.getObject("last_status_code") != null ? rs.getInt("last_status_code") : null,
            lastAttempt != null ? lastAttempt.toLocalDateTime() : null,
            rs.getInt("consecutive_fails"),
            created != null ? created.toLocalDateTime() : null,
            updated != null ? updated.toLocalDateTime() : null
        );
    };

    private static final RowMapper<WebhookDeliveryLogRecord> LOG_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp attempted = rs.getTimestamp("attempted_at");

        return new WebhookDeliveryLogRecord(
            rs.getString("delivery_id"),
            rs.getString("vendor_id"),
            rs.getString("target_url"),
            rs.getString("alert_type"),
            rs.getObject("status_code") != null ? rs.getInt("status_code") : null,
            rs.getBoolean("success"),
            rs.getString("error_message"),
            rs.getObject("latency_ms") != null ? rs.getInt("latency_ms") : null,
            attempted != null ? attempted.toLocalDateTime() : null
        );
    };

    // ── Webhook CRUD ─────────────────────────────────────────────────

    public Optional<VendorWebhookRecord> findByVendorId(String vendorId) {
        String sql = """
            SELECT webhook_id, vendor_id, webhook_url, secret_key, is_active, status,
                   last_status_code, last_attempt_at, consecutive_fails, created_at, updated_at
            FROM vendor_webhooks WHERE vendor_id = ?
            """;
        List<VendorWebhookRecord> list = jdbcTemplate.query(sql, WEBHOOK_ROW_MAPPER, vendorId);
        return list.stream().findFirst();
    }

    public VendorWebhookRecord saveOrUpdate(VendorWebhookRecord webhook) {
        String id = (webhook.webhookId() != null && !webhook.webhookId().isBlank())
            ? webhook.webhookId()
            : UUID.randomUUID().toString();

        // Upsert: INSERT OR REPLACE to handle the UNIQUE constraint on vendor_id
        String sql = """
            INSERT INTO vendor_webhooks (webhook_id, vendor_id, webhook_url, secret_key, is_active, status,
                                         last_status_code, last_attempt_at, consecutive_fails, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)
            ON CONFLICT(vendor_id) DO UPDATE SET
                webhook_url = excluded.webhook_url,
                secret_key = excluded.secret_key,
                is_active = excluded.is_active,
                status = 'UNTESTED',
                consecutive_fails = 0,
                updated_at = CURRENT_TIMESTAMP
            """;

        jdbcTemplate.update(sql,
            id,
            webhook.vendorId(),
            webhook.webhookUrl(),
            webhook.secretKey(),
            webhook.isActive() ? 1 : 0,
            webhook.status() != null ? webhook.status() : "UNTESTED",
            webhook.lastStatusCode(),
            webhook.lastAttemptAt() != null ? Timestamp.valueOf(webhook.lastAttemptAt()) : null,
            webhook.consecutiveFails(),
            webhook.createdAt() != null ? Timestamp.valueOf(webhook.createdAt()) : null
        );

        return findByVendorId(webhook.vendorId())
            .orElseThrow(() -> new IllegalStateException("Failed to retrieve saved vendor webhook for vendor: " + webhook.vendorId()));
    }

    public void updateDeliveryStatus(String vendorId, boolean success, int statusCode, int consecutiveFails) {
        String status = success ? "HEALTHY" : "FAILING";
        String sql = """
            UPDATE vendor_webhooks
            SET status = ?, last_status_code = ?, last_attempt_at = CURRENT_TIMESTAMP,
                consecutive_fails = ?, updated_at = CURRENT_TIMESTAMP
            WHERE vendor_id = ?
            """;
        jdbcTemplate.update(sql, status, statusCode, consecutiveFails, vendorId);
    }

    public void deleteByVendorId(String vendorId) {
        jdbcTemplate.update("DELETE FROM vendor_webhooks WHERE vendor_id = ?", vendorId);
    }

    // ── Delivery Logs ────────────────────────────────────────────────

    public void logDelivery(WebhookDeliveryLogRecord log) {
        String id = (log.deliveryId() != null && !log.deliveryId().isBlank())
            ? log.deliveryId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO webhook_delivery_logs (delivery_id, vendor_id, target_url, alert_type,
                                               status_code, success, error_message, latency_ms, attempted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
            """;

        jdbcTemplate.update(sql,
            id,
            log.vendorId(),
            log.targetUrl(),
            log.alertType(),
            log.statusCode(),
            log.success() ? 1 : 0,
            log.errorMessage(),
            log.latencyMs(),
            log.attemptedAt() != null ? Timestamp.valueOf(log.attemptedAt()) : null
        );
    }

    public List<WebhookDeliveryLogRecord> findRecentLogsByVendorId(String vendorId, int limit) {
        String sql = """
            SELECT delivery_id, vendor_id, target_url, alert_type, status_code,
                   success, error_message, latency_ms, attempted_at
            FROM webhook_delivery_logs
            WHERE vendor_id = ?
            ORDER BY attempted_at DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, LOG_ROW_MAPPER, vendorId, limit);
    }
}
