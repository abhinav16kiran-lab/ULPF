package com.ulpf.analytics.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.analytics.service.AnalyticsService;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaProvisioningService;

    public AnalyticsController(
            AnalyticsService analyticsService,
            com.ulpf.mapping.service.DynamicSchemaProvisioningService dynamicSchemaProvisioningService) {
        this.analyticsService = analyticsService;
        this.dynamicSchemaProvisioningService = dynamicSchemaProvisioningService;
    }

    @GetMapping("/analytics/schemas")
    public ResponseEntity<?> getSchemas() {
        return ResponseEntity.ok(dynamicSchemaProvisioningService.getClickHouseSchemaMetadata());
    }

    @GetMapping("/analytics/tables")
    public ResponseEntity<?> getTables() {
        return ResponseEntity.ok(analyticsService.getTables());
    }

    @GetMapping("/analytics/tables/{table}/columns")
    public ResponseEntity<?> getTableColumns(@PathVariable String table) {
        if (table == null || table.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "table name is required"));
        }
        var columns = analyticsService.getTableColumns(table);
        return ResponseEntity.ok(Map.of(
                "table", table,
                "columns", columns
        ));
    }

    public record SqlQueryRequest(String query) {}

    @PostMapping("/analytics/sql")
    public ResponseEntity<?> executeSql(@RequestBody SqlQueryRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query parameter is required"));
        }
        try {
            var result = analyticsService.executeDirectSql(request.query());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) String aggregation,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        if (isBlank(table) || isBlank(column) || isBlank(aggregation)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "table, column, and aggregation are required"
            ));
        }

        if (!analyticsService.isValidAggregation(aggregation)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "aggregation must be one of COUNT, AVG, MIN, MAX, SUM"
            ));
        }

        try {
            var result = analyticsService.runQuery(table, column, aggregation, timeRange, from, to);

            return ResponseEntity.ok(Map.of(
                    "table", result.table(),
                    "column", result.column(),
                    "aggregation", result.aggregation(),
                    "result", result.result()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/analytics/lineage/{lineageId}")
    public ResponseEntity<?> getLineageBacktracking(@PathVariable String lineageId) {
        if (isBlank(lineageId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "lineageId is required"));
        }

        var rawEvents = analyticsService.getLineage(lineageId);

        return ResponseEntity.ok(Map.of(
                "lineageId", lineageId,
                "rawCount", rawEvents.size(),
                "rawEvents", rawEvents
        ));
    }

    @GetMapping("/analytics/export/parquet")
    public ResponseEntity<?> exportParquet(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String vendorId,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "10000") Integer limit
    ) {
        try {
            byte[] parquetData = analyticsService.exportParquet(table, vendorId, sourceId, from, to, limit);

            if (parquetData == null || parquetData.length == 0) {
                return ResponseEntity.noContent().build();
            }

            String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .format(java.time.LocalDateTime.now());
            String filename = "ulpf_export_" + (vendorId != null && !vendorId.isBlank() ? vendorId : "all") + "_" + timestamp + ".parquet";

            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/vnd.apache.parquet")
                    .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .body(parquetData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to export parquet data: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Failed to export Parquet data from storage: " + e.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/analytics/import/file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importLogFile(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String vendorId,
            @RequestParam(required = false) String sourceId
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded log file cannot be empty"));
        }

        var result = analyticsService.importLogFile(file, vendorId, sourceId);
        return ResponseEntity.ok(result);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}