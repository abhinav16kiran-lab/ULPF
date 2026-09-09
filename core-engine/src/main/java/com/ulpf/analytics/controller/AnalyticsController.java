package com.ulpf.analytics.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.analytics.service.AnalyticsService;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) String aggregation
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

        var result = analyticsService.runQuery(table, column, aggregation);

        return ResponseEntity.ok(Map.of(
                "table", result.table(),
                "column", result.column(),
                "aggregation", result.aggregation(),
                "result", result.result()
        ));
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
    public ResponseEntity<byte[]> exportParquet(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String vendorId,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "10000") Integer limit
    ) {
        byte[] parquetData = analyticsService.exportParquet(table, vendorId, sourceId, from, to, limit);

        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now());
        String filename = "ulpf_export_" + (vendorId != null && !vendorId.isBlank() ? vendorId : "all") + "_" + timestamp + ".parquet";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/vnd.apache.parquet")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(parquetData);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}