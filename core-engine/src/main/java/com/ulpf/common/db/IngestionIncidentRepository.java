package com.ulpf.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository managing operational ingestion failure incidents, replay windows, and lifecycle states.
 */
@Repository
public class IngestionIncidentRepository {

    private static final Logger log = LoggerFactory.getLogger(IngestionIncidentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public record IncidentRecord(
            String incidentId,
            String sourceId,
            String failureType,
            String tableName,
            LocalDateTime startedAt,
            LocalDateTime lastSeenAt,
            LocalDateTime resolvedAt,
            long affectedCount,
            String status,
            String details
    ) {}

    private final RowMapper<IncidentRecord> rowMapper = (rs, rowNum) -> new IncidentRecord(
            rs.getString("incident_id"),
            rs.getString("source_id"),
            rs.getString("failure_type"),
            rs.getString("table_name"),
            rs.getString("started_at") != null ? LocalDateTime.parse(rs.getString("started_at")) : null,
            rs.getString("last_seen_at") != null ? LocalDateTime.parse(rs.getString("last_seen_at")) : null,
            rs.getString("resolved_at") != null ? LocalDateTime.parse(rs.getString("resolved_at")) : null,
            rs.getLong("affected_count"),
            rs.getString("status"),
            rs.getString("details")
    );

    public IngestionIncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createIncident(IncidentRecord record) {
        String sql = """
            INSERT INTO ingestion_alert_incidents
            (incident_id, source_id, failure_type, table_name, started_at, last_seen_at, resolved_at, affected_count, status, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                record.incidentId(),
                record.sourceId(),
                record.failureType(),
                record.tableName(),
                record.startedAt() != null ? record.startedAt().toString() : LocalDateTime.now().toString(),
                record.lastSeenAt() != null ? record.lastSeenAt().toString() : LocalDateTime.now().toString(),
                record.resolvedAt() != null ? record.resolvedAt().toString() : null,
                record.affectedCount(),
                record.status() != null ? record.status() : "OPEN",
                record.details()
        );
    }

    public void updateCountsAndLastSeen(String incidentId, long affectedCount, LocalDateTime lastSeenAt) {
        String sql = "UPDATE ingestion_alert_incidents SET affected_count = ?, last_seen_at = ? WHERE incident_id = ?";
        jdbcTemplate.update(sql, affectedCount, lastSeenAt != null ? lastSeenAt.toString() : LocalDateTime.now().toString(), incidentId);
    }

    public void resolveIncident(String incidentId, LocalDateTime resolvedAt) {
        String sql = "UPDATE ingestion_alert_incidents SET status = 'RESOLVED', resolved_at = ? WHERE incident_id = ?";
        jdbcTemplate.update(sql, resolvedAt != null ? resolvedAt.toString() : LocalDateTime.now().toString(), incidentId);
    }

    public Optional<IncidentRecord> findOpenBySourceAndType(String sourceId, String failureType) {
        String sql = "SELECT * FROM ingestion_alert_incidents WHERE source_id = ? AND failure_type = ? AND status = 'OPEN' ORDER BY started_at DESC LIMIT 1";
        List<IncidentRecord> results = jdbcTemplate.query(sql, rowMapper, sourceId, failureType);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<IncidentRecord> findOpenBySource(String sourceId) {
        String sql = "SELECT * FROM ingestion_alert_incidents WHERE source_id = ? AND status = 'OPEN' ORDER BY started_at ASC";
        return jdbcTemplate.query(sql, rowMapper, sourceId);
    }

    public List<IncidentRecord> findAllOpen() {
        String sql = "SELECT * FROM ingestion_alert_incidents WHERE status = 'OPEN' ORDER BY started_at ASC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    public Optional<IncidentRecord> findById(String incidentId) {
        String sql = "SELECT * FROM ingestion_alert_incidents WHERE incident_id = ?";
        List<IncidentRecord> results = jdbcTemplate.query(sql, rowMapper, incidentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
