package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.CanonicalEventRecord;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import com.ulpf.common.db.CredentialRepository;
import com.ulpf.common.db.CredentialRepository.CredentialRecord;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating credential authentication, active mapping resolution,
 * P1 sensor optimization divergence, raw log preservation, and canonical emission buffering.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final CredentialRepository credentialRepository;
    private final MappingRepository mappingRepository;
    private final ClickHouseIngestionRepository clickHouseIngestionRepository;
    private final SensorTelemetryEvaluator sensorTelemetryEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventIngestionService(
            CredentialRepository credentialRepository,
            MappingRepository mappingRepository,
            ClickHouseIngestionRepository clickHouseIngestionRepository,
            SensorTelemetryEvaluator sensorTelemetryEvaluator
    ) {
        this.credentialRepository = credentialRepository;
        this.mappingRepository = mappingRepository;
        this.clickHouseIngestionRepository = clickHouseIngestionRepository;
        this.sensorTelemetryEvaluator = sensorTelemetryEvaluator;
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
        LocalDateTime now = LocalDateTime.now();

        // Resolve active mapping version if present (lazy-loaded from SQLite/RAM)
        Optional<MappingVersionRecord> mappingOpt = mappingRepository.findActiveBySourceId(cred.sourceId());
        Integer mappingVersion = mappingOpt.map(MappingVersionRecord::version).orElse(null);

        // Inspect metadata from active mapping_json
        String logType = "REG_LOG";
        Double delta = null;
        Long maxIntervalMs = 60000L;
        String sensorField = null;

        if (mappingOpt.isPresent()) {
            try {
                JsonNode mappingRoot = objectMapper.readTree(mappingOpt.get().mappingJson());
                if (mappingRoot.has("metadata")) {
                    JsonNode meta = mappingRoot.get("metadata");
                    if (meta.has("log_type")) {
                        logType = meta.get("log_type").asText("REG_LOG");
                    }
                    if (meta.has("delta") && !meta.get("delta").isNull()) {
                        delta = meta.get("delta").asDouble();
                    }
                    if (meta.has("max_interval_ms") && !meta.get("max_interval_ms").isNull()) {
                        maxIntervalMs = meta.get("max_interval_ms").asLong(60000L);
                    }
                    if (meta.has("sensor_field") && !meta.get("sensor_field").isNull()) {
                        sensorField = meta.get("sensor_field").asText();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadata from mappingJson for source {}: {}", cred.sourceId(), e.getMessage());
            }
        }

        boolean isSensor = "SEN_TEL".equalsIgnoreCase(logType) || "SENSOR".equalsIgnoreCase(logType);

        // Assign active lineage ID: for SEN_TEL logs, share active window lineage ID; for REG_LOG, generate 1:1 ID
        String lineageId = isSensor 
                ? sensorTelemetryEvaluator.getOrCreateLineageId(cred.sourceId())
                : UUID.randomUUID().toString();

        // Serialize raw payload to JSON
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            rawJson = String.valueOf(payload);
        }

        // STEP 1: Always preserve raw event reading in ClickHouse ulpf_raw.raw_events
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

        // STEP 2: Evaluator Divergence Path
        if (isSensor) {
            Double numericVal = extractNumericValue(payload, sensorField);
            SensorTelemetryEvaluator.EvaluationResult evalResult = 
                    sensorTelemetryEvaluator.evaluate(cred.sourceId(), numericVal, delta, maxIntervalMs);

            if (evalResult.shouldEmit()) {
                CanonicalEventRecord canonicalEvent = new CanonicalEventRecord(
                        eventId,
                        evalResult.lineageId(),
                        cred.vendorId(),
                        cred.sourceId(),
                        mappingVersion,
                        now,
                        evalResult.value(),
                        rawJson
                );
                clickHouseIngestionRepository.enqueueCanonical(canonicalEvent);
            }
        } else {
            // Regular log path: emit canonical event immediately
            CanonicalEventRecord canonicalEvent = new CanonicalEventRecord(
                    eventId,
                    lineageId,
                    cred.vendorId(),
                    cred.sourceId(),
                    mappingVersion,
                    now,
                    null,
                    rawJson
            );
            clickHouseIngestionRepository.enqueueCanonical(canonicalEvent);
        }

        return new IngestResult(eventId, cred.vendorId(), cred.sourceId(), "ACCEPTED", now);
    }

    private Double extractNumericValue(Object payload, String sensorField) {
        if (payload == null) return null;
        try {
            JsonNode node = (payload instanceof JsonNode jn) ? jn : objectMapper.valueToTree(payload);
            if (sensorField != null && !sensorField.isBlank() && node.has(sensorField)) {
                JsonNode fieldNode = node.get(sensorField);
                if (fieldNode.isNumber()) {
                    return fieldNode.asDouble();
                } else {
                    try {
                        return Double.parseDouble(fieldNode.asText());
                    } catch (Exception ignored) {}
                }
            }
            for (String candidate : List.of("value", "reading", "temp", "temperature", "val", "metric")) {
                if (node.has(candidate) && node.get(candidate).isNumber()) {
                    return node.get(candidate).asDouble();
                }
            }
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (entry.getValue().isNumber()) {
                        return entry.getValue().asDouble();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}