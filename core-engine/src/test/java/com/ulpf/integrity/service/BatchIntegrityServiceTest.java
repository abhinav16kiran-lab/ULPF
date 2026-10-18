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

    @Test
    void testRawEventRecordCompactConstructorDefaultsNullTimestamp() {
        RawEventRecord record = new RawEventRecord("e1", "l1", "v1", "src_1", 1, null, "payload");
        assertNotNull(record.receivedAt(), "receivedAt should not be null after compact constructor runs");
    }

    @Test
    void testVerifyBlockIntegrity_ClickHouseQueryContainsNullsFirst() {
        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 2, "e1", "e2", "mock_root", "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(clickhouseJdbcTemplate.query(sqlCaptor.capture(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        batchIntegrityService.verifyBlockIntegrity(1L);

        String executedSql = sqlCaptor.getValue();
        assertTrue(executedSql.contains("ORDER BY received_at ASC NULLS FIRST, event_id ASC NULLS FIRST"),
                "ClickHouse windowSql should specify NULLS FIRST for both received_at and event_id");
    }

    @Test
    void testVerifyBlockIntegrity_DefensiveSortingOrderMatchesMerkleRoot() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 1, 1, 10, 0, 5);
        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, t1, "payload1");
        RawEventRecord r2 = new RawEventRecord("e2", "l1", "v1", "src_fw_1", 1, t2, "payload2");

        // Calculate expected root when sorted [r1, r2]
        String leaf1 = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload1");
        String leaf2 = MerkleTreeCalculator.hashSha256("e2" + "v1" + "src_fw_1" + "l1" + "payload2");
        String expectedRoot = merkleTreeCalculator.calculateMerkleRoot(List.of(leaf1, leaf2));

        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 2, "e1", "e2", expectedRoot, "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));

        // Even if ClickHouse returned rows in reverse order [r2, r1], in-memory defensive sorting must restore [r1, r2]
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any(), any(), any()))
                .thenReturn(List.of(r2, r1));

        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(1L);
        assertEquals("VALID", result.status());
        assertFalse(result.isTampered());
        assertEquals(expectedRoot, result.computedMerkleRoot());
    }

    @Test
    void testVerifyBlockIntegrity_DeduplicatesDuplicateDatabaseRecords() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 1, 1, 10, 0, 5);
        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, t1, "payload1");
        RawEventRecord r2 = new RawEventRecord("e2", "l1", "v1", "src_fw_1", 1, t2, "payload2");

        String leaf1 = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload1");
        String leaf2 = MerkleTreeCalculator.hashSha256("e2" + "v1" + "src_fw_1" + "l1" + "payload2");
        String expectedRoot = merkleTreeCalculator.calculateMerkleRoot(List.of(leaf1, leaf2));

        IntegrityBlockRecord block = new IntegrityBlockRecord(
                1L, "src_fw_1", 2, "e1", "e2", expectedRoot, "prev_hash", LocalDateTime.now()
        );
        when(integrityRepository.findBlockById(1L)).thenReturn(java.util.Optional.of(block));

        // ClickHouse returns duplicated rows from retry flushes: [r1, r1, r2, r2]
        when(clickhouseJdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<RawEventRecord>>any(), any(), any(), any(), any()))
                .thenReturn(List.of(r1, r1, r2, r2));

        BatchIntegrityService.VerificationResult result = batchIntegrityService.verifyBlockIntegrity(1L);
        assertEquals("VALID", result.status(), "Duplicate database rows should be deduplicated by eventId");
        assertFalse(result.isTampered());
        assertEquals(expectedRoot, result.computedMerkleRoot());
    }

    @Test
    void testProcessRawBatch_IdempotentSkipsExistingBlock() {
        RawEventRecord r1 = new RawEventRecord("e1", "l1", "v1", "src_fw_1", 1, LocalDateTime.now(), "payload");
        String leaf1 = MerkleTreeCalculator.hashSha256("e1" + "v1" + "src_fw_1" + "l1" + "payload");
        String expectedRoot = merkleTreeCalculator.calculateMerkleRoot(List.of(leaf1));

        IntegrityBlockRecord existingBlock = new IntegrityBlockRecord(
                10L, "src_fw_1", 1, "e1", "e1", expectedRoot, "prev_hash", LocalDateTime.now()
        );

        when(integrityRepository.findExistingBlock(eq("src_fw_1"), eq("e1"), eq("e1"), eq(expectedRoot)))
                .thenReturn(java.util.Optional.of(existingBlock));

        List<IntegrityBlockRecord> blocks = batchIntegrityService.processRawBatch(List.of(r1));

        assertEquals(1, blocks.size());
        assertEquals(10L, blocks.get(0).blockId());
        // Verify saveBlock was NOT called since block already exists
        verify(integrityRepository, never()).saveBlock(any());
    }
}
