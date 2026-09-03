package com.ulpf.common.db;

import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRepositoryTest {

    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE users (
                user_id TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """);

        userRepository = new UserRepository(jdbcTemplate);
    }

    @Test
    void testSaveAndFindByUsername() {
        User user = new User(null, "testuser", "hashedpass", Role.ADMIN, null);
        User saved = userRepository.save(user);

        assertTrue(userRepository.existsByUsername("testuser"));

        Optional<User> retrieved = userRepository.findByUsername("testuser");
        assertTrue(retrieved.isPresent());
        assertEquals("testuser", retrieved.get().username());
        assertEquals(Role.ADMIN, retrieved.get().role());
    }
}
