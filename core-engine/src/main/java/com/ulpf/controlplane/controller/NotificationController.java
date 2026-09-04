package com.ulpf.controlplane.controller;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.controlplane.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public ResponseEntity<?> getNotifications(@AuthenticationPrincipal UlpfPrincipal principal) {
        List<NotificationRecord> notifications = notificationService.getNotifications(principal.username());
        return ResponseEntity.ok(Map.of("notifications", notifications));
    }

    @PutMapping("/notifications/{notificationId}/read")
    public ResponseEntity<?> markAsRead(
            @AuthenticationPrincipal UlpfPrincipal principal,
            @PathVariable String notificationId
    ) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(Map.of("message", "Notification marked as read"));
    }
}