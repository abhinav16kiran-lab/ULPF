package com.ulpf.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.ulpf.mapping.model.MappingProposal;
import com.ulpf.mapping.service.MappingProposalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OnboardingService(
            UserRepository userRepository,
            VendorRepository vendorRepository,
            SourceRepository sourceRepository,
            CredentialRepository credentialRepository,
            OnboardingRepository onboardingRepository,
            MappingRepository mappingRepository,
            MappingProposalService mappingProposalService
    ) {
        this.userRepository = userRepository;
        this.vendorRepository = vendorRepository;
        this.sourceRepository = sourceRepository;
        this.credentialRepository = credentialRepository;
        this.onboardingRepository = onboardingRepository;
        this.mappingRepository = mappingRepository;
        this.mappingProposalService = mappingProposalService;
    }

    public record OnboardingSubmissionResult(
            String requestId,
            String sourceId,
            String vendorId,
            String rawApiKey,
            String status,
            String message
    ) {}

    public OnboardingSubmissionResult submitRequest(
            String username,
            String vendorName,
            String sourceName,
            String sourceType,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile
    ) {
        return submitRequest(username, vendorName, sourceName, sourceType, "REG_LOG", null, null, null, sampleLogFile, schemaFile);
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
            MultipartFile schemaFile
    ) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String effectiveLogType = (logType != null && !logType.isBlank()) ? logType.toUpperCase() : "REG_LOG";
        if ("SENSOR".equals(effectiveLogType)) {
            effectiveLogType = "SEN_TEL";
        }

        // 1. Resolve or create vendor record for user
        VendorRecord vendor = vendorRepository.findByOwnerUserId(user.userId())
                .orElseGet(() -> vendorRepository.save(new VendorRecord(null, user.userId(), vendorName, "ACTIVE", null)));

        // 2. Create source record with status PENDING_APPROVAL
        SourceRecord source = sourceRepository.save(new SourceRecord(
                null, vendor.vendorId(), sourceName, sourceType, "PENDING_APPROVAL", null
        ));

        // 3. Generate raw API Key (e.g., ulpf_live_...) & SHA-256 key hash
        String rawApiKey = "ulpf_live_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String keyHash = hashSha256(rawApiKey);

        // 4. Save credential record with status PENDING_APPROVAL
        credentialRepository.save(new CredentialRecord(
                null, source.sourceId(), vendor.vendorId(), keyHash, "PENDING_APPROVAL", null
        ));

        // 5. Extract sample snippet & save files to disk
        String requestId = UUID.randomUUID().toString();
        String sampleMetadataJson = processAndStoreSampleFiles(requestId, sampleLogFile, schemaFile, effectiveLogType, delta, maxIntervalMs, sensorField);

        // 6. Generate candidate mapping version via AI mapping engine
        List<MappingProposal> proposals = new ArrayList<>();
        mappingProposalService.saveMappingVersion(source.sourceId(), proposals);

        // Inject metadata block into candidate mapping_json
        injectMetadataIntoCandidateMapping(source.sourceId(), effectiveLogType, delta, maxIntervalMs, sensorField);

        // 7. Save onboarding request record
        OnboardingRequestRecord req = onboardingRepository.saveRequest(new OnboardingRequestRecord(
                requestId, user.userId(), source.sourceId(), "NEW_SOURCE", sampleMetadataJson, "SUBMITTED", LocalDateTime.now()
        ));

        log.info("Onboarding request {} submitted for source {} with raw API key generated and metadata injected", requestId, source.sourceId());

        return new OnboardingSubmissionResult(
                req.requestId(),
                source.sourceId(),
                vendor.vendorId(),
                rawApiKey,
                req.status(),
                "Onboarding request submitted successfully. Please save your API key now — for security reasons, it will not be displayed again. The key will become ACTIVE once approved by an administrator."
        );
    }

    private void injectMetadataIntoCandidateMapping(String sourceId, String logType, Double delta, Long maxIntervalMs, String sensorField) {
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
                log.warn("Could not inject metadata into candidate mapping for sourceId {}: {}", sourceId, e.getMessage());
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
        OnboardingRequestRecord req = onboardingRepository.findRequestById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding request not found: " + requestId));

        String finalStatus = "APPROVED".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED";
        onboardingRepository.updateRequestStatus(requestId, finalStatus);

        if (req.sourceId() != null) {
            if ("APPROVED".equals(finalStatus)) {
                sourceRepository.activateSource(req.sourceId());
                credentialRepository.activateCredentialForSource(req.sourceId());

                // Activate candidate mapping version
                activateCandidateMappingForSource(req.sourceId());

                onboardingRepository.saveNotification(
                        req.userId(),
                        "Onboarding Request Approved",
                        "Your onboarding request (ID: " + requestId + ") for log source has been APPROVED! Your API key is now ACTIVE."
                );
            } else {
                sourceRepository.revokeSource(req.sourceId());
                // Drop unapproved candidate mapping records immediately on rejection
                mappingRepository.deleteCandidateVersions(req.sourceId());

                onboardingRepository.saveNotification(
                        req.userId(),
                        "Onboarding Request Rejected",
                        "Your onboarding request (ID: " + requestId + ") was REJECTED."
                );
            }
        }

        credentialRepository.clearCache();
        mappingRepository.clearCache();
        return onboardingRepository.findRequestById(requestId).orElse(req);
    }

    private void activateCandidateMappingForSource(String sourceId) {
        Optional<MappingVersionRecord> candidateOpt = mappingRepository.findCandidateBySourceId(sourceId);
        if (candidateOpt.isPresent()) {
            mappingRepository.activateVersion(candidateOpt.get().mappingId(), sourceId);
        }
    }

    public void updateCandidateMapping(String requestId, String newMappingJson) {
        OnboardingRequestRecord req = onboardingRepository.findRequestById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Onboarding request not found: " + requestId));
        if (req.sourceId() != null) {
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
            String sensorField
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("log_type", logType);
        metadata.put("delta", delta);
        metadata.put("max_interval_ms", maxIntervalMs != null ? maxIntervalMs : 60000L);
        metadata.put("sensor_field", sensorField);

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

    public static String hashSha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}