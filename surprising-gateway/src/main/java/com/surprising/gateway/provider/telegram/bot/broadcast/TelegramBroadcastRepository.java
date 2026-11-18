package com.surprising.gateway.provider.telegram.bot.broadcast;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TelegramBroadcastRepository {

    private final JdbcTemplate jdbc;

    public TelegramBroadcastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void rememberChat(String subject, long chatId) {
        jdbc.update("""
                INSERT INTO gateway_telegram_chats(subject, chat_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (subject) DO UPDATE
                   SET chat_id = EXCLUDED.chat_id,
                       updated_at = now()
                """, subject, chatId);
    }

    public long insert(long createdBy, String bodyHtml) {
        Long id = jdbc.queryForObject("""
                INSERT INTO gateway_telegram_broadcasts(body_html, status, recipient_count, created_by)
                VALUES (?, 'QUEUED', 0, ?)
                RETURNING broadcast_id
                """, Long.class, bodyHtml, createdBy);
        if (id == null) throw new IllegalStateException("broadcast id was not returned");
        return id;
    }

    /** Copies every linked Telegram account. A recorded chat is preferred; otherwise the numeric subject is the private chat id. */
    public int copyRecipients(long broadcastId) {
        return jdbc.update("""
                INSERT INTO gateway_telegram_broadcast_recipients(broadcast_id, subject, user_id, chat_id, status)
                SELECT ?, i.subject, i.user_id,
                       COALESCE(c.chat_id, CASE WHEN i.subject ~ '^[0-9]{1,18}$' THEN i.subject::bigint END),
                       'PENDING'
                  FROM gateway_telegram_identities i
                  LEFT JOIN gateway_telegram_chats c ON c.subject = i.subject
                 WHERE COALESCE(c.chat_id, CASE WHEN i.subject ~ '^[0-9]{1,18}$' THEN i.subject::bigint END) IS NOT NULL
                """, broadcastId);
    }

    public BroadcastRow require(long broadcastId) {
        return jdbc.query("""
                SELECT broadcast_id, status, recipient_count, sent_count, failed_count, body_html, created_at, finished_at, error
                  FROM gateway_telegram_broadcasts
                 WHERE broadcast_id = ?
                """, (rs, row) -> map(rs), broadcastId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("broadcast not found"));
    }

    public List<BroadcastRow> list() {
        return jdbc.query("""
                SELECT broadcast_id, status, recipient_count, sent_count, failed_count, body_html, created_at, finished_at, error
                  FROM gateway_telegram_broadcasts
                 ORDER BY broadcast_id DESC
                 LIMIT 30
                """, (rs, row) -> map(rs));
    }

    public void releaseStaleClaims() {
        jdbc.update("""
                UPDATE gateway_telegram_broadcast_recipients
                   SET status = 'PENDING', claimed_at = NULL
                 WHERE status = 'SENDING'
                   AND claimed_at < now() - interval '2 minutes'
                """);
    }

    @Transactional
    public List<PendingSend> claim(int limit) {
        return jdbc.query("""
                WITH picked AS (
                    SELECT broadcast_id, subject
                      FROM gateway_telegram_broadcast_recipients
                     WHERE status = 'PENDING'
                     ORDER BY broadcast_id, subject
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                ), updated AS (
                    UPDATE gateway_telegram_broadcast_recipients r
                       SET status = 'SENDING', claimed_at = now()
                      FROM picked
                     WHERE r.broadcast_id = picked.broadcast_id
                       AND r.subject = picked.subject
                    RETURNING r.broadcast_id, r.subject, r.chat_id
                )
                SELECT u.broadcast_id, u.subject, u.chat_id, b.body_html
                  FROM updated u
                  JOIN gateway_telegram_broadcasts b ON b.broadcast_id = u.broadcast_id
                """, (rs, row) -> new PendingSend(rs.getLong("broadcast_id"), rs.getString("subject"),
                        rs.getLong("chat_id"), rs.getString("body_html")), limit);
    }

    public void complete(long broadcastId, String subject, String status, String error) {
        jdbc.update("""
                UPDATE gateway_telegram_broadcast_recipients
                   SET status = ?, error = ?, claimed_at = NULL
                 WHERE broadcast_id = ? AND subject = ? AND status = 'SENDING'
                """, status, error, broadcastId, subject);
    }

    public void seedCounts(long broadcastId) {
        jdbc.update("""
                UPDATE gateway_telegram_broadcasts b
                   SET recipient_count = (SELECT count(*) FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id),
                       status = CASE WHEN EXISTS (
                           SELECT 1 FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id
                       ) THEN 'QUEUED' ELSE 'COMPLETED' END,
                       finished_at = CASE WHEN EXISTS (
                           SELECT 1 FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id
                       ) THEN NULL ELSE now() END
                 WHERE b.broadcast_id = ?
                """, broadcastId);
    }

    public void failOutstanding(String error) {
        jdbc.update("""
                UPDATE gateway_telegram_broadcast_recipients
                   SET status = 'FAILED', error = ?, claimed_at = NULL
                 WHERE status IN ('PENDING', 'SENDING')
                """, error);
        jdbc.update("""
                UPDATE gateway_telegram_broadcasts b
                   SET failed_count = (SELECT count(*) FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id AND r.status = 'FAILED'),
                       status = 'COMPLETED',
                       error = ?,
                       finished_at = COALESCE(b.finished_at, now())
                 WHERE b.status IN ('QUEUED', 'SENDING')
                """, error);
    }

    public void refresh(long broadcastId) {
        jdbc.update("""
                UPDATE gateway_telegram_broadcasts b
                   SET recipient_count = (SELECT count(*) FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id),
                       sent_count = (SELECT count(*) FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id AND r.status = 'SENT'),
                       failed_count = (SELECT count(*) FROM gateway_telegram_broadcast_recipients r WHERE r.broadcast_id = b.broadcast_id AND r.status = 'FAILED'),
                       status = CASE WHEN EXISTS (
                           SELECT 1 FROM gateway_telegram_broadcast_recipients r
                            WHERE r.broadcast_id = b.broadcast_id AND r.status IN ('PENDING', 'SENDING')
                       ) THEN 'SENDING' ELSE 'COMPLETED' END,
                       finished_at = CASE WHEN EXISTS (
                           SELECT 1 FROM gateway_telegram_broadcast_recipients r
                            WHERE r.broadcast_id = b.broadcast_id AND r.status IN ('PENDING', 'SENDING')
                       ) THEN NULL ELSE COALESCE(b.finished_at, now()) END
                 WHERE b.broadcast_id = ?
                """, broadcastId);
    }

    private static BroadcastRow map(ResultSet rs) throws SQLException {
        return new BroadcastRow(
                rs.getLong("broadcast_id"),
                rs.getString("status"),
                rs.getInt("recipient_count"),
                rs.getInt("sent_count"),
                rs.getInt("failed_count"),
                rs.getString("body_html"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("error"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record BroadcastRow(long broadcastId, String status, int recipientCount, int sentCount, int failedCount,
                               String bodyHtml, Instant createdAt, Instant finishedAt, String error) {}

    public record PendingSend(long broadcastId, String subject, long chatId, String html) {}
}
