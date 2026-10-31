package com.ulpf.common.db;

import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Centralized repository for user authentication and user metadata in SQLite users table.
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new User(
            rs.getString("user_id"),
            rs.getString("username"),
            rs.getString("name"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            createdAt
        );
    };

    public User save(User user) {
        String id = (user.userId() != null && !user.userId().isBlank())
            ? user.userId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO users (user_id, username, name, password_hash, role)
            VALUES (?, ?, ?, ?, ?)
            """;

        insert(sql, id, user.username(), user.name(), user.passwordHash(), user.role().name());

        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to retrieve saved user with id: " + id));
    }

    public Optional<User> findById(String userId) {
        String sql = "SELECT user_id, username, name, password_hash, role, created_at FROM users WHERE user_id = ?";
        List<User> users = jdbcTemplate.query(sql, ROW_MAPPER, userId);
        return users.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        String sql = "SELECT user_id, username, name, password_hash, role, created_at FROM users WHERE username = ?";
        List<User> users = jdbcTemplate.query(sql, ROW_MAPPER, username);
        return users.stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, username);
        return count != null && count > 0;
    }

    public List<User> findUsersByRole(Role role) {
        String sql = "SELECT user_id, username, name, password_hash, role, created_at FROM users WHERE role = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, role.name());
    }

    public void updatePasswordAndRole(String userId, String passwordHash, Role role) {
        String sql = "UPDATE users SET password_hash = ?, role = ? WHERE user_id = ?";
        jdbcTemplate.update(sql, passwordHash, role.name(), userId);
    }

    public void updateUserRole(String userId, Role role) {
        String sql = "UPDATE users SET role = ? WHERE user_id = ?";
        jdbcTemplate.update(sql, role.name(), userId);
    }

    // ---- Admin accounts (docs/plans/2026-10-31-admin-accounts-setup-and-invites.md) ----

    /** True once the one-time setup has created the super-admin. */
    public boolean superAdminExists() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE is_super_admin = 1)", Boolean.class));
    }

    public Optional<User> findSuperAdmin() {
        String sql = "SELECT user_id, username, name, password_hash, role, created_at FROM users WHERE is_super_admin = 1";
        return jdbcTemplate.query(sql, ROW_MAPPER).stream().findFirst();
    }

    public boolean isSuperAdmin(String userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ? AND is_super_admin = 1)", Boolean.class, userId));
    }

    /**
     * Inserts the super-admin (role ADMIN, flag set). The partial unique index on the flag makes a
     * second super-admin impossible, so a racing second call fails with a {@link DuplicateKeyException}
     * (as does a taken username).
     */
    public User createSuperAdmin(String username, String name, String passwordHash) {
        String id = UUID.randomUUID().toString();
        String sql = """
            INSERT INTO users (user_id, username, name, password_hash, role, is_super_admin)
            VALUES (?, ?, ?, ?, 'ADMIN', 1)
            """;
        insert(sql, id, username, name, passwordHash);
        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to retrieve saved super-admin with id: " + id));
    }

    public void updatePassword(String userId, String passwordHash) {
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE user_id = ?", passwordHash, userId);
    }

    /**
     * Deletes the user with everything that only makes sense while the user exists: its notifications and the
     * admin links it made or that target it. One transaction, so a failure (for example a vendor still owning
     * the user, see {@link #isReferencedByVendorData}) leaves everything as it was.
     */
    @Transactional
    public void deleteUserWithNotifications(String userId) {
        jdbcTemplate.update("DELETE FROM admin_links WHERE target_user_id = ? OR created_by = ?", userId, userId);
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    /** True if a vendor or an onboarding request points at this user (deleting it would break those rows). */
    public boolean isReferencedByVendorData(String userId) {
        String sql = """
            SELECT EXISTS (SELECT 1 FROM vendors WHERE owner_user_id = ?)
                OR EXISTS (SELECT 1 FROM onboarding_requests WHERE user_id = ?)
            """;
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, userId, userId));
    }

    /**
     * Runs an INSERT, reporting a UNIQUE violation (taken username, second super-admin) as a
     * {@link DuplicateKeyException}. Spring has no SQLite error codes, so it would otherwise surface as an
     * uncategorized SQL error that callers cannot tell apart from a real failure.
     */
    private void insert(String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (UncategorizedSQLException e) {
            if (e.getSQLException() instanceof SQLiteException sqlite
                    && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
                throw new DuplicateKeyException(sqlite.getMessage(), e);
            }
            throw e;
        }
    }
}
