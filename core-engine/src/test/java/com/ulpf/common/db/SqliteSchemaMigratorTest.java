package com.ulpf.common.db;

import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrading a database created before the admin accounts change: the super-admin column is added, and the
 * ADMIN rows left over from the old .env seeder are removed exactly once (first start after the upgrade).
 */
class SqliteSchemaMigratorTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: tests only
    private JdbcTemplate jdbc;
    private UserRepository users;
    private SqliteSchemaMigrator migrator;

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema-before-admin-accounts.sql"); // the "old" database
        users = new UserRepository(jdbc);
        migrator = new SqliteSchemaMigrator(jdbc, encoder);
    }

    private User save(String username, Role role, String password) {
        return users.save(new User(null, username, username, encoder.encode(password), role, null));
    }

    private int notifications(String userId) {
        return TestSqlite.count(jdbc, "SELECT COUNT(*) FROM notifications WHERE user_id = ?", userId);
    }

    @Test
    void addsTheSuperAdminColumnKeepsUsersAndVendorsAndIsSafeToRunTwice() {
        save("plain_user", Role.USER, "user-pass");
        save("some_vendor", Role.VENDOR, "vendor-pass");

        migrator.migrate();
        assertDoesNotThrow(migrator::migrate, "a second run (next restart) must be harmless");

        assertTrue(TestSqlite.columns(jdbc, "users").contains("is_super_admin"));
        assertTrue(users.findByUsername("plain_user").isPresent());
        assertTrue(users.findByUsername("some_vendor").isPresent());
    }

    @Test
    void firstRunRemovesLegacyAdminsAndTheirNotificationsOnly() {
        User oldAdmin = save("old_admin", Role.ADMIN, "old-admin-pass");
        save("older_admin", Role.ADMIN, "older-admin-pass");
        User plainUser = save("plain_user", Role.USER, "user-pass");
        jdbc.update("INSERT INTO notifications (notification_id, user_id, title, message) VALUES ('n1', ?, 't', 'm')", oldAdmin.userId());
        jdbc.update("INSERT INTO notifications (notification_id, user_id, title, message) VALUES ('n2', ?, 't', 'm')", plainUser.userId());

        migrator.migrate();

        assertTrue(users.findByUsername("old_admin").isEmpty(), "legacy admin must be deleted, not demoted");
        assertTrue(users.findByUsername("older_admin").isEmpty(), "every legacy admin must be deleted");
        assertEquals(0, notifications(oldAdmin.userId()), "legacy admin's notifications go with the account");
        assertTrue(users.findByUsername("plain_user").isPresent(), "USER accounts are untouched");
        assertEquals(1, notifications(plainUser.userId()), "other users' notifications are untouched");
    }

    @Test
    void cleanupHappensOnceSoAdminsCreatedLaterSurviveRestarts() {
        migrator.migrate();                                             // first start after the upgrade
        users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
        save("invited_admin", Role.ADMIN, "invited-admin-pass");

        migrator.migrate();                                             // a later restart

        assertTrue(users.findByUsername("root_admin").isPresent(), "the super-admin must survive restarts");
        assertTrue(users.findByUsername("invited_admin").isPresent(), "admins created after the upgrade must survive restarts");
    }

    @Test
    void legacyAdminThatOwnsVendorDataIsLockedNotDeleted() {
        User oldAdmin = save("old_admin", Role.ADMIN, "old-admin-pass");
        VendorRecord vendor = new VendorRepository(jdbc, new CredentialRepository(jdbc))
                .save(new VendorRecord(null, oldAdmin.userId(), "Made Through Hole B", "ACTIVE", null));

        assertDoesNotThrow(migrator::migrate, "startup must not fail because of a legacy reference");

        User kept = users.findByUsername("old_admin").orElseThrow(); // deleting it would break the vendor's rows
        assertEquals(Role.USER, kept.role(), "it must no longer be an admin");
        assertFalse(encoder.matches("old-admin-pass", kept.passwordHash()), "its old password must stop working");
        assertTrue(new VendorRepository(jdbc, new CredentialRepository(jdbc)).findById(vendor.vendorId()).isPresent(),
                "the vendor's data must be untouched");
    }
}
