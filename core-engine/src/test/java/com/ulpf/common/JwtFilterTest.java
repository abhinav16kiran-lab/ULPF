package com.ulpf.common;

import com.ulpf.common.db.SqliteSchemaMigrator;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.testsupport.TestSqlite;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtFilter trusts the database, not the token, for "does this account still exist", its role and the
 * super-admin flag, and rejects tokens issued before a password change.
 */
class JwtFilterTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-only-not-for-production";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private AccountLookup lookup;
    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestSqlite.inMemory("sqlite/schema.sql");
        new SqliteSchemaMigrator(jdbc, encoder).migrate();
        users = new UserRepository(jdbc);
        lookup = new AccountLookup(users);
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        filter = new JwtFilter(jwtUtil, lookup);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String tokenFor(User user) {
        return jwtUtil.generateToken(user.userId(), user.username(), user.role().name(), AccountLookup.fingerprint(user.passwordHash()));
    }

    /** Runs one request through the filter; returns the response. Sets {@link #passedThrough}. */
    private MockHttpServletResponse call(String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/notifications");
        request.setServletPath("/v1/notifications");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        passedThrough = chain.getRequest() != null;
        authorities = passedThrough && SecurityContextHolder.getContext().getAuthentication() != null
                ? names(SecurityContextHolder.getContext().getAuthentication()) : Set.of();
        return response;
    }

    private boolean passedThrough;
    private Set<String> authorities;

    private static Set<String> names(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private User admin(String username) {
        return users.save(new User(null, username, username, encoder.encode(username + "-pass-1234"), Role.ADMIN, null));
    }

    @Test
    void aValidTokenOfAnExistingAccountPasses() throws Exception {
        call(tokenFor(admin("helper")));
        assertTrue(passedThrough);
        assertTrue(authorities.contains("ROLE_ADMIN"));
    }

    @Test
    void theTokenOfADeletedAccountIsRejected() throws Exception {
        User helper = admin("helper");
        String token = tokenFor(helper);
        users.deleteUserWithNotifications(helper.userId());
        lookup.evict(helper.userId());

        MockHttpServletResponse response = call(token);

        assertEquals(401, response.getStatus());
        assertFalse(passedThrough);
    }

    @Test
    void aTokenIssuedBeforeAPasswordChangeIsRejected() throws Exception {
        User helper = admin("helper");
        String oldToken = tokenFor(helper);
        users.updatePassword(helper.userId(), encoder.encode("brand-new-pass-12"));
        lookup.evict(helper.userId());

        assertEquals(401, call(oldToken).getStatus(), "old sessions end when the password changes");

        call(tokenFor(users.findById(helper.userId()).orElseThrow()));
        assertTrue(passedThrough, "a token issued after the change works");
    }

    @Test
    void theRoleComesFromTheDatabaseNotTheToken() throws Exception {
        User helper = admin("helper");
        String token = tokenFor(helper);                       // says ADMIN
        users.updateUserRole(helper.userId(), Role.USER);     // database now says USER
        lookup.evict(helper.userId());

        call(token);

        assertTrue(authorities.contains("ROLE_USER"));
        assertFalse(authorities.contains("ROLE_ADMIN"), "a stale ADMIN claim must not grant admin rights");
    }

    @Test
    void superAdminRightsComeFromTheDatabaseFlagOnly() throws Exception {
        User root = users.createSuperAdmin("root_admin", "Super-admin", encoder.encode("root-admin-pass-12"));
        call(tokenFor(root));
        assertTrue(authorities.contains("ROLE_SUPER_ADMIN"));
        assertTrue(authorities.contains("ROLE_ADMIN"), "the super-admin is also an admin");

        User helper = admin("helper");
        String forged = Jwts.builder()
                .claims(Map.of("id", helper.userId(), "role", "ADMIN", "superAdmin", true, "role2", "SUPER_ADMIN",
                        "pwv", AccountLookup.fingerprint(helper.passwordHash())))
                .subject(helper.username())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();
        call(forged);
        assertFalse(authorities.contains("ROLE_SUPER_ADMIN"), "claims in a token cannot grant super-admin");
    }

    @Test
    void requestsWithoutATokenAreLeftToTheSecurityRules() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/notifications");
        request.setServletPath("/v1/notifications");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "no token: the filter passes the request on");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
