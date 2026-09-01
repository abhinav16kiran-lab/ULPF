package com.ulpf.controlplane.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import com.ulpf.common.JwtUtil;

@Service
public class AuthService {

    // temporary fake DB — replace with real UserRepository later
    private static final Map<String, String> fake_db = Map.of(
        "username", "password",
        "username2", "password2"
    );

    private final JwtUtil jwtUtil;

    AuthService(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public String login(String username, String password) {
        String storedPassword = fake_db.get(username);

        if (storedPassword == null || !storedPassword.equals(password)) {
            throw new BadCredentialsException("invalid credentials");
        }

        // fake DB has no real userId, so just generate one per login for now
        String userId = UUID.randomUUID().toString();

        return jwtUtil.generateToken(userId, username);
    }
}