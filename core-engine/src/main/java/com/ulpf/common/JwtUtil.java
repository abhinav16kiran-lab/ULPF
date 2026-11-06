package com.ulpf.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${jwt.secret:super-secret-jwt-signing-key-for-ulpf-dev-12345-minimum-256-bits}")
    private String secret;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String userId, String username) {
        return generateToken(userId, username, null);
    }

    public String generateToken(String userId, String username, String role) {
        return generateToken(userId, username, role, null);
    }

    /**
     * Login token. {@code pwv} is a fingerprint of the account's password hash
     * ({@link AccountLookup#fingerprint(String)}); {@link JwtFilter} rejects the token once the password changes.
     */
    public String generateToken(String userId, String username, String role, String passwordFingerprint) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", userId);
        if (role != null) {
            claims.put("role", role);
        }
        if (passwordFingerprint != null) {
            claims.put("pwv", passwordFingerprint);
        }

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractPasswordFingerprint(String token) {
        return extractAllClaims(token).get("pwv", String.class);
    }

    /**
     * Verifies the signature and expiry once and returns every claim; throws a {@code JwtException} otherwise.
     * Callers that need several claims use this instead of one {@code extract*} call (one parse) per claim.
     */
    public Claims parseClaims(String token) {
        return extractAllClaims(token);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractId(String token) {
        return extractAllClaims(token).get("id", String.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public boolean validateToken(String token) {
        return extractExpiration(token).after(new Date());
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}