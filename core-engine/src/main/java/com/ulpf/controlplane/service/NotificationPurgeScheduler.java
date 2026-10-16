package com.ulpf.controlplane.service;

import com.ulpf.common.db.OnboardingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Asynchronous background scheduler for purging expired notifications, clearing heavy
 * sample log metadata after 7 days, and deleting completed onboarding requests from SQLite.
 */
@Component
public class NotificationPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationPurgeScheduler.class);

    private final OnboardingRepository onboardingRepository;

    @Value("${notifications.ttl.read-days:14}")
    private int readRetentionDays = 14;

    @Value("${notifications.ttl.unread-days:60}")
    private int unreadRetentionDays = 60;

    @Value("${onboarding.ttl.sample-clear-days:7}")
    private int sampleClearDays = 7;

    @Value("${onboarding.ttl.completed-days:90}")
    private int completedRequestRetentionDays = 90;

    public NotificationPurgeScheduler(OnboardingRepository onboardingRepository) {
        this.onboardingRepository = onboardingRepository;
    }

    /**
     * Startup purge check to ensure expired records and sample files are cleaned up
     * even if the application was offline during the scheduled 2:00 AM cron window.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Executing startup TTL data retention purge check...");
        runDailyTtlPurge();
    }

    /**
     * Daily background job (default 2:00 AM) to purge expired notifications and onboarding metadata.
     */
    @Scheduled(cron = "${notifications.ttl.cron:0 0 2 * * *}")
    public void runDailyTtlPurge() {
        log.info("Starting scheduled TTL data retention purge for SQLite notifications and onboarding requests...");

        try {
            int purgedNotifications = onboardingRepository.purgeExpiredNotifications(readRetentionDays, unreadRetentionDays);
            int clearedSamples = onboardingRepository.clearExpiredSampleMetadata(sampleClearDays);
            int purgedRequests = onboardingRepository.purgeExpiredOnboardingRequests(completedRequestRetentionDays);

            cleanupDiskSampleFiles(sampleClearDays);

            log.info("TTL Purge Complete: Purged {} notifications, cleared sample metadata from {} completed requests (> {}d), and purged {} old requests (> {}d)",
                    purgedNotifications, clearedSamples, sampleClearDays, purgedRequests, completedRequestRetentionDays);
        } catch (Exception e) {
            log.error("Failed to execute scheduled TTL data retention purge: {}", e.getMessage(), e);
        }
    }

    public int triggerManualPurge() {
        int purgedNotifications = onboardingRepository.purgeExpiredNotifications(readRetentionDays, unreadRetentionDays);
        int clearedSamples = onboardingRepository.clearExpiredSampleMetadata(sampleClearDays);
        int purgedRequests = onboardingRepository.purgeExpiredOnboardingRequests(completedRequestRetentionDays);
        cleanupDiskSampleFiles(sampleClearDays);
        return purgedNotifications + clearedSamples + purgedRequests;
    }

    private void cleanupDiskSampleFiles(int daysOld) {
        File[] candidateDirs = new File[] {
                new File("storage/onboarding-samples"),
                new File("core-engine/storage/onboarding-samples")
        };

        for (File dir : candidateDirs) {
            try {
                if (!dir.exists() || !dir.isDirectory()) {
                    continue;
                }

                long cutoffTime = System.currentTimeMillis() - (daysOld * 86400000L);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.lastModified() < cutoffTime) {
                            boolean deleted = file.delete();
                            if (deleted) {
                                log.info("Deleted expired sample file from disk: {}", file.getName());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error cleaning up disk sample files in {}: {}", dir.getPath(), e.getMessage());
            }
        }
    }
}
