package com.ulpf.common.db;

import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendorRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private CredentialRepository credentialRepository;
    private VendorRepository vendorRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE vendors (vendor_id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL, vendor_name TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE sources (source_id TEXT PRIMARY KEY, vendor_id TEXT NOT NULL, source_name TEXT NOT NULL, source_type TEXT NOT NULL, status TEXT NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE credentials (credential_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, key_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbcTemplate.execute("INSERT INTO users VALUES ('u1', 'admin', 'pass', 'ADMIN')");

        credentialRepository = new CredentialRepository(jdbcTemplate);
        vendorRepository = new VendorRepository(jdbcTemplate, credentialRepository);
    }

    @Test
    void testSaveAndFindVendor() {
        VendorRecord vendor = new VendorRecord(null, "u1", "Acme Security", "ACTIVE", null);
        VendorRecord saved = vendorRepository.save(vendor);

        Optional<VendorRecord> fetched = vendorRepository.findById(saved.vendorId());
        assertTrue(fetched.isPresent());
        assertEquals("Acme Security", fetched.get().vendorName());
        assertEquals("ACTIVE", fetched.get().status());
    }

    @Test
    void testSuspendAndActivateVendorZeroMutationOnChildren() {
        VendorRecord vendor = vendorRepository.save(new VendorRecord("v1", "u1", "Acme Security", "ACTIVE", null));
        jdbcTemplate.execute("INSERT INTO sources VALUES ('s1', 'v1', 'Firewall', 'FIREWALL', 'ACTIVE')");
        jdbcTemplate.execute("INSERT INTO sources VALUES ('s2', 'v1', 'WAF', 'WEB_APP', 'SUSPENDED')");
        credentialRepository.save(new CredentialRecord("c1", "s1", "v1", "hash1", "ACTIVE", null));

        // Suspend vendor
        vendorRepository.suspendVendor("v1");
        assertEquals("SUSPENDED", vendorRepository.findById("v1").get().status());

        // Credential lookup should fail dynamically
        assertFalse(credentialRepository.findActiveByKeyHash("hash1").isPresent());

        // Verify child sources retain their individual statuses (zero mutation)
        String s1Status = jdbcTemplate.queryForObject("SELECT status FROM sources WHERE source_id = 's1'", String.class);
        String s2Status = jdbcTemplate.queryForObject("SELECT status FROM sources WHERE source_id = 's2'", String.class);
        assertEquals("ACTIVE", s1Status, "s1 should remain ACTIVE in DB");
        assertEquals("SUSPENDED", s2Status, "s2 should remain SUSPENDED in DB");

        // Reactivate vendor
        vendorRepository.activateVendor("v1");
        assertEquals("ACTIVE", vendorRepository.findById("v1").get().status());
        assertTrue(credentialRepository.findActiveByKeyHash("hash1").isPresent());
    }

    @Test
    void testRevokeVendorCascadesToSourcesAndCredentials() {
        vendorRepository.save(new VendorRecord("v1", "u1", "Acme Security", "ACTIVE", null));
        jdbcTemplate.execute("INSERT INTO sources VALUES ('s1', 'v1', 'Firewall', 'FIREWALL', 'ACTIVE')");
        credentialRepository.save(new CredentialRecord("c1", "s1", "v1", "hash1", "ACTIVE", null));

        vendorRepository.revokeVendor("v1");

        assertEquals("REVOKED", vendorRepository.findById("v1").get().status());
        String s1Status = jdbcTemplate.queryForObject("SELECT status FROM sources WHERE source_id = 's1'", String.class);
        String c1Status = jdbcTemplate.queryForObject("SELECT status FROM credentials WHERE credential_id = 'c1'", String.class);

        assertEquals("REVOKED", s1Status);
        assertEquals("REVOKED", c1Status);
        assertFalse(credentialRepository.findActiveByKeyHash("hash1").isPresent());
    }
}
