package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.AdminLinkRepository;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.AdminLinkService.CreatedLink;
import com.ulpf.controlplane.service.AdminLinkService.LinkInfo;
import com.ulpf.controlplane.service.AdminLinkService.LinkUse;
import com.ulpf.testsupport.MutableClock;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invite links (create a regular admin) and reset links (new password for one admin): 12 h, single use. */
class AdminLinkServiceTest {

    private static final String PASS = "new-admin-pass-12";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));
    private JdbcTemplate jdbc;
    private UserRepository users;
    private AdminLinkService links;
    private User superAdmin;
    private User helper; // an existing regular admin

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
        links = new AdminLinkService(new AdminLinkRepository(jdbc), users, encoder, new AccountLookup(users), clock);
        superAdmin = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
        helper = users.save(new User(null, "helper", "helper", encoder.encode("helper-pass-1234"), Role.ADMIN, null));
    }

    private static void assertRefused(HttpStatus expected, Executable action) {
        AccountActionException e = assertThrows(AccountActionException.class, action);
        assertEquals(expected, e.status(), e.getMessage());
    }

    private int linksWithHash(String hash) {
        return TestSqlite.count(jdbc, "SELECT COUNT(*) FROM admin_links WHERE token_hash = ?", hash);
    }

    @Test
    void inviteTokenIsReturnedOnceAndStoredOnlyAsAHash() {
        CreatedLink invite = links.createInvite(superAdmin.userId(), "Ashitha");

        assertTrue(invite.token().length() >= 43, "at least 256 random bits, base64url");
        assertEquals(1, linksWithHash(OnboardingService.hashSha256(invite.token())), "the SHA-256 hash is stored");
        assertEquals(0, linksWithHash(invite.token()), "the raw token is never stored");
        assertEquals(clock.instant().plus(Duration.ofHours(12)), invite.expiresAt());
    }

    @Test
    void describeShowsKindAndLabel() {
        LinkInfo info = links.describe(links.createInvite(superAdmin.userId(), "Ashitha").token());
        assertEquals(Kind.INVITE, info.kind());
        assertEquals("Ashitha", info.label());
    }

    @Test
    void usingAnInviteCreatesARegularAdminExactlyOnce() {
        String token = links.createInvite(superAdmin.userId(), null).token();

        links.use(token, new LinkUse("new_admin", PASS, PASS));

        User created = users.findByUsername("new_admin").orElseThrow();
        assertEquals(Role.ADMIN, created.role());
        assertFalse(users.isSuperAdmin(created.userId()), "invited admins are regular admins");
        assertTrue(encoder.matches(PASS, created.passwordHash()));
        assertRefused(HttpStatus.GONE, () -> links.use(token, new LinkUse("second_admin", PASS, PASS)));
        assertTrue(users.findByUsername("second_admin").isEmpty());
    }

    @Test
    void expiredCancelledAndUnknownLinksAreRejected() {
        CreatedLink cancelled = links.createInvite(superAdmin.userId(), null);
        links.cancel(cancelled.linkId());
        assertRefused(HttpStatus.GONE, () -> links.use(cancelled.token(), new LinkUse("b_admin", PASS, PASS)));
        assertRefused(HttpStatus.GONE, () -> links.use("no-such-token", new LinkUse("c_admin", PASS, PASS)));

        String expired = links.createInvite(superAdmin.userId(), null).token();
        clock.advance(Duration.ofHours(12).plusSeconds(1));
        assertRefused(HttpStatus.GONE, () -> links.use(expired, new LinkUse("a_admin", PASS, PASS)));
        assertRefused(HttpStatus.GONE, () -> links.describe(expired));
        assertEquals(2, users.findUsersByRole(Role.ADMIN).size(), "no admin may be created");
    }

    @Test
    void aRejectedFormDoesNotUseUpTheLink() {
        String token = links.createInvite(superAdmin.userId(), null).token();

        assertRefused(HttpStatus.CONFLICT, () -> links.use(token, new LinkUse("helper", PASS, PASS)));            // taken
        assertRefused(HttpStatus.BAD_REQUEST, () -> links.use(token, new LinkUse("new_admin", PASS, "other-pass-123")));
        assertRefused(HttpStatus.BAD_REQUEST, () -> links.use(token, new LinkUse("new_admin", "short-pass1", "short-pass1")));
        assertTrue(encoder.matches("helper-pass-1234", users.findByUsername("helper").orElseThrow().passwordHash()),
                "an invite must never change an existing account's password");

        links.use(token, new LinkUse("new_admin", PASS, PASS));
        assertTrue(users.findByUsername("new_admin").isPresent(), "the link still works after a rejected attempt");
    }

    @Test
    void aResetLinkChangesOnlyItsTargetsPasswordOnce() {
        String superHashBefore = users.findSuperAdmin().orElseThrow().passwordHash();
        CreatedLink reset = links.createReset(superAdmin.userId(), helper.userId());
        LinkInfo info = links.describe(reset.token());
        assertEquals(Kind.RESET, info.kind());
        assertEquals("helper", info.targetUsername());

        links.use(reset.token(), new LinkUse("ignored_name", PASS, PASS));

        assertTrue(encoder.matches(PASS, users.findByUsername("helper").orElseThrow().passwordHash()));
        assertEquals(superHashBefore, users.findSuperAdmin().orElseThrow().passwordHash());
        assertTrue(users.findByUsername("ignored_name").isEmpty(), "a reset link never creates an account");
        assertRefused(HttpStatus.GONE, () -> links.use(reset.token(), new LinkUse(null, "another-pass-12", "another-pass-12")));
    }

    @Test
    void aResetLinkCannotTargetTheSuperAdminOrANonAdmin() {
        User plain = users.save(new User(null, "plain_user", "plain", encoder.encode("plain-pass"), Role.USER, null));
        assertRefused(HttpStatus.CONFLICT, () -> links.createReset(superAdmin.userId(), superAdmin.userId()));
        assertRefused(HttpStatus.NOT_FOUND, () -> links.createReset(superAdmin.userId(), plain.userId()));
    }

    @Test
    void onlyTheSuperAdminCanCreateLinks() {
        assertRefused(HttpStatus.FORBIDDEN, () -> links.createInvite(helper.userId(), null));
        assertRefused(HttpStatus.FORBIDDEN, () -> links.createReset(helper.userId(), helper.userId()));
    }
}
