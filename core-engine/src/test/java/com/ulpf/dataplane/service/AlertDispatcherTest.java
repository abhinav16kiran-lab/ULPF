package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertDispatcherTest {

    private OnboardingRepository onboardingRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private AlertDispatcher alertDispatcher;
    private HttpServer httpServer;
    private int port;
    private final BlockingQueue<ReceivedRequest> receivedRequests = new LinkedBlockingQueue<>();

    record ReceivedRequest(String body, String signatureHeader) {}

    @BeforeEach
    void setUp() throws Exception {
        onboardingRepository = mock(OnboardingRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);

        // Setup lightweight JDK HttpServer for webhook testing
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = httpServer.getAddress().getPort();
        httpServer.createContext("/webhook", exchange -> {
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String sig = exchange.getRequestHeaders().getFirst("X-ULPF-Signature");
            receivedRequests.add(new ReceivedRequest(body, sig));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        httpServer.start();

        alertDispatcher = new AlertDispatcher(onboardingRepository, userRepository, notificationService, new ObjectMapper());
        alertDispatcher.setWebhookUrl("http://localhost:" + port + "/webhook");
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void testWebhookBodyValidJsonWithSpecialCharactersAndContainsAllFields() throws Exception {
        String trickySourceName = "Source \"with quotes\"\nand newlines \\ & tabs";
        AlertPayload payload = new AlertPayload(
                "TABLE_MISSING",
                "CRITICAL",
                "src_test_1",
                trickySourceName,
                "ven_1",
                "Vendor Alpha",
                "events_table_1",
                "ClickHouse table missing",
                150L,
                Set.of("col1", "col2"),
                Instant.now(),
                "Critical table missing alert message",
                "POST /v1/admin/sources/src_test_1/replay"
        );

        alertDispatcher.sendWebhookAlertSafely(payload).get(5, TimeUnit.SECONDS);

        ReceivedRequest req = receivedRequests.poll(5, TimeUnit.SECONDS);
        assertNotNull(req, "Webhook request should have been received");

        // Verify valid JSON parsing
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(req.body());
        assertEquals("TABLE_MISSING", json.get("alertType").asText());
        assertEquals("src_test_1", json.get("sourceId").asText());
        assertEquals(trickySourceName, json.get("sourceName").asText());
        assertEquals("Vendor Alpha", json.get("vendorName").asText());
        assertEquals("events_table_1", json.get("tableName").asText());
        assertEquals(150L, json.get("affectedCount").asLong());
        assertEquals("POST /v1/admin/sources/src_test_1/replay", json.get("recommendedAction").asText());
    }

    @Test
    void testWebhookSignatureHeaderMatchesWhenSecretIsSet() throws Exception {
        String secret = "test-secret-key-123";
        alertDispatcher.setWebhookSecret(secret);

        AlertPayload payload = new AlertPayload(
                "SCHEMA_DRIFT",
                "WARN",
                "src_test_2",
                "Standard Source",
                "ven_2",
                "Vendor Beta",
                "events_table_2",
                null,
                50L,
                Collections.emptySet(),
                Instant.now(),
                "Drift detected",
                "Update mapping schema"
        );

        alertDispatcher.sendWebhookAlertSafely(payload).get(5, TimeUnit.SECONDS);

        ReceivedRequest req = receivedRequests.poll(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertNotNull(req.signatureHeader(), "X-ULPF-Signature header should be present");

        String expectedSignature = AlertDispatcher.computeHmacSha256(req.body(), secret);
        assertEquals(expectedSignature, req.signatureHeader());
    }

    @Test
    void testDeadWebhookUrlNeverThrowsAndNeverBlocks() {
        // Set a non-routable / dead address
        alertDispatcher.setWebhookUrl("http://127.0.0.1:59999/dead-endpoint");

        AlertPayload payload = new AlertPayload(
                "TABLE_MISSING",
                "CRITICAL",
                "src_test_3",
                "Source 3",
                "ven_3",
                "Vendor 3",
                "events_3",
                "Timeout error",
                10L,
                Collections.emptySet(),
                Instant.now(),
                "Message",
                "Action"
        );

        // Must complete without throwing exception
        assertDoesNotThrow(() -> {
            alertDispatcher.sendWebhookAlertSafely(payload).get(5, TimeUnit.SECONDS);
        });
    }

    @Test
    void testDispatchAlertRoutesToAdminAndVendorUsers() {
        when(userRepository.findUsersByRole(com.ulpf.controlplane.model.Role.ADMIN)).thenReturn(Collections.emptyList());

        AlertPayload payload = new AlertPayload(
                "TEST_ALERT", "INFO", "src_4", "Source 4", "ven_4", "Vendor 4",
                "table_4", null, 1L, Collections.emptySet(), Instant.now(), "msg", "action"
        );

        alertDispatcher.dispatchAlert(
                payload,
                "Admin Alert Title",
                "Admin Alert Message",
                "vendor_user_1",
                "Vendor Alert Title",
                "Vendor Alert Message"
        );

        verify(notificationService).createNotification(eq("admin"), eq("Admin Alert Title"), eq("Admin Alert Message"));
        verify(notificationService).createNotification(eq("vendor_user_1"), eq("Vendor Alert Title"), eq("Vendor Alert Message"));
    }
}
