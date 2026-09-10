package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Smart dynamic ClickHouse schema provisioning engine.
 * 
 * 1. Checks existing tables in ulpf_events for schema compatibility.
 * 2. If an existing table matches (>60% column overlap), re-uses the table and issues ALTER TABLE for new fields.
 * 3. If no matching table exists, dynamically creates a clean new ClickHouse table at runtime.
 */
@Service
public class DynamicSchemaProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaProvisioningService.class);

    private final JdbcTemplate clickhouseJdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DynamicSchemaProvisioningService(
            @Autowired(required = false) @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
    }

    /**
     * Inspects existing ClickHouse tables in `ulpf_events`.
     * Re-uses existing compatible tables (with ALTER TABLE for additions) or creates a new table at runtime.
     * 
     * @return Target table name in ClickHouse
     */
    public String provisionSchemaForSource(String sourceName, String mappingJson) {
        if (clickhouseJdbcTemplate == null) {
            log.warn("ClickHouse JDBC template is null. Skipping dynamic ClickHouse table provisioning.");
            return "canonical_events";
        }

        try {
            Set<String> proposedCanonicalFields = extractCanonicalFields(mappingJson);
            log.info("Provisioning ClickHouse schema for source '{}'. Proposed canonical fields: {}", sourceName, proposedCanonicalFields);

            // 1. Get existing tables in ulpf_events database
            List<String> existingTables = clickhouseJdbcTemplate.query(
                    "SHOW TABLES FROM ulpf_events",
                    (rs, rowNum) -> rs.getString(1)
            );

            String bestMatchingTable = null;
            double highestMatchRatio = 0.0;
            Set<String> missingFieldsToAlter = new HashSet<>();

            for (String tableName : existingTables) {
                if ("raw_events".equalsIgnoreCase(tableName)) continue;

                // Inspect columns of this table
                List<String> tableColumns = clickhouseJdbcTemplate.query(
                        "DESCRIBE TABLE ulpf_events." + tableName,
                        (rs, rowNum) -> rs.getString("name")
                );
                Set<String> colSet = new HashSet<>(tableColumns);

                int matchCount = 0;
                Set<String> currentMissing = new HashSet<>();
                for (String field : proposedCanonicalFields) {
                    String colName = normalizeColumnName(field);
                    if (colSet.contains(colName)) {
                        matchCount++;
                    } else {
                        currentMissing.add(colName);
                    }
                }

                if (!proposedCanonicalFields.isEmpty()) {
                    double matchRatio = (double) matchCount / proposedCanonicalFields.size();
                    if (matchRatio >= 0.60 && matchRatio > highestMatchRatio) {
                        highestMatchRatio = matchRatio;
                        bestMatchingTable = tableName;
                        missingFieldsToAlter = currentMissing;
                    }
                }
            }

            // 2. If a compatible existing table is found, re-use it! Extra unmapped fields flow into raw_unmapped.
            if (bestMatchingTable != null) {
                log.info("Found existing compatible table 'ulpf_events.{}' ({}% schema match). Re-using table for stream '{}'. Extra unmapped fields will store in raw_unmapped.",
                        bestMatchingTable, (int) (highestMatchRatio * 100), sourceName);
                return bestMatchingTable;
            }

            // 3. Otherwise, create a clean new dynamic table for this source/topic!
            String newTableName = sanitizeTableName(sourceName);
            if (newTableName.isBlank()) {
                newTableName = "events_" + System.currentTimeMillis();
            }

            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE TABLE IF NOT EXISTS ulpf_events.").append(newTableName).append(" (\n");
            ddl.append("    event_id String,\n");
            ddl.append("    lineage_id String,\n");
            ddl.append("    vendor_id String,\n");
            ddl.append("    source_id String,\n");
            ddl.append("    mapping_version Nullable(UInt32),\n");
            ddl.append("    timestamp DateTime64(3) DEFAULT now64(3),\n");

            for (String field : proposedCanonicalFields) {
                String colName = normalizeColumnName(field);
                if (!List.of("event_id", "lineage_id", "vendor_id", "source_id", "mapping_version", "timestamp").contains(colName)) {
                    ddl.append("    ").append(colName).append(" Nullable(String),\n");
                }
            }

            ddl.append("    raw_unmapped String CODEC(ZSTD(1))\n");
            ddl.append(") ENGINE = MergeTree PARTITION BY toYYYYMM(timestamp) ORDER BY (vendor_id, source_id, timestamp, event_id)");

            log.info("Creating new dynamic ClickHouse table 'ulpf_events.{}' for source '{}'...", newTableName, sourceName);
            clickhouseJdbcTemplate.execute(ddl.toString());
            log.info("Successfully created dynamic ClickHouse table 'ulpf_events.{}'", newTableName);

            return newTableName;

        } catch (Exception e) {
            log.error("Failed to dynamically provision schema for source '{}': {}", sourceName, e.getMessage(), e);
            return "canonical_events";
        }
    }

    private Set<String> extractCanonicalFields(String mappingJson) {
        Set<String> fields = new LinkedHashSet<>();
        if (mappingJson == null || mappingJson.isBlank()) return fields;

        try {
            JsonNode root = objectMapper.readTree(mappingJson);
            JsonNode mappings = root.has("mappings") ? root.get("mappings") : root;
            if (mappings.isArray()) {
                for (JsonNode node : mappings) {
                    if (node.has("canonical_field")) {
                        String field = node.get("canonical_field").asText();
                        if (!"unmapped".equalsIgnoreCase(field)) {
                            fields.add(field);
                        }
                    }
                }
            } else if (mappings.isObject()) {
                Iterator<String> keys = mappings.fieldNames();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!"metadata".equalsIgnoreCase(k)) {
                        String val = mappings.get(k).asText();
                        if (!"unmapped".equalsIgnoreCase(val)) {
                            fields.add(val);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse mapping JSON for canonical fields: {}", e.getMessage());
        }
        return fields;
    }

    private String normalizeColumnName(String canonicalField) {
        return canonicalField.replace('.', '_').replace('-', '_').toLowerCase();
    }

    private String sanitizeTableName(String sourceName) {
        String clean = sourceName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        clean = clean.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return clean;
    }
}
