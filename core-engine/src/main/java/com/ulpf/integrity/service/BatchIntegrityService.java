package com.ulpf.integrity.service;

// import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import com.ulpf.integrity.repository.IntegrityRepository;
import com.ulpf.integrity.repository.IntegrityRepository.IntegrityBlockRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Core orchestration service for Merkle Tree batch tamper-evidence and forensic
 * log verification.
 */
@Service
public class BatchIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(BatchIntegrityService.class);

    private final MerkleTreeCalculator merkleTreeCalculator;
    private final IntegrityRepository integrityRepository;
    private final JdbcTemplate clickhouseJdbcTemplate;

    public BatchIntegrityService(
            MerkleTreeCalculator merkleTreeCalculator,
            IntegrityRepository integrityRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.merkleTreeCalculator = merkleTreeCalculator;
        this.integrityRepository = integrityRepository;
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
    }

    public record VerificationResult(
            Long blockId,
            String sourceId,
            String firstEventId,
            String lastEventId,
            String databaseTable,
            String status,
            String storedMerkleRoot,
            String computedMerkleRoot,
            Integer eventCount,
            boolean isTampered,
            String message) {
    }

    public record BulkVerificationResult(
            int totalBlocksChecked,
            int validCount,
            int tamperedCount,
            List<VerificationResult> tamperedBlocks) {
    }

    /**
     * Processes a batch of raw log events, computes Merkle Root per source, and
     * chains block hashes.
     */
    public List<IntegrityBlockRecord> processRawBatch(List<RawEventRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }

        List<IntegrityBlockRecord> createdBlocks = new ArrayList<>();

        // Group batch events by sourceId
        Map<String, List<RawEventRecord>> bySource = batch.stream()
                .filter(r -> r.sourceId() != null)
                .collect(Collectors.groupingBy(RawEventRecord::sourceId));

        for (Map.Entry<String, List<RawEventRecord>> entry : bySource.entrySet()) {
            String sourceId = entry.getKey();
            List<RawEventRecord> sourceBatch = entry.getValue();

            if (sourceBatch.isEmpty())
                continue;

            // Sort batch events deterministically by timestamp and eventId to ensure 100% reproducible Merkle trees
            sourceBatch.sort(java.util.Comparator.comparing(RawEventRecord::receivedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                    .thenComparing(RawEventRecord::eventId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));

            // 1. Calculate SHA-256 for each full raw event record
            List<String> leafHashes = sourceBatch.stream()
                    .map(r -> MerkleTreeCalculator.hashSha256(
                            (r.eventId() != null ? r.eventId() : "") +
                            (r.vendorId() != null ? r.vendorId() : "") +
                            (r.sourceId() != null ? r.sourceId() : "") +
                            (r.lineageId() != null ? r.lineageId() : "") +
                            (r.rawPayload() != null ? r.rawPayload() : "")
                    ))
                    .collect(Collectors.toList());

            // 2. Compute Merkle Root
            String merkleRoot = merkleTreeCalculator.calculateMerkleRoot(leafHashes);

            // 3. Resolve previous block hash for chain integrity
            String previousBlockHash = integrityRepository.findLatestBlockHash(sourceId);

            String firstEventId = sourceBatch.get(0).eventId();
            String lastEventId = sourceBatch.get(sourceBatch.size() - 1).eventId();

            IntegrityBlockRecord blockToSave = new IntegrityBlockRecord(
                    null,
                    sourceId,
                    sourceBatch.size(),
                    firstEventId,
                    lastEventId,
                    merkleRoot,
                    previousBlockHash,
                    LocalDateTime.now());

            IntegrityBlockRecord savedBlock = integrityRepository.saveBlock(blockToSave);
            createdBlocks.add(savedBlock);

            log.info("Generated Merkle Integrity Block #{} for source {}: Root={} (prev={})",
                    savedBlock.blockId(), sourceId, merkleRoot.substring(0, 12) + "...",
                    previousBlockHash.substring(0, 12) + "...");
        }

        return createdBlocks;
    }

    /**
     * Verifies the cryptographic integrity of a batch by re-querying raw logs from
     * ClickHouse
     * and comparing the dynamic Merkle Root against the stored block record in
     * SQLite.
     */
    public VerificationResult verifyBlockIntegrity(Long blockId) {
        Optional<IntegrityBlockRecord> blockOpt = integrityRepository.findBlockById(blockId);
        if (blockOpt.isEmpty()) {
            return new VerificationResult(blockId, null, null, null, null, "NOT_FOUND", null, null, 0, true,
                    "Integrity block #" + blockId + " not found");
        }

        IntegrityBlockRecord block = blockOpt.get();

        List<RawEventRecord> rawRecords = fetchRawEventRecordsFromClickHouse(block);

        if (rawRecords.isEmpty()) {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    block.firstEventId(),
                    block.lastEventId(),
                    "ulpf_raw.raw_events",
                    "NO_DATA",
                    block.merkleRoot(),
                    null,
                    block.eventCount(),
                    true,
                    "No raw events found in ClickHouse storage for block verification");
        }

        List<String> leafHashes = rawRecords.stream()
                .map(r -> MerkleTreeCalculator.hashSha256(
                        (r.eventId() != null ? r.eventId() : "") +
                        (r.vendorId() != null ? r.vendorId() : "") +
                        (r.sourceId() != null ? r.sourceId() : "") +
                        (r.lineageId() != null ? r.lineageId() : "") +
                        (r.rawPayload() != null ? r.rawPayload() : "")
                ))
                .collect(Collectors.toList());

        String computedMerkleRoot = merkleTreeCalculator.calculateMerkleRoot(leafHashes);

        boolean matches = block.merkleRoot().equalsIgnoreCase(computedMerkleRoot);

        if (matches) {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    block.firstEventId(),
                    block.lastEventId(),
                    "ulpf_raw.raw_events",
                    "VALID",
                    block.merkleRoot(),
                    computedMerkleRoot,
                    rawRecords.size(),
                    false,
                    "Cryptographic Merkle Proof verified successfully! Log payloads match 100%.");
        } else {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    block.firstEventId(),
                    block.lastEventId(),
                    "ulpf_raw.raw_events",
                    "TAMPERED_DETECTED",
                    block.merkleRoot(),
                    computedMerkleRoot,
                    rawRecords.size(),
                    true,
                    "WARNING: Cryptographic mismatch detected! Log payloads have been modified or tampered with.");
        }
    }

    private List<RawEventRecord> fetchRawEventRecordsFromClickHouse(IntegrityBlockRecord block) {
        if (clickhouseJdbcTemplate == null || block == null) {
            return List.of();
        }
        try {
            String sourceId = block.sourceId();
            String firstEventId = block.firstEventId();
            String lastEventId = block.lastEventId();
            int limit = block.eventCount() != null ? block.eventCount() : 100;

            org.springframework.jdbc.core.RowMapper<RawEventRecord> rowMapper = (rs, rowNum) -> new RawEventRecord(
                rs.getString("event_id"),
                rs.getString("lineage_id"),
                rs.getString("vendor_id"),
                rs.getString("source_id"),
                rs.getObject("mapping_version") != null ? rs.getInt("mapping_version") : null,
                rs.getTimestamp("received_at") != null ? rs.getTimestamp("received_at").toLocalDateTime() : null,
                rs.getString("raw_payload")
            );

            if (firstEventId != null && firstEventId.equals(lastEventId)) {
                String sql = "SELECT event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload FROM ulpf_raw.raw_events WHERE source_id = ? AND event_id = ?";
                return clickhouseJdbcTemplate.query(sql, rowMapper, sourceId, firstEventId);
            }

            if (firstEventId != null && lastEventId != null) {
                String windowSql = """
                        SELECT event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload
                        FROM ulpf_raw.raw_events
                        WHERE source_id = ?
                          AND received_at >= (SELECT received_at FROM ulpf_raw.raw_events WHERE event_id = ? LIMIT 1)
                          AND received_at <= (SELECT received_at FROM ulpf_raw.raw_events WHERE event_id = ? LIMIT 1)
                        ORDER BY received_at ASC, event_id ASC
                        LIMIT ?
                        """;
                List<RawEventRecord> records = clickhouseJdbcTemplate.query(
                        windowSql,
                        rowMapper,
                        sourceId,
                        firstEventId,
                        lastEventId,
                        limit
                );
                if (!records.isEmpty()) {
                    return records;
                }
            }

            String fallbackSql = """
                    SELECT event_id, lineage_id, vendor_id, source_id, mapping_version, received_at, raw_payload
                    FROM ulpf_raw.raw_events
                    WHERE source_id = ?
                    ORDER BY received_at ASC, event_id ASC
                    LIMIT ?
                    """;
            return clickhouseJdbcTemplate.query(fallbackSql, rowMapper, sourceId, limit);
        } catch (Exception e) {
            log.warn("Could not fetch raw log records from ClickHouse for verification: {}", e.getMessage());
            return List.of();
        }
    }

    public List<IntegrityBlockRecord> getRecentBlocks(int limit) {
        return integrityRepository.findAllBlocks(limit);
    }

    public BulkVerificationResult verifyAllBlocks() {
        List<IntegrityBlockRecord> allBlocks = integrityRepository.findAllBlocks(100000);
        if (allBlocks.isEmpty()) {
            return new BulkVerificationResult(0, 0, 0, List.of());
        }

        List<VerificationResult> results = allBlocks.parallelStream()
                .map(block -> verifyBlockIntegrity(block.blockId()))
                .toList();

        int total = results.size();
        List<VerificationResult> tampered = results.stream().filter(VerificationResult::isTampered).toList();
        int tamperedCount = tampered.size();
        int validCount = total - tamperedCount;

        return new BulkVerificationResult(total, validCount, tamperedCount, tampered);
    }
}
