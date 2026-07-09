package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminQueryTaskRepository {

    static final Duration DEFAULT_EXPIRY = Duration.ofDays(3);
    private static final List<String> SUPPORTED_QUERY_TYPES = List.of(
            "SYSTEM_OPERATION_LATENCY",
            "OUTBOX_BACKLOG",
            "APPROVAL_BACKLOG",
            "ALERT_DELIVERY_FAILURES",
            "ORDER_AUDIT_SEARCH",
            "TRIGGER_ORDER_AUDIT_SEARCH",
            "MATCH_TRADE_AUDIT_SEARCH");

    private final JdbcTemplate jdbcTemplate;

    public AdminQueryTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminQueryTaskResponse create(JwtPrincipal principal,
                                         String queryType,
                                         String queryParams,
                                         Instant now) {
        String normalizedType = normalizeQueryType(queryType);
        Instant expiresAt = now.plus(DEFAULT_EXPIRY);
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_query_tasks (
                    requested_by_user_id, requested_by_username, query_type, status,
                    query_params, requested_at, expires_at
                ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                RETURNING query_task_id, requested_by_user_id, requested_by_username,
                          query_type, status, query_params, result_json, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at,
                          expires_at, archived_at, archive_reason
                """, this::toTask,
                principal.userId(),
                principal.username(),
                normalizedType,
                queryParams,
                Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    public List<AdminQueryTaskResponse> tasks(String status, String queryType, int limit) {
        return taskPage(status, queryType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminQueryTaskResponse> taskPage(String status,
                                                                       String queryType,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        String normalizedStatus = normalizeNullableStatus(status);
        String normalizedType = normalizeNullableQueryType(queryType);
        int safeLimit = AdminCursorPage.limit(limit, 500);
        AdminCursorPage.SortSpec requestedAtDesc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "query_task_id", true);
        AdminCursorPage.SortSpec requestedAtAsc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "query_task_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, requestedAtDesc, List.of(requestedAtDesc, requestedAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(normalizedType);
        args.add(normalizedType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminQueryTaskResponse> rows = jdbcTemplate.query("""
                SELECT query_task_id, requested_by_user_id, requested_by_username,
                       query_type, status, query_params, NULL::text AS result_json,
                       row_count, byte_size, error_message, requested_at, started_at,
                       finished_at, expires_at, archived_at, archive_reason
                 FROM gateway_admin_query_tasks
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR query_type = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                this::toTask,
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminQueryTaskResponse::requestedAt,
                AdminQueryTaskResponse::queryTaskId);
    }

    public Optional<AdminQueryTaskResponse> task(long queryTaskId) {
        return jdbcTemplate.query("""
                SELECT query_task_id, requested_by_user_id, requested_by_username,
                       query_type, status, query_params, result_json, row_count,
                       byte_size, error_message, requested_at, started_at, finished_at,
                       expires_at, archived_at, archive_reason
                  FROM gateway_admin_query_tasks
                 WHERE query_task_id = ?
                """, this::toTask, queryTaskId).stream().findFirst();
    }

    public AdminQueryTaskResponse markRunning(long queryTaskId, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_query_tasks
                   SET status = 'RUNNING',
                       started_at = COALESCE(started_at, ?)
                 WHERE query_task_id = ?
                RETURNING query_task_id, requested_by_user_id, requested_by_username,
                          query_type, status, query_params, result_json, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at,
                          expires_at, archived_at, archive_reason
                """, this::toTask, Timestamp.from(now), queryTaskId);
    }

    public AdminQueryTaskResponse markSucceeded(long queryTaskId,
                                                String resultJson,
                                                int rowCount,
                                                Instant now) {
        long byteSize = resultJson == null ? 0 : resultJson.getBytes(StandardCharsets.UTF_8).length;
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_query_tasks
                   SET status = 'SUCCEEDED',
                       result_json = ?,
                       row_count = ?,
                       byte_size = ?,
                       error_message = NULL,
                       finished_at = ?
                 WHERE query_task_id = ?
                RETURNING query_task_id, requested_by_user_id, requested_by_username,
                          query_type, status, query_params, result_json, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at,
                          expires_at, archived_at, archive_reason
                """, this::toTask,
                resultJson,
                rowCount,
                byteSize,
                Timestamp.from(now),
                queryTaskId);
    }

    public AdminQueryTaskResponse markFailed(long queryTaskId, String errorMessage, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_query_tasks
                   SET status = 'FAILED',
                       error_message = ?,
                       finished_at = ?
                 WHERE query_task_id = ?
                RETURNING query_task_id, requested_by_user_id, requested_by_username,
                          query_type, status, query_params, result_json, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at,
                          expires_at, archived_at, archive_reason
                """, this::toTask, truncate(errorMessage, 2048), Timestamp.from(now), queryTaskId);
    }

    public long countActiveForUser(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM gateway_admin_query_tasks
                 WHERE requested_by_user_id = ?
                   AND status IN ('PENDING', 'RUNNING')
                """, Long.class, userId);
        return count == null ? 0L : count;
    }

    public long countActiveGlobal() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM gateway_admin_query_tasks
                 WHERE status IN ('PENDING', 'RUNNING')
                """, Long.class);
        return count == null ? 0L : count;
    }

    public long countCreatedForUserSince(long userId, Instant since) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM gateway_admin_query_tasks
                 WHERE requested_by_user_id = ?
                   AND requested_at >= ?
                """, Long.class, userId, Timestamp.from(since));
        return count == null ? 0L : count;
    }

    public long retainedResultBytes() {
        Long bytes = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(byte_size), 0)
                  FROM gateway_admin_query_tasks
                 WHERE status <> 'ARCHIVED'
                   AND result_json IS NOT NULL
                """, Long.class);
        return bytes == null ? 0L : bytes;
    }

    public long countExpiredReadyToArchive(Instant now) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM gateway_admin_query_tasks
                 WHERE status IN ('SUCCEEDED', 'FAILED')
                   AND archived_at IS NULL
                   AND expires_at <= ?
                """, Long.class, Timestamp.from(now));
        return count == null ? 0L : count;
    }

    public int archiveExpired(Instant now, int limit, String reason) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbcTemplate.update("""
                WITH picked AS (
                    SELECT query_task_id
                      FROM gateway_admin_query_tasks
                     WHERE status IN ('SUCCEEDED', 'FAILED')
                       AND archived_at IS NULL
                       AND expires_at <= ?
                     ORDER BY expires_at ASC, query_task_id ASC
                     LIMIT ?
                )
                UPDATE gateway_admin_query_tasks task
                   SET status = 'ARCHIVED',
                       result_json = NULL,
                       archived_at = ?,
                       archive_reason = ?
                  FROM picked
                 WHERE task.query_task_id = picked.query_task_id
                """, Timestamp.from(now), safeLimit, Timestamp.from(now), truncate(reason, 500));
    }

    public int archiveFinishedBefore(Instant cutoff, Instant now, int limit, String reason) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbcTemplate.update("""
                WITH picked AS (
                    SELECT query_task_id
                      FROM gateway_admin_query_tasks
                     WHERE status IN ('SUCCEEDED', 'FAILED')
                       AND archived_at IS NULL
                       AND COALESCE(finished_at, requested_at) <= ?
                     ORDER BY COALESCE(finished_at, requested_at) ASC, query_task_id ASC
                     LIMIT ?
                )
                UPDATE gateway_admin_query_tasks task
                   SET status = 'ARCHIVED',
                       result_json = NULL,
                       archived_at = ?,
                       archive_reason = ?
                  FROM picked
                 WHERE task.query_task_id = picked.query_task_id
                """, Timestamp.from(cutoff), safeLimit, Timestamp.from(now), truncate(reason, 500));
    }

    private AdminQueryTaskResponse toTask(ResultSet rs, int rowNum) throws SQLException {
        return new AdminQueryTaskResponse(
                rs.getLong("query_task_id"),
                rs.getLong("requested_by_user_id"),
                rs.getString("requested_by_username"),
                rs.getString("query_type"),
                rs.getString("status"),
                rs.getString("query_params"),
                rs.getString("result_json"),
                rs.getInt("row_count"),
                rs.getLong("byte_size"),
                rs.getString("error_message"),
                rs.getTimestamp("requested_at").toInstant(),
                nullableInstant(rs, "started_at"),
                nullableInstant(rs, "finished_at"),
                rs.getTimestamp("expires_at").toInstant(),
                nullableInstant(rs, "archived_at"),
                rs.getString("archive_reason"));
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalizeQueryType(String value) {
        String normalized = normalizeNullableQueryType(value);
        if (normalized == null) {
            throw new IllegalArgumentException("queryType is required");
        }
        return normalized;
    }

    private String normalizeNullableQueryType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_QUERY_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported queryType: " + value);
        }
        return normalized;
    }

    private String normalizeNullableStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "ARCHIVED").contains(normalized)) {
            throw new IllegalArgumentException("unsupported query task status: " + value);
        }
        return normalized;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
