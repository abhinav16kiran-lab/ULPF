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
 * Repository for vendor organization entity management and lifecycle status transitions
 * (ACTIVE, SUSPENDED, REVOKED).
 */
@Repository
public class VendorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CredentialRepository credentialRepository;

    public VendorRepository(JdbcTemplate jdbcTemplate, CredentialRepository credentialRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialRepository = credentialRepository;
    }

    public record VendorRecord(
        String vendorId,
        String ownerUserId,
        String vendorName,
        String status,
        LocalDateTime createdAt
    ) {}

    private static final RowMapper<VendorRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new VendorRecord(
            rs.getString("vendor_id"),
            rs.getString("owner_user_id"),
            rs.getString("vendor_name"),
            rs.getString("status"),
            createdAt
        );
    };

    public VendorRecord save(VendorRecord vendor) {
        String id = (vendor.vendorId() != null && !vendor.vendorId().isBlank())
            ? vendor.vendorId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO vendors (vendor_id, owner_user_id, vendor_name, status)
            VALUES (?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, vendor.ownerUserId(), vendor.vendorName(), vendor.status());
        credentialRepository.clearCache();

        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to retrieve saved vendor with id: " + id));
    }

    public Optional<VendorRecord> findById(String vendorId) {
        String sql = "SELECT vendor_id, owner_user_id, vendor_name, status, created_at FROM vendors WHERE vendor_id = ?";
        List<VendorRecord> list = jdbcTemplate.query(sql, ROW_MAPPER, vendorId);
        return list.stream().findFirst();
    }

    public Optional<VendorRecord> findByOwnerUserId(String ownerUserId) {
        String sql = "SELECT vendor_id, owner_user_id, vendor_name, status, created_at FROM vendors WHERE owner_user_id = ?";
        List<VendorRecord> list = jdbcTemplate.query(sql, ROW_MAPPER, ownerUserId);
        return list.stream().findFirst();
    }

    public void suspendVendor(String vendorId) {
        String sql = "UPDATE vendors SET status = 'SUSPENDED' WHERE vendor_id = ?";
        jdbcTemplate.update(sql, vendorId);
        credentialRepository.clearCache();
    }

    public void activateVendor(String vendorId) {
        String sql = "UPDATE vendors SET status = 'ACTIVE' WHERE vendor_id = ?";
        jdbcTemplate.update(sql, vendorId);
        credentialRepository.clearCache();
    }

    public void revokeVendor(String vendorId) {
        // Atomic bulk cascade update: vendors -> sources -> credentials
        String sqlRevokeCredentials = """
            UPDATE credentials SET status = 'REVOKED'
            WHERE source_id IN (SELECT source_id FROM sources WHERE vendor_id = ?)
            """;
        jdbcTemplate.update(sqlRevokeCredentials, vendorId);

        String sqlRevokeSources = "UPDATE sources SET status = 'REVOKED' WHERE vendor_id = ?";
        jdbcTemplate.update(sqlRevokeSources, vendorId);

        String sqlRevokeVendor = "UPDATE vendors SET status = 'REVOKED' WHERE vendor_id = ?";
        jdbcTemplate.update(sqlRevokeVendor, vendorId);

        credentialRepository.clearCache();
    }
}
