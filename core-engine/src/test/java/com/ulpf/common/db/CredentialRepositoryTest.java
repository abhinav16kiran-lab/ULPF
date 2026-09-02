package com.ulpf.common.db;

import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialRepositoryTest {

    private CredentialRepository credentialRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
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
}
