package com.ulpf.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for onboarding requests and user notifications in SQLite.
 */
@Repository
public class OnboardingRepository {

    private final JdbcTemplate jdbcTemplate;

    public OnboardingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record OnboardingRequestRecord(
        String requestId,
        String userId,
        String sourceId,
        String requestType,
        String sampleMetadata,
        String status,
        LocalDateTime createdAt
    ) {}

    public record NotificationRecord(
        String notificationId,
        String userId,
        String title,
        String message,
        boolean read,
        LocalDateTime createdAt
    ) {}

    private static final RowMapper<OnboardingRequestRecord> REQUEST_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new OnboardingRequestRecord(
            rs.getString("request_id"),
            rs.getString("user_id"),
            rs.getString("source_id"),
            rs.getString("request_type"),
            rs.getString("sample_metadata"),
            rs.getString("status"),
            createdAt
        );
    };

    private static final RowMapper<NotificationRecord> NOTIFICATION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp timestamp = rs.getTimestamp("created_at");
        LocalDateTime createdAt = timestamp != null ? timestamp.toLocalDateTime() : null;

        return new NotificationRecord(
            rs.getString("notification_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getBoolean("read"),
            createdAt
        );
    };

    public OnboardingRequestRecord saveRequest(OnboardingRequestRecord req) {
        String id = (req.requestId() != null && !req.requestId().isBlank())
            ? req.requestId()
            : UUID.randomUUID().toString();

        String sql = """
            INSERT INTO onboarding_requests (request_id, user_id, source_id, request_type, sample_metadata, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, id, req.userId(), req.sourceId(), req.requestType(), req.sampleMetadata(), req.status());
        return findRequestById(id).orElseThrow(() -> new IllegalStateException("Failed to save onboarding request: " + id));
    }

    public Optional<OnboardingRequestRecord> findRequestById(String requestId) {
        String sql = "SELECT request_id, user_id, source_id, request_type, sample_metadata, status, created_at FROM onboarding_requests WHERE request_id = ?";
        List<OnboardingRequestRecord> list = jdbcTemplate.query(sql, REQUEST_ROW_MAPPER, requestId);
        return list.stream().findFirst();
    }

    public List<OnboardingRequestRecord> findAllRequests() {
        String sql = "SELECT request_id, user_id, source_id, request_type, sample_metadata, status, created_at FROM onboarding_requests ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, REQUEST_ROW_MAPPER);
    }

    public void updateRequestStatus(String requestId, String status) {
        String sql = "UPDATE onboarding_requests SET status = ? WHERE request_id = ?";
        jdbcTemplate.update(sql, status, requestId);
    }

    public NotificationRecord saveNotification(String userId, String title, String message) {
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO notifications (notification_id, user_id, title, message, read) VALUES (?, ?, ?, ?, 0)";
        jdbcTemplate.update(sql, id, userId, title, message);

        String fetchSql = "SELECT notification_id, user_id, title, message, read, created_at FROM notifications WHERE notification_id = ?";
        List<NotificationRecord> list = jdbcTemplate.query(fetchSql, NOTIFICATION_ROW_MAPPER, id);
        return list.get(0);
    }

    public List<NotificationRecord> findNotificationsByUserId(String userId) {
        String sql = "SELECT notification_id, user_id, title, message, read, created_at FROM notifications WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, NOTIFICATION_ROW_MAPPER, userId);
    }

    public void markNotificationAsRead(String notificationId) {
        String sql = "UPDATE notifications SET read = 1 WHERE notification_id = ?";
        jdbcTemplate.update(sql, notificationId);
    }

    public int purgeExpiredNotifications(int readRetentionDays, int unreadRetentionDays) {
        String sqlRead = "DELETE FROM notifications WHERE read = 1 AND created_at < datetime('now', '-' || ? || ' days')";
        int purgedRead = jdbcTemplate.update(sqlRead, readRetentionDays);

        String sqlUnread = "DELETE FROM notifications WHERE read = 0 AND created_at < datetime('now', '-' || ? || ' days')";
        int purgedUnread = jdbcTemplate.update(sqlUnread, unreadRetentionDays);

        return purgedRead + purgedUnread;
    }

    public int purgeExpiredOnboardingRequests(int completedRetentionDays) {
        String sql = "DELETE FROM onboarding_requests WHERE status IN ('APPROVED', 'REJECTED') AND created_at < datetime('now', '-' || ? || ' days')";
        return jdbcTemplate.update(sql, completedRetentionDays);
    }

    public int clearExpiredSampleMetadata(int sampleClearDays) {
        String sql = "UPDATE onboarding_requests SET sample_metadata = NULL WHERE status IN ('APPROVED', 'REJECTED') AND sample_metadata IS NOT NULL AND created_at < datetime('now', '-' || ? || ' days')";
        return jdbcTemplate.update(sql, sampleClearDays);
    }
}
