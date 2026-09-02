package com.ulpf.mapping.repository;

import com.ulpf.mapping.model.CanonicalEmbedding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for loading canonical field embeddings from SQLite.
 * Embeddings are cached in memory after first load since they change rarely.
 */
@Repository
public class EmbeddingRepository {
    
    private final JdbcTemplate jdbcTemplate;
    private List<CanonicalEmbedding> cachedEmbeddings;
    
    public EmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Load all canonical embeddings into memory cache at startup.
     * This is safe since the table is small (one row per canonical field).
     */
    @PostConstruct
    public void loadEmbeddings() {
        try {
            cachedEmbeddings = findAllCanonicalEmbeddings();
        } catch (Exception e) {
            // Table might be empty or not exist yet - that's okay
            cachedEmbeddings = new ArrayList<>();
        }
    }
    
    /**
     * Get all canonical field embeddings from cache.
     * 
     * @return list of canonical embeddings
     */
    public List<CanonicalEmbedding> findAllCanonicalEmbeddings() {
        if (cachedEmbeddings != null) {
            return cachedEmbeddings;
        }
        
        String sql = "SELECT canonical_field, embedding FROM mapping_embeddings";
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String canonicalField = rs.getString("canonical_field");
            byte[] embeddingBytes = rs.getBytes("embedding");
            double[] embeddingVector = bytesToDoubleArray(embeddingBytes);
            return new CanonicalEmbedding(canonicalField, embeddingVector);
        });
    }
    
    /**
     * Convert byte array (BLOB) to double array.
     * Assumes the BLOB stores doubles in little-endian format.
     */
    private double[] bytesToDoubleArray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new double[0];
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int numDoubles = bytes.length / Double.BYTES;
        double[] result = new double[numDoubles];
        
        for (int i = 0; i < numDoubles; i++) {
            result[i] = buffer.getDouble();
        }
        
        return result;
    }
    
    /**
     * Refresh the cache by reloading from database.
     * Call this after embeddings are updated.
     */
    public void refreshCache() {
        cachedEmbeddings = null;
        loadEmbeddings();
    }
}
