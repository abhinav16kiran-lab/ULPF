package com.ulpf.mapping.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DynamicSchemaProvisioningServiceTest {

    @Test
    public void testConstructTableName() {
        DynamicSchemaProvisioningService service = new DynamicSchemaProvisioningService(null);

        String tableName1 = service.constructTableName("shitty source ", List.of());
        assertEquals("events_shitty_source", tableName1);

        String tableName2 = service.constructTableName("Auth-Service Logs!!!", List.of());
        assertEquals("events_auth_service_logs", tableName2);

        String tableName3 = service.constructTableName("", List.of());
        assertEquals("events_stream", tableName3);

        // Name collision check when events_shitty_source already exists
        String tableName4 = service.constructTableName("shitty source ", List.of("events_shitty_source"));
        assertEquals("events_shitty_source_2", tableName4);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testProvisionSchemaForSourceExtractsObjectFieldsProperly() {
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        DynamicSchemaProvisioningService service = new DynamicSchemaProvisioningService(jdbcTemplate);

        org.mockito.Mockito.when(jdbcTemplate.query(org.mockito.ArgumentMatchers.eq("SHOW TABLES FROM ulpf_events"), org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        String mappingJson = """
            {
                "src_ip": {
                    "canonicalField": "source_ip",
                    "confidence": 0.95
                },
                "bytes_transferred": {
                    "canonicalField": "network.bytes",
                    "confidence": 0.88
                }
            }
            """;

        String tableName = service.provisionSchemaForSource("src_1", "Web Server", mappingJson);
        assertEquals("events_web_server", tableName);

        org.mockito.ArgumentCaptor<String> ddlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbcTemplate).execute(ddlCaptor.capture());

        String ddl = ddlCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("source_ip Nullable(String)"), "DDL must contain source_ip column");
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("network_bytes Nullable(String)"), "DDL must contain normalized network_bytes column");
    }
}
