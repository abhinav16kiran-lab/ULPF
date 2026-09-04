package com.ulpf.controlplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.User;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService {

    private final UserRepository userRepository;
    private final OnboardingRepository onboardingRepository;

    public NotificationService(UserRepository userRepository, OnboardingRepository onboardingRepository) {
        this.userRepository = userRepository;
        this.onboardingRepository = onboardingRepository;
    }

    public List<NotificationRecord> getNotifications(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        return onboardingRepository.findNotificationsByUserId(user.userId());
    }

    public void markAsRead(String notificationId) {
        onboardingRepository.markNotificationAsRead(notificationId);
    }
}