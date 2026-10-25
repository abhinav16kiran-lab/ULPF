package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Shared, stateless transformation utility for log mapping resolution and column extraction.
 * Reused across both real-time data ingestion and historical backfill replay jobs.
 */
@Component
public class MappingTransformer {

    private static final Logger log = LoggerFactory.getLogger(MappingTransformer.class);
    private final ObjectMapper objectMapper;

    public record TransformedResult(
            Map<String, Object> mappedFields,
            String canonicalPayloadJson,
            String rawUnmappedJson,
            Double numericValue
    ) {}

    public MappingTransformer() {
        this(new ObjectMapper());
    }

    public MappingTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public String resolveCanonicalField(String vendorKey, JsonNode mappingRoot) {
        if (mappingRoot == null || vendorKey == null) {
            return null;
        }

        JsonNode target = mappingRoot;
        if (!target.has(vendorKey) && target.has("mappings") && target.get("mappings").isObject()) {
            target = target.get("mappings");
        }

        if (!target.has(vendorKey)) {
            return null;
        }

        JsonNode fieldMapping = target.get(vendorKey);
        if (fieldMapping != null && fieldMapping.isObject()) {
            if (fieldMapping.has("canonicalField")) {
                String cf = fieldMapping.get("canonicalField").asText();
                if (cf != null && !cf.isBlank() && !"unmapped".equalsIgnoreCase(cf)) {
                    return cf;
                }
            } else if (fieldMapping.has("canonical_field")) {
                String cf = fieldMapping.get("canonical_field").asText();
                if (cf != null && !cf.isBlank() && !"unmapped".equalsIgnoreCase(cf)) {
                    return cf;
                }
            }
        } else if (fieldMapping != null && fieldMapping.isTextual()) {
            String cf = fieldMapping.asText();
            if (cf != null && !cf.isBlank() && !"unmapped".equalsIgnoreCase(cf)) {
                return cf;
            }
        }
        return null;
    }

    public boolean isKeyMappedInJson(String vendorKey, JsonNode mappingRoot) {
        return resolveCanonicalField(vendorKey, mappingRoot) != null;
    }

    public String normalizeColumnName(String canonicalField) {
        if (canonicalField == null) return "";
        return canonicalField.replace('.', '_').replace('-', '_').trim().toLowerCase();
    }

    public Double extractNumericValue(JsonNode node, String sensorField) {
        if (node == null) return null;
        try {
            if (sensorField != null && !sensorField.isBlank() && node.has(sensorField)) {
                JsonNode fieldNode = node.get(sensorField);
                if (fieldNode != null) {
                    if (fieldNode.isNumber()) {
                        return fieldNode.asDouble();
                    } else if (fieldNode.isTextual()) {
                        try {
                            return Double.parseDouble(fieldNode.asText().trim());
                        } catch (Exception ignored) {}
                    }
                }
            }
            for (String candidate : List.of("value", "reading", "temp", "temperature", "val", "metric")) {
                if (node.has(candidate) && node.get(candidate).isNumber()) {
                    return node.get(candidate).asDouble();
                }
            }
            if (node.isObject()) {
                var fields = node.properties().iterator();
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

    public Double extractNumericValue(Object payload, String sensorField) {
        if (payload == null) return null;
        if (payload instanceof JsonNode jn) {
            return extractNumericValue(jn, sensorField);
        }
        try {
            return extractNumericValue(objectMapper.valueToTree(payload), sensorField);
        } catch (Exception e) {
            return null;
        }
    }

    public TransformedResult transform(
            String rawJson,
            JsonNode mappingRoot,
            List<JsonNode> historicalMappingRoots,
            String sensorField
    ) {
        if (rawJson == null || rawJson.isBlank()) {
            return new TransformedResult(Collections.emptyMap(), "{}", "{}", null);
        }
        try {
            JsonNode payloadNode = objectMapper.readTree(rawJson);
            if (payloadNode == null || (!payloadNode.isObject() && !payloadNode.isArray())) {
                throw new IllegalArgumentException("Payload is not a valid JSON object or array");
            }
            return transform(payloadNode, mappingRoot, historicalMappingRoots, sensorField);
        } catch (Exception e) {
            log.warn("Error parsing raw JSON in transform: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JSON payload: " + e.getMessage(), e);
        }
    }

    public TransformedResult transform(
            JsonNode payloadNode,
            JsonNode mappingRoot,
            List<JsonNode> historicalMappingRoots,
            String sensorField
    ) {
        Map<String, Object> mappedFields = new LinkedHashMap<>();
        Map<String, Object> unmappedFields = new LinkedHashMap<>();
        Double numericVal = extractNumericValue(payloadNode, sensorField);

        if (payloadNode == null || !payloadNode.isObject()) {
            String raw = payloadNode != null ? payloadNode.toString() : "{}";
            return new TransformedResult(Collections.emptyMap(), "{}", raw, numericVal);
        }

        try {
            var fieldsIterator = payloadNode.properties().iterator();
            while (fieldsIterator.hasNext()) {
                var entry = fieldsIterator.next();
                String vendorKey = entry.getKey();
                JsonNode valNode = entry.getValue();

                String canonicalField = resolveCanonicalField(vendorKey, mappingRoot);
                if (canonicalField == null && historicalMappingRoots != null) {
                    for (JsonNode hist : historicalMappingRoots) {
                        canonicalField = resolveCanonicalField(vendorKey, hist);
                        if (canonicalField != null) break;
                    }
                }

                if (canonicalField != null && !canonicalField.isBlank() && !"unmapped".equalsIgnoreCase(canonicalField)) {
                    String normCol = normalizeColumnName(canonicalField);
                    if (valNode.isValueNode()) {
                        mappedFields.put(normCol, valNode.asText());
                    } else {
                        mappedFields.put(normCol, objectMapper.writeValueAsString(valNode));
                    }
                } else {
                    if (valNode.isValueNode()) {
                        unmappedFields.put(vendorKey, valNode.asText());
                    } else {
                        unmappedFields.put(vendorKey, valNode);
                    }
                }
            }

            String canonicalJson = mappedFields.isEmpty() ? "{}" : objectMapper.writeValueAsString(mappedFields);
            String unmappedJson = unmappedFields.isEmpty() ? "{}" : objectMapper.writeValueAsString(unmappedFields);

            return new TransformedResult(mappedFields, canonicalJson, unmappedJson, numericVal);
        } catch (Exception e) {
            log.warn("Error transforming payload: {}", e.getMessage());
            return new TransformedResult(mappedFields, "{}", "{}", numericVal);
        }
    }
}
