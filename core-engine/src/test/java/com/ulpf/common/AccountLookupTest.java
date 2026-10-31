package com.ulpf.common;

import com.ulpf.common.AccountLookup.Account;
import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The cached live account view that JwtFilter checks on every request. */
class AccountLookupTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private AccountLookup lookup;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = spy(new UserRepository(jdbc));
        lookup = new AccountLookup(users);
    }

    @Test
    void returnsTheLiveAccountWithRoleFlagAndFingerprint() {
        User root = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));

        Account account = lookup.find(root.userId()).orElseThrow();

        assertEquals("root_admin", account.username());
        assertEquals(Role.ADMIN, account.role());
        assertTrue(account.superAdmin());
        assertEquals(AccountLookup.fingerprint(root.passwordHash()), account.passwordFingerprint());
    }

    @Test
    void repeatedLookupsReadTheDatabaseOnce() {
        User user = users.save(new User(null, "plain_user", "plain", encoder.encode("plain-pass"), Role.USER, null));

        lookup.find(user.userId());
        lookup.find(user.userId());
        lookup.find(user.userId());

        verify(users, times(1)).findById(user.userId());
    }

    @Test
    void anEvictedDeletedAccountIsGone() {
        User user = users.save(new User(null, "plain_user", "plain", encoder.encode("plain-pass"), Role.USER, null));
        lookup.find(user.userId());

        users.deleteUserWithNotifications(user.userId());
        lookup.evict(user.userId());

        assertFalse(lookup.find(user.userId()).isPresent());
    }

    @Test
    void fingerprintIsStableShortAndChangesWithThePassword() {
        String hash = encoder.encode("some-password-12");
        String other = encoder.encode("some-password-12"); // same password, new salt: a new hash

        assertEquals(AccountLookup.fingerprint(hash), AccountLookup.fingerprint(hash));
        assertNotEquals(AccountLookup.fingerprint(hash), AccountLookup.fingerprint(other));
        assertTrue(AccountLookup.fingerprint(hash).length() <= 16, "small enough to ride in every token");
        assertFalse(hash.contains(AccountLookup.fingerprint(hash)), "must not reveal the hash");
    }
}
