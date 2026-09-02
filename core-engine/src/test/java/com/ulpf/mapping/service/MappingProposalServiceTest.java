package com.ulpf.mapping.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.mapping.model.MappingProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for MappingProposalService.
 * Tests batch persistence to mapping_versions table.
 */
class MappingProposalServiceTest {
    
    private MappingProposalService service;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new MappingProposalService(jdbcTemplate);
        objectMapper = new ObjectMapper();
    }
    
    @Test
    void testSavesOneRowPerBatch() {
        List<MappingProposal> proposals = List.of(
            new MappingProposal("SrcAddress", "src_ip", 1.0, "ALIAS_LOOKUP"),
            new MappingProposal("destIP", "dest_ip", 0.85, "TFIDF"),
            new MappingProposal("unknown", null, 0.0, "NONE")
        );
        
        service.saveMappingVersion("source-123", proposals);
        
        // Verify exactly one INSERT was called
        verify(jdbcTemplate, times(1)).update(
            anyString(),
            anyString(),  // mapping_id
            eq("source-123"),  // source_id
            anyInt(),  // version
            anyString()  // mapping_json
        );
    }
    
    @Test
    void testMappingJsonContainsAllFields() throws Exception {
        List<MappingProposal> proposals = List.of(
            new MappingProposal("field1", "src_ip", 1.0, "ALIAS_LOOKUP"),
            new MappingProposal("field2", "dest_ip", 0.85, "TFIDF"),
            new MappingProposal("field3", null, 0.0, "NONE")
        );
        
        // Capture the JSON argument
        when(jdbcTemplate.update(anyString(), any(), any(), any(), anyString())).thenAnswer(invocation -> {
            String json = invocation.getArgument(4);
            
            // Parse and validate JSON structure
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> parsed = objectMapper.readValue(json, Map.class);
            
            assertEquals(3, parsed.size(), "Should contain all 3 fields");
            assertTrue(parsed.containsKey("field1"));
            assertTrue(parsed.containsKey("field2"));
            assertTrue(parsed.containsKey("field3"));
            
            // Validate field1 structure
            Map<String, Object> field1 = parsed.get("field1");
            assertEquals("src_ip", field1.get("canonicalField"));
            assertEquals(1.0, field1.get("confidence"));
            assertEquals("ALIAS_LOOKUP", field1.get("source"));
            
            // Validate field3 (unmapped field)
            Map<String, Object> field3 = parsed.get("field3");
            assertNull(field3.get("canonicalField"));
            assertEquals(0.0, field3.get("confidence"));
            assertEquals("NONE", field3.get("source"));
            
            return 1;
        });
        
        service.saveMappingVersion("source-123", proposals);
        
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), anyString());
    }
    
    @Test
    void testStatusSetToCandidate() {
        List<MappingProposal> proposals = List.of(
            new MappingProposal("field1", "src_ip", 1.0, "ALIAS_LOOKUP")
        );
        
        when(jdbcTemplate.update(anyString(), any(), any(), any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("'CANDIDATE'"), "Status should be set to CANDIDATE");
            return 1;
        });
        
        service.saveMappingVersion("source-123", proposals);
    }
    
    @Test
    void testHandlesEmptyProposalList() {
        List<MappingProposal> proposals = List.of();
        
        when(jdbcTemplate.update(anyString(), any(), any(), any(), anyString())).thenAnswer(invocation -> {
            String json = invocation.getArgument(4);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            assertEquals(0, parsed.size(), "Empty proposals should result in empty JSON object");
            
            return 1;
        });
        
        service.saveMappingVersion("source-123", proposals);
    }
}
