package com.ulpf.controlplane.service;

// import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.CredentialRepository;
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
import com.ulpf.testsupport.TestSqlite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingServiceTest {

        private OnboardingService onboardingService;
        private UserRepository userRepository;
        private VendorRepository vendorRepository;
        private SourceRepository sourceRepository;
        private CredentialRepository credentialRepository;
        private OnboardingRepository onboardingRepository;
        private MappingRepository mappingRepository;
        private JdbcTemplate jdbcTemplate;
        private ApiKeyService apiKeyService;

        @BeforeEach
        void setUp() {
                SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
                dataSource.setDriverClassName("org.sqlite.JDBC");
                dataSource.setUrl("jdbc:sqlite::memory:");
                dataSource.setSuppressClose(true);

                ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                                new ClassPathResource("sqlite/schema.sql"));
                populator.execute(dataSource);

                jdbcTemplate = new JdbcTemplate(dataSource);

                userRepository = new UserRepository(jdbcTemplate);
                credentialRepository = new CredentialRepository(jdbcTemplate);
                vendorRepository = new VendorRepository(jdbcTemplate, credentialRepository);
                sourceRepository = new SourceRepository(jdbcTemplate, credentialRepository);
                onboardingRepository = new OnboardingRepository(jdbcTemplate);
                mappingRepository = new MappingRepository(jdbcTemplate);
                MappingProposalService mappingProposalService = new MappingProposalService(mappingRepository);
                
                // Create mock MappingLearningService for tests
                var fieldPreprocessor = new com.ulpf.mapping.service.FieldPreprocessor();
                var correctionExtractor = new com.ulpf.mapping.service.CorrectionExtractor(new com.fasterxml.jackson.databind.ObjectMapper());
                var aliasRepository = new com.ulpf.mapping.repository.AliasRepository(jdbcTemplate);
                var mappingLearningService = new com.ulpf.mapping.service.MappingLearningService(
                    correctionExtractor, fieldPreprocessor, aliasRepository);

                userRepository.save(new User(null, "vendor_alice", "Alice Vendor", "pass_hash", Role.USER, null));

                onboardingService = new OnboardingService(
                                userRepository,
                                vendorRepository,
                                sourceRepository,
                                credentialRepository,
                                onboardingRepository,
                                mappingRepository,
                                mappingProposalService,
                                mappingLearningService);
                apiKeyService = new ApiKeyService(userRepository, vendorRepository, sourceRepository,
                                credentialRepository, onboardingRepository);
        }

        private int credentialCount(String sourceId) {
                return TestSqlite.count(jdbcTemplate, "SELECT COUNT(*) FROM credentials WHERE source_id = ?", sourceId);
        }

        @Test
        void testSubmitOnboardingRequestCreatesNoKey() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain",
                                "src_ip=192.168.1.1 action=ALLOW\nsrc_ip=192.168.1.2 action=DENY".getBytes());

                var result = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                assertNotNull(result.requestId());
                assertEquals("SUBMITTED", result.status());
                assertEquals("PENDING_APPROVAL", sourceRepository.findById(result.sourceId()).get().status());
                assertEquals(0, credentialCount(result.sourceId()), "no key may exist before approval");
                assertFalse(result.message().contains("API key is"), "the message must not promise a key: " + result.message());
        }

        @Test
        void testAdminApproveActivatesSourceWithoutKeyAndTellsVendorToCreateOne() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var result = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                OnboardingRequestRecord approved = onboardingService.processAdminDecision(result.requestId(),
                                "APPROVED");
                assertEquals("APPROVED", approved.status());

                assertEquals("ACTIVE", sourceRepository.findById(result.sourceId()).get().status());
                assertEquals(0, credentialCount(result.sourceId()), "approval must not create a key");

                User user = userRepository.findByUsername("vendor_alice").get();
                assertEquals(Role.VENDOR, user.role());
                List<NotificationRecord> notifications = onboardingRepository.findNotificationsByUserId(user.userId());
                assertEquals(1, notifications.size());
                assertTrue(notifications.get(0).title().contains("Approved"));
                assertTrue(notifications.get(0).message().contains("Create API key"),
                                "the vendor must be told how to get a key: " + notifications.get(0).message());
        }

        @Test
        void testMySourcesShowsWhenTheActiveKeyWasCreated() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());
                var result = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);
                onboardingService.processAdminDecision(result.requestId(), "APPROVED");

                assertEquals(null, onboardingService.getUserSources("vendor_alice").get(0).apiKeyCreatedAt(),
                                "an approved source starts without a key");

                apiKeyService.issueKey("vendor_alice", result.sourceId(), false);

                var source = onboardingService.getUserSources("vendor_alice").get(0);
                assertEquals(result.sourceId(), source.sourceId());
                assertNotNull(source.apiKeyCreatedAt());
        }

        @Test
        void testSubmitUpdateRequestCreatesUpdateRecordAndPreservesKey() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                onboardingService.processAdminDecision(initResult.requestId(), "APPROVED");
                String keyHash = OnboardingService.hashSha256(
                                apiKeyService.issueKey("vendor_alice", initResult.sourceId(), false).apiKey());

                // Submit update request for existing source
                MockMultipartFile newSampleFile = new MockMultipartFile(
                                "sampleLogFile", "v2_sample.log", "text/plain", "v2 log data with new field".getBytes());

                var updateResult = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, newSampleFile, null);

                assertNotNull(updateResult.requestId());
                assertEquals(initResult.sourceId(), updateResult.sourceId());
                assertEquals("SUBMITTED", updateResult.status());

                // Verify request record in DB has canonical type SCHEMA_UPDATE
                OnboardingRequestRecord req = onboardingRepository.findRequestById(updateResult.requestId()).get();
                assertEquals("SCHEMA_UPDATE", req.requestType());
                assertEquals(initResult.sourceId(), req.sourceId());
                assertTrue(credentialRepository.findActiveByKeyHash(keyHash).isPresent(),
                                "a schema update must leave the active key working");
        }

        @Test
        void testSubmitUpdateRequestFailsWhenPendingRequestAlreadyExists() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                onboardingService.processAdminDecision(initResult.requestId(), "APPROVED");

                // Submit first update request
                MockMultipartFile newSampleFile = new MockMultipartFile(
                                "sampleLogFile", "v2_sample.log", "text/plain", "v2 log data".getBytes());

                var firstUpdate = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, newSampleFile, null);
                assertEquals("SUBMITTED", firstUpdate.status());

                // Attempt to submit second update request while first is still pending
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, newSampleFile, null)
                );
                assertTrue(ex.getMessage().contains("already awaiting review"));
        }

        @Test
        void testAdminRejectSchemaUpdateDoesNotRevokeActiveSource() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                onboardingService.processAdminDecision(initResult.requestId(), "APPROVED");
                assertEquals("ACTIVE", sourceRepository.findById(initResult.sourceId()).get().status());

                // Submit update request
                MockMultipartFile newSampleFile = new MockMultipartFile(
                                "sampleLogFile", "v2_sample.log", "text/plain", "bad schema log data".getBytes());

                var updateResult = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, newSampleFile, null);

                // Admin rejects the schema update
                onboardingService.processAdminDecision(updateResult.requestId(), "REJECTED", "Schema is inconsistent");

                // The request should be REJECTED, but the source should REMAIN ACTIVE!
                assertEquals("REJECTED", onboardingRepository.findRequestById(updateResult.requestId()).get().status());
                assertEquals("ACTIVE", sourceRepository.findById(initResult.sourceId()).get().status(),
                                "Rejecting a schema update must not revoke an active log source");

                // Candidate versions must be cleaned up
                assertTrue(mappingRepository.findCandidateBySourceId(initResult.sourceId()).isEmpty(),
                                "Candidate versions must be cleaned up upon rejection");
        }

        @Test
        void testWithdrawRequestSucceedsAndAllowsSubsequentUpdate() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);
                onboardingService.processAdminDecision(initResult.requestId(), "APPROVED");

                // Submit first update request
                MockMultipartFile v2File = new MockMultipartFile(
                                "sampleLogFile", "v2.log", "text/plain", "v2 data".getBytes());
                var firstUpdate = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, v2File, null);
                assertEquals("SUBMITTED", firstUpdate.status());

                // Vendor realizes mistake and withdraws the request
                var withdrawn = onboardingService.withdrawRequest("vendor_alice", firstUpdate.requestId());
                assertEquals("REJECTED", withdrawn.status());

                // Active source must remain ACTIVE, candidate mapping must be cleaned up
                assertEquals("ACTIVE", sourceRepository.findById(initResult.sourceId()).get().status());
                assertTrue(mappingRepository.findCandidateBySourceId(initResult.sourceId()).isEmpty());

                // Now vendor can submit a fresh update request without 409 conflict
                MockMultipartFile v3File = new MockMultipartFile(
                                "sampleLogFile", "v3.log", "text/plain", "v3 corrected data".getBytes());
                var secondUpdate = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, v3File, null);
                assertEquals("SUBMITTED", secondUpdate.status());
        }

        @Test
        void testAdminCannotApproveWithdrawnRequest() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);
                onboardingService.processAdminDecision(initResult.requestId(), "APPROVED");

                // Submit update request
                MockMultipartFile v2File = new MockMultipartFile(
                                "sampleLogFile", "v2.log", "text/plain", "v2 data".getBytes());
                var updateResult = onboardingService.submitUpdateRequest(
                                "vendor_alice", initResult.sourceId(), "FIREWALL", "REG_LOG", null, null, null, v2File, null);

                // Withdraw request
                onboardingService.withdrawRequest("vendor_alice", updateResult.requestId());

                // Admin attempts to approve withdrawn request -> must fail with IllegalStateException
                IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                        onboardingService.processAdminDecision(updateResult.requestId(), "APPROVED")
                );
                assertTrue(ex.getMessage().contains("terminal state"));
        }

        @Test
        void testWithdrawRequestFailsForNonOwner() {
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var initResult = onboardingService.submitRequest(
                                "vendor_alice", "Alice Corp", "Firewall Alpha", "FIREWALL", sampleFile, null);

                // Create second user bob
                userRepository.save(new com.ulpf.controlplane.model.User(null, "vendor_bob", "Bob", "hash", com.ulpf.controlplane.model.Role.USER, null));

                // Bob attempts to withdraw Alice's request -> must fail
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                        onboardingService.withdrawRequest("vendor_bob", initResult.requestId())
                );
                assertTrue(ex.getMessage().contains("only withdraw your own"));
        }

        @Test
        void testApprovingRequestOwnedByAdminKeepsAdminRole() {
                // The admin is hidden from the onboarding page only in the UI; the service must not
                // turn an ADMIN into a VENDOR if such a request ever gets approved.
                userRepository.save(new User(null, "root_admin", "Admin", "pass_hash", Role.ADMIN, null));
                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                var result = onboardingService.submitRequest(
                                "root_admin", "Admin Corp", "Test Source", "FIREWALL", sampleFile, null);
                onboardingService.processAdminDecision(result.requestId(), "APPROVED");

                assertEquals(Role.ADMIN, userRepository.findByUsername("root_admin").get().role(),
                                "approval must never change an ADMIN's role");
        }

        @AfterAll
        static void tearDownAll() {
                File dir = new File("storage/onboarding-samples");
                if (dir.exists() && dir.isDirectory()) {
                        File[] files = dir.listFiles();
                        if (files != null) {
                                for (File file : files) {
                                        file.delete();
                                }
                        }
                }
        }
}
