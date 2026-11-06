package com.ulpf.controlplane.controller;

import com.ulpf.controlplane.service.SetupService;
import com.ulpf.controlplane.service.SetupService.SetupRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public one-time setup endpoints. {@code GET /v1/setup/status} tells the login screen whether to send the
 * operator to the setup screen; {@code POST /v1/setup} creates the super-admin and works only once.
 * Refusals are {@code AccountActionException}s, mapped to their status by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/v1/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("setupRequired", setupService.isSetupRequired());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> setup(@RequestBody SetupRequest request) {
        String username = setupService.completeSetup(request).username();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", username));
    }
}
