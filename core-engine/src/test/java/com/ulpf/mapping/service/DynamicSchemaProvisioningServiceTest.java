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
}
