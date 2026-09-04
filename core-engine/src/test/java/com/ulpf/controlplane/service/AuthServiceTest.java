package com.ulpf.controlplane.service;

import com.ulpf.common.JwtUtil;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtUtil, passwordEncoder);
    }

    @Test
    void testLoginSuccess_SameRole() {
        User user = new User("10", "johndoe", "John Doe", "hashedpass", Role.USER, null);
        when(userRepository.findByUsername("johndoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass123", "hashedpass")).thenReturn(true);
        when(jwtUtil.generateToken("10", "johndoe", "USER")).thenReturn("jwt-token-user");

        AuthService.LoginResult result = authService.login("johndoe", "pass123", "USER");

        assertNotNull(result);
        assertEquals("johndoe", result.username());
        assertEquals("USER", result.role());
        assertEquals("jwt-token-user", result.token());
    }

    @Test
    void testLoginSuccess_PromotedVendorLoggingInAsUser() {
        // User was promoted from USER to VENDOR, but selects USER in frontend login dropdown
        User vendorUser = new User("11", "promotedvendor", "Vendor One", "hashedpass", Role.VENDOR, null);
        when(userRepository.findByUsername("promotedvendor")).thenReturn(Optional.of(vendorUser));
        when(passwordEncoder.matches("pass123", "hashedpass")).thenReturn(true);
        when(jwtUtil.generateToken("11", "promotedvendor", "VENDOR")).thenReturn("jwt-token-vendor");

        AuthService.LoginResult result = authService.login("promotedvendor", "pass123", "USER");

        assertNotNull(result);
        assertEquals("promotedvendor", result.username());
        assertEquals("VENDOR", result.role());
        assertEquals("jwt-token-vendor", result.token());
    }

    @Test
    void testLoginSuccess_AdminLoggingInAsUserOrVendor() {
        User adminUser = new User("1", "admin", "System Admin", "hashedpass", Role.ADMIN, null);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("pass123", "hashedpass")).thenReturn(true);
        when(jwtUtil.generateToken("1", "admin", "ADMIN")).thenReturn("jwt-token-admin");

        AuthService.LoginResult result = authService.login("admin", "pass123", "USER");

        assertNotNull(result);
        assertEquals("admin", result.username());
        assertEquals("ADMIN", result.role());
    }

    @Test
    void testLoginFailure_RegularUserAttemptingVendorOrAdminRole() {
        User regularUser = new User("10", "johndoe", "John Doe", "hashedpass", Role.USER, null);
        when(userRepository.findByUsername("johndoe")).thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches("pass123", "hashedpass")).thenReturn(true);

        assertThrows(BadCredentialsException.class, () -> {
            authService.login("johndoe", "pass123", "VENDOR");
        });

        assertThrows(BadCredentialsException.class, () -> {
            authService.login("johndoe", "pass123", "ADMIN");
        });
    }

    @Test
    void testLoginFailure_InvalidPassword() {
        User user = new User("10", "johndoe", "John Doe", "hashedpass", Role.USER, null);
        when(userRepository.findByUsername("johndoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "hashedpass")).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> {
            authService.login("johndoe", "wrongpass", "USER");
        });
    }

    @Test
    void testLoginFailure_UserNotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> {
            authService.login("nonexistent", "pass123", "USER");
        });
    }
}
