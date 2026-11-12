package com.ulpf.controlplane.service;

import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.ApiKeyService.IssuedApiKey;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyServiceTest {

    private JdbcTemplate jdbc;
    private UserRepository users;
    private VendorRepository vendors;
    private SourceRepository sources;
    private CredentialRepository credentials;
    private OnboardingRepository onboarding;
    private ApiKeyService apiKeyService;

    private User owner;
    private VendorRecord ownerVendor;
    private SourceRecord activeSource;

    @BeforeEach
    void setUp() {
        jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        users = new UserRepository(jdbc);
        credentials = new CredentialRepository(jdbc);
        vendors = new VendorRepository(jdbc, credentials);
        sources = new SourceRepository(jdbc, credentials);
        onboarding = new OnboardingRepository(jdbc);
        apiKeyService = new ApiKeyService(users, vendors, sources, credentials, onboarding);

        owner = users.save(new User(null, "owner", "Owner", "hash", Role.VENDOR, null));
        ownerVendor = vendors.save(new VendorRecord(null, owner.userId(), "Owner Corp", "ACTIVE", null));
        activeSource = sources.save(new SourceRecord(null, ownerVendor.vendorId(), "Firewall Alpha", "FIREWALL", "ACTIVE", null));
    }

    private SourceRecord sourceWithStatus(String status) {
        return sources.save(new SourceRecord(null, ownerVendor.vendorId(), "Source " + status, "FIREWALL", status, null));
    }

    private ApiKeyException refusal(String username, String sourceId, boolean replace) {
        return assertThrows(ApiKeyException.class, () -> apiKeyService.issueKey(username, sourceId, replace));
    }

    private boolean ingestAccepts(String rawKey) {
        return credentials.findActiveByKeyHash(OnboardingService.hashSha256(rawKey)).isPresent();
    }

    private int activeKeyCount(String sourceId) {
        return TestSqlite.count(jdbc, "SELECT COUNT(*) FROM credentials WHERE source_id = ? AND status = 'ACTIVE'", sourceId);
    }

    @Test
    void theOwnerCreatesAKeyThatIngestAccepts() {
        IssuedApiKey issued = apiKeyService.issueKey("owner", activeSource.sourceId(), false);

        assertTrue(issued.apiKey().matches("ulpf_live_[0-9a-f]{40}"), "unexpected key format: " + issued.apiKey());
        assertEquals(activeSource.sourceId(), issued.sourceId());
        assertNotNull(issued.createdAt());
        assertFalse(issued.replacedPreviousKey());
        assertTrue(ingestAccepts(issued.apiKey()));
        assertEquals(0, TestSqlite.count(jdbc, "SELECT COUNT(*) FROM credentials WHERE key_hash = ?", issued.apiKey()),
                "only the hash may be stored, never the raw key");
    }

    @Test
    void anotherUsersSourceIsForbidden() {
        users.save(new User(null, "intruder", "Intruder", "hash", Role.VENDOR, null));

        ApiKeyException e = refusal("intruder", activeSource.sourceId(), false);

        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals("NOT_OWNER", e.code());
        assertEquals(0, activeKeyCount(activeSource.sourceId()));
    }

    @Test
    void anUnknownUserIsForbidden() {
        assertEquals(HttpStatus.FORBIDDEN, refusal("nobody", activeSource.sourceId(), false).status());
    }

    @Test
    void anUnknownSourceIsNotFound() {
        ApiKeyException e = refusal("owner", "no-such-source", false);

        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals("SOURCE_NOT_FOUND", e.code());
    }

    @Test
    void aSourceThatIsNotActiveGetsNoKey() {
        for (String status : List.of("PENDING_APPROVAL", "SUSPENDED", "REVOKED")) {
            SourceRecord source = sourceWithStatus(status);

            ApiKeyException e = refusal("owner", source.sourceId(), false);

            assertEquals(HttpStatus.CONFLICT, e.status(), status);
            assertEquals("SOURCE_NOT_ACTIVE", e.code(), status);
            assertEquals(0, TestSqlite.count(jdbc, "SELECT COUNT(*) FROM credentials WHERE source_id = ?", source.sourceId()));
        }
    }

    @Test
    void aSuspendedVendorGetsNoKey() {
        jdbc.update("UPDATE vendors SET status = 'SUSPENDED' WHERE vendor_id = ?", ownerVendor.vendorId());

        ApiKeyException e = refusal("owner", activeSource.sourceId(), false);

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("SOURCE_NOT_ACTIVE", e.code());
    }

    @Test
    void anExistingKeyIsReplacedOnlyWhenAskedExplicitly() {
        IssuedApiKey first = apiKeyService.issueKey("owner", activeSource.sourceId(), false);

        ApiKeyException e = refusal("owner", activeSource.sourceId(), false);

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("KEY_EXISTS", e.code());
        assertTrue(ingestAccepts(first.apiKey()), "a refused replace must leave the current key working");
    }

    @Test
    void regenerateStopsTheOldKeyAtOnce() {
        IssuedApiKey oldKey = apiKeyService.issueKey("owner", activeSource.sourceId(), false);
        assertTrue(ingestAccepts(oldKey.apiKey())); // the old key is now in the RAM cache

        IssuedApiKey newKey = apiKeyService.issueKey("owner", activeSource.sourceId(), true);

        assertTrue(newKey.replacedPreviousKey());
        assertNotEquals(oldKey.apiKey(), newKey.apiKey());
        assertFalse(ingestAccepts(oldKey.apiKey()), "the old key must stop working at once");
        assertTrue(ingestAccepts(newKey.apiKey()));
        assertEquals(1, activeKeyCount(activeSource.sourceId()));
    }

    @Test
    void replaceExistingWithNoKeyYetCreatesTheFirstKey() {
        IssuedApiKey issued = apiKeyService.issueKey("owner", activeSource.sourceId(), true);

        assertFalse(issued.replacedPreviousKey());
        assertTrue(ingestAccepts(issued.apiKey()));
    }

    @Test
    void everyNewKeyNotifiesTheOwnerWithoutTheKey() {
        IssuedApiKey first = apiKeyService.issueKey("owner", activeSource.sourceId(), false);
        IssuedApiKey second = apiKeyService.issueKey("owner", activeSource.sourceId(), true);

        List<NotificationRecord> notes = onboarding.findNotificationsByUserId(owner.userId());

        assertEquals(2, notes.size());
        for (NotificationRecord note : notes) {
            String text = note.title() + " " + note.message();
            assertTrue(text.contains("Firewall Alpha"), "the notification must name the source: " + text);
            assertFalse(text.contains(first.apiKey()) || text.contains(second.apiKey()) || text.contains("ulpf_live_"),
                    "a notification must never contain the key: " + text);
        }
    }
}
