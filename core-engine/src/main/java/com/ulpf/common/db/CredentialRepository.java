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
 * Repository for API Key credential validation and resolution against SQLite credentials, sources, and vendors tables.
 */
@Repository
public class CredentialRepository {

    private final JdbcTemplate jdbcTemplate;

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
     * Resolves active credential record by key_hash using JOIN across credentials and sources tables.
     */
    public Optional<CredentialRecord> findActiveByKeyHash(String keyHash) {
        String sql = """
            SELECT c.credential_id, c.source_id, s.vendor_id, c.key_hash, c.status, c.created_at
            FROM credentials c
            JOIN sources s ON c.source_id = s.source_id
            WHERE c.key_hash = ? AND c.status = 'ACTIVE' AND s.status = 'ACTIVE'
            """;

        List<CredentialRecord> list = jdbcTemplate.query(sql, ROW_MAPPER, keyHash);
        return list.stream().findFirst();
    }

    public CredentialRecord save(CredentialRecord cred) {
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
        String sql = "UPDATE credentials SET status = 'REVOKED' WHERE credential_id = ?";
        jdbcTemplate.update(sql, credentialId);
    }
}
