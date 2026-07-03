package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SupportTicketRepository {

    private static final int MAX_TICKET_LIMIT = 200;
    private static final AdminCursorPage.SortSpec TICKET_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "ticket_id", true);
    private static final List<AdminCursorPage.SortSpec> TICKET_SORTS = List.of(
            TICKET_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "ticket_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "ticket_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "ticket_id", false));
    private static final int MAX_NOTE_LIMIT = 500;
    private static final AdminCursorPage.SortSpec NOTE_CREATED_ASC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "note_id", false);
    private static final List<AdminCursorPage.SortSpec> NOTE_SORTS = List.of(
            NOTE_CREATED_ASC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "note_id", true));

    private final JdbcTemplate jdbcTemplate;

    public SupportTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SupportTicket> tickets(Long userId, String status, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM gateway_support_tickets
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                 ORDER BY updated_at DESC, ticket_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toTicket(rs), userId, userId, status, status,
                AdminCursorPage.limit(limit, MAX_TICKET_LIMIT));
    }

    public CursorPage<SupportTicket> ticketsPage(Long userId,
                                                 String status,
                                                 int limit,
                                                 String cursor,
                                                 String sort) {
        int safeLimit = AdminCursorPage.limit(limit, MAX_TICKET_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, TICKET_UPDATED_DESC, TICKET_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(status);
        args.add(status);
        String sql = """
                SELECT *
                  FROM gateway_support_tickets
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, ticket_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<SupportTicket> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toTicket(rs), args.toArray());
        AdminCursorPage.CursorPage<SupportTicket> page = AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                ticketTimestampExtractor(sortSpec), SupportTicket::ticketId);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public SupportTicket createTicket(long userId,
                                      String priority,
                                      String category,
                                      String title,
                                      Long assignedAdminUserId,
                                      long createdByUserId,
                                      Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_support_tickets (
                    user_id, status, priority, category, title, assigned_admin_user_id,
                    created_by_user_id, created_at, updated_at
                ) VALUES (?, 'OPEN', ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, (rs, rowNum) -> new SupportTicket(
                rs.getLong("ticket_id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("category"),
                rs.getString("title"),
                nullableLong(rs, "assigned_admin_user_id"),
                rs.getLong("created_by_user_id"),
                nullableLong(rs, "resolved_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                nullableInstant(rs, "closed_at")), userId, priority, category, title, assignedAdminUserId,
                createdByUserId, Timestamp.from(now), Timestamp.from(now));
    }

    public List<SupportTicketNote> notes(long ticketId, int limit) {
        return notesPage(ticketId, limit, null, null).items();
    }

    public CursorPage<SupportTicketNote> notesPage(long ticketId,
                                                   int limit,
                                                   String cursor,
                                                   String sort) {
        int safeLimit = AdminCursorPage.limit(limit, MAX_NOTE_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, NOTE_CREATED_ASC, NOTE_SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(ticketId);
        String sql = """
                SELECT *
                  FROM gateway_support_ticket_notes
                 WHERE ticket_id = ?
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, note_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<SupportTicketNote> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> toNote(rs), args.toArray());
        AdminCursorPage.CursorPage<SupportTicketNote> page = AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                noteTimestampExtractor(sortSpec), SupportTicketNote::noteId);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public SupportTicketNote addNote(long ticketId,
                                     long adminUserId,
                                     String noteType,
                                     String visibility,
                                     String body,
                                     Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_support_ticket_notes (
                    ticket_id, admin_user_id, note_type, visibility, body, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING *
                """, (rs, rowNum) -> new SupportTicketNote(
                rs.getLong("note_id"),
                rs.getLong("ticket_id"),
                rs.getLong("admin_user_id"),
                rs.getString("note_type"),
                rs.getString("visibility"),
                rs.getString("body"),
                rs.getTimestamp("created_at").toInstant()), ticketId, adminUserId, noteType, visibility, body,
                Timestamp.from(now));
    }

    public SupportTicket updateStatus(long ticketId, String status, long adminUserId, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_support_tickets
                   SET status = ?,
                       resolved_by_user_id = CASE
                           WHEN ? IN ('RESOLVED', 'CLOSED') THEN ?
                           ELSE resolved_by_user_id
                       END,
                       closed_at = CASE
                           WHEN ? = 'CLOSED' THEN ?
                           ELSE NULL
                       END,
                       updated_at = ?
                 WHERE ticket_id = ?
                RETURNING *
                """, (rs, rowNum) -> new SupportTicket(
                rs.getLong("ticket_id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("category"),
                rs.getString("title"),
                nullableLong(rs, "assigned_admin_user_id"),
                rs.getLong("created_by_user_id"),
                nullableLong(rs, "resolved_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                nullableInstant(rs, "closed_at")), status, status, adminUserId, status, Timestamp.from(now),
                Timestamp.from(now), ticketId);
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private SupportTicket toTicket(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SupportTicket(
                rs.getLong("ticket_id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("category"),
                rs.getString("title"),
                nullableLong(rs, "assigned_admin_user_id"),
                rs.getLong("created_by_user_id"),
                nullableLong(rs, "resolved_by_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                nullableInstant(rs, "closed_at"));
    }

    private SupportTicketNote toNote(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SupportTicketNote(
                rs.getLong("note_id"),
                rs.getLong("ticket_id"),
                rs.getLong("admin_user_id"),
                rs.getString("note_type"),
                rs.getString("visibility"),
                rs.getString("body"),
                rs.getTimestamp("created_at").toInstant());
    }

    private java.util.function.Function<SupportTicket, Instant> ticketTimestampExtractor(
            AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> SupportTicket::createdAt;
            case "updatedAt" -> SupportTicket::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    private java.util.function.Function<SupportTicketNote, Instant> noteTimestampExtractor(
            AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> SupportTicketNote::createdAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

    public record SupportTicket(long ticketId,
                                long userId,
                                String status,
                                String priority,
                                String category,
                                String title,
                                Long assignedAdminUserId,
                                long createdByUserId,
                                Long resolvedByUserId,
                                Instant createdAt,
                                Instant updatedAt,
                                Instant closedAt) {
    }

    public record SupportTicketNote(long noteId,
                                    long ticketId,
                                    long adminUserId,
                                    String noteType,
                                    String visibility,
                                    String body,
                                    Instant createdAt) {
    }

    public record CursorPage<T>(
            List<T> items,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
    }
}
