package com.ulpf.controlplane.service;

import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the startup seeder leaves exactly one ADMIN: the account configured in .env.
 * Real in-memory SQLite (real schema, foreign keys on) and real BCrypt, no mocks.
 */
class AdminUserSeederTest {

    private UserRepository userRepository;
    private OnboardingRepository onboardingRepository;
    private VendorRepository vendorRepository;
    private JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:?foreign_keys=on");
        dataSource.setSuppressClose(true);
        new ResourceDatabasePopulator(new ClassPathResource("sqlite/schema.sql")).execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        userRepository = new UserRepository(jdbcTemplate);
        onboardingRepository = new OnboardingRepository(jdbcTemplate);
        vendorRepository = new VendorRepository(jdbcTemplate, new CredentialRepository(jdbcTemplate));
    }

    /** Runs the seeder as if .env held these credentials. */
    private void seed(String username, String password) {
        new AdminUserSeeder(userRepository, encoder, username, password).run(null);
    }

    private User user(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    private int notificationCount(String userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void usernameChangeRemovesPreviousAdminAndItsNotifications() {
        seed("alice", "alice-pass-1");
        String aliceId = user("alice").userId();
        onboardingRepository.saveNotification(aliceId, "System alert", "Something happened");

        seed("bob", "bob-pass-1");

        assertEquals(Role.ADMIN, user("bob").role());
        assertTrue(userRepository.findByUsername("alice").isEmpty(),
                "replaced admin 'alice' must no longer exist (not even as USER)");
        assertEquals(0, notificationCount(aliceId), "replaced admin's notifications must be deleted with the account");
    }

    @Test
    void exactlyOneAdminRemainsAndItIsTheConfiguredOne() {
        seed("alice", "alice-pass-1");
        seed("bob", "bob-pass-1");

        List<User> admins = userRepository.findUsersByRole(Role.ADMIN);
        assertEquals(1, admins.size(), "exactly one ADMIN row expected");
        assertEquals("bob", admins.get(0).username());
    }

    @Test
    void severalStaleAdminsAreAllRemoved() {
        seed("alice", "alice-pass-1");
        seed("carol", "carol-pass-1");
        seed("bob", "bob-pass-1");

        assertTrue(userRepository.findByUsername("alice").isEmpty(), "stale admin 'alice' must be removed");
        assertTrue(userRepository.findByUsername("carol").isEmpty(), "stale admin 'carol' must be removed");
        assertEquals(Role.ADMIN, user("bob").role());
    }

    @Test
    void staleAdminThatOwnsVendorDataIsLockedNotDeleted() {
        seed("alice", "alice-pass-1");
        String aliceId = user("alice").userId();
        VendorRecord vendor = vendorRepository.save(new VendorRecord(null, aliceId, "Alice Corp", "ACTIVE", null));

        seed("bob", "bob-pass-1");

        User alice = user("alice"); // row must survive: deleting it would mean deleting a real vendor's data
        assertEquals(Role.USER, alice.role(), "stale admin owning vendor data must lose ADMIN");
        assertFalse(encoder.matches("alice-pass-1", alice.passwordHash()),
                "stale admin's old password must stop working (account locked)");
        assertTrue(vendorRepository.findById(vendor.vendorId()).isPresent(), "vendor data must be untouched");
    }

    @Test
    void configuredNameThatExistsAsUserIsPromotedAndOldAdminRemoved() {
        seed("alice", "alice-pass-1");
        userRepository.save(new User(null, "bob", "Bob", encoder.encode("bob-old-pass"), Role.USER, null));

        seed("bob", "bob-pass-1");

        assertEquals(Role.ADMIN, user("bob").role());
        assertTrue(encoder.matches("bob-pass-1", user("bob").passwordHash()), "bob must now use the .env password");
        assertTrue(userRepository.findByUsername("alice").isEmpty(), "previous admin 'alice' must be removed");
    }
}
