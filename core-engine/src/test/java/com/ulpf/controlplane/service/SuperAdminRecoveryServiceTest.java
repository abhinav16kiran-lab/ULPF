package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-side reset of a lost super-admin password (run only by startup/reset-super-admin.sh). */
class SuperAdminRecoveryServiceTest {

    private static final String NEW_PASS = "brand-new-pass-12";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private SuperAdminRecoveryService recovery;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
        recovery = new SuperAdminRecoveryService(users, encoder);
    }

    private static void assertRefused(HttpStatus expected, Executable action) {
        AccountActionException e = assertThrows(AccountActionException.class, action);
        assertEquals(expected, e.status(), e.getMessage());
    }

    @Test
    void resetSetsTheNewPasswordAndChangesTheFingerprint() {
        User root = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("lost-old-pass-12"));
        String oldFingerprint = AccountLookup.fingerprint(root.passwordHash());

        recovery.resetPassword(NEW_PASS, NEW_PASS);

        String newHash = users.findSuperAdmin().orElseThrow().passwordHash();
        assertTrue(encoder.matches(NEW_PASS, newHash));
        assertNotEquals(oldFingerprint, AccountLookup.fingerprint(newHash), "old login tokens must stop matching");
    }

    @Test
    void resetTouchesNoOtherAccount() {
        users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("lost-old-pass-12"));
        User helper = users.save(new User(null, "helper", "helper", encoder.encode("helper-pass-1234"), Role.ADMIN, null));

        recovery.resetPassword(NEW_PASS, NEW_PASS);

        assertEquals(helper.passwordHash(), users.findById(helper.userId()).orElseThrow().passwordHash());
    }

    @Test
    void invalidPasswordsAreRejectedAndNothingChanges() {
        User root = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("lost-old-pass-12"));

        assertRefused(HttpStatus.BAD_REQUEST, () -> recovery.resetPassword("short-pass1", "short-pass1"));
        assertRefused(HttpStatus.BAD_REQUEST, () -> recovery.resetPassword(NEW_PASS, "different-pass-12"));
        assertEquals(root.passwordHash(), users.findSuperAdmin().orElseThrow().passwordHash());
    }

    @Test
    void resetBeforeSetupIsRefused() {
        assertRefused(HttpStatus.CONFLICT, () -> recovery.resetPassword(NEW_PASS, NEW_PASS));
    }
}
