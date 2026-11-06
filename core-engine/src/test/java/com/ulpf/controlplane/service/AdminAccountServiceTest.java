package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.AdminAccountService.AdminSummary;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The super-admin lists admins and revokes regular admins; revoking deletes the account. */
class AdminAccountServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private JdbcTemplate jdbc;
    private UserRepository users;
    private AdminAccountService accounts;
    private User superAdmin;
    private User helper;

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
        accounts = new AdminAccountService(users, new AccountLookup(users));
        superAdmin = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
        helper = users.save(new User(null, "helper", "helper", encoder.encode("helper-pass-1234"), Role.ADMIN, null));
    }

    private static void assertRefused(HttpStatus expected, Executable action) {
        AccountActionException e = assertThrows(AccountActionException.class, action);
        assertEquals(expected, e.status(), e.getMessage());
    }

    @Test
    void listShowsEveryAdminWithTheSuperAdminFlag() {
        List<AdminSummary> admins = accounts.listAdmins();
        assertEquals(2, admins.size());
        assertEquals("root_admin", admins.get(0).username(), "super-admin first");
        assertTrue(admins.get(0).superAdmin());
        assertFalse(admins.get(1).superAdmin());
    }

    @Test
    void revokeDeletesTheAccountAndItsNotifications() {
        jdbc.update("INSERT INTO notifications (notification_id, user_id, title, message) VALUES ('n1', ?, 't', 'm')", helper.userId());

        accounts.revoke(superAdmin.userId(), helper.userId());

        assertTrue(users.findById(helper.userId()).isEmpty(), "a revoked admin leaves no account behind");
        assertEquals(0, TestSqlite.count(jdbc, "SELECT COUNT(*) FROM notifications WHERE user_id = ?", helper.userId()));
    }

    @Test
    void theSuperAdminCannotBeRevokedAndOnlyTheSuperAdminCanRevoke() {
        User second = users.save(new User(null, "second", "second", encoder.encode("second-pass-1234"), Role.ADMIN, null));

        assertRefused(HttpStatus.CONFLICT, () -> accounts.revoke(superAdmin.userId(), superAdmin.userId()));
        assertRefused(HttpStatus.FORBIDDEN, () -> accounts.revoke(helper.userId(), superAdmin.userId()));
        assertRefused(HttpStatus.FORBIDDEN, () -> accounts.revoke(helper.userId(), second.userId()));
        assertTrue(users.findById(second.userId()).isPresent());
    }

    @Test
    void revokeIsRefusedWhenVendorDataPointsAtTheAdmin() {
        VendorRepository vendors = new VendorRepository(jdbc, new CredentialRepository(jdbc));
        VendorRecord vendor = vendors.save(new VendorRecord(null, helper.userId(), "Legacy Corp", "ACTIVE", null));

        assertRefused(HttpStatus.CONFLICT, () -> accounts.revoke(superAdmin.userId(), helper.userId()));

        assertTrue(users.findById(helper.userId()).isPresent());
        assertTrue(vendors.findById(vendor.vendorId()).isPresent(), "vendor data is never destroyed");
    }

    @Test
    void revokingSomethingThatIsNotAnAdminIsNotFound() {
        User plain = users.save(new User(null, "plain_user", "plain", encoder.encode("plain-pass"), Role.USER, null));
        assertRefused(HttpStatus.NOT_FOUND, () -> accounts.revoke(superAdmin.userId(), plain.userId()));
        assertRefused(HttpStatus.NOT_FOUND, () -> accounts.revoke(superAdmin.userId(), "no-such-id"));
        assertTrue(users.findById(plain.userId()).isPresent());
    }
}
