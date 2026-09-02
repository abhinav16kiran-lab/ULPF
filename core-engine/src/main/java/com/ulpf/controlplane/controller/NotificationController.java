package com.ulpf.controlplane.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.NotificationService;

@RestController
@RequestMapping("/v1")
public class NotificationController {

    private final NotificationService notificationService;

    NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public ResponseEntity<?> getNotifications(@AuthenticationPrincipal UlpfPrincipal principal) {
        var notifications = notificationService.getNotifications(principal.username());
        return ResponseEntity.ok(Map.of("notifications", notifications));
    }
}