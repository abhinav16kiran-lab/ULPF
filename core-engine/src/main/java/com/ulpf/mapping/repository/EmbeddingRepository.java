package com.ulpf.mapping.repository;

import com.ulpf.mapping.model.CanonicalEmbedding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// import jakarta.annotation.PostConstruct;
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
    private volatile List<CanonicalEmbedding> cachedEmbeddings;

    public EmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Get all canonical field embeddings, lazy-loading from SQLite into cache on
     * first demand.
     * Lock-free read path via volatile double-checked locking to avoid thread
     * serialization.
     * 
     * @return list of canonical embeddings
     */
    public List<CanonicalEmbedding> findAllCanonicalEmbeddings() {
        List<CanonicalEmbedding> local = cachedEmbeddings;
        if (local != null) {
            return local;
        }

        synchronized (this) {
            local = cachedEmbeddings;
            if (local == null) {
                try {
                    String sql = "SELECT canonical_field, embedding FROM mapping_embeddings";

                    local = jdbcTemplate.query(sql, (rs, rowNum) -> {
                        String canonicalField = rs.getString("canonical_field");
                        byte[] embeddingBytes = rs.getBytes("embedding");
                        double[] embeddingVector = bytesToDoubleArray(embeddingBytes);
                        return new CanonicalEmbedding(canonicalField, embeddingVector);
                    });
                } catch (Exception e) {
                    // Table might be empty or not exist yet
                    local = new ArrayList<>();
                }
                cachedEmbeddings = local;
            }
            return local;
        }
    }

    /**
     * Clear the in-memory cache to release RAM when idle.
     */
    public synchronized void clearCache() {
        cachedEmbeddings = null;
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
     * Refresh the cache by clearing memory.
     * The next access will lazy-load fresh embeddings from the database.
     */
    public synchronized void refreshCache() {
        cachedEmbeddings = null;
    }
}
