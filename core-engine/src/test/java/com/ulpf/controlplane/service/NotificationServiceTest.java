package com.ulpf.controlplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {

    private UserRepository userRepository;
    private OnboardingRepository onboardingRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE notifications (notification_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, read BOOLEAN NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        userRepository = new UserRepository(jdbcTemplate);
        onboardingRepository = new OnboardingRepository(jdbcTemplate);
        notificationService = new NotificationService(userRepository, onboardingRepository);

        userRepository.save(new User(null, "bob", "Bob Smith", "pass_hash", Role.USER, null));
    }

    @Test
    void testGetNotificationsAndMarkAsRead() {
        User bob = userRepository.findByUsername("bob").get();

        NotificationRecord n1 = onboardingRepository.saveNotification(bob.userId(), "Onboarding Approved", "Your key is active");

        List<NotificationRecord> notifications = notificationService.getNotifications("bob");
        assertEquals(1, notifications.size());
        assertEquals("Onboarding Approved", notifications.get(0).title());
        assertFalse(notifications.get(0).read());

        notificationService.markAsRead(n1.notificationId());

        List<NotificationRecord> updated = notificationService.getNotifications("bob");
        assertTrue(updated.get(0).read());
    }
}
