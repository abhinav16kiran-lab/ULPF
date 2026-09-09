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
            String status,
            String storedMerkleRoot,
            String computedMerkleRoot,
            Integer eventCount,
            boolean isTampered,
            String message) {
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

            // 1. Calculate SHA-256 for each raw payload
            List<String> leafHashes = sourceBatch.stream()
                    .map(r -> MerkleTreeCalculator.hashSha256(r.rawPayload()))
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
            return new VerificationResult(blockId, null, "NOT_FOUND", null, null, 0, true,
                    "Integrity block #" + blockId + " not found");
        }

        IntegrityBlockRecord block = blockOpt.get();

        List<String> rawPayloads = fetchRawPayloadsFromClickHouse(block.sourceId(), block.eventCount());

        if (rawPayloads.isEmpty()) {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    "NO_DATA",
                    block.merkleRoot(),
                    null,
                    block.eventCount(),
                    true,
                    "No raw events found in ClickHouse storage for block verification");
        }

        List<String> leafHashes = rawPayloads.stream()
                .map(MerkleTreeCalculator::hashSha256)
                .collect(Collectors.toList());

        String computedMerkleRoot = merkleTreeCalculator.calculateMerkleRoot(leafHashes);

        boolean matches = block.merkleRoot().equalsIgnoreCase(computedMerkleRoot);

        if (matches) {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    "VALID",
                    block.merkleRoot(),
                    computedMerkleRoot,
                    rawPayloads.size(),
                    false,
                    "Cryptographic Merkle Proof verified successfully! Log payloads match 100%.");
        } else {
            return new VerificationResult(
                    blockId,
                    block.sourceId(),
                    "TAMPERED_DETECTED",
                    block.merkleRoot(),
                    computedMerkleRoot,
                    rawPayloads.size(),
                    true,
                    "WARNING: Cryptographic mismatch detected! Log payloads have been modified or tampered with.");
        }
    }

    private List<String> fetchRawPayloadsFromClickHouse(String sourceId, int limit) {
        if (clickhouseJdbcTemplate == null) {
            return List.of();
        }
        try {
            String sql = """
                    SELECT raw_payload
                    FROM ulpf_raw.raw_events
                    WHERE source_id = ?
                    ORDER BY received_at DESC
                    LIMIT ?
                    """;
            return clickhouseJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("raw_payload"), sourceId, limit);
        } catch (Exception e) {
            log.warn("Could not fetch raw log payloads from ClickHouse for verification: {}", e.getMessage());
            return List.of();
        }
    }

    public List<IntegrityBlockRecord> getRecentBlocks(int limit) {
        return integrityRepository.findAllBlocks(limit);
    }
}
