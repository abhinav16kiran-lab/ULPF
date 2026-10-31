package com.ulpf.common.db;

import com.ulpf.common.db.AdminLinkRepository.AdminLink;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRepositoryTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: tests only
    private JdbcTemplate jdbc;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        userRepository = new UserRepository(jdbc);
    }

    private User save(String username, Role role) {
        return userRepository.save(new User(null, username, username, encoder.encode(username + "-pass"), role, null));
    }

    private User createRoot() {
        return userRepository.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
    }

    @Test
    void testSaveAndFindByUsername() {
        User user = new User(null, "testuser", "Test User", "hashedpass", Role.ADMIN, null);
        User saved = userRepository.save(user);

        assertTrue(userRepository.existsByUsername("testuser"));

        Optional<User> retrieved = userRepository.findByUsername("testuser");
        assertTrue(retrieved.isPresent());
        assertEquals("testuser", retrieved.get().username());
        assertEquals("Test User", retrieved.get().name());
        assertEquals(Role.ADMIN, retrieved.get().role());
    }

    @Test
    void theSuperAdminIsTheOneFlaggedAdmin() {
        User helper = save("helper", Role.ADMIN);
        assertFalse(userRepository.superAdminExists());
        assertTrue(userRepository.findSuperAdmin().isEmpty());

        User root = createRoot();

        assertEquals(Role.ADMIN, root.role());
        assertTrue(userRepository.superAdminExists());
        assertEquals(root, userRepository.findSuperAdmin().orElseThrow());
        assertTrue(userRepository.isSuperAdmin(root.userId()));
        assertFalse(userRepository.isSuperAdmin(helper.userId()), "a regular admin is not the super-admin");
        assertFalse(userRepository.isSuperAdmin("no-such-user"));
    }

    @Test
    void theDatabaseAllowsOnlyOneSuperAdmin() {
        createRoot();

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.createSuperAdmin("second_root", "Super-admin", encoder.encode("second-root-pass-12")));
        assertTrue(userRepository.findByUsername("second_root").isEmpty());
    }

    @Test
    void updatePasswordChangesOnlyThatAccount() {
        User helper = save("helper", Role.ADMIN);
        User other = save("other", Role.ADMIN);

        userRepository.updatePassword(helper.userId(), "new-hash");

        assertEquals("new-hash", userRepository.findById(helper.userId()).orElseThrow().passwordHash());
        assertEquals(other.passwordHash(), userRepository.findById(other.userId()).orElseThrow().passwordHash());
    }

    @Test
    void deletingAUserAlsoRemovesItsNotificationsAndTheLinksAimedAtIt() {
        User root = createRoot();
        User helper = save("helper", Role.ADMIN);
        User other = save("other", Role.USER);
        jdbc.update("INSERT INTO notifications (notification_id, user_id, title, message) VALUES ('n1', ?, 't', 'm')", helper.userId());
        jdbc.update("INSERT INTO notifications (notification_id, user_id, title, message) VALUES ('n2', ?, 't', 'm')", other.userId());
        AdminLinkRepository links = new AdminLinkRepository(jdbc);
        Instant now = Instant.parse("2026-10-31T10:00:00Z");
        links.save(new AdminLink(null, Kind.RESET, "reset-hash", null, helper.userId(), root.userId(), now, now.plusSeconds(60), null));

        userRepository.deleteUserWithNotifications(helper.userId());

        assertTrue(userRepository.findById(helper.userId()).isEmpty());
        assertEquals(0, TestSqlite.count(jdbc, "SELECT COUNT(*) FROM notifications WHERE user_id = ?", helper.userId()));
        assertTrue(links.findByTokenHash("reset-hash").isEmpty(), "a reset link for a deleted account is useless");
        assertEquals(1, TestSqlite.count(jdbc, "SELECT COUNT(*) FROM notifications WHERE user_id = ?", other.userId()));
    }

    @Test
    void vendorDataReferencesAreDetected() {
        User plain = save("plain_user", Role.USER);
        User owner = save("vendor_owner", Role.VENDOR);
        User requester = save("requester", Role.USER);
        new VendorRepository(jdbc, new CredentialRepository(jdbc))
                .save(new VendorRecord(null, owner.userId(), "Acme", "ACTIVE", null));
        jdbc.update("INSERT INTO onboarding_requests (request_id, user_id, request_type, status) VALUES ('r1', ?, 'NEW_VENDOR', 'SUBMITTED')",
                requester.userId());

        assertFalse(userRepository.isReferencedByVendorData(plain.userId()));
        assertTrue(userRepository.isReferencedByVendorData(owner.userId()), "owns a vendor");
        assertTrue(userRepository.isReferencedByVendorData(requester.userId()), "submitted an onboarding request");
    }
}
