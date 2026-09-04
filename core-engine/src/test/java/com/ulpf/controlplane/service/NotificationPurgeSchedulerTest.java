package com.ulpf.controlplane.service;

import com.ulpf.common.db.OnboardingRepository;
import com.ulpf.common.db.OnboardingRepository.NotificationRecord;
import com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationPurgeSchedulerTest {

    private JdbcTemplate jdbcTemplate;
    private OnboardingRepository onboardingRepository;
    private NotificationPurgeScheduler purgeScheduler;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE onboarding_requests (request_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, source_id TEXT, request_type TEXT NOT NULL, sample_metadata TEXT, status TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE notifications (notification_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, read BOOLEAN NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        UserRepository userRepository = new UserRepository(jdbcTemplate);
        onboardingRepository = new OnboardingRepository(jdbcTemplate);
        purgeScheduler = new NotificationPurgeScheduler(onboardingRepository);

        userRepository.save(new User("u1", "alice", "Alice", "pass", Role.USER, null));
    }

    @Test
    void testPurgeExpiredNotificationsAndRequests() {
        // Insert notification read 20 days ago (should be purged if readRetention = 14)
        jdbcTemplate.execute("INSERT INTO notifications VALUES ('n1', 'u1', 'Old Read', 'msg', 1, datetime('now', '-20 days'))");
        // Insert notification read 2 days ago (should remain)
        jdbcTemplate.execute("INSERT INTO notifications VALUES ('n2', 'u1', 'Recent Read', 'msg', 1, datetime('now', '-2 days'))");
        // Insert unread notification created 5 days ago (should remain)
        jdbcTemplate.execute("INSERT INTO notifications VALUES ('n3', 'u1', 'Fresh Unread', 'msg', 0, datetime('now', '-5 days'))");

        // Insert completed request 100 days ago (should be purged if completedRetention = 90)
        jdbcTemplate.execute("INSERT INTO onboarding_requests VALUES ('r1', 'u1', 's1', 'NEW_SOURCE', '{\"sample\":\"data\"}', 'APPROVED', datetime('now', '-100 days'))");
        // Insert completed request 10 days ago (sample_metadata should be cleared to NULL after 7 days)
        jdbcTemplate.execute("INSERT INTO onboarding_requests VALUES ('r2', 'u1', 's2', 'NEW_SOURCE', '{\"sample\":\"data\"}', 'APPROVED', datetime('now', '-10 days'))");
        // Insert active request 5 days ago (sample_metadata should remain intact)
        jdbcTemplate.execute("INSERT INTO onboarding_requests VALUES ('r3', 'u1', 's3', 'NEW_SOURCE', '{\"sample\":\"data\"}', 'SUBMITTED', datetime('now', '-5 days'))");

        int purgedNotifications = onboardingRepository.purgeExpiredNotifications(14, 60);
        int clearedSamples = onboardingRepository.clearExpiredSampleMetadata(7);
        int purgedRequests = onboardingRepository.purgeExpiredOnboardingRequests(90);

        assertEquals(1, purgedNotifications, "Should purge 1 old read notification");
        assertEquals(2, clearedSamples, "Should clear sample metadata for 2 requests older than 7 days");
        assertEquals(1, purgedRequests, "Should purge 1 old approved request older than 90 days");

        List<NotificationRecord> remainingNotifications = onboardingRepository.findNotificationsByUserId("u1");
        assertEquals(2, remainingNotifications.size());

        OnboardingRequestRecord r2 = onboardingRepository.findRequestById("r2").get();
        assertNull(r2.sampleMetadata(), "Sample metadata for 10-day old approved request should be cleared to NULL");

        OnboardingRequestRecord r3 = onboardingRepository.findRequestById("r3").get();
        assertEquals("{\"sample\":\"data\"}", r3.sampleMetadata(), "Sample metadata for active 5-day old request should remain");
    }
}
