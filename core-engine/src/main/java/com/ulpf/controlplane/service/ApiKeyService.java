package com.ulpf.controlplane.service;

import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.SourceRepository;
import com.ulpf.common.db.SourceRepository.SourceRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.common.db.VendorRepository;
import com.ulpf.common.db.VendorRepository.VendorRecord;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Creates and replaces the ingestion API key of a log source. No key exists before an admin approves the source:
 * the vendor who owns the source creates it here, and later regenerates it here. The raw key is returned once and
 * never stored; only its SHA-256 hash goes to the {@code credentials} table. A regenerate revokes the old key in the
 * same transaction, so the old key stops working at once. An admin cannot reach this service (the
 * {@code /v1/onboard/**} rule admits USER and VENDOR only), so nobody but the vendor ever sees a key.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String KEY_PREFIX = "ulpf_live_";
    private static final int KEY_RANDOM_BYTES = 20; // 160 random bits, 40 hex characters
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final SourceRepository sourceRepository;
    private final CredentialRepository credentialRepository;
    private final OnboardingRepository onboardingRepository;

    /** A new key. {@code apiKey} is the raw key: show it once and do not log or store it. */
    public record IssuedApiKey(String sourceId, String apiKey, LocalDateTime createdAt, boolean replacedPreviousKey) {}

    public ApiKeyService(UserRepository userRepository, VendorRepository vendorRepository,
            SourceRepository sourceRepository, CredentialRepository credentialRepository,
            OnboardingRepository onboardingRepository) {
        this.userRepository = userRepository;
        this.vendorRepository = vendorRepository;
        this.sourceRepository = sourceRepository;
        this.credentialRepository = credentialRepository;
        this.onboardingRepository = onboardingRepository;
    }

    /**
     * Creates a key for an active source of the user's vendor. If the source already has a key, the call is refused
     * with {@code KEY_EXISTS} unless {@code replaceExisting} is true; then the old key is revoked first.
     */
    @Transactional
    public IssuedApiKey issueKey(String username, String sourceId, boolean replaceExisting) {
        Optional<User> user = userRepository.findByUsername(username);
        SourceRecord source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ApiKeyException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND",
                        "Log source not found: " + sourceId));
        VendorRecord vendor = vendorRepository.findById(source.vendorId())
                .filter(v -> user.isPresent() && v.ownerUserId().equals(user.get().userId()))
                .orElseThrow(() -> new ApiKeyException(HttpStatus.FORBIDDEN, "NOT_OWNER",
                        "You can only manage API keys for your own log sources"));
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(vendor.status())) {
            throw new ApiKeyException(HttpStatus.CONFLICT, "SOURCE_NOT_ACTIVE",
                    "An API key can be created only for an approved, active log source");
        }

        boolean hasKey = credentialRepository.findActiveBySourceId(sourceId).isPresent();
        if (hasKey && !replaceExisting) {
            throw new ApiKeyException(HttpStatus.CONFLICT, "KEY_EXISTS",
                    "This log source already has an API key. Regenerate it to replace the current key.");
        }
        if (hasKey) {
            credentialRepository.revokeActiveForSource(sourceId);
        }

        String rawKey = KEY_PREFIX + HexFormat.of().formatHex(randomBytes());
        CredentialRecord saved = credentialRepository.save(new CredentialRecord(
                null, sourceId, vendor.vendorId(), OnboardingService.hashSha256(rawKey), "ACTIVE", null));

        onboardingRepository.saveNotification(user.get().userId(),
                hasKey ? "API Key Regenerated" : "API Key Created",
                "A new API key was created for log source \"" + source.sourceName() + "\" (ID: " + sourceId + ")."
                        + (hasKey ? " The previous key no longer works." : "")
                        + " If you did not do this, regenerate the key and change your password.");
        log.info("API key {} for source {}", hasKey ? "regenerated" : "created", sourceId);

        LocalDateTime createdAt = saved.createdAt() != null ? saved.createdAt() : LocalDateTime.now();
        return new IssuedApiKey(sourceId, rawKey, createdAt, hasKey);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
