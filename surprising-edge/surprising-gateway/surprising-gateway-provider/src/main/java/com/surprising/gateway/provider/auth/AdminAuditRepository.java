package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminOperationLogResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AdminAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(AdminOperationRecord record) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO gateway_admin_operation_logs (
                        admin_user_id, admin_username, admin_roles, service, http_method, request_path,
                        query_string, target_uri, request_body_sha256, response_status, duration_ms, success,
                        error_message, trace_id, user_agent, ip_address, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.adminUserId(),
                    record.adminUsername(),
                    record.adminRoles() == null ? null : String.join(",", record.adminRoles()),
                    normalizeService(record.service()),
                    normalizeMethod(record.httpMethod()),
                    truncate(record.requestPath(), 2048),
                    truncate(record.queryString(), 2048),
                    truncate(record.targetUri(), 4096),
                    record.requestBodySha256(),
                    record.responseStatus(),
                    record.durationMs(),
                    record.success(),
                    truncate(record.errorMessage(), 1024),
                    truncate(record.traceId(), 128),
                    truncate(record.userAgent(), 512),
                    truncate(record.ipAddress(), 128),
                    Timestamp.from(record.createdAt() == null ? Instant.now() : record.createdAt()));
        } catch (DataAccessException ex) {
            log.warn("admin operation audit write failed service={} method={} path={}",
                    record.service(), record.httpMethod(), record.requestPath(), ex);
        }
    }

    public List<AdminOperationLogResponse> operationLogs(Long adminUserId,
                                                         String service,
                                                         String method,
                                                         Boolean success,
                                                         int limit) {
        return operationLogPage(adminUserId, service, method, success, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminOperationLogResponse> operationLogPage(Long adminUserId,
                                                                                  String service,
                                                                                  String method,
                                                                                  Boolean success,
                                                                                  int limit,
                                                                                  String cursor,
                                                                                  String sort) {
        String normalizedService = nullableLower(service);
        String normalizedMethod = nullableUpper(method);
        int safeLimit = AdminCursorPage.limit(limit, 500);
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "operation_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "operation_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(adminUserId);
        args.add(adminUserId);
        args.add(normalizedService);
        args.add(normalizedService);
        args.add(normalizedMethod);
        args.add(normalizedMethod);
        args.add(success);
        args.add(success);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminOperationLogResponse> rows = jdbcTemplate.query("""
                SELECT operation_id, admin_user_id, admin_username, admin_roles, service, http_method,
                       request_path, query_string, target_uri, request_body_sha256, response_status,
                       duration_ms, success, error_message, trace_id, user_agent, ip_address, created_at
                  FROM gateway_admin_operation_logs
                 WHERE (CAST(? AS text) IS NULL OR admin_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR service = ?)
                   AND (CAST(? AS text) IS NULL OR http_method = ?)
                   AND (CAST(? AS text) IS NULL OR success = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                this::toResponse,
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminOperationLogResponse::createdAt,
                AdminOperationLogResponse::operationId);
    }

    private AdminOperationLogResponse toResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminOperationLogResponse(
                rs.getLong("operation_id"),
                nullableLong(rs, "admin_user_id"),
                rs.getString("admin_username"),
                splitRoles(rs.getString("admin_roles")),
                rs.getString("service"),
                rs.getString("http_method"),
                rs.getString("request_path"),
                rs.getString("query_string"),
                rs.getString("target_uri"),
                rs.getString("request_body_sha256"),
                nullableInteger(rs, "response_status"),
                nullableLong(rs, "duration_ms"),
                rs.getBoolean("success"),
                rs.getString("error_message"),
                rs.getString("trace_id"),
                rs.getString("user_agent"),
                rs.getString("ip_address"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private List<String> splitRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private String normalizeService(String service) {
        String normalized = nullableLower(service);
        return normalized == null || !normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")
                ? "unknown"
                : normalized;
    }

    private String normalizeMethod(String method) {
        String normalized = nullableUpper(method);
        return normalized == null || !normalized.matches("[A-Z]{3,16}")
                ? "GET"
                : normalized;
    }

    private String nullableLower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullableUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record AdminOperationRecord(
            Long adminUserId,
            String adminUsername,
            List<String> adminRoles,
            String service,
            String httpMethod,
            String requestPath,
            String queryString,
            String targetUri,
            String requestBodySha256,
            Integer responseStatus,
            Long durationMs,
            boolean success,
            String errorMessage,
            String traceId,
            String userAgent,
            String ipAddress,
            Instant createdAt) {
    }
}
