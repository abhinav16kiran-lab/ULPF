package com.ulpf.common.db;

import com.ulpf.common.db.ClickHouseIngestionRepository.RawEventRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class ClickHouseIngestionRepositoryTest {

    private ClickHouseIngestionRepository ingestionRepository;
    private JdbcTemplate clickhouseJdbcTemplate;

    @BeforeEach
    void setUp() {
        clickhouseJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        ingestionRepository = new ClickHouseIngestionRepository(clickhouseJdbcTemplate);
    }

    @Test
    void testEnqueueAndFlush() {
        RawEventRecord event = new RawEventRecord(
                "evt-100",
                "lin-100",
                "vendor1",
                "source1",
                1,
                LocalDateTime.now(),
                "{\"raw\":\"payload\"}"
        );

        ingestionRepository.enqueue(event);
        assertEquals(1, ingestionRepository.getQueueSize());

        ingestionRepository.flush();

        assertEquals(0, ingestionRepository.getQueueSize());
        verify(clickhouseJdbcTemplate, atLeastOnce()).batchUpdate(anyString(), any(), anyInt(), any());
    }
}
