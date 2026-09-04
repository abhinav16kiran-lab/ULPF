package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.MappingRepository;
import com.ulpf.common.db.MappingRepository.MappingVersionRecord;
import com.ulpf.mapping.model.MappingProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for MappingProposalService and MappingRepository persistence.
 */
class MappingProposalServiceTest {

    private MappingProposalService service;
    private MappingRepository mappingRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
            "CREATE TABLE mapping_versions (mapping_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, version INTEGER NOT NULL, mapping_json TEXT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        );

        mappingRepository = new MappingRepository(jdbcTemplate);
        service = new MappingProposalService(mappingRepository);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testSaveMappingVersionCreatesCandidateRecord() throws Exception {
        List<MappingProposal> proposals = List.of(
            new MappingProposal("SrcAddress", "src_ip", 1.0, "ALIAS_LOOKUP"),
            new MappingProposal("destIP", "dest_ip", 0.85, "TFIDF"),
            new MappingProposal("unknown", null, 0.0, "NONE")
        );

        MappingVersionRecord record = service.saveMappingVersion("source-123", proposals);

        assertNotNull(record.mappingId());
        assertEquals("source-123", record.sourceId());
        assertEquals(1, record.version());
        assertEquals("CANDIDATE", record.status());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> parsed = objectMapper.readValue(record.mappingJson(), Map.class);
        assertEquals(3, parsed.size());

        Map<String, Object> field1 = parsed.get("SrcAddress");
        assertEquals("src_ip", field1.get("canonicalField"));
        assertEquals(1.0, field1.get("confidence"));
        assertEquals("ALIAS_LOOKUP", field1.get("source"));
    }

    @Test
    void testIncrementalVersionNumber() {
        List<MappingProposal> proposals = List.of(
            new MappingProposal("field1", "src_ip", 1.0, "ALIAS_LOOKUP")
        );

        MappingVersionRecord v1 = service.saveMappingVersion("source-123", proposals);
        assertEquals(1, v1.version());

        MappingVersionRecord v2 = service.saveMappingVersion("source-123", proposals);
        assertEquals(2, v2.version());

        MappingVersionRecord otherSource = service.saveMappingVersion("source-456", proposals);
        assertEquals(1, otherSource.version(), "Different source should start at version 1");
    }

    @Test
    void testHandlesEmptyProposalList() throws Exception {
        List<MappingProposal> proposals = List.of();

        MappingVersionRecord record = service.saveMappingVersion("source-123", proposals);
        assertEquals("{}", record.mappingJson());
    }
}
