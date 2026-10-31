package com.ulpf.controlplane.service;

import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.SetupService.SetupRequest;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time setup of the super-admin, guarded by the setup credentials from .env. */
class SetupServiceTest {

    private static final String SETUP_USER = "setup-admin";     // what .env holds
    private static final String SETUP_PASS = "Xk3pQ9vR2m";       // generated 10-character setup password
    private static final String NEW_USER = "root_admin";
    private static final String NEW_PASS = "root-admin-pass-12";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private SetupService setup;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
        setup = new SetupService(users, encoder, SETUP_USER, SETUP_PASS);
    }

    private static SetupRequest request(String setupUser, String setupPass, String user, String pass, String confirm) {
        return new SetupRequest(setupUser, setupPass, user, pass, confirm);
    }

    private static SetupRequest valid() {
        return request(SETUP_USER, SETUP_PASS, NEW_USER, NEW_PASS, NEW_PASS);
    }

    private static void assertRefused(HttpStatus expected, Executable action) {
        AccountActionException e = assertThrows(AccountActionException.class, action);
        assertEquals(expected, e.status(), e.getMessage());
    }

    private void assertNoAdmin() {
        assertTrue(users.findUsersByRole(Role.ADMIN).isEmpty(), "nothing may be created");
    }

    @Test
    void setupIsRequiredUntilTheSuperAdminExists() {
        assertTrue(setup.isSetupRequired());
        setup.completeSetup(valid());
        assertFalse(setup.isSetupRequired());
    }

    @Test
    void wrongSetupCredentialsAreRejected() {
        assertRefused(HttpStatus.UNAUTHORIZED, () -> setup.completeSetup(request(SETUP_USER, "wrong-pass", NEW_USER, NEW_PASS, NEW_PASS)));
        assertRefused(HttpStatus.UNAUTHORIZED, () -> setup.completeSetup(request("someone", SETUP_PASS, NEW_USER, NEW_PASS, NEW_PASS)));
        assertNoAdmin();
    }

    @Test
    void blankSetupCredentialsInEnvDisableSetup() {
        SetupService unconfigured = new SetupService(users, encoder, "", "");
        assertRefused(HttpStatus.CONFLICT, () -> unconfigured.completeSetup(request("", "", NEW_USER, NEW_PASS, NEW_PASS)));
        assertNoAdmin();
    }

    @Test
    void invalidNewCredentialsAreRejected() {
        assertRefused(HttpStatus.BAD_REQUEST, () -> setup.completeSetup(request(SETUP_USER, SETUP_PASS, NEW_USER, NEW_PASS, "different-pass-12")));
        assertRefused(HttpStatus.BAD_REQUEST, () -> setup.completeSetup(request(SETUP_USER, SETUP_PASS, NEW_USER, "short-pass1", "short-pass1")));
        assertRefused(HttpStatus.BAD_REQUEST, () -> setup.completeSetup(request(SETUP_USER, SETUP_PASS, "bad name!", NEW_PASS, NEW_PASS)));
        assertNoAdmin();
    }

    @Test
    void successfulSetupCreatesTheSuperAdminFromTheNewCredentials() {
        setup.completeSetup(valid());

        User superAdmin = users.findSuperAdmin().orElseThrow();
        assertEquals(NEW_USER, superAdmin.username());
        assertEquals(Role.ADMIN, superAdmin.role(), "the super-admin signs in as ADMIN");
        assertTrue(users.isSuperAdmin(superAdmin.userId()));
        assertTrue(encoder.matches(NEW_PASS, superAdmin.passwordHash()));
        assertTrue(users.findByUsername(SETUP_USER).isEmpty(), "the .env setup credentials are not an account");
    }

    @Test
    void setupWorksOnlyOnce() {
        setup.completeSetup(valid());
        assertRefused(HttpStatus.CONFLICT, () -> setup.completeSetup(request(SETUP_USER, SETUP_PASS, "second_root", NEW_PASS, NEW_PASS)));
        assertEquals(1, users.findUsersByRole(Role.ADMIN).size());
    }

    @Test
    void changingEnvAfterSetupChangesNothing() {
        setup.completeSetup(valid());
        String hashBefore = users.findSuperAdmin().orElseThrow().passwordHash();

        SetupService afterRestart = new SetupService(users, encoder, "other-setup", "Other-Pass-1");

        assertFalse(afterRestart.isSetupRequired());
        assertRefused(HttpStatus.CONFLICT, () -> afterRestart.completeSetup(request("other-setup", "Other-Pass-1", "other_root", NEW_PASS, NEW_PASS)));
        User superAdmin = users.findSuperAdmin().orElseThrow();
        assertEquals(NEW_USER, superAdmin.username());
        assertEquals(hashBefore, superAdmin.passwordHash());
    }

    @Test
    void theDatabaseAllowsOnlyOneSuperAdmin() {
        users.createSuperAdmin("first_root", "Super-admin", encoder.encode(NEW_PASS));
        assertThrows(DataIntegrityViolationException.class,
                () -> users.createSuperAdmin("second_root", "Super-admin", encoder.encode(NEW_PASS)),
                "two setups racing must not both succeed");
    }
}
