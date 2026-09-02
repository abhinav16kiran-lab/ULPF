package com.ulpf.mapping.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Repository for loading mapping aliases from SQLite into memory.
 * Loads once at startup for fast dictionary lookups.
 */
@Repository
public class AliasRepository {
    
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, String> aliasMap = new HashMap<>();
    
    public AliasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Load all aliases into memory at startup.
     */
    @PostConstruct
    public void loadAliases() {
        String sql = "SELECT alias_key, canonical_field FROM mapping_aliases";
        jdbcTemplate.query(sql, (rs) -> {
            String aliasKey = rs.getString("alias_key");
            String canonicalField = rs.getString("canonical_field");
            aliasMap.put(aliasKey, canonicalField);
        });
    }
    
    /**
     * Get the in-memory alias map.
     */
    public Map<String, String> getAliasMap() {
        return aliasMap;
    }
}
