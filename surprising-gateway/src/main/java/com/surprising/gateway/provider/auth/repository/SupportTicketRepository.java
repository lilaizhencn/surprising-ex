package com.surprising.gateway.provider.auth.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;

import com.surprising.gateway.provider.auth.model.SupportModels.CursorPage;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportTicket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_support_tickets} 表。
 */
@Repository
public class SupportTicketRepository {

    private static final AdminCursorPage.SortSpec TICKET_UPDATED_DESC =
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "ticket_id", true);
    private static final List<AdminCursorPage.SortSpec> TICKET_SORTS = List.of(
            TICKET_UPDATED_DESC,
            new AdminCursorPage.SortSpec("updatedAt", "updated_at", "ticket_id", false),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "ticket_id", true),
            new AdminCursorPage.SortSpec("createdAt", "created_at", "ticket_id", false));

    private final JdbcTemplate jdbcTemplate;

    public SupportTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CursorPage<SupportTicket> ticketsPage(Long userId,
                                                 String status,
                                                 AuthPageQuery pageQuery) {
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = TICKET_SORTS.stream()
                .filter(spec -> spec.field().equals(pageQuery.order().field())
                        && spec.descending() == pageQuery.order().descending())
                .findFirst().orElseThrow(() -> new IllegalStateException("unsupported native page order"));
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
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
                """, (rs, rowNum) -> toTicket(rs), userId, priority, category, title, assignedAdminUserId,
                createdByUserId, Timestamp.from(now), Timestamp.from(now));
    }

    /** resolvedByUserId 为空时保留上一处理人；关闭时间按 Service 提供的事实写入或清空。 */
    public SupportTicket updateStatus(long ticketId, String status, Long resolvedByUserId, Instant closedAt, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_support_tickets
                   SET status = ?,
                       resolved_by_user_id = COALESCE(?, resolved_by_user_id),
                       closed_at = ?,
                       updated_at = ?
                 WHERE ticket_id = ?
                RETURNING *
                """, (rs, rowNum) -> toTicket(rs), status, resolvedByUserId, closedAt == null ? null : Timestamp.from(closedAt),
                Timestamp.from(now), ticketId);
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

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private java.util.function.Function<SupportTicket, Instant> ticketTimestampExtractor(
            AdminCursorPage.SortSpec sort) {
        return switch (sort.field()) {
            case "createdAt" -> SupportTicket::createdAt;
            case "updatedAt" -> SupportTicket::updatedAt;
            default -> throw new IllegalArgumentException("unsupported sort: " + sort.token());
        };
    }

}
