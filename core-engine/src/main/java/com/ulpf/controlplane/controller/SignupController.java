package com.ulpf.controlplane.controller;

import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.SignupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> request) {

        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {

            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "error", "MISSING_FIELD",
                            "message", "Username and password are required"
                    ));
        }

        try {
            User user = signupService.signup(username, password);

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of(
                            "userId", user.userId(),
                            "username", user.username(),
                            "role", user.role().name(),
                            "createdAt", user.createdAt()
                    ));

        } catch (IllegalArgumentException e) {

            if ("USERNAME_TAKEN".equals(e.getMessage())) {
                return ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "error", "USERNAME_TAKEN",
                                "message", "Username is already taken"
                        ));
            }

            throw e;
        }
    }
}