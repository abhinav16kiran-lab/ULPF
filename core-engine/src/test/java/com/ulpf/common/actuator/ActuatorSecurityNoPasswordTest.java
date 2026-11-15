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

/** Fail closed: with no monitor password configured, no actuator endpoint except health answers, whatever the credentials. */
@SpringBootTest(properties = "ulpf.monitor.password=")
@ActiveProfiles("test")
class ActuatorSecurityNoPasswordTest {

    private static Path dbFile;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        dbFile = Files.createTempFile("ulpf-actuator-nopass", ".db");
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

    @Test
    void withoutAPasswordTheMetricsStayClosed() throws Exception {
        assertEquals(200, status(get("/actuator/health")));
        for (String auth : new String[]{null, basic("monitor", ""), basic("monitor", "monitor"), basic("", "")}) {
            var request = get("/actuator/metrics");
            if (auth != null) request = request.header(HttpHeaders.AUTHORIZATION, auth);
            int s = status(request);
            assertTrue(s == 401 || s == 403, "expected 401 or 403 with auth=" + auth + ", got " + s);
        }
    }
}
