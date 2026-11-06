package com.ulpf.controlplane.cli;

import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-shot command behind startup/reset-super-admin.sh: two lines on stdin, exit code 0 or 1. */
class ResetSuperAdminCommandTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private JdbcTemplate jdbc;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
    }

    private int run(String stdin) {
        return ResetSuperAdminCommand.run(jdbc, encoder, new BufferedReader(new StringReader(stdin)), new PrintStream(output, true));
    }

    @Test
    void readsThePasswordTwiceAndResetsIt() {
        users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("lost-old-pass-12"));

        assertEquals(0, run("brand-new-pass-12\nbrand-new-pass-12\n"));

        assertTrue(encoder.matches("brand-new-pass-12", users.findSuperAdmin().orElseThrow().passwordHash()));
        assertTrue(output.toString().contains("root_admin"), "says whose password changed");
    }

    @Test
    void aRefusalExitsWithOneAndChangesNothing() {
        users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("lost-old-pass-12"));

        assertEquals(1, run("brand-new-pass-12\nsomething-else-12\n"));
        assertEquals(1, run(""), "no input");

        assertTrue(encoder.matches("lost-old-pass-12", users.findSuperAdmin().orElseThrow().passwordHash()));
        assertTrue(output.toString().contains("Passwords do not match"));
        assertFalse(output.toString().contains("brand-new-pass-12"), "never echoes a password");
    }
}
