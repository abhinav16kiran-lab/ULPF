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
}
