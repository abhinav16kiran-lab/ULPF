package com.ulpf.controlplane.service;

import com.ulpf.common.JwtUtil;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginResult(String username, String role, String token) {}

    public LoginResult login(String username, String password, String requestedRole) {
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        // Verify that the requested role matches the user's stored role in SQLite
        if (requestedRole != null && !user.role().name().equalsIgnoreCase(requestedRole.trim())) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        String userRoleStr = user.role().name();
        String token = jwtUtil.generateToken(user.userId(), user.username(), userRoleStr);

        return new LoginResult(user.username(), userRoleStr, token);
    }

    public void signUp(String name, String username, String password, String confirmPassword) {
        if (password == null || confirmPassword == null || !password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match");
        }

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        String hashedPassword = passwordEncoder.encode(password);
        // All public signups are automatically assigned Role.USER
        User newUser = new User(null, username, name, hashedPassword, Role.USER, null);
        userRepository.save(newUser);
    }
}