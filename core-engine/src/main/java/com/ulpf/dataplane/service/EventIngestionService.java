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
    private final SchemaDriftNotificationService schemaDriftNotificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventIngestionService(
            CredentialRepository credentialRepository,
            MappingRepository mappingRepository,
            ClickHouseIngestionRepository clickHouseIngestionRepository,
            SensorTelemetryEvaluator sensorTelemetryEvaluator
    ) {
        this(credentialRepository, mappingRepository, clickHouseIngestionRepository, sensorTelemetryEvaluator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EventIngestionService(
            CredentialRepository credentialRepository,
            MappingRepository mappingRepository,
            ClickHouseIngestionRepository clickHouseIngestionRepository,
            SensorTelemetryEvaluator sensorTelemetryEvaluator,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SchemaDriftNotificationService schemaDriftNotificationService
    ) {
        this.credentialRepository = credentialRepository;
        this.mappingRepository = mappingRepository;
        this.clickHouseIngestionRepository = clickHouseIngestionRepository;
        this.sensorTelemetryEvaluator = sensorTelemetryEvaluator;
        this.schemaDriftNotificationService = schemaDriftNotificationService;
    }

    public record IngestResult(String eventId, String vendorId, String sourceId, String status, LocalDateTime receivedAt, String traceId) {
        public IngestResult(String eventId, String vendorId, String sourceId, String status, LocalDateTime receivedAt) {
            this(eventId, vendorId, sourceId, status, receivedAt, null);
        }
    }

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
        return ingest(apiKey, payload, null);
    }

    public IngestResult ingest(String apiKey, Object payload, com.ulpf.common.tracing.TraceContext traceContext) {
        Optional<CredentialRecord> credOpt = resolveCredentialFromApiKey(apiKey);
        if (credOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid API key credential");
        }

        CredentialRecord cred = credOpt.get();
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // Extract or refine trace context from payload and MDC
        com.ulpf.common.tracing.TraceContext activeTraceContext = traceContext;
        if (activeTraceContext == null) {
            activeTraceContext = com.ulpf.common.tracing.TraceContextExtractor.extract(null, payload);
        }
        com.ulpf.common.tracing.TraceMdcAdapter.put(activeTraceContext);
        String traceId = activeTraceContext != null ? activeTraceContext.traceId() : null;

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

        // Evaluate sensor stream or assign regular log lineage ID atomically
        String lineageId;
        SensorTelemetryEvaluator.EvaluationResult evalResult = null;

        if (isSensor) {
            Double numericVal = extractNumericValue(payload, sensorField);
            evalResult = sensorTelemetryEvaluator.evaluate(cred.sourceId(), numericVal, delta, maxIntervalMs);
            lineageId = evalResult.lineageId();
        } else {
            lineageId = UUID.randomUUID().toString();
        }

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

        // Extract unmapped fields into Lossless Overflow Field (raw_unmapped JSON)
        String rawUnmappedJson = extractUnmappedFields(payload, mappingOpt, cred.sourceId());

        // STEP 2: Evaluator Divergence Path
        if (isSensor) {
            if (evalResult != null && evalResult.shouldEmit()) {
                CanonicalEventRecord canonicalEvent = new CanonicalEventRecord(
                        eventId,
                        lineageId,
                        cred.vendorId(),
                        cred.sourceId(),
                        mappingVersion,
                        now,
                        evalResult.value(),
                        rawJson,
                        rawUnmappedJson
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
                    rawJson,
                    rawUnmappedJson
            );
            clickHouseIngestionRepository.enqueueCanonical(canonicalEvent);
        }


        return new IngestResult(eventId, cred.vendorId(), cred.sourceId(), "ACCEPTED", now, traceId);
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

    private String extractUnmappedFields(Object payload, Optional<MappingVersionRecord> mappingOpt, String sourceId) {
        if (payload == null) {
            return "{}";
        }
        try {
            JsonNode payloadNode;
            if (payload instanceof JsonNode jn) {
                payloadNode = jn;
            } else if (payload instanceof String strPayload) {
                try {
                    payloadNode = objectMapper.readTree(strPayload);
                } catch (Exception e) {
                    payloadNode = objectMapper.valueToTree(payload);
                }
            } else {
                payloadNode = objectMapper.valueToTree(payload);
            }

            if (payloadNode == null || !payloadNode.isObject()) {
                return "{}";
            }

            JsonNode mappingRoot = null;
            if (mappingOpt.isPresent()) {
                try {
                    mappingRoot = objectMapper.readTree(mappingOpt.get().mappingJson());
                } catch (Exception e) {
                    log.warn("Failed to parse mappingJson in extractUnmappedFields: {}", e.getMessage());
                }
            }

            com.fasterxml.jackson.databind.node.ObjectNode unmappedNode = objectMapper.createObjectNode();
            var fields = payloadNode.fields();

            List<HistoricalVersion> historicalVersions = null;
            java.util.Map<Integer, java.util.Set<String>> fallbackKeysByVersion = new java.util.HashMap<>();

            while (fields.hasNext()) {
                var entry = fields.next();
                String vendorKey = entry.getKey();
                JsonNode value = entry.getValue();

                boolean isMapped = isKeyMappedInJson(vendorKey, mappingRoot);

                // Fallback check against historical/retired versions if not mapped in active version
                if (!isMapped && sourceId != null && mappingRepository != null) {
                    if (historicalVersions == null) {
                        historicalVersions = loadHistoricalVersions(sourceId, mappingOpt);
                    }
                    for (HistoricalVersion hist : historicalVersions) {
                        if (isKeyMappedInJson(vendorKey, hist.rootNode())) {
                            isMapped = true;
                            fallbackKeysByVersion.computeIfAbsent(hist.version(), k -> new java.util.HashSet<>()).add(vendorKey);
                            log.debug("Field '{}' matched against historical mapping version v{} for source {}", vendorKey, hist.version(), sourceId);
                            break;
                        }
                    }
                }

                if (!isMapped) {
                    unmappedNode.set(vendorKey, value);
                }
            }

            // Dispatch deduplicated notification to vendor if historical fallback occurred
            if (!fallbackKeysByVersion.isEmpty() && schemaDriftNotificationService != null && sourceId != null) {
                for (var entry : fallbackKeysByVersion.entrySet()) {
                    schemaDriftNotificationService.notifyHistoricalFallback(sourceId, entry.getKey(), entry.getValue());
                }
            } else if (fallbackKeysByVersion.isEmpty() && schemaDriftNotificationService != null && sourceId != null && mappingOpt.isPresent()) {
                // If log stream fully resumed active mapping (0 fallbacks) and previously had a fallback alert active
                if (schemaDriftNotificationService.hasFallbackAlert(sourceId)) {
                    schemaDriftNotificationService.notifyVersionUpgrade(sourceId, mappingOpt.get().version());
                }
            }

            if (!unmappedNode.isEmpty()) {
                if (schemaDriftNotificationService != null && sourceId != null) {
                    java.util.Set<String> unmappedKeys = new java.util.HashSet<>();
                    var fieldNames = unmappedNode.fieldNames();
                    while (fieldNames.hasNext()) {
                        unmappedKeys.add(fieldNames.next());
                    }
                    schemaDriftNotificationService.checkAndNotifyDrift(sourceId, unmappedKeys);
                }
                return objectMapper.writeValueAsString(unmappedNode);
            }

            return "{}";
        } catch (Exception e) {
            log.warn("Failed to extract unmapped fields: {}", e.getMessage());
            return "{}";
        }
    }

    private record HistoricalVersion(int version, JsonNode rootNode) {}

    private boolean isKeyMappedInJson(String vendorKey, JsonNode mappingRoot) {
        if (mappingRoot == null || !mappingRoot.has(vendorKey)) {
            return false;
        }
        JsonNode fieldMapping = mappingRoot.get(vendorKey);
        if (fieldMapping != null && fieldMapping.isObject() && fieldMapping.has("canonicalField")) {
            String canonicalField = fieldMapping.get("canonicalField").asText();
            return canonicalField != null && !canonicalField.isBlank() && !"unmapped".equalsIgnoreCase(canonicalField);
        } else if (fieldMapping != null && fieldMapping.isTextual()) {
            String canonicalField = fieldMapping.asText();
            return canonicalField != null && !canonicalField.isBlank() && !"unmapped".equalsIgnoreCase(canonicalField);
        }
        return false;
    }

    private List<HistoricalVersion> loadHistoricalVersions(String sourceId, Optional<MappingVersionRecord> activeMappingOpt) {
        List<HistoricalVersion> list = new java.util.ArrayList<>();
        try {
            List<MappingVersionRecord> allVersions = mappingRepository.findAllValidVersionsBySourceId(sourceId);
            for (MappingVersionRecord ver : allVersions) {
                if (activeMappingOpt.isPresent() && activeMappingOpt.get().mappingId() != null
                        && activeMappingOpt.get().mappingId().equals(ver.mappingId())) {
                    continue;
                }
                if (ver.mappingJson() != null && !ver.mappingJson().isBlank()) {
                    try {
                        list.add(new HistoricalVersion(ver.version(), objectMapper.readTree(ver.mappingJson())));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load historical mapping versions for source {}: {}", sourceId, e.getMessage());
        }
        return list;
    }
}