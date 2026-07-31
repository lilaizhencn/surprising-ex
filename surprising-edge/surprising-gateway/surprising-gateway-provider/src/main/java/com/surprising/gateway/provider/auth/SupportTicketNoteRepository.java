package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.SupportTicketRepository.CursorPage;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicketNote;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_support_ticket_notes} 表。
 */
@Repository
public class SupportTicketNoteRepository {

    private static final int MAX_NOTE_LIMIT = 500;
    private static final AdminCursorPage.SortSpec NOTE_CREATED_ASC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "note_id", false);
    private static final List<AdminCursorPage.SortSpec> NOTE_SORTS = List.of(
            NOTE_CREATED_ASC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "note_id", true));

    private final JdbcTemplate jdbcTemplate;

    public SupportTicketNoteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        List<SupportTicketNote> fetchedRows = jdbcTemplate.query(
                sql, (rs, rowNum) -> toNote(rs), args.toArray());
        AdminCursorPage.CursorPage<SupportTicketNote> page = AdminCursorPage.page(
                fetchedRows, safeLimit, sortSpec, SupportTicketNote::createdAt, SupportTicketNote::noteId);
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
                """, (rs, rowNum) -> toNote(rs), ticketId, adminUserId, noteType, visibility, body,
                Timestamp.from(now));
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
}
