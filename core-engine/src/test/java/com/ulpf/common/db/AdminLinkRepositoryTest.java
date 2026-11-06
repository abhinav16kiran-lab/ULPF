package com.ulpf.common.db;

import com.ulpf.common.db.AdminLinkRepository.AdminLink;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage of single-use admin links; the "use once" guarantee is a single conditional UPDATE. */
class AdminLinkRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-31T10:00:00.123Z");
    private static final Duration LIFETIME = Duration.ofHours(12);

    private AdminLinkRepository links;
    private User root;

    @BeforeEach
    void setUp() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        JdbcTemplate jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        links = new AdminLinkRepository(jdbc);
        root = new UserRepository(jdbc).createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
    }

    private AdminLink invite(String tokenHash, Instant createdAt) {
        return links.save(new AdminLink(null, Kind.INVITE, tokenHash, "for Ashitha", null, root.userId(),
                createdAt, createdAt.plus(LIFETIME), null));
    }

    @Test
    void aSavedLinkIsFoundByItsHashWithEveryField() {
        AdminLink saved = invite("hash-1", T0);

        AdminLink found = links.findByTokenHash("hash-1").orElseThrow();

        assertNotNull(saved.linkId(), "an id is assigned");
        assertEquals(saved, found);
        assertEquals(T0, found.createdAt(), "times keep millisecond precision");
        assertEquals(T0.plus(LIFETIME), found.expiresAt());
        assertTrue(links.findByTokenHash("no-such-hash").isEmpty());
    }

    @Test
    void aLinkCanBeMarkedUsedOnlyOnce() {
        AdminLink link = invite("hash-1", T0);

        assertTrue(links.markUsed(link.linkId(), T0.plusSeconds(60)));
        assertFalse(links.markUsed(link.linkId(), T0.plusSeconds(61)), "a second use must lose");
        assertEquals(T0.plusSeconds(60), links.findByTokenHash("hash-1").orElseThrow().usedAt());
    }

    @Test
    void anExpiredLinkCannotBeMarkedUsed() {
        AdminLink link = invite("hash-1", T0);

        assertFalse(links.markUsed(link.linkId(), T0.plus(LIFETIME)), "valid strictly before expiresAt");
        assertNull(links.findByTokenHash("hash-1").orElseThrow().usedAt());
    }

    @Test
    void pendingListsOnlyUnusedUnexpiredLinksOldestFirst() {
        AdminLink used = invite("used", T0);
        links.markUsed(used.linkId(), T0.plusSeconds(1));
        invite("expired", T0.minus(LIFETIME));
        AdminLink newer = invite("newer", T0.plusSeconds(20));
        AdminLink older = invite("older", T0.plusSeconds(10));

        List<AdminLink> pending = links.findPending(T0.plusSeconds(30));

        assertEquals(List.of(older.linkId(), newer.linkId()), pending.stream().map(AdminLink::linkId).toList());
    }

    @Test
    void deleteRemovesTheLink() {
        AdminLink link = invite("hash-1", T0);

        links.delete(link.linkId());

        assertTrue(links.findByTokenHash("hash-1").isEmpty());
    }
}
