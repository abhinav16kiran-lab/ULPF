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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
}
