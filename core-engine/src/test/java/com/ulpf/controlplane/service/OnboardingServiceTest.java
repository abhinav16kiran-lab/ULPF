package com.ulpf.controlplane.service;

// import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.mapping.service.MappingProposalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingServiceTest {

        private OnboardingService onboardingService;
        private UserRepository userRepository;
        private VendorRepository vendorRepository;
        private SourceRepository sourceRepository;
        private CredentialRepository credentialRepository;
        private OnboardingRepository onboardingRepository;

        @BeforeEach
        void setUp() {
                SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
                dataSource.setDriverClassName("org.sqlite.JDBC");
                dataSource.setUrl("jdbc:sqlite::memory:");
                dataSource.setSuppressClose(true);

                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
                jdbcTemplate.execute(
                                "CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE vendors (vendor_id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL UNIQUE, vendor_name TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE sources (source_id TEXT PRIMARY KEY, vendor_id TEXT NOT NULL, source_name TEXT NOT NULL, source_type TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE credentials (credential_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, key_hash TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE onboarding_requests (request_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, source_id TEXT, request_type TEXT NOT NULL, sample_metadata TEXT, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE notifications (notification_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, read BOOLEAN NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                jdbcTemplate.execute(
                                "CREATE TABLE mapping_versions (mapping_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, version INTEGER NOT NULL, mapping_json TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

                userRepository = new UserRepository(jdbcTemplate);
                credentialRepository = new CredentialRepository(jdbcTemplate);
                vendorRepository = new VendorRepository(jdbcTemplate, credentialRepository);
                sourceRepository = new SourceRepository(jdbcTemplate, credentialRepository);
                onboardingRepository = new OnboardingRepository(jdbcTemplate);
                MappingRepository mappingRepository = new MappingRepository(jdbcTemplate);
                MappingProposalService mappingProposalService = new MappingProposalService(mappingRepository);

                userRepository.save(new User(null, "vendor_alice", "Alice Vendor", "pass_hash", Role.USER, null));

                onboardingService = new OnboardingService(
                                userRepository,
                                vendorRepository,
                                sourceRepository,
                                credentialRepository,
                                onboardingRepository,
                                mappingRepository,
                                mappingProposalService);
        }

        @Test
        void testSubmitOnboardingRequestGeneratesPendingKeyAndFiles() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain",
                                "src_ip=192.168.1.1 action=ALLOW\nsrc_ip=192.168.1.2 action=DENY".getBytes());

                var result = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                assertNotNull(result.requestId());
                assertNotNull(result.rawApiKey());
                assertTrue(result.rawApiKey().startsWith("ulpf_live_"));
                assertEquals("SUBMITTED", result.status());

                // Verify source status is PENDING_APPROVAL
                assertEquals("PENDING_APPROVAL", sourceRepository.findById(result.sourceId()).get().status());

                // Verify key hash is generated and lookup fails while PENDING_APPROVAL
                String keyHash = OnboardingService.hashSha256(result.rawApiKey());
                Optional<CredentialRecord> credOpt = credentialRepository.findActiveByKeyHash(keyHash);
                assertFalse(credOpt.isPresent(), "Key lookup must fail when status is PENDING_APPROVAL");
        }

        @Test
        void testAdminApproveActivatesKeyAndPushesNotification() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var result = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                String keyHash = OnboardingService.hashSha256(result.rawApiKey());

                // Process Admin Approval
                OnboardingRequestRecord approved = onboardingService.processAdminDecision(result.requestId(),
                                "APPROVED");
                assertEquals("APPROVED", approved.status());

                // Verify source and credential are now ACTIVE
                assertEquals("ACTIVE", sourceRepository.findById(result.sourceId()).get().status());
                Optional<CredentialRecord> activeCred = credentialRepository.findActiveByKeyHash(keyHash);
                assertTrue(activeCred.isPresent(), "Key lookup must succeed once APPROVED");

                // Verify Notification was sent
                User user = userRepository.findByUsername("vendor_alice").get();
                List<NotificationRecord> notifications = onboardingRepository.findNotificationsByUserId(user.userId());
                assertEquals(1, notifications.size());
                assertTrue(notifications.get(0).title().contains("Approved"));
        }
}
