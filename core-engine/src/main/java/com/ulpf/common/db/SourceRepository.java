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
 * Repository for log source entity management and lifecycle status transitions
 * (ACTIVE, SUSPENDED, REVOKED).
 */
@Repository
public class SourceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CredentialRepository credentialRepository;

    public SourceRepository(JdbcTemplate jdbcTemplate, CredentialRepository credentialRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialRepository = credentialRepository;
    }

    public record SourceRecord(
        String sourceId,
        String vendorId,
        String sourceName,
        String sourceType,
        String status,
        LocalDateTime createdAt
    ) {}

    private static final RowMapper<SourceRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new SourceRecord(
            rs.getString("source_id"),
            rs.getString("vendor_id"),
            rs.getString("source_name"),
            rs.getString("source_type"),
            rs.getString("status"),
            createdAt
        );
    };

    public SourceRecord save(SourceRecord source) {
        String id = (source.sourceId() != null && !source.sourceId().isBlank())
            ? source.sourceId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO sources (source_id, vendor_id, source_name, source_type, status)
            VALUES (?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, source.vendorId(), source.sourceName(), source.sourceType(), source.status());
        credentialRepository.clearCache();

        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to retrieve saved source with id: " + id));
    }

    public Optional<SourceRecord> findById(String sourceId) {
        String sql = "SELECT source_id, vendor_id, source_name, source_type, status, created_at FROM sources WHERE source_id = ?";
        List<SourceRecord> list = jdbcTemplate.query(sql, ROW_MAPPER, sourceId);
        return list.stream().findFirst();
    }

    public List<SourceRecord> findByVendorId(String vendorId) {
        String sql = "SELECT source_id, vendor_id, source_name, source_type, status, created_at FROM sources WHERE vendor_id = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, vendorId);
    }

    public void suspendSource(String sourceId) {
        String sql = "UPDATE sources SET status = 'SUSPENDED' WHERE source_id = ?";
        jdbcTemplate.update(sql, sourceId);
        credentialRepository.clearCache();
    }

    public void activateSource(String sourceId) {
        String sql = "UPDATE sources SET status = 'ACTIVE' WHERE source_id = ?";
        jdbcTemplate.update(sql, sourceId);
        credentialRepository.clearCache();
    }

    public void revokeSource(String sourceId) {
        // Atomic bulk cascade update: sources -> credentials
        String sqlRevokeCredentials = "UPDATE credentials SET status = 'REVOKED' WHERE source_id = ?";
        jdbcTemplate.update(sqlRevokeCredentials, sourceId);

        String sqlRevokeSource = "UPDATE sources SET status = 'REVOKED' WHERE source_id = ?";
        jdbcTemplate.update(sqlRevokeSource, sourceId);

        credentialRepository.clearCache();
    }
}
