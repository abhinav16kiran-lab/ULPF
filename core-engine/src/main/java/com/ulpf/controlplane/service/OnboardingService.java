package com.ulpf.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

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
        String sampleMetadataJson = processAndStoreSampleFiles(requestId, sampleLogFile, schemaFile);

        // 6. Generate candidate mapping version via AI mapping engine
        List<MappingProposal> proposals = new ArrayList<>();
        mappingProposalService.saveMappingVersion(source.sourceId(), proposals);

        // 7. Save onboarding request record
        OnboardingRequestRecord req = onboardingRepository.saveRequest(new OnboardingRequestRecord(
                requestId, user.userId(), source.sourceId(), "NEW_SOURCE", sampleMetadataJson, "SUBMITTED", LocalDateTime.now()
        ));

        log.info("Onboarding request {} submitted for source {} with raw API key generated", requestId, source.sourceId());

        return new OnboardingSubmissionResult(
                req.requestId(),
                source.sourceId(),
                vendor.vendorId(),
                rawApiKey,
                req.status(),
                "Onboarding request submitted successfully. Please save your API key now — for security reasons, it will not be displayed again. The key will become ACTIVE once approved by an administrator."
        );
    }

    public List<OnboardingRequestRecord> getAllRequests() {
        return onboardingRepository.findAllRequests();
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
        Optional<MappingVersionRecord> candidateOpt = mappingRepository.findActiveBySourceId(sourceId);
        if (candidateOpt.isPresent()) {
            mappingRepository.activateVersion(candidateOpt.get().mappingId(), sourceId);
        }
    }

    private String processAndStoreSampleFiles(String requestId, MultipartFile sampleLogFile, MultipartFile schemaFile) {
        Map<String, Object> metadata = new HashMap<>();

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