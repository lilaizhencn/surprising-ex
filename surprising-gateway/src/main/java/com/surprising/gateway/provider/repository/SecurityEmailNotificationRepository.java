package com.surprising.gateway.provider.repository;

import com.surprising.gateway.provider.model.SecurityEmailDelivery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 gateway_security_email_notifications；意图随业务提交，任务按可过期租约续办。 */
@Repository
public class SecurityEmailNotificationRepository {
    private final JdbcTemplate jdbc;

    public SecurityEmailNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String eventKey, String recipient, String subject, String body, Instant now) {
        var ids = jdbc.query("""
                INSERT INTO gateway_security_email_notifications
                    (notification_id,event_key,recipient,subject,body,next_attempt_at,created_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(event_key) DO UPDATE SET event_key=EXCLUDED.event_key
                WHERE gateway_security_email_notifications.recipient=EXCLUDED.recipient
                    AND gateway_security_email_notifications.subject=EXCLUDED.subject
                    AND gateway_security_email_notifications.body=EXCLUDED.body
                RETURNING notification_id
                """, (row, index) -> row.getObject(1, UUID.class), UUID.randomUUID(), eventKey,
                recipient, subject, body, Timestamp.from(now), Timestamp.from(now));
        if (ids.isEmpty()) throw new IllegalStateException("security notification event has different content");
    }

    public List<SecurityEmailDelivery> claim(Instant now) {
        UUID lease = UUID.randomUUID();
        return jdbc.query("""
                WITH due AS (
                    SELECT notification_id FROM gateway_security_email_notifications
                    WHERE (status='PENDING' AND next_attempt_at<=?)
                        OR (status='SENDING' AND lease_until<=?)
                    ORDER BY next_attempt_at,notification_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE gateway_security_email_notifications n
                SET status='SENDING',lease_id=?,lease_until=?,attempts=n.attempts+1
                FROM due WHERE n.notification_id=due.notification_id
                RETURNING n.notification_id,n.lease_id,n.recipient,n.subject,n.body,n.attempts
                """, (row, index) -> new SecurityEmailDelivery(
                        row.getObject("notification_id", UUID.class), row.getObject("lease_id", UUID.class),
                        row.getString("recipient"), row.getString("subject"), row.getString("body"), row.getInt("attempts")),
                Timestamp.from(now), Timestamp.from(now), lease, Timestamp.from(now.plusSeconds(60)));
    }

    public void complete(SecurityEmailDelivery delivery, Instant now) {
        jdbc.update("""
                UPDATE gateway_security_email_notifications
                SET status='SENT',sent_at=?,lease_id=NULL,lease_until=NULL,last_failure=NULL
                WHERE notification_id=? AND status='SENDING' AND lease_id=?
                """, Timestamp.from(now), delivery.notificationId(), delivery.leaseId());
    }

    public void retry(SecurityEmailDelivery delivery, Instant nextAttempt, String failureType) {
        jdbc.update("""
                UPDATE gateway_security_email_notifications
                SET status='PENDING',next_attempt_at=?,lease_id=NULL,lease_until=NULL,last_failure=?
                WHERE notification_id=? AND status='SENDING' AND lease_id=?
                """, Timestamp.from(nextAttempt), failureType, delivery.notificationId(), delivery.leaseId());
    }

    public void deleteDeliveredBefore(Instant cutoff) {
        jdbc.update("""
                DELETE FROM gateway_security_email_notifications WHERE notification_id IN (
                    SELECT notification_id FROM gateway_security_email_notifications
                    WHERE status='SENT' AND sent_at<? ORDER BY sent_at,notification_id LIMIT 128)
                """, Timestamp.from(cutoff));
    }
}
