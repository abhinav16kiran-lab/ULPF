package com.ulpf.dataplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.service.NotificationService;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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

    record ReceivedRequest(String body, String signatureHeader, String timestampHeader) {}

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
            String ts = exchange.getRequestHeaders().getFirst("X-ULPF-Timestamp");
            receivedRequests.add(new ReceivedRequest(body, sig, ts));
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
        assertNotNull(req.timestampHeader(), "X-ULPF-Timestamp header should be present");
        assertNotNull(req.signatureHeader(), "X-ULPF-Signature header should be present");

        String expectedSignature = "sha256=" + AlertDispatcher.computeHmacSha256(req.timestampHeader() + "." + req.body(), secret);
        assertEquals(expectedSignature, req.signatureHeader());
    }

    @Test
    void testVendorOwnerAdmin_GetsExactlyOneNotification() {
        User adminVendorUser = new User("user_both", "user_both", "Both", "hash", Role.ADMIN, LocalDateTime.now());
        when(userRepository.findUsersByRole(Role.ADMIN)).thenReturn(List.of(adminVendorUser));

        AlertPayload payload = new AlertPayload(
                "TEST_ALERT", "INFO", "src_both", "Source Both", "ven_both", "Vendor Both",
                "table_both", null, 1L, Collections.emptySet(), Instant.now(), "Admin Msg", "Action"
        );

        alertDispatcher.dispatchAlert(
                payload,
                "Admin Alert Title",
                "Admin Alert Message",
                "user_both",
                "Vendor Alert Title",
                "Vendor Alert Message"
        );

        // user_both should receive vendor notification, and the admin loop should skip user_both
        verify(notificationService, times(1)).createNotification(eq("user_both"), eq("Vendor Alert Title"), eq("Vendor Alert Message"));
        verify(notificationService, never()).createNotification(eq("user_both"), eq("Admin Alert Title"), eq("Admin Alert Message"));
    }

    @Test
    void testInvalidWebhookUrl_DisablesWebhook() {
        alertDispatcher.setWebhookUrl("ftp://invalid-scheme/alert");
        assertNull(ReflectionTestUtils.getField(alertDispatcher, "validatedWebhookUri"));

        AlertPayload payload = new AlertPayload(
                "TEST_ALERT", "INFO", "src_inv", "Source Inv", "ven_inv", "Vendor Inv",
                "table_inv", null, 1L, Collections.emptySet(), Instant.now(), "msg", "action"
        );

        CompletableFuture<Void> future = alertDispatcher.sendWebhookAlertSafely(payload);
        assertTrue(future.isDone());
        assertNull(receivedRequests.poll());
    }

    @Test
    void testWebhookFailureLog_ContainsOnlyHost() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // Stop server so webhook delivery fails
            httpServer.stop(0);
            httpServer = null;

            alertDispatcher.setWebhookUrl("http://localhost:" + port + "/secret-path/token?token=secret123");

            AlertPayload payload = new AlertPayload(
                    "TEST_ALERT", "INFO", "src_log", "Source Log", "ven_log", "Vendor Log",
                    "table_log", null, 1L, Collections.emptySet(), Instant.now(), "msg", "action"
            );

            alertDispatcher.sendWebhookAlertSafely(payload).join();

            boolean foundHost = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("localhost"));
            assertTrue(foundHost, "Log should contain host 'localhost'");

            boolean leakedSecretOrPath = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("secret-path")
                            || event.getFormattedMessage().contains("secret123"));
            assertFalse(leakedSecretOrPath, "Log should NOT contain path or query string");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void testWebhookSaturation_DoesNotBlockOrThrow() {
        HttpServer slowServer = null;
        try {
            slowServer = HttpServer.create(new InetSocketAddress(0), 0);
            slowServer.createContext("/slow", exchange -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            slowServer.start();

            alertDispatcher.setWebhookUrl("http://localhost:" + slowServer.getAddress().getPort() + "/slow");

            AlertPayload payload = new AlertPayload(
                    "STORM_ALERT", "WARN", "src_storm", "Source Storm", "ven_storm", "Vendor Storm",
                    "table_storm", null, 1L, Collections.emptySet(), Instant.now(), "Storm msg", "Storm action"
            );

            long start = System.currentTimeMillis();
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 500; i++) {
                    alertDispatcher.sendWebhookAlertSafely(payload);
                }
            });
            long duration = System.currentTimeMillis() - start;
            // 500 sends dispatched to bounded executor with DiscardPolicy must return almost instantly (< 2000ms)
            assertTrue(duration < 2000, "500 webhook dispatches took too long: " + duration + "ms");
        } catch (Exception e) {
            fail("Saturation test failed: " + e.getMessage());
        } finally {
            if (slowServer != null) {
                slowServer.stop(0);
            }
        }
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
