package com.ulpf.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tests the real security chain (JwtFilter + SpringSecurity rules) on the vendor onboarding endpoints.
 * Onboarding is for USER and VENDOR accounts only; the admin must be refused at the door, not just hidden in the UI.
 * Real login and real JWTs; a temporary SQLite file so every pooled connection sees the same database.
 */
@SpringBootTest(properties = {
        "ulpf.admin.username=sec_admin",
        "ulpf.admin.password=SecAdmin@12345"
})
@ActiveProfiles("test")
class OnboardingSecurityTest {

    private static final String ADMIN = "sec_admin";
    private static final String ADMIN_PASSWORD = "SecAdmin@12345";
    private static Path dbFile;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        dbFile = Files.createTempFile("ulpf-onboarding-security", ".db");
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

    private String login(String username, String password, String role) throws Exception {
        String body = mvc.perform(post("/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("token").asText();
    }

    private int status(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void adminTokenIsRejectedOnEveryOnboardingEndpoint() throws Exception {
        String bearer = "Bearer " + login(ADMIN, ADMIN_PASSWORD, "ADMIN");

        Map<String, Integer> statuses = new LinkedHashMap<>();
        statuses.put("POST /v1/onboard/{admin}", status(multipart("/v1/onboard/{u}", ADMIN)
                .param("vendorName", "Admin Corp").param("sourceName", "Test Source").param("sourceType", "FIREWALL")
                .header("Authorization", bearer)));
        statuses.put("POST /v1/onboard/update/{admin}", status(multipart("/v1/onboard/update/{u}", ADMIN)
                .param("sourceId", "no-such-source")
                .header("Authorization", bearer)));
        statuses.put("POST /v1/onboard/withdraw/{id}", status(post("/v1/onboard/withdraw/{id}", "no-such-request")
                .header("Authorization", bearer)));
        statuses.put("GET /v1/onboard/my-requests", status(get("/v1/onboard/my-requests")
                .header("Authorization", bearer)));
        statuses.put("GET /v1/onboard/my-sources", status(get("/v1/onboard/my-sources")
                .header("Authorization", bearer)));

        assertTrue(statuses.values().stream().allMatch(s -> s == 403),
                "ADMIN token must get 403 on every onboarding endpoint, got: " + statuses);
    }
}
