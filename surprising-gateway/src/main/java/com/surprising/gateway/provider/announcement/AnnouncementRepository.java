package com.surprising.gateway.provider.announcement;

import com.surprising.gateway.provider.announcement.AnnouncementModels.Announcement;
import com.surprising.gateway.provider.announcement.AnnouncementModels.AuditEntry;
import com.surprising.gateway.provider.announcement.AnnouncementModels.Translation;
import com.surprising.gateway.provider.announcement.AnnouncementModels.TranslationInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AnnouncementRepository {

    private static final RowMapper<BaseRow> BASE_ROW = (rs, rowNum) -> new BaseRow(
            rs.getLong("announcement_id"), rs.getString("category"), rs.getString("status"),
            rs.getInt("priority"), instant(rs, "starts_at"), instant(rs, "expires_at"),
            rs.getLong("version"), rs.getLong("created_by"), rs.getLong("updated_by"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AnnouncementRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AdminResult listAdmin(String status, String query, int offset, int limit) {
        String state = blankToNull(status);
        String phrase = blankToNull(query);
        String where = " WHERE (? IS NULL OR a.status = ?)"
                + " AND (? IS NULL OR EXISTS (SELECT 1 FROM gateway_announcement_translations t"
                + " WHERE t.announcement_id = a.announcement_id AND (t.title ILIKE ? OR t.summary ILIKE ? OR t.body ILIKE ?)))";
        String like = phrase == null ? null : "%" + phrase + "%";
        Integer count = jdbc.queryForObject("SELECT count(*) FROM gateway_announcements a" + where,
                Integer.class, state, state, phrase, like, like, like);
        List<Long> ids = jdbc.query("SELECT a.announcement_id FROM gateway_announcements a" + where
                        + " ORDER BY a.updated_at DESC, a.announcement_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getLong(1), state, state, phrase, like, like, like, limit, offset);
        return new AdminResult(count == null ? 0 : count, ids.stream().map(this::findAdmin).flatMap(Optional::stream).toList());
    }

    public Optional<Announcement> findAdmin(long id) {
        List<BaseRow> rows = jdbc.query("SELECT * FROM gateway_announcements WHERE announcement_id = ?", BASE_ROW, id);
        return rows.stream().findFirst().map(row -> adminView(row, null));
    }

    public List<AuditEntry> audit(long id) {
        return jdbc.query("""
                SELECT audit_id, announcement_id, action, admin_user_id, admin_username,
                       reason, snapshot::text AS snapshot, created_at
                  FROM gateway_announcement_audit
                 WHERE announcement_id = ?
                 ORDER BY audit_id DESC
                 LIMIT 200
                """, (rs, rowNum) -> new AuditEntry(rs.getLong("audit_id"), rs.getLong("announcement_id"),
                rs.getString("action"), rs.getLong("admin_user_id"), rs.getString("admin_username"),
                rs.getString("reason"), rs.getString("snapshot"), instant(rs, "created_at")), id);
    }

    @Transactional
    public long create(AnnouncementModels.SaveRequest request, long actorId, Instant now) {
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
        replaceChildren(id, request);
        return id;
    }

    @Transactional
    public boolean update(long id, AnnouncementModels.SaveRequest request, long actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE gateway_announcements
                   SET category = ?, priority = ?, starts_at = ?, expires_at = ?,
                       status = CASE WHEN status = 'WITHDRAWN' THEN 'DRAFT' ELSE status END,
                       version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ?
                """, request.category(), request.priority(), Timestamp.from(request.startsAt()),
                request.expiresAt() == null ? null : Timestamp.from(request.expiresAt()),
                actorId, Timestamp.from(now), id, request.version());
        if (updated == 0) return false;
        replaceChildren(id, request);
        jdbc.update("DELETE FROM gateway_announcement_reads WHERE announcement_id = ?", id);
        return true;
    }

    @Transactional
    public boolean publish(long id, long expectedVersion, long actorId, Instant now) {
        return jdbc.update("""
                UPDATE gateway_announcements
                   SET status = 'PUBLISHED', version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ? AND status IN ('DRAFT','PUBLISHED')
                """, actorId, Timestamp.from(now), id, expectedVersion) == 1;
    }

    @Transactional
    public boolean withdraw(long id, long expectedVersion, long actorId, Instant now) {
        return jdbc.update("""
                UPDATE gateway_announcements
                   SET status = 'WITHDRAWN', version = version + 1, updated_by = ?, updated_at = ?
                 WHERE announcement_id = ? AND version = ? AND status <> 'WITHDRAWN'
                """, actorId, Timestamp.from(now), id, expectedVersion) == 1;
    }

    public List<Announcement> listClient(String locale, String productLine, String platform,
                                         String placement, Long userId, int offset, int limit) {
        String requested = normalizeLocale(locale);
        String base = baseLocale(requested);
        String line = normalizeToken(productLine, "ALL");
        String client = normalizeToken(platform, "WEB");
        String slot = normalizeToken(placement, "CENTER");
        String where = clientWhere();
        String translationJoin = """
                JOIN LATERAL (
                    SELECT tr.locale, tr.title, tr.summary, tr.body
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
        String sql = "SELECT a.*, t.locale AS content_locale, t.title, t.summary, t.body, r.read_at "
                + "FROM gateway_announcements a " + translationJoin
                + "LEFT JOIN gateway_announcement_reads r ON r.announcement_id = a.announcement_id AND r.user_id = ? "
                + where + " ORDER BY a.priority DESC, a.starts_at DESC, a.announcement_id DESC LIMIT ? OFFSET ?";
        List<Announcement> results = jdbc.query(sql, (rs, rowNum) -> clientView(rs),
                requested, base, userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                line, line, client, slot, limit, offset);
        return results.stream().map(row -> withResolvedTargets(row, productLine, slot)).toList();
    }

    public int countClient(String productLine, String platform, String placement, Long userId, boolean unreadOnly) {
        String line = normalizeToken(productLine, "ALL");
        String client = normalizeToken(platform, "WEB");
        String slot = normalizeToken(placement, "CENTER");
        String where = clientWhere();
        String unread = unreadOnly
                ? " AND ? IS NOT NULL AND NOT EXISTS (SELECT 1 FROM gateway_announcement_reads ur"
                    + " WHERE ur.announcement_id = a.announcement_id AND ur.user_id = ?)"
                : "";
        String sql = "SELECT count(*) FROM gateway_announcements a WHERE" + where.substring(where.indexOf("WHERE") + 5) + unread;
        Object[] args = unreadOnly
                ? new Object[] {Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), line, line, client, slot, userId, userId}
                : new Object[] {Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), line, line, client, slot};
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    public Optional<Announcement> findClient(long id, String locale, String productLine, String platform,
                                              String placement, Long userId) {
        String requested = normalizeLocale(locale);
        String base = baseLocale(requested);
        String line = normalizeToken(productLine, "ALL");
        String client = normalizeToken(platform, "WEB");
        String slot = normalizeToken(placement, "CENTER");
        String sql = """
                SELECT a.*, t.locale AS content_locale, t.title, t.summary, t.body, r.read_at
                  FROM gateway_announcements a
                  JOIN LATERAL (
                    SELECT tr.locale, tr.title, tr.summary, tr.body
                      FROM gateway_announcement_translations tr
                     WHERE tr.announcement_id = a.announcement_id
                     ORDER BY CASE WHEN tr.locale = ? THEN 0 WHEN tr.locale = ? THEN 1
                                   WHEN tr.locale = 'en-US' THEN 2 WHEN tr.locale = 'zh-CN' THEN 3 ELSE 4 END,
                              tr.locale
                     LIMIT 1
                  ) t ON TRUE
                  LEFT JOIN gateway_announcement_reads r ON r.announcement_id = a.announcement_id AND r.user_id = ?
                """ + clientWhere() + " AND a.announcement_id = ? LIMIT 1";
        List<Announcement> matches = jdbc.query(sql, (rs, rowNum) -> clientView(rs), requested, base, userId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), line, line, client, slot, id);
        return matches.stream().findFirst().map(row -> withResolvedTargets(row, productLine, slot));
    }

    @Transactional
    public boolean markRead(long announcementId, long userId, String productLine, String platform,
                            String placement, Instant now) {
        if (findClient(announcementId, "en-US", productLine, platform, placement, userId).isEmpty()) return false;
        jdbc.update("""
                INSERT INTO gateway_announcement_reads (announcement_id, user_id, read_at)
                VALUES (?, ?, ?)
                ON CONFLICT (announcement_id, user_id) DO NOTHING
                """, announcementId, userId, Timestamp.from(now));
        return true;
    }

    @Transactional
    public int markAllRead(long userId, String productLine, String platform, String placement, Instant now) {
        String line = normalizeToken(productLine, "ALL");
        String client = normalizeToken(platform, "WEB");
        String slot = normalizeToken(placement, "CENTER");
        return jdbc.update("""
                INSERT INTO gateway_announcement_reads (announcement_id, user_id, read_at)
                SELECT a.announcement_id, ?, ?
                  FROM gateway_announcements a
                 WHERE a.status = 'PUBLISHED' AND a.starts_at <= ?
                   AND (a.expires_at IS NULL OR a.expires_at > ?)
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PRODUCT_LINE'
                          AND (t.target_value = 'ALL' OR ? = 'ALL' OR t.target_value = ?))
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PLATFORM'
                          AND (t.target_value = 'ALL' OR t.target_value = ?))
                   AND EXISTS (SELECT 1 FROM gateway_announcement_targets t
                        WHERE t.announcement_id = a.announcement_id AND t.target_type = 'PLACEMENT'
                          AND (t.target_value = 'ALL' OR t.target_value = ?))
                   AND NOT EXISTS (SELECT 1 FROM gateway_announcement_reads r
                        WHERE r.announcement_id = a.announcement_id AND r.user_id = ?)
                ON CONFLICT (announcement_id, user_id) DO NOTHING
                """, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), line, line, client, slot, userId);
    }

    @Transactional
    public void audit(long id, String action, long actorId, String username, String reason, Instant now) {
        Announcement snapshot = findAdmin(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
        String snapshotJson;
        try {
            snapshotJson = json.writeValueAsString(snapshot);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot serialize announcement audit snapshot", ex);
        }
        jdbc.update("""
                INSERT INTO gateway_announcement_audit
                    (announcement_id, action, admin_user_id, admin_username, reason, snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, action, actorId, username, blankToNull(reason), snapshotJson, Timestamp.from(now));
    }

    private void replaceChildren(long id, AnnouncementModels.SaveRequest request) {
        jdbc.update("DELETE FROM gateway_announcement_translations WHERE announcement_id = ?", id);
        for (TranslationInput translation : request.translations()) {
            jdbc.update("""
                    INSERT INTO gateway_announcement_translations (announcement_id, locale, title, summary, body)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, translation.locale(), translation.title().trim(), nullToEmpty(translation.summary()),
                    translation.body().trim());
        }
        jdbc.update("DELETE FROM gateway_announcement_targets WHERE announcement_id = ?", id);
        insertTargets(id, "PRODUCT_LINE", request.productLines());
        insertTargets(id, "PLATFORM", request.platforms());
        insertTargets(id, "PLACEMENT", request.placements());
    }

    private void insertTargets(long id, String type, List<String> values) {
        for (String value : values) {
            jdbc.update("INSERT INTO gateway_announcement_targets (announcement_id, target_type, target_value) VALUES (?, ?, ?)",
                    id, type, value);
        }
    }

    private Announcement adminView(BaseRow row, Instant readAt) {
        List<Translation> translations = jdbc.query("""
                SELECT locale, title, summary, body FROM gateway_announcement_translations
                 WHERE announcement_id = ? ORDER BY locale
                """, (rs, rowNum) -> new Translation(rs.getString("locale"), rs.getString("title"),
                rs.getString("summary"), rs.getString("body")), row.id());
        return new Announcement(row.id(), row.category(), row.status(), row.priority(),
                targets(row.id(), "PLACEMENT"), targets(row.id(), "PRODUCT_LINE"), targets(row.id(), "PLATFORM"),
                row.startsAt(), row.expiresAt(), row.version(), translations, null, null, null, null,
                readAt, row.createdAt(), row.updatedAt(), effectiveStatus(row.status(), row.startsAt(), row.expiresAt()));
    }

    private List<String> targets(long id, String type) {
        return jdbc.query("SELECT target_value FROM gateway_announcement_targets WHERE announcement_id = ? AND target_type = ? ORDER BY target_value",
                (rs, rowNum) -> rs.getString(1), id, type);
    }

    private Announcement clientView(ResultSet rs) throws SQLException {
        BaseRow row = baseRow(rs);
        Timestamp read = rs.getTimestamp("read_at");
        return new Announcement(row.id(), row.category(), row.status(), row.priority(), List.of(), List.of(),
                List.of(), row.startsAt(), row.expiresAt(), row.version(), List.of(), rs.getString("content_locale"),
                rs.getString("title"), rs.getString("summary"), rs.getString("body"),
                read == null ? null : read.toInstant(), row.createdAt(), row.updatedAt(),
                effectiveStatus(row.status(), row.startsAt(), row.expiresAt()));
    }

    private Announcement withResolvedTargets(Announcement row, String productLine, String placement) {
        return new Announcement(row.announcementId(), row.category(), row.status(), row.priority(),
                List.of(normalizeToken(placement, "CENTER")), productLine == null || productLine.isBlank() ? List.of("ALL") : List.of(productLine),
                List.of(), row.startsAt(), row.expiresAt(), row.version(), row.translations(), row.locale(), row.title(),
                row.summary(), row.body(), row.readAt(), row.createdAt(), row.updatedAt(), row.effectiveStatus());
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

    private BaseRow baseRow(ResultSet rs) throws SQLException {
        return new BaseRow(rs.getLong("announcement_id"), rs.getString("category"), rs.getString("status"),
                rs.getInt("priority"), instant(rs, "starts_at"), instant(rs, "expires_at"),
                rs.getLong("version"), rs.getLong("created_by"), rs.getLong("updated_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String effectiveStatus(String status, Instant startsAt, Instant expiresAt) {
        if ("WITHDRAWN".equals(status) || "DRAFT".equals(status)) return status;
        Instant now = Instant.now();
        if (startsAt != null && startsAt.isAfter(now)) return "SCHEDULED";
        if (expiresAt != null && !expiresAt.isAfter(now)) return "EXPIRED";
        return "PUBLISHED";
    }

    static String normalizeLocale(String value) {
        String locale = value == null ? "en-US" : value.trim().replace('_', '-');
        return switch (locale.toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn", "zh-hans" -> "zh-CN";
            case "en", "en-us" -> "en-US";
            default -> locale;
        };
    }

    private static String baseLocale(String value) {
        int split = value.indexOf('-');
        return split < 0 ? value : value.substring(0, split);
    }

    private static String normalizeToken(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record AdminResult(int count, List<Announcement> announcements) {
    }

    private record BaseRow(long id, String category, String status, int priority,
                           Instant startsAt, Instant expiresAt, long version,
                           long createdBy, long updatedBy, Instant createdAt, Instant updatedAt) {
    }
}
