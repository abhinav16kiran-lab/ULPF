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
 * The monitor password comes from a mounted secret file (Spring config tree): /run/secrets/monitor_password in a
 * container. The file may end with a newline; Spring trims it. This test uses its own folder instead of /run/secrets.
 */
@SpringBootTest(properties = "spring.config.import=optional:configtree:target/test-secrets/")
@ActiveProfiles("test")
class ActuatorSecurityConfigTreeTest {

    static {
        try {
            Path dir = Path.of("target/test-secrets");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("monitor_password"), "from-secret-file-42\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path dbFile;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        dbFile = Files.createTempFile("ulpf-actuator-configtree", ".db");
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
    void thePasswordFromTheSecretFileAuthenticates() throws Exception {
        assertEquals(200, status(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, basic("monitor", "from-secret-file-42"))));
        assertEquals(401, status(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, basic("monitor", "from-secret-file-42\n"))));
    }
}
