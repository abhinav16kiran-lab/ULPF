package com.ulpf.controlplane.service;

// import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.User;
import com.ulpf.dataplane.format.FormatDetectionResult;
import com.ulpf.dataplane.format.LogFormatDetector;
import com.ulpf.mapping.service.MappingEngineOrchestrator;
import com.ulpf.mapping.model.MappingProposal;
import com.ulpf.mapping.service.MappingLearningService;
import com.ulpf.mapping.service.MappingProposalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final SourceRepository sourceRepository;
    private final CredentialRepository credentialRepository;
    private final OnboardingRepository onboardingRepository;
    private final MappingRepository mappingRepository;
    private final MappingProposalService mappingProposalService;
    private final MappingLearningService mappingLearningService;
    private final LogFormatDetector logFormatDetector;
    private final MappingEngineOrchestrator mappingEngineOrchestrator;
    private final com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaProvisioningService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record AdminStats(int pendingReviewCount, int activeVendorsCount) {}

    private volatile AdminStats cachedAdminStats = null;
    private volatile long lastAdminStatsFetchTime = 0L;
    private static final long STATS_CACHE_TTL_MS = 30_000L;

    public AdminStats getAdminStats() {
        long now = System.currentTimeMillis();
        if (cachedAdminStats == null || (now - lastAdminStatsFetchTime) > STATS_CACHE_TTL_MS) {
            synchronized (this) {
                if (cachedAdminStats == null || (now - lastAdminStatsFetchTime) > STATS_CACHE_TTL_MS) {
                    int pending = onboardingRepository.countPendingRequests();
                    int activeVendors = vendorRepository.countActiveVendors();
                    cachedAdminStats = new AdminStats(pending, activeVendors);
                    lastAdminStatsFetchTime = now;
                    log.info("Refreshed in-memory admin stats cache: pending={}, activeVendors={}", pending, activeVendors);
                }
            }
        }
        return cachedAdminStats;
    }

    public void invalidateAdminStatsCache() {
        this.cachedAdminStats = null;
    }

    public OnboardingService(
            UserRepository userRepository,
            VendorRepository vendorRepository,
            SourceRepository sourceRepository,
            CredentialRepository credentialRepository,
            OnboardingRepository onboardingRepository,
            MappingRepository mappingRepository,
            MappingProposalService mappingProposalService,
            MappingLearningService mappingLearningService) {
        this(userRepository, vendorRepository, sourceRepository, credentialRepository, onboardingRepository,
                mappingRepository, mappingProposalService, mappingLearningService, new LogFormatDetector(), null, null);
    }

    @Autowired
    public OnboardingService(
            UserRepository userRepository,
            VendorRepository vendorRepository,
            SourceRepository sourceRepository,
            CredentialRepository credentialRepository,
            OnboardingRepository onboardingRepository,
            MappingRepository mappingRepository,
            MappingProposalService mappingProposalService,
            MappingLearningService mappingLearningService,
            LogFormatDetector logFormatDetector,
            @Autowired(required = false) MappingEngineOrchestrator mappingEngineOrchestrator,
            @Autowired(required = false) com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaProvisioningService) {
        this.userRepository = userRepository;
        this.vendorRepository = vendorRepository;
        this.sourceRepository = sourceRepository;
        this.credentialRepository = credentialRepository;
        this.onboardingRepository = onboardingRepository;
        this.mappingRepository = mappingRepository;
        this.mappingProposalService = mappingProposalService;
        this.mappingLearningService = mappingLearningService;
        this.logFormatDetector = logFormatDetector;
        this.mappingEngineOrchestrator = mappingEngineOrchestrator;
        this.dynamicSchemaProvisioningService = dynamicSchemaProvisioningService;
    }

    public record OnboardingSubmissionResult(
            String requestId,
            String sourceId,
            String vendorId,
            String rawApiKey,
            String status,
            String message) {
    }

    public OnboardingSubmissionResult submitRequest(
            String username,
            String vendorName,
            String sourceName,
            String sourceType,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile) {
        return submitRequest(username, vendorName, sourceName, sourceType, "REG_LOG", null, null, null, sampleLogFile,
                schemaFile);
    }

    public OnboardingSubmissionResult submitRequest(
            String username,
            String vendorName,
            String sourceName,
            String sourceType,
            String logType,
            Double delta,
            Long maxIntervalMs,
            String sensorField,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String effectiveLogType = (logType != null && !logType.isBlank()) ? logType.toUpperCase() : "REG_LOG";
        if ("SENSOR".equals(effectiveLogType)) {
            effectiveLogType = "SEN_TEL";
        }

        // 1. Resolve or create vendor record for user
        VendorRecord vendor = vendorRepository.findByOwnerUserId(user.userId())
                .orElseGet(
                        () -> vendorRepository.save(new VendorRecord(null, user.userId(), vendorName, "ACTIVE", null)));

        // 2. Create source record with status PENDING_APPROVAL
        SourceRecord source = sourceRepository.save(new SourceRecord(
                null, vendor.vendorId(), sourceName, sourceType, "PENDING_APPROVAL", null));

        // 3. Generate raw API Key (e.g., ulpf_live_...) & SHA-256 key hash
        String rawApiKey = "ulpf_live_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String keyHash = hashSha256(rawApiKey);

        // 4. Save credential record with status PENDING_APPROVAL
        credentialRepository.save(new CredentialRecord(
                null, source.sourceId(), vendor.vendorId(), keyHash, "PENDING_APPROVAL", null));

        // 5. Generate candidate mapping version via AI mapping engine using format autodetector on sample snippet
        List<MappingProposal> proposals = generateProposalsFromSample(sampleLogFile);
        mappingProposalService.saveMappingVersion(source.sourceId(), proposals);

        // Inject metadata block into candidate mapping_json
        injectMetadataIntoCandidateMapping(source.sourceId(), effectiveLogType, delta, maxIntervalMs, sensorField);

        // 6. Extract sample snippet, include candidate mappings & save files to disk
        String requestId = UUID.randomUUID().toString();
        String sampleMetadataJson = processAndStoreSampleFiles(requestId, sampleLogFile, schemaFile, effectiveLogType,
                delta, maxIntervalMs, sensorField, proposals);

        // 7. Save onboarding request record
        OnboardingRequestRecord req = onboardingRepository.saveRequest(new OnboardingRequestRecord(
                requestId, user.userId(), source.sourceId(), "NEW_SOURCE", sampleMetadataJson, "SUBMITTED",
                LocalDateTime.now()));

        invalidateAdminStatsCache();
        log.info("Onboarding request {} submitted for source {} with raw API key generated and metadata injected",
                requestId, source.sourceId());

        return new OnboardingSubmissionResult(
                req.requestId(),
                source.sourceId(),
                vendor.vendorId(),
                rawApiKey,
                req.status(),
                "Onboarding request submitted successfully. Please save your API key now — for security reasons, it will not be displayed again. The key will become ACTIVE once approved by an administrator.");
    }

    public OnboardingSubmissionResult submitUpdateRequest(
            String username,
            String sourceId,
            String sourceType,
            String logType,
            Double delta,
            Long maxIntervalMs,
            String sensorField,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        SourceRecord source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Log source not found: " + sourceId));

        String effectiveLogType = (logType != null && !logType.isBlank()) ? logType.toUpperCase() : "REG_LOG";
        if ("SENSOR".equals(effectiveLogType)) {
            effectiveLogType = "SEN_TEL";
        }

        // 1. Delete any stale candidate mapping for this source if present before generating new candidate
        mappingRepository.deleteCandidateVersions(sourceId);

        // 2. Generate new candidate mapping version via AI mapping engine using format autodetector on sample snippet
        List<MappingProposal> proposals = generateProposalsFromSample(sampleLogFile);
        mappingProposalService.saveMappingVersion(source.sourceId(), proposals);

        // 3. Inject metadata block into candidate mapping_json
        injectMetadataIntoCandidateMapping(source.sourceId(), effectiveLogType, delta, maxIntervalMs, sensorField);

        // 4. Extract sample snippet, include candidate mappings & store files
        String requestId = UUID.randomUUID().toString();
        String sampleMetadataJson = processAndStoreSampleFiles(requestId, sampleLogFile, schemaFile, effectiveLogType,
                delta, maxIntervalMs, sensorField, proposals);

        // 5. Save onboarding request record with request_type = "UPDATE_SOURCE"
        OnboardingRequestRecord req = onboardingRepository.saveRequest(new OnboardingRequestRecord(
                requestId, user.userId(), source.sourceId(), "UPDATE_SOURCE", sampleMetadataJson, "SUBMITTED",
                LocalDateTime.now()));

        invalidateAdminStatsCache();
        log.info("Schema update request {} submitted for existing source {}", requestId, source.sourceId());

        return new OnboardingSubmissionResult(
                req.requestId(),
                source.sourceId(),
                source.vendorId(),
                null,
                req.status(),
                "Schema update request submitted successfully. Your existing API key remains active. The proposed candidate mapping version will take effect once approved by an administrator.");
    }

    private void injectMetadataIntoCandidateMapping(String sourceId, String logType, Double delta, Long maxIntervalMs,
            String sensorField) {
        Optional<MappingVersionRecord> candidateOpt = mappingRepository.findCandidateBySourceId(sourceId);
        if (candidateOpt.isPresent()) {
            try {
                MappingVersionRecord candidate = candidateOpt.get();
                ObjectNode rootNode = (ObjectNode) objectMapper.readTree(candidate.mappingJson());
                ObjectNode metaNode = rootNode.putObject("metadata");
                metaNode.put("log_type", logType);
                if (delta != null) {
                    metaNode.put("delta", delta);
                } else {
                    metaNode.putNull("delta");
                }
                metaNode.put("max_interval_ms", maxIntervalMs != null ? maxIntervalMs : 60000L);
                if (sensorField != null && !sensorField.isBlank()) {
                    metaNode.put("sensor_field", sensorField);
                } else {
                    metaNode.putNull("sensor_field");
                }
                String updatedJson = objectMapper.writeValueAsString(rootNode);
                mappingRepository.updateCandidateMappingJson(sourceId, updatedJson);
            } catch (Exception e) {
                log.warn("Could not inject metadata into candidate mapping for sourceId {}: {}", sourceId,
                        e.getMessage());
            }
        }
    }

    public List<OnboardingRequestRecord> getAllRequests() {
        return onboardingRepository.findAllRequests();
    }

    public List<OnboardingRequestRecord> getUserRequests(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        return onboardingRepository.findRequestsByUserId(user.userId());
    }

    public List<SourceRecord> getUserSources(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        Optional<VendorRecord> vendorOpt = vendorRepository.findByOwnerUserId(user.userId());
        if (vendorOpt.isEmpty()) {
            return List.of();
        }
        return sourceRepository.findByVendorId(vendorOpt.get().vendorId());
    }

    public OnboardingRequestRecord processAdminDecision(String requestId, String decision) {
        return processAdminDecision(requestId, decision, null);
    }

    public OnboardingRequestRecord processAdminDecision(String requestId, String decision, String feedbackNote) {
        OnboardingRequestRecord req = onboardingRepository.findRequestById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding request not found: " + requestId));

        if (!"APPROVED".equalsIgnoreCase(decision) && !"REJECTED".equalsIgnoreCase(decision)) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
        }

        boolean isApproved = "APPROVED".equalsIgnoreCase(decision);

        if (isApproved) {
            if (req.sourceId() != null) {
                // 1. Provision ClickHouse database schema & activate candidate mapping FIRST
                // If ClickHouse schema provisioning fails, an exception is thrown and approval halts cleanly here.
                activateCandidateMappingForSource(req.sourceId());

                // 2. Activate source, API key credentials, and upgrade user role ONLY after ClickHouse schema succeeds
                sourceRepository.activateSource(req.sourceId());
                credentialRepository.activateCredentialForSource(req.sourceId());
                userRepository.updateUserRole(req.userId(), com.ulpf.controlplane.model.Role.VENDOR);

                // 3. Mark request as APPROVED only after all credentials, roles, and schemas are active
                onboardingRepository.updateRequestStatus(requestId, "APPROVED");

                onboardingRepository.saveNotification(
                        req.userId(),
                        "Onboarding Request Approved",
                        "Your onboarding request (ID: " + requestId
                                + ") for log source has been APPROVED! Your API key is now ACTIVE.");
            } else {
                onboardingRepository.updateRequestStatus(requestId, "APPROVED");
            }
        } else {
            // REJECTED flow
            onboardingRepository.updateRequestStatus(requestId, "REJECTED");
            if (req.sourceId() != null) {
                sourceRepository.revokeSource(req.sourceId());
                mappingRepository.deleteCandidateVersions(req.sourceId());

                String notifMsg = "Your onboarding request (ID: " + requestId + ") was REJECTED.";
                if (feedbackNote != null && !feedbackNote.trim().isEmpty()) {
                    notifMsg += " Reason: " + feedbackNote.trim();
                }

                onboardingRepository.saveNotification(
                        req.userId(),
                        "Onboarding Request Rejected",
                        notifMsg);
            }
        }

        invalidateAdminStatsCache();
        credentialRepository.clearCache();
        mappingRepository.clearCache();
        return onboardingRepository.findRequestById(requestId).orElse(req);
    }

    private void activateCandidateMappingForSource(String sourceId) {
        Optional<MappingVersionRecord> candidateOpt = mappingRepository.findCandidateBySourceId(sourceId);
        if (candidateOpt.isPresent()) {
            MappingVersionRecord candidate = candidateOpt.get();

            // 1. Provision ClickHouse schema FIRST
            if (dynamicSchemaProvisioningService != null) {
                Optional<SourceRecord> srcOpt = sourceRepository.findById(sourceId);
                String sourceName = srcOpt.map(SourceRecord::sourceName).orElse("stream_" + sourceId);
                dynamicSchemaProvisioningService.provisionSchemaForSource(sourceId, sourceName, candidate.mappingJson());
            }

            // 2. Activate mapping version record in SQLite ONLY after ClickHouse schema provisioning succeeds
            mappingRepository.activateVersion(candidate.mappingId(), sourceId);
        }
    }

    public void updateCandidateMapping(String requestId, String newMappingJson) {
        OnboardingRequestRecord req = onboardingRepository.findRequestById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding request not found: " + requestId));

        if (req.sourceId() != null) {
            // Get the OLD (AI-proposed) mapping before updating
            Optional<MappingVersionRecord> candidateOpt = mappingRepository.findCandidateBySourceId(req.sourceId());

            if (candidateOpt.isPresent()) {
                String oldMappingJson = candidateOpt.get().mappingJson();

                // LEARNING STEP: Extract and learn from human corrections
                try {
                    int aliasesLearned = mappingLearningService.learnFromCorrections(oldMappingJson, newMappingJson);
                    log.info("Learned {} new aliases from mapping corrections for request {}", aliasesLearned,
                            requestId);
                } catch (Exception e) {
                    // Don't fail the update if learning fails - just log it
                    log.warn("Failed to learn from corrections for request {}: {}", requestId, e.getMessage());
                }
            }

            // Update the candidate mapping (original behavior)
            mappingRepository.updateCandidateMappingJson(req.sourceId(), newMappingJson);
        }
    }

    private String processAndStoreSampleFiles(
            String requestId,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile,
            String logType,
            Double delta,
            Long maxIntervalMs,
            String sensorField,
            List<MappingProposal> proposals) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("log_type", logType);
        metadata.put("delta", delta);
        metadata.put("max_interval_ms", maxIntervalMs != null ? maxIntervalMs : 60000L);
        metadata.put("sensor_field", sensorField);

        if (proposals != null && !proposals.isEmpty()) {
            Map<String, Object> candidateMap = new java.util.LinkedHashMap<>();
            for (MappingProposal p : proposals) {
                String target = (p.getCanonicalField() != null && !p.getCanonicalField().isBlank())
                        ? p.getCanonicalField() : "unmapped";
                candidateMap.put(p.getVendorFieldRaw(), target);
            }
            metadata.put("candidate_mapping", candidateMap);
        }

        if (sampleLogFile != null && !sampleLogFile.isEmpty()) {
            String sampleSnippet = extractTopLines(sampleLogFile, 50);
            metadata.put("sample_snippet", sampleSnippet);
            metadata.put("sample_log_filename", sampleLogFile.getOriginalFilename());
            metadata.put("sample_log_size_bytes", sampleLogFile.getSize());

            saveFileToDisk(requestId, "sample_log", sampleLogFile);
        }

        if (schemaFile != null && !schemaFile.isEmpty()) {
            String schemaSnippet = extractTopLines(schemaFile, 50);
            metadata.put("schema_snippet", schemaSnippet);
            metadata.put("schema_filename", schemaFile.getOriginalFilename());

            saveFileToDisk(requestId, "schema_doc", schemaFile);
        }

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractTopLines(MultipartFile file, int maxLines) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String[] lines = content.split("\r?\n");
            StringBuilder sb = new StringBuilder();
            int count = Math.min(lines.length, maxLines);
            for (int i = 0; i < count; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private void saveFileToDisk(String requestId, String prefix, MultipartFile file) {
        try {
            File dir = new File("storage/onboarding-samples");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String safeName = prefix + "_" + requestId + "_" + file.getOriginalFilename();
            file.transferTo(new File(dir, safeName));
        } catch (IOException e) {
            log.warn("Could not save onboarding sample file to disk: {}", e.getMessage());
        }
    }

    private List<MappingProposal> generateProposalsFromSample(MultipartFile sampleLogFile) {
        if (sampleLogFile != null && !sampleLogFile.isEmpty() && mappingEngineOrchestrator != null) {
            try {
                String sampleSnippet = extractTopLines(sampleLogFile, 50);
                if (logFormatDetector != null) {
                    FormatDetectionResult formatResult = logFormatDetector.detect(sampleSnippet);
                    if (formatResult.parsedFields() != null && !formatResult.parsedFields().isEmpty()) {
                        List<String> rawKeys = new ArrayList<>(formatResult.parsedFields().keySet());
                        log.info("Autodetected format '{}' for sample log file. Extracted {} vendor field keys: {}",
                                formatResult.detectedFormat(), rawKeys.size(), rawKeys);
                        return mappingEngineOrchestrator.mapFields(rawKeys, false);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to generate AI mapping proposals from sample log file: {}", e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    public static String hashSha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}