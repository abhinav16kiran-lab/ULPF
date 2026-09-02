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
        String id = (mapping.mappingId() != null && !mapping.mappingId().isBlank())
            ? mapping.mappingId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO mapping_versions (mapping_id, source_id, version, mapping_json, status)
            VALUES (?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, mapping.sourceId(), mapping.version(), mapping.mappingJson(), mapping.status());

        // Invalidate cache for sourceId so next event gets fresh version
        activeMappingCache.remove(mapping.sourceId());

        return new MappingVersionRecord(id, mapping.sourceId(), mapping.version(), mapping.mappingJson(), mapping.status(), LocalDateTime.now());
    }

    public int getNextVersionNumber(String sourceId) {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM mapping_versions WHERE source_id = ?";
        Integer nextVer = jdbcTemplate.queryForObject(sql, Integer.class, sourceId);
        return nextVer != null ? nextVer : 1;
    }

    public void activateVersion(String mappingId, String sourceId) {
        // Retire existing active versions for this source
        String retireSql = "UPDATE mapping_versions SET status = 'RETIRED' WHERE source_id = ? AND status = 'ACTIVE'";
        jdbcTemplate.update(retireSql, sourceId);

        // Activate specified mapping
        String activateSql = "UPDATE mapping_versions SET status = 'ACTIVE' WHERE mapping_id = ?";
        jdbcTemplate.update(activateSql, mappingId);

        // Invalidate cache entry
        activeMappingCache.remove(sourceId);
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
