package com.surprising.gateway.provider.announcement.repository;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 仅写gateway_announcements；必要关联读取各方法说明分页与可见性用途。 */
@Repository
public class AnnouncementRepository {
    private final JdbcTemplate jdbc;
    public AnnouncementRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final org.springframework.jdbc.core.RowMapper<BaseRow> BASE_ROW = (rs,n) -> baseRow(rs);

    /** 关联翻译仅用于多语言关键词筛选；主表分页后由Service批量加载翻译/目标，EXISTS避免一对多放大页。 */
    public AdminRows listAdmin(String status, String query, int offset, int limit) {
        String state = blankToNull(status);
        String phrase = blankToNull(query);
        String where = " WHERE (?::text IS NULL OR a.status = ?)"
                + " AND (?::text IS NULL OR EXISTS (SELECT 1 FROM gateway_announcement_translations t"
                + " WHERE t.announcement_id = a.announcement_id AND (t.title ILIKE ? OR t.summary ILIKE ? OR t.body ILIKE ?)))";
        String like = phrase == null ? null : "%" + phrase + "%";
        Integer count = jdbc.queryForObject("SELECT count(*) FROM gateway_announcements a" + where,
                Integer.class, state, state, phrase, like, like, like);
        List<BaseRow> rows = jdbc.query("SELECT a.* FROM gateway_announcements a" + where
                        + " ORDER BY a.updated_at DESC, a.announcement_id DESC LIMIT ? OFFSET ?",
                BASE_ROW, state, state, phrase, like, like, like, limit, offset);
        return new AdminRows(count == null ? 0 : count, rows);
    }
    public java.util.Optional<BaseRow> find(long id) {
        return jdbc.query("SELECT * FROM gateway_announcements WHERE announcement_id=?",BASE_ROW,id).stream().findFirst();
    }

    /** 已读与公告编辑按主表行串行，避免旧阅读在编辑清空后重新写回。 */
    public boolean lockForReading(long id) {
        return !jdbc.query("SELECT announcement_id FROM gateway_announcements WHERE announcement_id=? FOR SHARE",
                (rs,n)->rs.getLong(1),id).isEmpty();
    }

    public long create(Content request, long actorId, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO gateway_announcements
                        (category, status, priority, starts_at, expires_at, version,
                         created_by, updated_by, created_at, updated_at)
                    VALUES (?, 'DRAFT', ?, ?, ?, 1, ?, ?, ?, ?)
                    """, new String[] {"announcement_id"});
            statement.setString(1, request.category());
            statement.setInt(2, request.priority());
            statement.setTimestamp(3, Timestamp.from(request.startsAt()));
            statement.setTimestamp(4, request.expiresAt() == null ? null : Timestamp.from(request.expiresAt()));
            statement.setLong(5, actorId);
            statement.setLong(6, actorId);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            return statement;
        }, key);
        Number idValue = key.getKey();
        if (idValue == null) throw new IllegalStateException("announcement ID was not generated");
        long id = idValue.longValue();
        return id;
    }
    public boolean update(long id, Content request, long expectedVersion, long actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE gateway_announcements
                   SET category = ?, priority = ?, starts_at = ?, expires_at = ?,
                       status = CASE WHEN status = 'WITHDRAWN' THEN 'DRAFT' ELSE status END,
                       version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ?
                """, request.category(), request.priority(), Timestamp.from(request.startsAt()),
                request.expiresAt() == null ? null : Timestamp.from(request.expiresAt()),
                actorId, Timestamp.from(now), id, expectedVersion);
        return updated == 1;
    }
    public boolean publish(long id, long expectedVersion, long actorId, Instant now) {
        return jdbc.update("""
                UPDATE gateway_announcements
                   SET status = 'PUBLISHED', version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ? AND status IN ('DRAFT','PUBLISHED')
                """, actorId, Timestamp.from(now), id, expectedVersion) == 1;
    }
    public boolean withdraw(long id, long expectedVersion, long actorId, Instant now) {
        return jdbc.update("""
                UPDATE gateway_announcements
                   SET status = 'WITHDRAWN', version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ? AND status <> 'WITHDRAWN'
                """, actorId, Timestamp.from(now), id, expectedVersion) == 1;
    }
    /** 客户端关联读取：目标表限制可见范围，LATERAL只选一种语言，已读表限定本人；在同一SQL内筛选/排序/分页。 */
    public List<ClientRow> listClient(ClientFilter filter, Long userId, int offset, int limit, Instant now) {
        String requested = filter.locale();
        String base = filter.baseLocale();
        String line = filter.productLine();
        String client = filter.platform();
        String slot = filter.placement();
        String where = clientWhere();
        String translationJoin = """
                JOIN LATERAL (
                    SELECT tr.locale, tr.title, tr.summary, tr.body, tr.body_html
                      FROM gateway_announcement_translations tr
                     WHERE tr.announcement_id = a.announcement_id
                     ORDER BY CASE WHEN tr.locale = ? THEN 0
                                   WHEN tr.locale = ? THEN 1
                                   WHEN tr.locale = 'en-US' THEN 2
                                   WHEN tr.locale = 'zh-CN' THEN 3 ELSE 4 END,
                              tr.locale
                     LIMIT 1
                ) t ON TRUE
                """;
        String sql = "SELECT a.*, t.locale AS content_locale, t.title, t.summary, t.body, t.body_html, r.read_at "
                + "FROM gateway_announcements a " + translationJoin
                + "LEFT JOIN gateway_announcement_reads r ON r.announcement_id = a.announcement_id AND r.user_id = ? "
                + where + " ORDER BY a.priority DESC, a.starts_at DESC, a.announcement_id DESC LIMIT ? OFFSET ?";
        List<ClientRow> results = jdbc.query(sql, (rs, rowNum) -> clientRow(rs),
                requested, base, userId, Timestamp.from(now), Timestamp.from(now),
                line, line, client, slot, limit, offset);
        return results;
    }
    /** 关联目标表按可见资格计数；unreadOnly时仅反选本人已读。避免翻译的一对多关联放大数量。 */
    public int countClient(ClientFilter filter, Long userId, boolean unreadOnly, Instant now) {
        String line = filter.productLine();
        String client = filter.platform();
        String slot = filter.placement();
        String where = clientWhere();
        String unread = unreadOnly
                ? " AND ?::bigint IS NOT NULL AND NOT EXISTS (SELECT 1 FROM gateway_announcement_reads ur"
                    + " WHERE ur.announcement_id = a.announcement_id AND ur.user_id = ?)"
                : "";
        String sql = "SELECT count(*) FROM gateway_announcements a WHERE" + where.substring(where.indexOf("WHERE") + 5) + unread;
        Object[] args = unreadOnly
                ? new Object[] {Timestamp.from(now), Timestamp.from(now), line, line, client, slot, userId, userId}
                : new Object[] {Timestamp.from(now), Timestamp.from(now), line, line, client, slot};
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
    /** 客户端关联读取：目标表限制可见范围，LATERAL只选一种语言，已读表限定本人；在同一SQL内筛选/排序/分页。 */
    public java.util.Optional<ClientRow> findClient(long id, ClientFilter filter, Long userId, Instant now) {
        String requested = filter.locale();
        String base = filter.baseLocale();
        String line = filter.productLine();
        String client = filter.platform();
        String slot = filter.placement();
        String sql = """
                SELECT a.*, t.locale AS content_locale, t.title, t.summary, t.body, t.body_html, r.read_at
                  FROM gateway_announcements a
                  JOIN LATERAL (
                    SELECT tr.locale, tr.title, tr.summary, tr.body, tr.body_html
                      FROM gateway_announcement_translations tr
                     WHERE tr.announcement_id = a.announcement_id
                     ORDER BY CASE WHEN tr.locale = ? THEN 0 WHEN tr.locale = ? THEN 1
                                   WHEN tr.locale = 'en-US' THEN 2 WHEN tr.locale = 'zh-CN' THEN 3 ELSE 4 END,
                              tr.locale
                     LIMIT 1
                  ) t ON TRUE
                  LEFT JOIN gateway_announcement_reads r ON r.announcement_id = a.announcement_id AND r.user_id = ?
                """ + clientWhere() + " AND a.announcement_id = ? LIMIT 1";
        List<ClientRow> matches = jdbc.query(sql, (rs, rowNum) -> clientRow(rs), requested, base, userId,
                Timestamp.from(now), Timestamp.from(now), line, line, client, slot, id);
        return matches.stream().findFirst();
    }
    private String clientWhere() {
        return " WHERE a.status = 'PUBLISHED' AND a.starts_at <= ? AND (a.expires_at IS NULL OR a.expires_at > ?)"
                + " AND EXISTS (SELECT 1 FROM gateway_announcement_targets p WHERE p.announcement_id = a.announcement_id"
                + " AND p.target_type = 'PRODUCT_LINE' AND (p.target_value = 'ALL' OR ? = 'ALL' OR p.target_value = ?))"
                + " AND EXISTS (SELECT 1 FROM gateway_announcement_targets c WHERE c.announcement_id = a.announcement_id"
                + " AND c.target_type = 'PLATFORM' AND (c.target_value = 'ALL' OR c.target_value = ?))"
                + " AND EXISTS (SELECT 1 FROM gateway_announcement_targets s WHERE s.announcement_id = a.announcement_id"
                + " AND s.target_type = 'PLACEMENT' AND (s.target_value = 'ALL' OR s.target_value = ?))";
    }
    private ClientRow clientRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClientRow(baseRow(rs),rs.getString("content_locale"),rs.getString("title"),rs.getString("summary"),
                rs.getString("body"),rs.getString("body_html"),instant(rs,"read_at"));
    }

    private static BaseRow baseRow(ResultSet rs) throws SQLException {
        return new BaseRow(rs.getLong("announcement_id"), rs.getString("category"), rs.getString("status"),
                rs.getInt("priority"), instant(rs, "starts_at"), instant(rs, "expires_at"),
                rs.getLong("version"), rs.getLong("created_by"), rs.getLong("updated_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static String blankToNull(String value) { return value==null || value.isBlank()?null:value.trim(); }
}
