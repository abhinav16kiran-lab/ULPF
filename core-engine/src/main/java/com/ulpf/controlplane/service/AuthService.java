package com.ulpf.controlplane.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import com.ulpf.common.JwtUtil;

@Service
public class AuthService {

    // temporary fake DB — replace with real UserRepository later
    private static final Map<String, String> fake_db = new ConcurrentHashMap<>(Map.of(
        "username", "password",
        "username2", "password2"
    )); //concurrent map to avoid concurrency issues in case of multiple signups/logins and since Map.of returns an immutable map

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

    public void signUp(String username, String password) {
        if (fake_db.containsKey(username)) {
            throw new IllegalArgumentException("Username already exists");
        }
        fake_db.put(username, password); //no hashing passwords for now, just for testing purposes
    }
}