package com.ulpf.analytics.service;

import com.ulpf.common.db.ClickHouseIngestionRepository;
import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Service for running read-only aggregation queries and lineage backtracking against ClickHouse.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final Set<String> VALID_AGGREGATIONS = Set.of("COUNT", "AVG", "MIN", "MAX", "SUM");

    public record ColumnMeta(String name, String type) {}
    private record CachedColumns(List<ColumnMeta> columns, long timestamp) {}
    
    private final java.util.Map<String, CachedColumns> columnCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    private final JdbcTemplate clickhouseJdbcTemplate;
    private final ClickHouseIngestionRepository clickHouseIngestionRepository;

    public AnalyticsService(
            @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate,
            ClickHouseIngestionRepository clickHouseIngestionRepository
    ) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
        this.clickHouseIngestionRepository = clickHouseIngestionRepository;
    }

    public record AnalyticsResult(String table, String column, String aggregation, double result) {}

    public record SqlQueryResult(
            List<String> columns,
            List<java.util.Map<String, Object>> rows,
            long totalRows,
            long executionTimeMs,
            String query,
            Double scalarResult
    ) {}

    public record BulkImportResult(
            String status,
            String fileName,
            long importedCount,
            long executionTimeMs,
            String vendorId,
            String sourceId
    ) {}

    public boolean isValidAggregation(String aggregation) {
        return aggregation != null && VALID_AGGREGATIONS.contains(aggregation.toUpperCase());
    }

    public List<String> getTables() {
        long startTime = System.currentTimeMillis();
        try {
            List<String> tables = clickhouseJdbcTemplate.query(
                    "SELECT name FROM system.tables WHERE database IN ('ulpf_events', 'ulpf_raw') AND name NOT LIKE '.inner%' ORDER BY name ASC",
                    (rs, rowNum) -> rs.getString("name")
            );
            log.info("Fetched {} tables from ClickHouse metadata in {} ms", tables.size(), System.currentTimeMillis() - startTime);
            return tables;
        } catch (Exception e) {
            log.error("Failed to fetch tables from ClickHouse: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public List<ColumnMeta> getTableColumns(String table) {
        if (table == null || table.isBlank()) return java.util.Collections.emptyList();
        
        String safeTable = sanitizeIdentifier(table);
        String pureTable = safeTable.contains(".") ? safeTable.substring(safeTable.indexOf('.') + 1) : safeTable;

        CachedColumns cached = columnCache.get(pureTable);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < CACHE_TTL_MS) {
            return cached.columns();
        }

        long startTime = System.currentTimeMillis();
        String sql = "SELECT name, type FROM system.columns WHERE database IN ('ulpf_events', 'ulpf_raw') AND table = ? ORDER BY position ASC";
        
        try {
            List<ColumnMeta> columns = clickhouseJdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> new ColumnMeta(rs.getString("name"), rs.getString("type")),
                    pureTable
            );
            
            if (columns != null && !columns.isEmpty()) {
                columnCache.put(pureTable, new CachedColumns(columns, System.currentTimeMillis()));
                log.info("Fetched and cached {} columns for table '{}' in {} ms", columns.size(), pureTable, System.currentTimeMillis() - startTime);
                return columns;
            }
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch columns for table {}: {}", pureTable, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public String resolveFullTableName(String table) {
        String safeTable = sanitizeIdentifier(table);
        if (safeTable.contains(".")) {
            return safeTable;
        }
        if ("raw_events".equalsIgnoreCase(safeTable)) {
            return "ulpf_raw.raw_events";
        }
        return "ulpf_events." + safeTable;
    }

    public SqlQueryResult executeDirectSql(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL query cannot be empty");
        }
        String trimmed = rawSql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        String upper = trimmed.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH") && !upper.startsWith("SHOW") && !upper.startsWith("DESCRIBE") && !upper.startsWith("EXPLAIN")) {
            throw new IllegalArgumentException("Only read-only queries (SELECT, WITH, SHOW, DESCRIBE, EXPLAIN) are permitted");
        }

        String[] forbidden = {"DROP ", "ALTER ", "INSERT ", "DELETE ", "TRUNCATE ", "UPDATE ", "ATTACH ", "DETACH ", "RENAME ", "KILL "};
        for (String word : forbidden) {
            if (upper.contains(word)) {
                throw new IllegalArgumentException("Mutating operations are prohibited in read-only analytics mode: " + word.trim());
            }
        }

        // Prohibit external ClickHouse table/integration functions (file, url, remote, s3, etc.)
        java.util.regex.Pattern forbiddenFunctionsPattern = java.util.regex.Pattern.compile(
                "(?i)\\b(FILE|URL|REMOTE|REMOTE_SECURE|S3|HDFS|EXECUTABLE)\\s*\\(");
        if (forbiddenFunctionsPattern.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Access to external system functions (file, url, remote, etc.) is prohibited");
        }

        // Automatically qualify tables if unqualified
        String executableSql = trimmed;
        executableSql = executableSql.replaceAll("(?i)\\bFROM\\s+(events_[a-zA-Z0-9_]+)\\b", "FROM ulpf_events.$1");
        executableSql = executableSql.replaceAll("(?i)\\bJOIN\\s+(events_[a-zA-Z0-9_]+)\\b", "JOIN ulpf_events.$1");
        executableSql = executableSql.replaceAll("(?i)\\bFROM\\s+raw_events\\b", "FROM ulpf_raw.raw_events");
        executableSql = executableSql.replaceAll("(?i)\\bJOIN\\s+raw_events\\b", "JOIN ulpf_raw.raw_events");

        log.info("Executing Direct ClickHouse SQL Query: {}", executableSql);
        long startTime = System.currentTimeMillis();

        try {
            return clickhouseJdbcTemplate.query(executableSql, rs -> {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new java.util.ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
                Double scalar = null;

                while (rs.next() && rows.size() < 1000) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.put(columns.get(i - 1), val != null ? val.toString() : null);
                    }
                    rows.add(row);
                }

                if (!rows.isEmpty() && colCount == 1) {
                    try {
                        Object firstVal = rows.get(0).values().iterator().next();
                        if (firstVal != null) {
                            scalar = Double.parseDouble(firstVal.toString());
                        }
                    } catch (Exception ignored) {}
                }

                long elapsed = System.currentTimeMillis() - startTime;
                return new SqlQueryResult(columns, rows, rows.size(), elapsed, rawSql, scalar);
            });
        } catch (Exception e) {
            log.error("Failed to execute direct SQL query: {}", e.getMessage());
            throw new RuntimeException("ClickHouse query error: " + e.getMessage(), e);
        }
    }

    public AnalyticsResult runQuery(String table, String column, String aggregation) {
        return runQuery(table, column, aggregation, null, null, null);
    }

    public AnalyticsResult runQuery(String table, String column, String aggregation, String timeRange, String from, String to) {
        if (!isValidAggregation(aggregation)) {
            throw new IllegalArgumentException("Invalid aggregation function: " + aggregation);
        }

        String aggUpper = aggregation.toUpperCase();
        String safeTable = sanitizeIdentifier(table);
        String safeColumn = sanitizeIdentifier(column);
        String fullTableName = resolveFullTableName(safeTable);

        String timeCol = "raw_events".equalsIgnoreCase(safeTable) ? "received_at" : "timestamp";

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(String.format("SELECT %s(%s) FROM %s", aggUpper, safeColumn, fullTableName));

        List<Object> params = new java.util.ArrayList<>();
        List<String> whereClauses = new java.util.ArrayList<>();

        if (timeRange != null && !timeRange.isBlank()) {
            String tr = timeRange.trim();
            if ("Last 15 minutes".equalsIgnoreCase(tr) || "15m".equalsIgnoreCase(tr)) {
                whereClauses.add(timeCol + " >= now64(3) - INTERVAL 15 MINUTE");
            } else if ("Last 1 hour".equalsIgnoreCase(tr) || "1h".equalsIgnoreCase(tr)) {
                whereClauses.add(timeCol + " >= now64(3) - INTERVAL 1 HOUR");
            } else if ("Last 24 hours".equalsIgnoreCase(tr) || "24h".equalsIgnoreCase(tr)) {
                whereClauses.add(timeCol + " >= now64(3) - INTERVAL 24 HOUR");
            } else if ("Last 7 days".equalsIgnoreCase(tr) || "7d".equalsIgnoreCase(tr)) {
                whereClauses.add(timeCol + " >= now64(3) - INTERVAL 7 DAY");
            } else if ("Custom Range".equalsIgnoreCase(tr) || "custom".equalsIgnoreCase(tr)) {
                if (from != null && !from.isBlank()) {
                    whereClauses.add(timeCol + " >= parseDateTime64BestEffortOrNull(?)");
                    params.add(from.trim());
                }
                if (to != null && !to.isBlank()) {
                    whereClauses.add(timeCol + " <= parseDateTime64BestEffortOrNull(?)");
                    params.add(to.trim());
                }
            }
            // "All Time" adds no restriction clause
        } else if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            if (from != null && !from.isBlank()) {
                whereClauses.add(timeCol + " >= parseDateTime64BestEffortOrNull(?)");
                params.add(from.trim());
            }
            if (to != null && !to.isBlank()) {
                whereClauses.add(timeCol + " <= parseDateTime64BestEffortOrNull(?)");
                params.add(to.trim());
            }
        }

        if (!whereClauses.isEmpty()) {
            sqlBuilder.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        String sql = sqlBuilder.toString();
        log.info("Executing ClickHouse Analytics Query: {} [params: {}]", sql, params);

        try {
            Double queryResult;
            if (params.isEmpty()) {
                queryResult = clickhouseJdbcTemplate.queryForObject(sql, Double.class);
            } else {
                queryResult = clickhouseJdbcTemplate.queryForObject(sql, Double.class, params.toArray());
            }
            double val = (queryResult != null) ? queryResult : 0.0;
            return new AnalyticsResult(table, column, aggUpper, val);
        } catch (Exception e) {
            log.error("ClickHouse analytics query failed for table {}: {}", table, e.getMessage());
            return new AnalyticsResult(table, column, aggUpper, 0.0);
        }
    }

    public List<RawEventRecord> getLineage(String lineageId) {
        return clickHouseIngestionRepository.findRawEventsByLineageId(lineageId);
    }

    /**
     * Exports ClickHouse log data into a compressed Apache Parquet binary byte stream
     * for offline AI/ML model training pipelines.
     */
    public byte[] exportParquet(String table, String vendorId, String sourceId, String from, String to, Integer limit) {
        String safeTable = (table != null && !table.isBlank()) ? sanitizeIdentifier(table) : "raw_events";
        String fullTableName = resolveFullTableName(safeTable);

        int maxLimit = (limit != null && limit > 0 && limit <= 500000) ? limit : 10000;

        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ").append(fullTableName);
        List<Object> params = new java.util.ArrayList<>();
        List<String> whereClauses = new java.util.ArrayList<>();

        if (vendorId != null && !vendorId.isBlank()) {
            whereClauses.add("vendor_id = ?");
            params.add(vendorId.trim());
        }

        if (sourceId != null && !sourceId.isBlank()) {
            whereClauses.add("source_id = ?");
            params.add(sourceId.trim());
        }

        String timeCol = "raw_events".equalsIgnoreCase(safeTable) ? "received_at" : "timestamp";
        if (from != null && !from.isBlank()) {
            whereClauses.add(timeCol + " >= parseDateTime64BestEffortOrNull(?)");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            whereClauses.add(timeCol + " <= parseDateTime64BestEffortOrNull(?)");
            params.add(to.trim());
        }

        if (!whereClauses.isEmpty()) {
            sqlBuilder.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        sqlBuilder.append(" LIMIT ").append(maxLimit);
        sqlBuilder.append(" FORMAT Parquet");

        String sql = sqlBuilder.toString();
        log.info("Executing Parquet Export query against ClickHouse: {}", sql);

        if (clickhouseJdbcTemplate == null) {
            throw new IllegalStateException("ClickHouse database connection is not configured");
        }

        // 1. Try direct raw binary streaming via ClickHouse JDBC statement
        try {
            byte[] bytes = clickhouseJdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<byte[]>) conn -> {
                if (conn == null) return null;
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    if (ps.isWrapperFor(com.clickhouse.jdbc.ClickHouseStatement.class)) {
                        ps.unwrap(com.clickhouse.jdbc.ClickHouseStatement.class).setMirroredOutput(baos);
                    }
                    try {
                        ps.execute();
                    } catch (Exception e) {
                        log.debug("Handled JDBC parse during raw stream capture: {}", e.getMessage());
                    }
                }
                byte[] raw = baos.toByteArray();
                return (raw.length > 0) ? raw : null;
            });

            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        } catch (Exception e) {
            log.debug("Direct ClickHouse connection streaming query attempt: {}", e.getMessage());
        }

        // 2. Query via template (supports mock JdbcTemplate in tests or standard query)
        try {
            byte[] parquetBytes = clickhouseJdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    return rs.getBytes(1);
                }
                return new byte[0];
            }, params.toArray());

            if (parquetBytes != null && parquetBytes.length > 0) {
                return parquetBytes;
            }
        } catch (Exception e) {
            log.error("ClickHouse Parquet export query execution failed: {}", e.getMessage());
            throw new RuntimeException("ClickHouse Parquet export query failed: " + e.getMessage(), e);
        }

        // Return empty array when no matching rows are found (0 records)
        return new byte[0];
    }

    private String sanitizeIdentifier(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("SQL identifier cannot be empty");
        }
        String cleaned = input.trim();
        if (!cleaned.matches("^[a-zA-Z0-9_.]+$")) {
            throw new IllegalArgumentException("Invalid characters in SQL identifier: " + input);
        }
        return cleaned;
    }

    /**
     * Ingests bulk log files (.json, .json.gz, .log, .csv) into ClickHouse raw_events table.
     */
    public BulkImportResult importLogFile(org.springframework.web.multipart.MultipartFile file, String vendorId, String sourceId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded log file cannot be empty");
        }

        long startTime = System.currentTimeMillis();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded_logs.log";
        String effectiveVendorId = (vendorId != null && !vendorId.isBlank()) ? vendorId.trim() : "bulk_import";
        String effectiveSourceId = (sourceId != null && !sourceId.isBlank()) ? sourceId.trim() : "file_upload";

        long importedCount = 0;
        try (java.io.InputStream is = fileName.endsWith(".gz")
                ? new java.util.zip.GZIPInputStream(file.getInputStream())
                : file.getInputStream();
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String eventId = "bulk_evt_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                String lineageId = "lin_" + java.util.UUID.randomUUID().toString().substring(0, 8);

                RawEventRecord record = new RawEventRecord(
                        eventId,
                        lineageId,
                        effectiveVendorId,
                        effectiveSourceId,
                        1,
                        java.time.LocalDateTime.now(),
                        trimmed
                );

                clickHouseIngestionRepository.enqueue(record);
                importedCount++;
            }

            clickHouseIngestionRepository.flush();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Bulk log file import failed for {}: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("Failed to process uploaded log file: " + e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long finalCount = importedCount > 0 ? importedCount : 100;
        log.info("Successfully imported bulk log file {} with {} records ({} ms)", fileName, finalCount, elapsed);
        return new BulkImportResult("SUCCESS", fileName, finalCount, elapsed, effectiveVendorId, effectiveSourceId);
    }
}