package com.ulpf.mapping.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Repository for loading mapping aliases from SQLite into memory.
 * Loads once at startup for fast dictionary lookups.
 */
@Repository
public class AliasRepository {
    
    private static final Logger log = LoggerFactory.getLogger(AliasRepository.class);
    
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
    
    /**
     * Insert a new alias into the mapping_aliases table and update in-memory cache.
     * Uses INSERT to add new aliases. If alias already exists, logs and continues gracefully.
     * 
     * @param canonicalField the canonical field name (e.g., "src_ip")
     * @param aliasKey the vendor's field name, normalized (e.g., "sourceaddress")
     * @param source the source of this alias: "seed" or "human_correction"
     */
    public void insertAlias(String canonicalField, String aliasKey, String source) {
        String sql = "INSERT INTO mapping_aliases (canonical_field, alias_key, source) VALUES (?, ?, ?)";
        
        try {
            int rowsAffected = jdbcTemplate.update(sql, canonicalField, aliasKey, source);
            
            if (rowsAffected > 0) {
                // Update in-memory cache immediately so next lookup finds it
                aliasMap.put(aliasKey, canonicalField);
                log.info("Learned new alias: '{}' → '{}' (source: {})", aliasKey, canonicalField, source);
            }
        } catch (Exception e) {
            // Log but don't fail - duplicate key or other constraint violations are acceptable
            log.debug("Alias insert failed (likely duplicate): {} → {} ({})", aliasKey, canonicalField, e.getMessage());
        }
    }
    
    /**
     * Force reload all aliases from database into memory.
     * Call this after bulk inserts or if cache gets out of sync.
     */
    public void reloadAliases() {
        aliasMap.clear();
        loadAliases();
        log.info("Reloaded {} aliases from database into memory", aliasMap.size());
    }
}
