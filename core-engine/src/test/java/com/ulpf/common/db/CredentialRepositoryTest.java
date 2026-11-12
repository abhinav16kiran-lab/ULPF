package com.ulpf.common.db;

import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private CredentialRepository credentialRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE vendors (vendor_id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL, vendor_name TEXT NOT NULL, status TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE sources (source_id TEXT PRIMARY KEY, vendor_id TEXT NOT NULL, source_name TEXT NOT NULL, source_type TEXT NOT NULL, status TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE credentials (credential_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, key_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbcTemplate.execute("INSERT INTO users VALUES ('u1', 'admin', 'pass', 'ADMIN')");
        jdbcTemplate.execute("INSERT INTO vendors VALUES ('v1', 'u1', 'Vendor One', 'ACTIVE')");
        jdbcTemplate.execute("INSERT INTO sources VALUES ('s1', 'v1', 'Firewall Source', 'FIREWALL', 'ACTIVE')");

        credentialRepository = new CredentialRepository(jdbcTemplate);
    }

    @Test
    void testCredentialSaveAndLookupByHash() {
        CredentialRecord cred = new CredentialRecord(null, "s1", "v1", "hash_abc_123", "ACTIVE", null);
        credentialRepository.save(cred);

        Optional<CredentialRecord> resolved = credentialRepository.findActiveByKeyHash("hash_abc_123");
        assertTrue(resolved.isPresent());
        assertEquals("s1", resolved.get().sourceId());
        assertEquals("v1", resolved.get().vendorId());
    }

    @Test
    void testSuspendedVendorBlocksCredentialLookup() {
        CredentialRecord cred = new CredentialRecord(null, "s1", "v1", "hash_suspended_vendor", "ACTIVE", null);
        credentialRepository.save(cred);

        // Suspend vendor
        jdbcTemplate.execute("UPDATE vendors SET status = 'SUSPENDED' WHERE vendor_id = 'v1'");
        credentialRepository.clearCache();

        Optional<CredentialRecord> resolved = credentialRepository.findActiveByKeyHash("hash_suspended_vendor");
        assertFalse(resolved.isPresent(), "Credential lookup must fail when vendor is SUSPENDED");
    }

    @Test
    void testSuspendedSourceBlocksCredentialLookup() {
        CredentialRecord cred = new CredentialRecord(null, "s1", "v1", "hash_suspended_source", "ACTIVE", null);
        credentialRepository.save(cred);

        // Suspend source
        jdbcTemplate.execute("UPDATE sources SET status = 'SUSPENDED' WHERE source_id = 's1'");
        credentialRepository.clearCache();

        Optional<CredentialRecord> resolved = credentialRepository.findActiveByKeyHash("hash_suspended_source");
        assertFalse(resolved.isPresent(), "Credential lookup must fail when source is SUSPENDED");
    }

    @Test
    void testRevokedCredentialLookupFails() {
        CredentialRecord cred = new CredentialRecord(null, "s1", "v1", "hash_revoked_cred", "REVOKED", null);
        credentialRepository.save(cred);

        Optional<CredentialRecord> resolved = credentialRepository.findActiveByKeyHash("hash_revoked_cred");
        assertFalse(resolved.isPresent(), "Credential lookup must fail when credential is REVOKED");
    }

    // --- Tests against the real schema (sqlite/schema.sql): constraints and indexes ---

    private static JdbcTemplate realSchemaWithActiveSource(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users (user_id, username, name, password_hash, role) VALUES ('u1', 'owner', 'Owner', 'h', 'VENDOR')");
        jdbc.update("INSERT INTO vendors (vendor_id, owner_user_id, vendor_name, status) VALUES ('v1', 'u1', 'Vendor One', 'ACTIVE')");
        jdbc.update("INSERT INTO sources (source_id, vendor_id, source_name, source_type, status) VALUES ('s1', 'v1', 'Firewall', 'FIREWALL', 'ACTIVE')");
        return jdbc;
    }

    @Test
    void aPendingCredentialStatusIsRejectedBySchema() {
        CredentialRepository repo = new CredentialRepository(realSchemaWithActiveSource(TestSqlite.inMemory("sqlite/schema.sql")));

        assertThrows(DataAccessException.class, // SQLite constraint errors reach Spring uncategorized
               
                () -> repo.save(new CredentialRecord(null, "s1", "v1", "hash_pending", "PENDING_APPROVAL", null)),
                "a key is created only for an approved source, so PENDING_APPROVAL is not a valid key status");
    }

    @Test
    void aSourceCanHaveOnlyOneActiveCredential() {
        CredentialRepository repo = new CredentialRepository(realSchemaWithActiveSource(TestSqlite.inMemory("sqlite/schema.sql")));
        repo.save(new CredentialRecord(null, "s1", "v1", "hash_first", "ACTIVE", null));

        assertThrows(DataAccessException.class,
                () -> repo.save(new CredentialRecord(null, "s1", "v1", "hash_second", "ACTIVE", null)));
        repo.save(new CredentialRecord(null, "s1", "v1", "hash_old", "REVOKED", null)); // revoked keys do not count
    }

    @Test
    void findActiveBySourceIdReturnsOnlyTheActiveKey() {
        CredentialRepository repo = new CredentialRepository(realSchemaWithActiveSource(TestSqlite.inMemory("sqlite/schema.sql")));
        assertTrue(repo.findActiveBySourceId("s1").isEmpty());

        repo.save(new CredentialRecord(null, "s1", "v1", "hash_old", "REVOKED", null));
        repo.save(new CredentialRecord(null, "s1", "v1", "hash_live", "ACTIVE", null));

        CredentialRecord active = repo.findActiveBySourceId("s1").orElseThrow();
        assertEquals("hash_live", active.keyHash());
        assertTrue(active.createdAt() != null);
    }

    @Test
    void revokeActiveForSourceStopsACachedKeyAtOnce() {
        CredentialRepository repo = new CredentialRepository(realSchemaWithActiveSource(TestSqlite.inMemory("sqlite/schema.sql")));
        repo.save(new CredentialRecord(null, "s1", "v1", "hash_live", "ACTIVE", null));
        assertTrue(repo.findActiveByKeyHash("hash_live").isPresent()); // now in the RAM cache

        repo.revokeActiveForSource("s1");

        assertFalse(repo.findActiveByKeyHash("hash_live").isPresent(), "a revoked key must not survive in the cache");
        assertTrue(repo.findActiveBySourceId("s1").isEmpty());
    }

    /**
     * The race: a revoke evicts the key from the cache, but until the transaction commits, an ingest request on
     * another connection still reads the key as ACTIVE and puts it back in the cache. Cache hits refresh the idle
     * timer, so a busy client could keep using the revoked key. The repository must evict again after the commit.
     */
    @Test
    void aKeyReCachedByAConcurrentReadDuringTheRevokeIsEvictedAfterCommit() throws Exception {
        Path file = Files.createTempFile("ulpf-credential-race", ".db");
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + file + "?busy_timeout=5000");
            dataSource.setDriverClassName("org.sqlite.JDBC");
            new ResourceDatabasePopulator(new ClassPathResource("sqlite/schema.sql")).execute(dataSource);
            CredentialRepository repo = new CredentialRepository(realSchemaWithActiveSource(new JdbcTemplate(dataSource)));
            repo.save(new CredentialRecord(null, "s1", "v1", "hash_live", "ACTIVE", null));

            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx -> {
                repo.revokeActiveForSource("s1");
                // Another thread uses another connection: it sees the last committed state, where the key is ACTIVE.
                boolean seenByConcurrentIngest = CompletableFuture
                        .supplyAsync(() -> repo.findActiveByKeyHash("hash_live").isPresent()).join();
                assertTrue(seenByConcurrentIngest, "test setup: the uncommitted revoke must not be visible yet");
            });

            assertFalse(repo.findActiveByKeyHash("hash_live").isPresent(),
                    "the key re-cached before the commit must be evicted after the commit");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
