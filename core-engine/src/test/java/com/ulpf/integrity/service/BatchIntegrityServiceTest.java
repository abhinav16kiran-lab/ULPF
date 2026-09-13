package com.ulpf.integrity.service;

import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import com.ulpf.integrity.repository.IntegrityRepository;
import com.ulpf.integrity.repository.IntegrityRepository.IntegrityBlockRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;

class BatchIntegrityServiceTest {

    private MerkleTreeCalculator merkleTreeCalculator;
    private IntegrityRepository integrityRepository;
    private JdbcTemplate clickhouseJdbcTemplate;
    private BatchIntegrityService batchIntegrityService;

    @BeforeEach
    void setUp() {
        merkleTreeCalculator = new MerkleTreeCalculator();
        integrityRepository = mock(IntegrityRepository.class);
        clickhouseJdbcTemplate = mock(JdbcTemplate.class);

        batchIntegrityService = new BatchIntegrityService(
                merkleTreeCalculator,
                integrityRepository,
                clickhouseJdbcTemplate
        );
    }

    @Test
    void testProcessRawBatchCreatesMerkleBlockAndChainsPreviousHash() {
        when(integrityRepository.findLatestBlockHash("src_fw_1"))
                .thenReturn("0000000000000000000000000000000000000000000000000000000000000000");

        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, LocalDateTime.now(), "{\"ip\":\"10.0.0.1\"}");
        RawEventRecord r2 = new RawEventRecord("e2", "l2", "v1", "src_fw_1", 1, LocalDateTime.now(), "{\"ip\":\"10.0.0.2\"}");

        IntegrityBlockRecord savedRecord = new IntegrityBlockRecord(
                1L, "src_fw_1", 2, "e1", "e2", "mock_root", "0000000000000000000000000000000000000000000000000000000000000000", LocalDateTime.now()
        );
        when(integrityRepository.saveBlock(any())).thenReturn(savedRecord);

        var blocks = batchIntegrityService.processRawBatch(List.of(r1, r2));

        assertEquals(1, blocks.size());

        ArgumentCaptor<IntegrityBlockRecord> captor = ArgumentCaptor.forClass(IntegrityBlockRecord.class);
        verify(integrityRepository, times(1)).saveBlock(captor.capture());

        IntegrityBlockRecord captured = captor.getValue();
        assertEquals("src_fw_1", captured.sourceId());
        assertEquals(2, captured.eventCount());
        assertEquals("e1", captured.firstEventId());
        assertEquals("e2", captured.lastEventId());
        assertNotNull(captured.merkleRoot());
        assertEquals(64, captured.merkleRoot().length());
    }

    @Test
    void testVerifyBlockIntegrity_NotFound() {
        when(integrityRepository.findBlockById(999L)).thenReturn(java.util.Optional.empty());
        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(999L);
        assertEquals("NOT_FOUND", result.status());
        assertTrue(result.isTampered());
    }

    @Test
    void testVerifyBlockIntegrity_NoData() {
        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 2, "e1", "e2", "mock_root", "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));
        // Mock DB returns empty
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any(), any(), any())).thenReturn(List.of());

        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(1L);
        assertEquals("NO_DATA", result.status());
        assertTrue(result.isTampered());
    }

    @Test
    void testVerifyBlockIntegrity_Valid() {
        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, LocalDateTime.now(), "payload");
        // Compute expected root for r1
        String expectedLeaf = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload");
        String expectedRoot = merkleTreeCalculator.calculateMerkleRoot(List.of(expectedLeaf));

        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 1, "e1", "e1", expectedRoot, "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));
        
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any()))
                .thenReturn(List.of(r1));

        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(1L);
        assertEquals("VALID", result.status());
        assertFalse(result.isTampered());
        assertEquals(expectedRoot, result.computedMerkleRoot());
        assertEquals("ulpf_raw.raw_events", result.databaseTable());
    }

    @Test
    void testVerifyBlockIntegrity_TamperedMetadata() {
        // Original event
        String originalLeaf = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload");
        String originalRoot = merkleTreeCalculator.calculateMerkleRoot(List.of(originalLeaf));

        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 1, "e1", "e1", originalRoot, "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));
        
        // Database returns tampered event (changed lineageId from l1 to l2, payload remains same)
        RawEventRecord tamperedRecord = new RawEventRecord("e1", "l2", "v1", "src_fw_1", 1, LocalDateTime.now(), "payload");
        
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any()))
                .thenReturn(List.of(tamperedRecord));

        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(1L);
        assertEquals("TAMPERED_DETECTED", result.status());
        assertTrue(result.isTampered());
    }

    @Test
    void testVerifyAllBlocks() {
        // Setup two blocks: one valid, one tampered
        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, LocalDateTime.now(), "payload");
        String leaf1 = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload");
        String root1 = merkleTreeCalculator.calculateMerkleRoot(List.of(leaf1));
        IntegrityBlockRecord block1 = new IntegrityBlockRecord(1L, "src_fw_1", 1, "e1", "e1", root1, "prev_hash", LocalDateTime.now());

        IntegrityBlockRecord block2 = new IntegrityBlockRecord(2L, "src_fw_1", 1, "e2", "e2", "different_root", root1, LocalDateTime.now());
        RawEventRecord r2 = new RawEventRecord("e2", "l2", "v1", "src_fw_1", 1, LocalDateTime.now(), "payload2");

        when(integrityRepository.findAllBlocks(anyInt())).thenReturn(List.of(block1, block2));
        
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block1));
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), eq("src_fw_1"), eq("e1")))
                .thenReturn(List.of(r1));
                
        when(integrityRepository.findBlockById(2L)).thenReturn(java.util.Optional.of(block2));
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), eq("src_fw_1"), eq("e2")))
                .thenReturn(List.of(r2));

        BatchIntegrityService.BulkVerificationResult result = batchIntegrityService.verifyAllBlocks();
        assertEquals(2, result.totalBlocksChecked());
        assertEquals(1, result.validCount());
        assertEquals(1, result.tamperedCount());
        assertEquals(1, result.tamperedBlocks().size());
        assertEquals(2L, result.tamperedBlocks().get(0).blockId());
    }
}
