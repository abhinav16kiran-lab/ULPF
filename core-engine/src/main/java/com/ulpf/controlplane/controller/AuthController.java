package com.ulpf.controlplane.controller;

import com.ulpf.controlplane.service.AuthService;
import com.ulpf.controlplane.service.AuthService.LoginResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null || request.role() == null
                || request.username().isBlank() || request.password().isBlank() || request.role().isBlank()) {
            return new ResponseEntity<>("All fields (username, password, role) are required", HttpStatus.BAD_REQUEST);
        }

        try {
            LoginResult result = authService.login(request.username(), request.password(), request.role());
            return ResponseEntity.ok(Map.of(
                    "username", result.username(),
                    "role", result.role(),
                    "token", result.token()
            ));
        } catch (BadCredentialsException e) {
            return new ResponseEntity<>("Invalid Credentials", HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestBody SignUpRequest request) {
        if (request.name() == null || request.username() == null || request.password() == null || request.confirmPassword() == null
                || request.name().isBlank() || request.username().isBlank() || request.password().isBlank() || request.confirmPassword().isBlank()) {
            return new ResponseEntity<>("All fields (name, username, password, confirmPassword) are required", HttpStatus.BAD_REQUEST);
        }

        try {
            authService.signUp(request.name(), request.username(), request.password(), request.confirmPassword());
            return ResponseEntity.ok("User registered successfully");
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/test")
    public String getMethodName() {
        return "Hello, this is a test endpoint to verify that authentication is working correctly.";
    }
}

record LoginRequest(String username, String password, String role) {}
record SignUpRequest(String name, String username, String password, String confirmPassword) {}