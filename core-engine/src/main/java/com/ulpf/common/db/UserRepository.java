package com.ulpf.common.db;

import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

        jdbcTemplate.update(sql, id, user.username(), user.name(), user.passwordHash(), user.role().name());

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
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    public Optional<User> findSuperAdmin() {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    public boolean isSuperAdmin(String userId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    /**
     * Inserts the super-admin (role ADMIN, flag set). The partial unique index on the flag makes a
     * second super-admin impossible, so a racing second call fails with a DataIntegrityViolationException.
     */
    public User createSuperAdmin(String username, String name, String passwordHash) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    public void updatePassword(String userId, String passwordHash) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    /** Deletes the user's notifications, then the user. */
    public void deleteUserWithNotifications(String userId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }

    /** True if a vendor or an onboarding request points at this user (deleting it would break those rows). */
    public boolean isReferencedByVendorData(String userId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 2)");
    }
}
