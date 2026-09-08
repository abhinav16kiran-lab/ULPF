package com.ulpf.controlplane.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller providing a lightweight, unauthenticated healthcheck endpoint for container orchestration (Podman/Docker).
 */
@RestController
@RequestMapping("/v1/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ulpf-core-engine",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
