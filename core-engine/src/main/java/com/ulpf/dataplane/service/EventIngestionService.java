package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating credential authentication, active mapping resolution, and raw ClickHouse ingestion buffering.
 */
@Service
public class EventIngestionService {

    private final CredentialRepository credentialRepository;
    private final MappingRepository mappingRepository;
    private final ClickHouseIngestionRepository clickHouseIngestionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventIngestionService(
            CredentialRepository credentialRepository,
            MappingRepository mappingRepository,
            ClickHouseIngestionRepository clickHouseIngestionRepository
    ) {
        this.credentialRepository = credentialRepository;
        this.mappingRepository = mappingRepository;
        this.clickHouseIngestionRepository = clickHouseIngestionRepository;
    }

    public record IngestResult(String eventId, String vendorId, String sourceId, String status, LocalDateTime receivedAt) {}

    public Optional<CredentialRecord> resolveCredentialFromApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String keyHash = com.ulpf.controlplane.service.OnboardingService.hashSha256(apiKey);
        return credentialRepository.findActiveByKeyHash(keyHash);
    }

    public String resolveVendorFromApiKey(String apiKey) {
        return resolveCredentialFromApiKey(apiKey)
                .map(CredentialRecord::vendorId)
                .orElse(null);
    }

    public IngestResult ingest(String apiKey, Object payload) {
        Optional<CredentialRecord> credOpt = resolveCredentialFromApiKey(apiKey);
        if (credOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid API key credential");
        }

        CredentialRecord cred = credOpt.get();
        String eventId = UUID.randomUUID().toString();
        String lineageId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // Resolve active mapping version if present (lazy-loaded from SQLite)
        Optional<MappingVersionRecord> mappingOpt = mappingRepository.findActiveBySourceId(cred.sourceId());
        Integer mappingVersion = mappingOpt.map(MappingVersionRecord::version).orElse(null);

        // Serialize payload to JSON string
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            rawJson = payload.toString();
        }

        // Buffer raw event in ClickHouse queue
        RawEventRecord rawEvent = new RawEventRecord(
                eventId,
                lineageId,
                cred.vendorId(),
                cred.sourceId(),
                mappingVersion,
                now,
                rawJson
        );

        clickHouseIngestionRepository.enqueue(rawEvent);

        return new IngestResult(eventId, cred.vendorId(), cred.sourceId(), "ACCEPTED", now);
    }
}