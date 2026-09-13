package com.ulpf.integrity.controller;

import com.ulpf.integrity.repository.IntegrityRepository.IntegrityBlockRecord;
import com.ulpf.integrity.service.BatchIntegrityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrityControllerTest {

    private BatchIntegrityService batchIntegrityService;
    private IntegrityController integrityController;

    @BeforeEach
    void setUp() {
        batchIntegrityService = mock(BatchIntegrityService.class);
        integrityController = new IntegrityController(batchIntegrityService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetIntegrityBlocks() {
        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_1", 10, "e1", "e10", "root", "prev", LocalDateTime.now());
        when(batchIntegrityService.getRecentBlocks(50)).thenReturn(List.of(block));

        ResponseEntity<?> response = integrityController.getIntegrityBlocks(50, null);
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<IntegrityBlockRecord> blocks = (List<IntegrityBlockRecord>) body.get("blocks");
        assertEquals(1, blocks.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetIntegrityBlocksWithSourceId() {
        IntegrityBlockRecord block1 = new IntegrityBlockRecord(1L, "src_1", 10, "e1", "e10", "root", "prev",
                LocalDateTime.now());
        IntegrityBlockRecord block2 = new IntegrityBlockRecord(2L, "src_2", 10, "e1", "e10", "root", "prev",
                LocalDateTime.now());

        when(batchIntegrityService.getRecentBlocks(50)).thenReturn(List.of(block1, block2));

        ResponseEntity<?> response = integrityController.getIntegrityBlocks(50, "src_1");
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<IntegrityBlockRecord> blocks = (List<IntegrityBlockRecord>) body.get("blocks");
        assertEquals(1, blocks.size());
        assertEquals("src_1", blocks.get(0).sourceId());
    }

    @Test
    void testVerifyBlockIntegrity_NotFound() {
        BatchIntegrityService.VerificationResult result = new BatchIntegrityService.VerificationResult(
                1L, null, null, null, null, "NOT_FOUND", null, null, 0, true, "msg");
        when(batchIntegrityService.verifyBlockIntegrity(1L)).thenReturn(result);

        ResponseEntity<?> response = integrityController.verifyBlockIntegrity(1L);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void testVerifyBlockIntegrity_Found() {
        BatchIntegrityService.VerificationResult result = new BatchIntegrityService.VerificationResult(
                1L, "src_1", "e1", "e10", "ulpf_raw.raw_events", "VALID", "root", "root", 10, false, "msg");
        when(batchIntegrityService.verifyBlockIntegrity(1L)).thenReturn(result);

        ResponseEntity<?> response = integrityController.verifyBlockIntegrity(1L);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testVerifyAllBlocks() {
        BatchIntegrityService.BulkVerificationResult result = new BatchIntegrityService.BulkVerificationResult(
                100, 99, 1, List.of(
                        new BatchIntegrityService.VerificationResult(
                                1L, "src_1", "e1", "e10", "ulpf_raw.raw_events", "TAMPERED_DETECTED", "root1", "root2",
                                10, true, "msg")));

        when(batchIntegrityService.verifyAllBlocks()).thenReturn(result);

        ResponseEntity<?> response = integrityController.verifyAllBlocks();
        assertEquals(200, response.getStatusCode().value());

        BatchIntegrityService.BulkVerificationResult body = (BatchIntegrityService.BulkVerificationResult) response
                .getBody();
        assertEquals(100, body.totalBlocksChecked());
        assertEquals(1, body.tamperedCount());
    }
}
