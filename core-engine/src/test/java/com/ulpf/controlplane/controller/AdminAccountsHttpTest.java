package com.ulpf.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Admin accounts end to end over HTTP, through the real security chain: one-time setup, super-admin-only
 * management, and that revoking or resetting an admin ends their existing sessions at once.
 * One Spring context and one temporary SQLite file for the whole class; tests run in order because
 * setup happens exactly once per database.
 */
@SpringBootTest(properties = {
        "ulpf.admin.username=http_setup",
        "ulpf.admin.password=HttpSetup1"
})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminAccountsHttpTest {

    private static final String ROOT = "root_admin";
    private static final String ROOT_PASS = "root-admin-pass-12";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path dbFile;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        dbFile = Files.createTempFile("ulpf-admin-accounts", ".db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile + "?foreign_keys=on");
    }

    @AfterAll
    static void deleteDb() throws IOException {
        Files.deleteIfExists(dbFile);
    }

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private BCryptPasswordEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---- helpers ----

    private MockHttpServletResponse send(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    private static String body(Object... keyValues) throws Exception {
        var node = JSON.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put((String) keyValues[i], (String) keyValues[i + 1]);
        }
        return JSON.writeValueAsString(node);
    }

    private MockHttpServletResponse login(String username, String password, String role) throws Exception {
        return send(post("/v1/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("username", username, "password", password, "role", role)));
    }

    private String bearer(String username, String password, String role) throws Exception {
        MockHttpServletResponse response = login(username, password, role);
        assertEquals(200, response.getStatus(), "login " + username + ": " + response.getContentAsString());
        return "Bearer " + json(response).get("token").asText();
    }

    private String rootBearer() throws Exception {
        return bearer(ROOT, ROOT_PASS, "ADMIN");
    }

    /** Invites and creates a regular admin through the API; returns its user id. */
    private String inviteAdmin(String username, String password) throws Exception {
        MockHttpServletResponse invite = send(post("/v1/admin/admins/invites").header("Authorization", rootBearer())
                .contentType(MediaType.APPLICATION_JSON).content(body("label", username)));
        assertEquals(201, invite.getStatus(), invite.getContentAsString());
        String token = json(invite).get("token").asText();
        MockHttpServletResponse used = send(post("/v1/links/{token}", token).contentType(MediaType.APPLICATION_JSON)
                .content(body("username", username, "password", password, "confirmPassword", password)));
        assertEquals(204, used.getStatus(), used.getContentAsString());
        return users.findByUsername(username).orElseThrow().userId();
    }

    private int notificationsStatus(String bearer) throws Exception {
        return send(get("/v1/notifications").header("Authorization", bearer)).getStatus();
    }

    // ---- tests ----

    @Test
    @Order(1)
    void oneTimeSetupCreatesTheSuperAdmin() throws Exception {
        MockHttpServletResponse status = send(get("/v1/setup/status"));
        assertEquals(200, status.getStatus(), "setup status endpoint");
        assertTrue(json(status).get("setupRequired").asBoolean(), "fresh database: setup required");

        MockHttpServletResponse setup = send(post("/v1/setup").contentType(MediaType.APPLICATION_JSON).content(body(
                "setupUsername", "http_setup", "setupPassword", "HttpSetup1",
                "username", ROOT, "password", ROOT_PASS, "confirmPassword", ROOT_PASS)));
        assertEquals(201, setup.getStatus(), setup.getContentAsString());

        assertFalse(json(send(get("/v1/setup/status"))).get("setupRequired").asBoolean());
        MockHttpServletResponse rootLogin = login(ROOT, ROOT_PASS, "ADMIN");
        assertEquals(200, rootLogin.getStatus());
        assertTrue(json(rootLogin).get("superAdmin").asBoolean(), "the login response tells the UI who the super-admin is");
        assertEquals(401, login("http_setup", "HttpSetup1", "ADMIN").getStatus(), "setup credentials are not a login");
    }

    @Test
    @Order(2)
    void onlyTheSuperAdminCanManageAdmins() throws Exception {
        inviteAdmin("helper_admin", "helper-admin-pass-12");
        assertFalse(json(login("helper_admin", "helper-admin-pass-12", "ADMIN")).get("superAdmin").asBoolean());
        users.save(new User(null, "some_vendor", "Vendor", encoder.encode("vendor-pass"), Role.VENDOR, null));
        users.save(new User(null, "some_user", "User", encoder.encode("user-pass"), Role.USER, null));

        for (String who : new String[]{
                bearer("helper_admin", "helper-admin-pass-12", "ADMIN"),
                bearer("some_vendor", "vendor-pass", "VENDOR"),
                bearer("some_user", "user-pass", "USER")}) {
            assertEquals(403, send(get("/v1/admin/admins").header("Authorization", who)).getStatus());
            assertEquals(403, send(post("/v1/admin/admins/invites").header("Authorization", who)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus());
        }
        assertEquals(200, send(get("/v1/admin/admins").header("Authorization", rootBearer())).getStatus());
    }

    @Test
    @Order(3)
    void aRevokedAdminIsSignedOutImmediately() throws Exception {
        String id = inviteAdmin("short_lived", "short-lived-pass-12");
        String token = bearer("short_lived", "short-lived-pass-12", "ADMIN");
        assertEquals(200, notificationsStatus(token));

        assertEquals(204, send(delete("/v1/admin/admins/{id}", id).header("Authorization", rootBearer())).getStatus());

        assertEquals(401, notificationsStatus(token), "the existing token must stop working on the next request");
        assertEquals(401, login("short_lived", "short-lived-pass-12", "ADMIN").getStatus());
    }

    @Test
    @Order(4)
    void aResetLinkSetsANewPasswordAndEndsOldSessions() throws Exception {
        String id = inviteAdmin("forgetful", "forgetful-pass-12");
        String oldToken = bearer("forgetful", "forgetful-pass-12", "ADMIN");

        MockHttpServletResponse reset = send(post("/v1/admin/admins/{id}/reset-link", id).header("Authorization", rootBearer()));
        assertEquals(201, reset.getStatus(), reset.getContentAsString());
        MockHttpServletResponse used = send(post("/v1/links/{token}", json(reset).get("token").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("password", "remembered-pass-12", "confirmPassword", "remembered-pass-12")));
        assertEquals(204, used.getStatus(), used.getContentAsString());

        assertEquals(401, notificationsStatus(oldToken), "sessions from before the reset end");
        assertEquals(200, login("forgetful", "remembered-pass-12", "ADMIN").getStatus());
        assertEquals(401, login("forgetful", "forgetful-pass-12", "ADMIN").getStatus());
    }

    @Test
    @Order(5)
    void linkPagesAreReachableWithAStaleTokenInTheBrowser() throws Exception {
        // The browser may still send an old, invalid token; public link endpoints must not answer 401.
        MockHttpServletResponse response = send(get("/v1/links/{token}", "no-such-token")
                .header("Authorization", "Bearer not-a-valid-token"));
        assertEquals(410, response.getStatus(), "unknown link: 410, not 401");
    }
}
