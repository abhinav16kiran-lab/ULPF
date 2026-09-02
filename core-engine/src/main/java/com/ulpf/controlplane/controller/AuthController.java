package com.ulpf.controlplane.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.controlplane.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;


@RestController
@RequestMapping("/v1")
public class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank()) {
            return new  ResponseEntity<>("Username and password must not be empty", HttpStatus.BAD_REQUEST)
                    ;
        }

        try {
            String token = authService.login(request.username(), request.password());
            return ResponseEntity.ok(Map.of(
                    "username", request.username(),
                    "token", token
            ));
        } catch (BadCredentialsException e) {
            return new ResponseEntity<>("Invalid Credentials", HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestBody SignUpRequest request) {
        if (request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank()) {
            return new ResponseEntity<>("Username and password must not be empty", HttpStatus.BAD_REQUEST);
        }

        try {
            authService.signUp(request.username(), request.password());
            return ResponseEntity.ok("User registered successfully");
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/test")
    public String getMethodName() {
        return new String("Hello, this is a test endpoint to verify that the authentication is working correctly.");
    }
    
}

record LoginRequest(String username, String password) {}
record SignUpRequest(String username, String password) {}