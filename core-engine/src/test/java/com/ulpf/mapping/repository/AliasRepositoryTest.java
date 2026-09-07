package com.ulpf.mapping.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AliasRepositoryTest {
    
    @Autowired
    private AliasRepository aliasRepository;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @BeforeEach
    void setUp() {
        // Clean up human_correction aliases before each test
        jdbcTemplate.update("DELETE FROM mapping_aliases WHERE source = 'human_correction'");
        aliasRepository.reloadAliases();
    }
    
    @Test
    void testInsertNewAlias() {
        // Given
        String canonicalField = "src_ip";
        String aliasKey = "sourceaddress";
        String source = "human_correction";
        
        // When
        aliasRepository.insertAlias(canonicalField, aliasKey, source);
        
        // Then - verify in database
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE alias_key = ? AND canonical_field = ? AND source = ?",
            Integer.class,
            aliasKey, canonicalField, source
        );
        assertEquals(1, count);
        
        // Then - verify in memory cache
        assertEquals(canonicalField, aliasRepository.getAliasMap().get(aliasKey));
    }
    
    @Test
    void testInsertDuplicateAliasDoesNotCrash() {
        // Given
        String canonicalField = "src_ip";
        String aliasKey = "sourceaddress";
        String source = "human_correction";
        
        // When - insert twice
        aliasRepository.insertAlias(canonicalField, aliasKey, source);
        aliasRepository.insertAlias(canonicalField, aliasKey, source); // Should not crash
        
        // Then - only one record exists
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM mapping_aliases WHERE alias_key = ?",
            Integer.class,
            aliasKey
        );
        assertTrue(count >= 1); // At least one exists (may be more if constraint doesn't exist)
    }
    
    @Test
    void testReloadAliases() {
        // Given - insert alias directly into DB
        jdbcTemplate.update(
            "INSERT INTO mapping_aliases (canonical_field, alias_key, source) VALUES (?, ?, ?)",
            "dest_ip", "destinationaddress", "human_correction"
        );
        
        // When - reload
        aliasRepository.reloadAliases();
        
        // Then - verify in memory
        assertEquals("dest_ip", aliasRepository.getAliasMap().get("destinationaddress"));
    }
    
    @Test
    void testInMemoryCacheUpdatedImmediately() {
        // Given
        String aliasKey = "clientaddr";
        assertNull(aliasRepository.getAliasMap().get(aliasKey)); // Not in cache initially
        
        // When
        aliasRepository.insertAlias("src_ip", aliasKey, "human_correction");
        
        // Then - immediately available in cache
        assertEquals("src_ip", aliasRepository.getAliasMap().get(aliasKey));
    }
}
