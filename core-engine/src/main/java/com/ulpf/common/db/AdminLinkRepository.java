package com.ulpf.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-use admin links ({@code admin_links}): invite links that create a regular admin, and reset links
 * that set a new password for one admin. Only the SHA-256 hash of a link's token is stored.
 *
 * <p>Times are stored as epoch milliseconds (UTC): comparing two integers is exact and needs no date parsing,
 * and "is this link still valid" is a plain {@code expires_at > now} in SQL.
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

    private static final String COLUMNS =
            "link_id, kind, token_hash, label, target_user_id, created_by, created_at, expires_at, used_at";

    private static final RowMapper<AdminLink> ROW_MAPPER = (rs, rowNum) -> new AdminLink(
            rs.getString("link_id"),
            Kind.valueOf(rs.getString("kind")),
            rs.getString("token_hash"),
            rs.getString("label"),
            rs.getString("target_user_id"),
            rs.getString("created_by"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            Instant.ofEpochMilli(rs.getLong("expires_at")),
            usedAt(rs.getLong("used_at"), rs.wasNull())
    );

    private final JdbcTemplate jdbcTemplate;

    public AdminLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Inserts the link; assigns an id when it has none. Returns the stored link. */
    public AdminLink save(AdminLink link) {
        String id = (link.linkId() != null && !link.linkId().isBlank()) ? link.linkId() : UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO admin_links (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, link.kind().name(), link.tokenHash(), link.label(), link.targetUserId(), link.createdBy(),
                link.createdAt().toEpochMilli(), link.expiresAt().toEpochMilli(), toMillis(link.usedAt()));
        return new AdminLink(id, link.kind(), link.tokenHash(), link.label(), link.targetUserId(), link.createdBy(),
                truncate(link.createdAt()), truncate(link.expiresAt()), truncate(link.usedAt()));
    }

    public Optional<AdminLink> findByTokenHash(String tokenHash) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM admin_links WHERE token_hash = ?", ROW_MAPPER, tokenHash)
                .stream().findFirst();
    }

    /** Links that are neither used nor expired at {@code now}, oldest first. */
    public List<AdminLink> findPending(Instant now) {
        String sql = "SELECT " + COLUMNS + " FROM admin_links WHERE used_at IS NULL AND expires_at > ? ORDER BY created_at";
        return jdbcTemplate.query(sql, ROW_MAPPER, now.toEpochMilli());
    }

    /**
     * Marks the link used if, and only if, it is still unused and unexpired. Returns false otherwise, so of two
     * simultaneous uses exactly one wins (a single conditional UPDATE).
     */
    public boolean markUsed(String linkId, Instant now) {
        String sql = "UPDATE admin_links SET used_at = ? WHERE link_id = ? AND used_at IS NULL AND expires_at > ?";
        return jdbcTemplate.update(sql, now.toEpochMilli(), linkId, now.toEpochMilli()) == 1;
    }

    public void delete(String linkId) {
        jdbcTemplate.update("DELETE FROM admin_links WHERE link_id = ?", linkId);
    }

    private static Long toMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant usedAt(long millis, boolean wasNull) {
        return wasNull ? null : Instant.ofEpochMilli(millis);
    }

    /** What survives a round trip through the database: millisecond precision. */
    private static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
