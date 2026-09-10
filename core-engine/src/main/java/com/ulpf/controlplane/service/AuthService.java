package com.ulpf.controlplane.service;

import com.ulpf.common.JwtUtil;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import com.ulpf.common.db.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final com.ulpf.common.db.VendorRepository vendorRepository;
    private final com.ulpf.common.db.OnboardingRepository onboardingRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            com.ulpf.common.db.VendorRepository vendorRepository,
            com.ulpf.common.db.OnboardingRepository onboardingRepository,
            JwtUtil jwtUtil,
            BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.vendorRepository = vendorRepository;
        this.onboardingRepository = onboardingRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginResult(String username, String role, String token) {}

    public LoginResult login(String username, String password, String requestedRole) {
        if (username == null || username.trim().length() < 3 || username.trim().length() > 30) {
            throw new IllegalArgumentException("Username must be between 3 and 30 characters");
        }
        String cleanUsername = username.trim();
        if (!cleanUsername.matches("^[a-zA-Z0-9_.-]+$")) {
            throw new IllegalArgumentException("Username can only contain letters, numbers, underscores, hyphens, and periods");
        }
        if (password == null || password.length() < 6 || password.length() > 100) {
            throw new IllegalArgumentException("Password must be between 6 and 100 characters");
        }

        Optional<User> userOpt = userRepository.findByUsername(cleanUsername);

        if (userOpt.isEmpty()) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        // Auto-promote user to VENDOR if an APPROVED onboarding request exists in SQLite database
        if (user.role() == Role.USER && onboardingRepository != null) {
            boolean hasApproved = onboardingRepository.findRequestsByUserId(user.userId())
                    .stream()
                    .anyMatch(req -> "APPROVED".equalsIgnoreCase(req.status()));
            if (hasApproved) {
                userRepository.updateUserRole(user.userId(), Role.VENDOR);
                user = new User(user.userId(), user.username(), user.name(), user.passwordHash(), Role.VENDOR, user.createdAt());
            }
        }

        // Verify that the requested role is satisfied by the user's stored role in SQLite (supports role hierarchy / promotions)
        if (requestedRole != null && !requestedRole.isBlank() && !user.role().satisfiesRequestedRole(requestedRole)) {
            throw new BadCredentialsException("Invalid Credentials");
        }

        String userRoleStr = user.role().name();
        String token = jwtUtil.generateToken(user.userId(), user.username(), userRoleStr);

        return new LoginResult(user.username(), userRoleStr, token);
    }

    public void signUp(String name, String username, String password, String confirmPassword) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 100) {
            throw new IllegalArgumentException("Name must be between 2 and 100 characters");
        }

        if (username == null || username.trim().length() < 3 || username.trim().length() > 30) {
            throw new IllegalArgumentException("Username must be between 3 and 30 characters");
        }

        String cleanUsername = username.trim();
        if (!cleanUsername.matches("^[a-zA-Z0-9_.-]+$")) {
            throw new IllegalArgumentException("Username can only contain letters, numbers, underscores, hyphens, and periods");
        }

        if (password == null || password.length() < 6 || password.length() > 100) {
            throw new IllegalArgumentException("Password must be between 6 and 100 characters");
        }

        if (confirmPassword == null || !password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match");
        }

        if (userRepository.existsByUsername(cleanUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }

        String hashedPassword = passwordEncoder.encode(password);
        // All public signups are automatically assigned Role.USER
        User newUser = new User(null, cleanUsername, name.trim(), hashedPassword, Role.USER, null);
        userRepository.save(newUser);
    }
}