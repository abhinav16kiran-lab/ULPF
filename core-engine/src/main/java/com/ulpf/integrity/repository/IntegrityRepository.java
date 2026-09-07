package com.ulpf.integrity.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SQLite repository for managing batch_integrity_blocks records.
 * Provides block insertion, chronological hash chain retrieval, and latest root lookups.
 */
@Repository
public class IntegrityRepository {

    private static final Logger log = LoggerFactory.getLogger(IntegrityRepository.class);
    public static final String GENESIS_BLOCK_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final JdbcTemplate jdbcTemplate;

    public IntegrityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record IntegrityBlockRecord(
            Long blockId,
            String sourceId,
            Integer eventCount,
            String firstEventId,
            String lastEventId,
            String merkleRoot,
            String previousBlockHash,
            LocalDateTime createdAt
    ) {}

    public IntegrityBlockRecord saveBlock(IntegrityBlockRecord record) {
        String sql = """
            INSERT INTO batch_integrity_blocks (source_id, event_count, first_event_id, last_event_id, merkle_root, previous_block_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        LocalDateTime now = record.createdAt() != null ? record.createdAt() : LocalDateTime.now();

        jdbcTemplate.update(
                sql,
                record.sourceId(),
                record.eventCount(),
                record.firstEventId(),
                record.lastEventId(),
                record.merkleRoot(),
                record.previousBlockHash(),
                Timestamp.valueOf(now)
        );

        Long newId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);

        return new IntegrityBlockRecord(
                newId,
                record.sourceId(),
                record.eventCount(),
                record.firstEventId(),
                record.lastEventId(),
                record.merkleRoot(),
                record.previousBlockHash(),
                now
        );
    }

    public String findLatestBlockHash(String sourceId) {
        try {
            String sql = """
                SELECT merkle_root 
                FROM batch_integrity_blocks 
                WHERE source_id = ? 
                ORDER BY block_id DESC 
                LIMIT 1
                """;
            List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("merkle_root"), sourceId);
            if (!results.isEmpty()) {
                return results.get(0);
            }
        } catch (Exception e) {
            log.warn("Failed to lookup latest block hash for sourceId {}: {}", sourceId, e.getMessage());
        }
        return GENESIS_BLOCK_HASH;
    }

    public Optional<IntegrityBlockRecord> findBlockById(Long blockId) {
        try {
            String sql = """
                SELECT block_id, source_id, event_count, first_event_id, last_event_id, merkle_root, previous_block_hash, created_at
                FROM batch_integrity_blocks
                WHERE block_id = ?
                """;
            List<IntegrityBlockRecord> list = jdbcTemplate.query(sql, (rs, rowNum) -> new IntegrityBlockRecord(
                    rs.getLong("block_id"),
                    rs.getString("source_id"),
                    rs.getInt("event_count"),
                    rs.getString("first_event_id"),
                    rs.getString("last_event_id"),
                    rs.getString("merkle_root"),
                    rs.getString("previous_block_hash"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
            ), blockId);

            return list.stream().findFirst();
        } catch (Exception e) {
            log.warn("Failed to find integrity block by id {}: {}", blockId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<IntegrityBlockRecord> findAllBlocks(int limit) {
        String sql = """
            SELECT block_id, source_id, event_count, first_event_id, last_event_id, merkle_root, previous_block_hash, created_at
            FROM batch_integrity_blocks
            ORDER BY block_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IntegrityBlockRecord(
                rs.getLong("block_id"),
                rs.getString("source_id"),
                rs.getInt("event_count"),
                rs.getString("first_event_id"),
                rs.getString("last_event_id"),
                rs.getString("merkle_root"),
                rs.getString("previous_block_hash"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
        ), limit);
    }

    public List<IntegrityBlockRecord> findBlocksBySourceId(String sourceId, int limit) {
        String sql = """
            SELECT block_id, source_id, event_count, first_event_id, last_event_id, merkle_root, previous_block_hash, created_at
            FROM batch_integrity_blocks
            WHERE source_id = ?
            ORDER BY block_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IntegrityBlockRecord(
                rs.getLong("block_id"),
                rs.getString("source_id"),
                rs.getInt("event_count"),
                rs.getString("first_event_id"),
                rs.getString("last_event_id"),
                rs.getString("merkle_root"),
                rs.getString("previous_block_hash"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
        ), sourceId, limit);
    }
}
