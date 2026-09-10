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
        try {
            jdbcTemplate.query(sql, (rs) -> {
                String aliasKey = rs.getString("alias_key");
                String canonicalField = rs.getString("canonical_field");
                aliasMap.put(aliasKey, canonicalField);
            });
        } catch (Exception e) {
            log.warn("Could not query mapping_aliases table: {}", e.getMessage());
        }

        if (aliasMap.isEmpty()) {
            seedInitialAliases();
        }
    }

    private void seedInitialAliases() {
        log.info("Seeding initial dictionary aliases into mapping_aliases...");
        Map<String, String> seeds = Map.ofEntries(
            Map.entry("timestamp", "timestamp"),
            Map.entry("time", "timestamp"),
            Map.entry("datetime", "timestamp"),
            Map.entry("eventtime", "timestamp"),
            Map.entry("ts", "timestamp"),
            Map.entry("level", "log.level"),
            Map.entry("loglevel", "log.level"),
            Map.entry("severity", "log.level"),
            Map.entry("service", "service.name"),
            Map.entry("servicename", "service.name"),
            Map.entry("app", "service.name"),
            Map.entry("environment", "service.environment"),
            Map.entry("env", "service.environment"),
            Map.entry("httpmethod", "http.request.method"),
            Map.entry("method", "http.request.method"),
            Map.entry("httppath", "url.path"),
            Map.entry("path", "url.path"),
            Map.entry("uri", "url.path"),
            Map.entry("url", "url.path"),
            Map.entry("httpstatuscode", "http.response.status_code"),
            Map.entry("statuscode", "http.response.status_code"),
            Map.entry("status", "http.response.status_code"),
            Map.entry("httplatencyms", "event.duration"),
            Map.entry("latencyms", "event.duration"),
            Map.entry("duration", "event.duration"),
            Map.entry("httpuseragent", "user_agent.original"),
            Map.entry("useragent", "user_agent.original"),
            Map.entry("networkclientip", "source.ip"),
            Map.entry("clientip", "source.ip"),
            Map.entry("srcip", "source.ip"),
            Map.entry("srcipaddr", "source.ip"),
            Map.entry("networkserverip", "destination.ip"),
            Map.entry("serverip", "destination.ip"),
            Map.entry("dstip", "destination.ip"),
            Map.entry("dstprt", "destination.port"),
            Map.entry("destport", "destination.port"),
            Map.entry("userusername", "user.name"),
            Map.entry("username", "user.name"),
            Map.entry("user", "user.name"),
            Map.entry("usrprincnm", "user.email"),
            Map.entry("useremail", "user.email"),
            Map.entry("userattemptcount", "user.attempt_count"),
            Map.entry("evtypeid", "event.action"),
            Map.entry("action", "event.action"),
            Map.entry("message", "message"),
            Map.entry("msg", "message")
        );

        seeds.forEach((aliasKey, canonicalField) -> {
            insertAlias(canonicalField, aliasKey, "seed");
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
