package com.ulpf.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Single-use admin links ({@code admin_links}): invite links that create a regular admin, and reset links
 * that set a new password for one admin. Only the SHA-256 hash of a link's token is stored.
 */
@Repository
public class AdminLinkRepository {

    public enum Kind { INVITE, RESET }

    public record AdminLink(
            String linkId,
            Kind kind,
            String tokenHash,
            String label,
            String targetUserId,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            Instant usedAt
    ) {}

    private final JdbcTemplate jdbcTemplate;

    public AdminLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminLink save(AdminLink link) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    public Optional<AdminLink> findByTokenHash(String tokenHash) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    /** Links that are neither used nor expired at {@code now}. */
    public List<AdminLink> findPending(Instant now) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    /**
     * Marks the link used if, and only if, it is still unused and unexpired. Returns false otherwise, so of two
     * simultaneous uses exactly one wins (a single conditional UPDATE).
     */
    public boolean markUsed(String linkId, Instant now) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    public void delete(String linkId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }
}
