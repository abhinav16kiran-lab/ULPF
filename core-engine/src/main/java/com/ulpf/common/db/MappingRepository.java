package com.ulpf.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for mapping versions with lazy-loading active mapping cache and 5-minute idle eviction.
 */
@Repository
public class MappingRepository {

    private static final Logger log = LoggerFactory.getLogger(MappingRepository.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${mapping.active-cache.idle-timeout-ms:300000}")
    private long idleTimeoutMs;

    private final Map<String, CachedMapping> activeMappingCache = new ConcurrentHashMap<>();

    public MappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record MappingVersionRecord(
        String mappingId,
        String sourceId,
        int version,
        String mappingJson,
        String status,
        LocalDateTime createdAt
    ) {}

    private record CachedMapping(
        MappingVersionRecord record,
        long lastAccessedTime
    ) {}

    private static final RowMapper<MappingVersionRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new MappingVersionRecord(
            rs.getString("mapping_id"),
            rs.getString("source_id"),
            rs.getInt("version"),
            rs.getString("mapping_json"),
            rs.getString("status"),
            createdAt
        );
    };

    /**
     * Finds active mapping for sourceId, lazy-loading from SQLite on demand and caching in RAM.
     */
    public Optional<MappingVersionRecord> findActiveBySourceId(String sourceId) {
        CachedMapping cached = activeMappingCache.get(sourceId);
        long now = System.currentTimeMillis();

        if (cached != null) {
            // Cache hit: update access timestamp
            activeMappingCache.put(sourceId, new CachedMapping(cached.record(), now));
            return Optional.of(cached.record());
        }

        // Cache miss: query SQLite
        String sql = """
            SELECT mapping_id, source_id, version, mapping_json, status, created_at
            FROM mapping_versions
            WHERE source_id = ? AND status = 'ACTIVE'
            ORDER BY version DESC
            """;

        List<MappingVersionRecord> results = jdbcTemplate.query(sql, ROW_MAPPER, sourceId);
        Optional<MappingVersionRecord> activeOpt = results.stream().findFirst();

        if (activeOpt.isPresent()) {
            activeMappingCache.put(sourceId, new CachedMapping(activeOpt.get(), now));
            log.info("Lazy-loaded active mapping version for sourceId: {} into RAM cache", sourceId);
        }

        return activeOpt;
    }

    public MappingVersionRecord saveMappingVersion(MappingVersionRecord mapping) {
        // Invalidate cache for sourceId first so next event gets fresh version
        if (mapping.sourceId() != null) {
            activeMappingCache.remove(mapping.sourceId());
        }

        String id = (mapping.mappingId() != null && !mapping.mappingId().isBlank())
            ? mapping.mappingId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO mapping_versions (mapping_id, source_id, version, mapping_json, status)
            VALUES (?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, mapping.sourceId(), mapping.version(), mapping.mappingJson(), mapping.status());

        return new MappingVersionRecord(id, mapping.sourceId(), mapping.version(), mapping.mappingJson(), mapping.status(), LocalDateTime.now());
    }

    public int getNextVersionNumber(String sourceId) {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM mapping_versions WHERE source_id = ?";
        Integer nextVer = jdbcTemplate.queryForObject(sql, Integer.class, sourceId);
        return nextVer != null ? nextVer : 1;
    }

    public void activateVersion(String mappingId, String sourceId) {
        // Invalidate cache entry FIRST before updating database status
        if (sourceId != null) {
            activeMappingCache.remove(sourceId);
        }

        // Retire existing active versions for this source
        String retireSql = "UPDATE mapping_versions SET status = 'RETIRED' WHERE source_id = ? AND status = 'ACTIVE'";
        jdbcTemplate.update(retireSql, sourceId);

        // Activate specified mapping
        String activateSql = "UPDATE mapping_versions SET status = 'ACTIVE' WHERE mapping_id = ?";
        jdbcTemplate.update(activateSql, mappingId);
    }

    /**
     * Finds the CANDIDATE mapping version for a given source ID.
     */
    public Optional<MappingVersionRecord> findCandidateBySourceId(String sourceId) {
        String sql = """
            SELECT mapping_id, source_id, version, mapping_json, status, created_at
            FROM mapping_versions
            WHERE source_id = ? AND status = 'CANDIDATE'
            ORDER BY version DESC
            """;

        List<MappingVersionRecord> results = jdbcTemplate.query(sql, ROW_MAPPER, sourceId);
        return results.stream().findFirst();
    }

    /**
     * Updates the mapping_json blob for a CANDIDATE mapping version (Admin editing feature).
     */
    public void updateCandidateMappingJson(String sourceId, String newMappingJson) {
        String sql = "UPDATE mapping_versions SET mapping_json = ? WHERE source_id = ? AND status = 'CANDIDATE'";
        jdbcTemplate.update(sql, newMappingJson, sourceId);
    }

    /**
     * Drops all CANDIDATE mapping versions for a source ID upon request rejection.
     */
    public void deleteCandidateVersions(String sourceId) {
        String sql = "DELETE FROM mapping_versions WHERE source_id = ? AND status = 'CANDIDATE'";
        jdbcTemplate.update(sql, sourceId);
    }

    /**
     * Drops a specific CANDIDATE mapping version by mapping ID upon request rejection.
     * Prevents accidental deletion of concurrent candidate requests for the same source.
     */
    public void deleteCandidateByMappingId(String mappingId) {
        String sql = "DELETE FROM mapping_versions WHERE mapping_id = ? AND status = 'CANDIDATE'";
        jdbcTemplate.update(sql, mappingId);
    }

    /**
     * Finds all valid (ACTIVE and RETIRED) mapping versions for a source ordered by version DESC.
     * Used for multi-version fallback matching during log ingestion.
     */
    public List<MappingVersionRecord> findAllValidVersionsBySourceId(String sourceId) {
        String sql = """
            SELECT mapping_id, source_id, version, mapping_json, status, created_at
            FROM mapping_versions
            WHERE source_id = ? AND status IN ('ACTIVE', 'RETIRED')
            ORDER BY version DESC
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, sourceId);
    }

    public void invalidateSource(String sourceId) {
        if (sourceId != null) {
            activeMappingCache.remove(sourceId);
        }
    }

    /**
     * Background scheduler running every 60 seconds to evict inactive mapping cache entries (> idleTimeoutMs).
     */
    @Scheduled(fixedDelay = 60000)
    public void evictIdleMappings() {
        long now = System.currentTimeMillis();
        activeMappingCache.entrySet().removeIf(entry -> {
            boolean isIdle = (now - entry.getValue().lastAccessedTime()) > idleTimeoutMs;
            if (isIdle) {
                log.info("Evicting active mapping cache for idle sourceId: {} from RAM", entry.getKey());
            }
            return isIdle;
        });
    }

    public void clearCache() {
        activeMappingCache.clear();
    }
}
