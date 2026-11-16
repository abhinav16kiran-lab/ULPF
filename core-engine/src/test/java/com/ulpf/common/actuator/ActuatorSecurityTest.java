package com.ulpf.common.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The actuator endpoints (backlog item 13, Phase 0): /actuator/health is open, every other actuator endpoint needs
 * the monitor user over HTTP Basic. A ULPF login token does not open them. The main security chain is unchanged.
 */
// These tests check the security chain, not the health of the databases. Without this, /actuator/health
// answers 503 (DOWN) when no ClickHouse runs, as in CI.
@SpringBootTest(properties = {"ulpf.monitor.password=monitor-test-pass-123", "management.health.db.enabled=false"})
@ActiveProfiles("test")
class ActuatorSecurityTest {

    private static Path dbFile;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        dbFile = Files.createTempFile("ulpf-actuator-security", ".db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile + "?foreign_keys=on");
    }

    @AfterAll
    static void deleteDb() throws IOException {
        Files.deleteIfExists(dbFile);
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private int status(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Autowired
    private UserRepository users;
    @Autowired
    private BCryptPasswordEncoder encoder;

    private String userToken() throws Exception {
        if (users.findByUsername("act_user").isEmpty()) {
            users.save(new User(null, "act_user", "act_user", encoder.encode("ActUser@12345"), Role.USER, null));
        }
        String body = mvc.perform(post("/v1/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"act_user\",\"password\":\"ActUser@12345\",\"role\":\"USER\"}"))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("token").asText();
    }

    @Test
    void healthIsOpen() throws Exception {
        assertEquals(200, status(get("/actuator/health")));
    }

    @Test
    void metricsNeedTheMonitorUser() throws Exception {
        assertEquals(401, status(get("/actuator/metrics")));
        assertEquals(401, status(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, basic("monitor", "wrong"))));
        assertEquals(200, status(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, basic("monitor", "monitor-test-pass-123"))));
    }

    @Test
    void aUlpfLoginTokenDoesNotOpenTheMetrics() throws Exception {
        assertEquals(401, status(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())));
    }

    @Test
    void theMonitorPasswordDoesNotOpenTheApplication() throws Exception {
        int s = status(get("/v1/onboard/my-sources").header(HttpHeaders.AUTHORIZATION, basic("monitor", "monitor-test-pass-123")));
        assertTrue(s == 401 || s == 403, "the monitor user is not an application user, got " + s);
    }

    @Test
    void theMainChainIsUnchanged() throws Exception {
        assertEquals(200, status(get("/v1/health")));
        assertEquals(401, status(post("/v1/events").contentType(MediaType.TEXT_PLAIN).content("x")),
                "ingest stays public and answers 401 for a missing API key");
        assertEquals(200, status(get("/v1/setup/status")));
    }
}
