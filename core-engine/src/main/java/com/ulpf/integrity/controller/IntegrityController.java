package com.ulpf.integrity.controller;

import com.ulpf.integrity.repository.IntegrityRepository.IntegrityBlockRecord;
import com.ulpf.integrity.service.BatchIntegrityService;
import com.ulpf.integrity.service.BatchIntegrityService.VerificationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for cryptographic Merkle Tree batch integrity inspection
 * and forensic log tamper-evidence verification.
 */
@RestController
@RequestMapping("/v1/integrity")
@PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
public class IntegrityController {

    private final BatchIntegrityService batchIntegrityService;

    public IntegrityController(BatchIntegrityService batchIntegrityService) {
        this.batchIntegrityService = batchIntegrityService;
    }

    @GetMapping("/blocks")
    public ResponseEntity<?> getIntegrityBlocks(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String search
    ) {
        List<IntegrityBlockRecord> blocks = batchIntegrityService.getRecentBlocks(limit, sourceId, search);
        return ResponseEntity.ok(Map.of("blocks", blocks));
    }

    @PostMapping("/verify/{blockId}")
    public ResponseEntity<?> verifyBlockIntegrity(@PathVariable Long blockId) {
        VerificationResult result = batchIntegrityService.verifyBlockIntegrity(blockId);
        if ("NOT_FOUND".equalsIgnoreCase(result.status())) {
            return ResponseEntity.status(404).body(Map.of("error", result.message()));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/verify-all")
    public ResponseEntity<?> verifyAllBlocks() {
        BatchIntegrityService.BulkVerificationResult result = batchIntegrityService.verifyAllBlocks();
        return ResponseEntity.ok(result);
    }
}
