package com.ulpf.common;

import com.ulpf.common.AccountLookup.Account;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer} requests. The token proves who the caller was when they signed in;
 * {@link AccountLookup} (cached 60 s) says who they are now: a revoked account or a changed password ends the
 * session on the next request, and role and {@code ROLE_SUPER_ADMIN} are taken from the database, not the token.
 * Requests without a token pass through untouched, for {@link SpringSecurity}'s rules to accept or refuse.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/v1/login",
            "/v1/signup",
            "/v1/events",
            "/v1/health",
            "/v1/setup/**",   // public; a stale token in the browser must not turn these into 401s
            "/v1/links/**",
            "/error",
            "/actuator/**"
    );

    private final JwtUtil jwtUtil;
    private final AccountLookup accountLookup;

    public JwtFilter(JwtUtil jwtUtil, AccountLookup accountLookup) {
        this.jwtUtil = jwtUtil;
        this.accountLookup = accountLookup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        for (String pattern : EXCLUDED_PATHS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);

        try {
            // One parse verifies the signature and expiry and yields every claim we need.
            Claims claims = jwtUtil.parseClaims(token);
            Optional<Account> account = accountLookup.find(claims.get("id", String.class));

            // The database decides: the account must still exist and its password must not have changed since
            // the token was issued. Role and super-admin come from the database, never from the token's claims.
            if (account.isEmpty() || !account.get().passwordFingerprint().equals(claims.get("pwv", String.class))) {
                reject(response);
                return;
            }

            Account live = account.get();
            String role = live.role().name();
            List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(
                    new SimpleGrantedAuthority("ROLE_" + role),
                    new SimpleGrantedAuthority(role)));
            if (live.superAdmin()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    new UlpfPrincipal(live.userId(), live.username(), role), null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException e) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("Invalid or expired token");
    }
}