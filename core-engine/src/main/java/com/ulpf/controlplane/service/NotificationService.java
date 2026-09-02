package com.ulpf.controlplane.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    public record Notification(
            String id,
            String title,
            String message,
            boolean read,
            LocalDateTime createdAt
    ) {}

    // fake in-memory store, keyed by username
    private static final Map<String, List<Notification>> fakeNotifications = new ConcurrentHashMap<>(Map.of(
        "username", List.of(
            new Notification("n1", "Welcome to ULPF", "Your account was created successfully.", false, LocalDateTime.now()),
            new Notification("n2", "Onboarding approved", "Your vendor account has been approved.", true, LocalDateTime.now())
        )
    ));

    public List<Notification> getNotifications(String username) {
        return fakeNotifications.getOrDefault(username, List.of());//empty list if no notifications for the user
    }
}