package com.ulpf.common.db;

import com.ulpf.common.db.IngestionIncidentRepository.IncidentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IngestionIncidentRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private IngestionIncidentRepository repository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS ingestion_alert_incidents (
                incident_id     TEXT PRIMARY KEY,
                source_id       TEXT NOT NULL,
                failure_type    TEXT NOT NULL,
                table_name      TEXT,
                started_at      TEXT NOT NULL,
                last_seen_at    TEXT NOT NULL,
                resolved_at     TEXT,
                affected_count  INTEGER NOT NULL DEFAULT 0,
                status          TEXT NOT NULL DEFAULT 'OPEN',
                details         TEXT
            );
        """);

        repository = new IngestionIncidentRepository(jdbcTemplate);
    }

    @Test
    void testCreateAndFindById() {
        LocalDateTime now = LocalDateTime.now();
        IncidentRecord record = new IncidentRecord(
                "inc-101",
                "src_1",
                "TABLE_MISSING",
                "events_test",
                now,
                now,
                null,
                50,
                "OPEN",
                "Table missing error"
        );

        repository.createIncident(record);

        Optional<IncidentRecord> found = repository.findById("inc-101");
        assertTrue(found.isPresent());
        assertEquals("inc-101", found.get().incidentId());
        assertEquals("src_1", found.get().sourceId());
        assertEquals("TABLE_MISSING", found.get().failureType());
        assertEquals("events_test", found.get().tableName());
        assertEquals(50, found.get().affectedCount());
        assertEquals("OPEN", found.get().status());
        assertNull(found.get().resolvedAt());
    }

    @Test
    void testUpdateCountsAndLastSeen() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(5);
        IncidentRecord record = new IncidentRecord(
                "inc-102",
                "src_1",
                "INSERT_FAILURE",
                "events_test",
                start,
                start,
                null,
                10,
                "OPEN",
                "Insert failed"
        );
        repository.createIncident(record);

        LocalDateTime lastSeen = LocalDateTime.now();
        repository.updateCountsAndLastSeen("inc-102", 150, lastSeen);

        Optional<IncidentRecord> found = repository.findById("inc-102");
        assertTrue(found.isPresent());
        assertEquals(150, found.get().affectedCount());
        assertEquals(lastSeen.withNano(0), found.get().lastSeenAt().withNano(0));
    }

    @Test
    void testResolveIncident() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(10);
        IncidentRecord record = new IncidentRecord(
                "inc-103",
                "src_2",
                "TABLE_MISSING",
                "events_test2",
                start,
                start,
                null,
                25,
                "OPEN",
                "Table missing"
        );
        repository.createIncident(record);

        LocalDateTime resolvedAt = LocalDateTime.now();
        repository.resolveIncident("inc-103", resolvedAt);

        Optional<IncidentRecord> found = repository.findById("inc-103");
        assertTrue(found.isPresent());
        assertEquals("RESOLVED", found.get().status());
        assertNotNull(found.get().resolvedAt());
        assertEquals(resolvedAt.withNano(0), found.get().resolvedAt().withNano(0));

        // When resolved, finding open by source and type returns empty
        Optional<IncidentRecord> open = repository.findOpenBySourceAndType("src_2", "TABLE_MISSING");
        assertFalse(open.isPresent());
    }

    @Test
    void testFindOpenBySourceAndType() {
        LocalDateTime now = LocalDateTime.now();
        repository.createIncident(new IncidentRecord("inc-201", "src_3", "TABLE_MISSING", "tbl_a", now, now, null, 5, "OPEN", null));
        repository.createIncident(new IncidentRecord("inc-202", "src_3", "INSERT_FAILURE", "tbl_a", now, now, null, 15, "OPEN", null));

        Optional<IncidentRecord> foundMissing = repository.findOpenBySourceAndType("src_3", "TABLE_MISSING");
        assertTrue(foundMissing.isPresent());
        assertEquals("inc-201", foundMissing.get().incidentId());

        Optional<IncidentRecord> foundInsert = repository.findOpenBySourceAndType("src_3", "INSERT_FAILURE");
        assertTrue(foundInsert.isPresent());
        assertEquals("inc-202", foundInsert.get().incidentId());

        Optional<IncidentRecord> notFound = repository.findOpenBySourceAndType("src_3", "MAPPING_MISSING");
        assertFalse(notFound.isPresent());
    }

    @Test
    void testFindOpenBySourceAndFindAllOpen() {
        LocalDateTime now = LocalDateTime.now();
        repository.createIncident(new IncidentRecord("inc-301", "src_4", "TABLE_MISSING", "tbl_b", now, now, null, 5, "OPEN", null));
        repository.createIncident(new IncidentRecord("inc-302", "src_4", "MISSING_COLUMNS", "tbl_b", now, now, null, 12, "OPEN", null));
        repository.createIncident(new IncidentRecord("inc-303", "src_5", "TABLE_MISSING", "tbl_c", now, now, null, 8, "OPEN", null));
        repository.createIncident(new IncidentRecord("inc-304", "src_4", "INSERT_FAILURE", "tbl_b", now, now, now, 20, "RESOLVED", null));

        List<IncidentRecord> openSrc4 = repository.findOpenBySource("src_4");
        assertEquals(2, openSrc4.size());

        List<IncidentRecord> allOpen = repository.findAllOpen();
        assertEquals(3, allOpen.size());
    }
}
