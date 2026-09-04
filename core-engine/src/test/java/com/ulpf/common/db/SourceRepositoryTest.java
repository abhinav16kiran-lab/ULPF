package com.ulpf.common.db;

import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private CredentialRepository credentialRepository;
    private SourceRepository sourceRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE vendors (vendor_id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL, vendor_name TEXT NOT NULL, status TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE sources (source_id TEXT PRIMARY KEY, vendor_id TEXT NOT NULL, source_name TEXT NOT NULL, source_type TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE credentials (credential_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, key_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbcTemplate.execute("INSERT INTO users VALUES ('u1', 'admin', 'pass', 'ADMIN')");
        jdbcTemplate.execute("INSERT INTO vendors VALUES ('v1', 'u1', 'Acme Security', 'ACTIVE')");

        credentialRepository = new CredentialRepository(jdbcTemplate);
        sourceRepository = new SourceRepository(jdbcTemplate, credentialRepository);
    }

    @Test
    void testSaveAndFindSource() {
        SourceRecord source = new SourceRecord(null, "v1", "FW_HQ", "FIREWALL", "ACTIVE", null);
        SourceRecord saved = sourceRepository.save(source);

        Optional<SourceRecord> fetched = sourceRepository.findById(saved.sourceId());
        assertTrue(fetched.isPresent());
        assertEquals("FW_HQ", fetched.get().sourceName());
        assertEquals("FIREWALL", fetched.get().sourceType());

        List<SourceRecord> vendorSources = sourceRepository.findByVendorId("v1");
        assertEquals(1, vendorSources.size());
    }

    @Test
    void testSuspendAndActivateSource() {
        sourceRepository.save(new SourceRecord("s1", "v1", "FW_HQ", "FIREWALL", "ACTIVE", null));
        credentialRepository.save(new CredentialRecord("c1", "s1", "v1", "hash_src_1", "ACTIVE", null));

        // Suspend source
        sourceRepository.suspendSource("s1");
        assertEquals("SUSPENDED", sourceRepository.findById("s1").get().status());
        assertFalse(credentialRepository.findActiveByKeyHash("hash_src_1").isPresent());

        // Activate source
        sourceRepository.activateSource("s1");
        assertEquals("ACTIVE", sourceRepository.findById("s1").get().status());
        assertTrue(credentialRepository.findActiveByKeyHash("hash_src_1").isPresent());
    }

    @Test
    void testRevokeSourceCascadesToCredentials() {
        sourceRepository.save(new SourceRecord("s1", "v1", "FW_HQ", "FIREWALL", "ACTIVE", null));
        credentialRepository.save(new CredentialRecord("c1", "s1", "v1", "hash_src_2", "ACTIVE", null));

        sourceRepository.revokeSource("s1");

        assertEquals("REVOKED", sourceRepository.findById("s1").get().status());
        String c1Status = jdbcTemplate.queryForObject("SELECT status FROM credentials WHERE credential_id = 'c1'", String.class);
        assertEquals("REVOKED", c1Status);
        assertFalse(credentialRepository.findActiveByKeyHash("hash_src_2").isPresent());
    }
}
