package com.ulpf.common.db;

import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingRepositoryTest {

    private MappingRepository mappingRepository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE mapping_versions (
                mapping_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                mapping_json TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """);

        mappingRepository = new MappingRepository(jdbcTemplate);
    }

    @Test
    void testActiveMappingLazyLoadAndActivation() {
        MappingVersionRecord mapping1 = new MappingVersionRecord(null, "src_web", 1, "{\"src_ip\":\"source_ip\"}", "ACTIVE", null);
        mappingRepository.saveMappingVersion(mapping1);

        Optional<MappingVersionRecord> activeOpt = mappingRepository.findActiveBySourceId("src_web");
        assertTrue(activeOpt.isPresent());
        assertEquals(1, activeOpt.get().version());

        MappingVersionRecord mapping2 = new MappingVersionRecord(null, "src_web", 2, "{\"src_ip\":\"source_ip\",\"dest_ip\":\"destination_ip\"}", "CANDIDATE", null);
        MappingVersionRecord saved2 = mappingRepository.saveMappingVersion(mapping2);

        mappingRepository.activateVersion(saved2.mappingId(), "src_web");

        Optional<MappingVersionRecord> activeOpt2 = mappingRepository.findActiveBySourceId("src_web");
        assertTrue(activeOpt2.isPresent());
        assertEquals(2, activeOpt2.get().version());
    }
}
