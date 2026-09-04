package com.ulpf.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for API Key credential validation and resolution against SQLite credentials, sources, and vendors tables.
 * Features an in-memory active credential cache with 5-minute idle eviction for microsecond lookup performance.
 */
@Repository
public class CredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(CredentialRepository.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${credentials.active-cache.idle-timeout-ms:300000}")
    private long idleTimeoutMs = 300000L;

    private final Map<String, CachedCredential> activeCredentialCache = new ConcurrentHashMap<>();

    public CredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record CredentialRecord(
        String credentialId,
        String sourceId,
        String vendorId,
        String keyHash,
        String status,
        LocalDateTime createdAt
    ) {}

    private record CachedCredential(
        CredentialRecord record,
        long lastAccessedTime
    ) {}

    private static final RowMapper<CredentialRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new CredentialRecord(
            rs.getString("credential_id"),
            rs.getString("source_id"),
            rs.getString("vendor_id"),
            rs.getString("key_hash"),
            rs.getString("status"),
            createdAt
        );
    };

    /**
     * Resolves active credential record by key_hash, using in-memory cache first to avoid SQLite disk I/O.
     */
    public Optional<CredentialRecord> findActiveByKeyHash(String keyHash) {
        CachedCredential cached = activeCredentialCache.get(keyHash);
        long now = System.currentTimeMillis();

        if (cached != null) {
            activeCredentialCache.put(keyHash, new CachedCredential(cached.record(), now));
            return Optional.of(cached.record());
        }

        String sql = """
            SELECT c.credential_id, c.source_id, s.vendor_id, c.key_hash, c.status, c.created_at
            FROM credentials c
            JOIN sources s ON c.source_id = s.source_id
            WHERE c.key_hash = ?
              AND c.status = 'ACTIVE'
              AND s.status = 'ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM vendors v
                  WHERE v.vendor_id = s.vendor_id
                    AND v.status = 'ACTIVE'
              )
            """;

        List<CredentialRecord> list = jdbcTemplate.query(sql, ROW_MAPPER, keyHash);
        Optional<CredentialRecord> activeOpt = list.stream().findFirst();

        if (activeOpt.isPresent()) {
            activeCredentialCache.put(keyHash, new CachedCredential(activeOpt.get(), now));
            log.info("Lazy-loaded active API key credential into RAM cache");
        }

        return activeOpt;
    }

    public CredentialRecord save(CredentialRecord cred) {
        // Evict matching entry from RAM first
        if (cred.keyHash() != null) {
            activeCredentialCache.remove(cred.keyHash());
        }

        String id = (cred.credentialId() != null && !cred.credentialId().isBlank())
            ? cred.credentialId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO credentials (credential_id, source_id, key_hash, status)
            VALUES (?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, cred.sourceId(), cred.keyHash(), cred.status());

        return findActiveByKeyHash(cred.keyHash())
            .orElseGet(() -> new CredentialRecord(id, cred.sourceId(), cred.vendorId(), cred.keyHash(), cred.status(), LocalDateTime.now()));
    }

    public void revokeCredential(String credentialId) {
        // Synchronously evict target credential from RAM first to guarantee zero microsecond stale access window
        evictByCredentialId(credentialId);

        String sql = "UPDATE credentials SET status = 'REVOKED' WHERE credential_id = ?";
        jdbcTemplate.update(sql, credentialId);
    }

    public void suspendCredential(String credentialId) {
        // Synchronously evict target credential from RAM first
        evictByCredentialId(credentialId);

        String sql = "UPDATE credentials SET status = 'SUSPENDED' WHERE credential_id = ?";
        jdbcTemplate.update(sql, credentialId);
    }

    public void activateCredentialForSource(String sourceId) {
        evictBySourceId(sourceId);

        String sql = "UPDATE credentials SET status = 'ACTIVE' WHERE source_id = ?";
        jdbcTemplate.update(sql, sourceId);
    }

    public void evictByCredentialId(String credentialId) {
        if (credentialId == null) return;
        activeCredentialCache.values().removeIf(cached -> credentialId.equals(cached.record().credentialId()));
    }

    public void evictBySourceId(String sourceId) {
        if (sourceId == null) return;
        activeCredentialCache.values().removeIf(cached -> sourceId.equals(cached.record().sourceId()));
    }

    public void evictByVendorId(String vendorId) {
        if (vendorId == null) return;
        activeCredentialCache.values().removeIf(cached -> vendorId.equals(cached.record().vendorId()));
    }

    /**
     * Background scheduler running every 60 seconds to evict inactive credential cache entries (> idleTimeoutMs).
     */
    @Scheduled(fixedDelay = 60000)
    public void evictIdleCredentials() {
        long now = System.currentTimeMillis();
        activeCredentialCache.entrySet().removeIf(entry -> {
            boolean isIdle = (now - entry.getValue().lastAccessedTime()) > idleTimeoutMs;
            if (isIdle) {
                log.info("Evicting API key credential cache for keyHash from RAM");
            }
            return isIdle;
        });
    }

    public void clearCache() {
        activeCredentialCache.clear();
    }
}
