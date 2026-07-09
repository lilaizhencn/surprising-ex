package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobResponse;
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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminExportRepository {

    static final Duration DEFAULT_EXPIRY = Duration.ofDays(7);
    private static final List<String> SUPPORTED_EXPORT_TYPES = List.of(
            "USERS",
            "LOGIN_LOGS",
            "ADMIN_OPERATIONS",
            "COMPLIANCE_USERS",
            "ORDERS",
            "TRIGGER_ORDERS",
            "MATCH_TRADES",
            "ACCOUNT_BALANCES",
            "PRODUCT_BALANCES",
            "POSITIONS",
            "ACCOUNT_LEDGER",
            "PRODUCT_LEDGER",
            "PRODUCT_TRANSFERS",
            "ACCOUNT_ADJUSTMENTS");

    private final JdbcTemplate jdbcTemplate;

    public AdminExportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminExportJobResponse create(JwtPrincipal principal,
                                         String exportType,
                                         String queryParams,
                                         Instant now) {
        String normalizedType = normalizeExportType(exportType);
        Instant expiresAt = now.plus(DEFAULT_EXPIRY);
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_export_jobs (
                    requested_by_user_id, requested_by_username, export_type, status, format,
                    query_params, requested_at, expires_at
                ) VALUES (?, ?, ?, 'PENDING', 'CSV', ?, ?, ?)
                RETURNING export_id, requested_by_user_id, requested_by_username, export_type,
                          status, format, query_params, file_name, content_type, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at, expires_at
                """, this::toJob,
                principal.userId(),
                principal.username(),
                normalizedType,
                queryParams,
                Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    public List<AdminExportJobResponse> jobs(String status, String exportType, int limit) {
        return jobPage(status, exportType, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminExportJobResponse> jobPage(String status,
                                                                      String exportType,
                                                                      int limit,
                                                                      String cursor,
                                                                      String sort) {
        String normalizedStatus = normalizeNullableStatus(status);
        String normalizedType = normalizeNullableExportType(exportType);
        int safeLimit = AdminCursorPage.limit(limit, 500);
        AdminCursorPage.SortSpec requestedAtDesc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "export_id", true);
        AdminCursorPage.SortSpec requestedAtAsc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "export_id", false);
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
        List<AdminExportJobResponse> rows = jdbcTemplate.query("""
                SELECT export_id, requested_by_user_id, requested_by_username, export_type,
                       status, format, query_params, file_name, content_type, row_count,
                       byte_size, error_message, requested_at, started_at, finished_at, expires_at
                  FROM gateway_admin_export_jobs
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR export_type = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                this::toJob,
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminExportJobResponse::requestedAt,
                AdminExportJobResponse::exportId);
    }

    public Optional<AdminExportJobResponse> job(long exportId) {
        return jdbcTemplate.query("""
                SELECT export_id, requested_by_user_id, requested_by_username, export_type,
                       status, format, query_params, file_name, content_type, row_count,
                       byte_size, error_message, requested_at, started_at, finished_at, expires_at
                  FROM gateway_admin_export_jobs
                 WHERE export_id = ?
                """, this::toJob, exportId).stream().findFirst();
    }

    public AdminExportJobResponse markRunning(long exportId, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_export_jobs
                   SET status = 'RUNNING',
                       started_at = COALESCE(started_at, ?)
                 WHERE export_id = ?
                RETURNING export_id, requested_by_user_id, requested_by_username, export_type,
                          status, format, query_params, file_name, content_type, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at, expires_at
                """, this::toJob, Timestamp.from(now), exportId);
    }

    public AdminExportJobResponse markSucceeded(long exportId,
                                                String fileName,
                                                String contentType,
                                                int rowCount,
                                                String csv,
                                                Instant now) {
        long byteSize = csv == null ? 0 : csv.getBytes(StandardCharsets.UTF_8).length;
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_export_jobs
                   SET status = 'SUCCEEDED',
                       file_name = ?,
                       content_type = ?,
                       row_count = ?,
                       byte_size = ?,
                       result_content = ?,
                       error_message = NULL,
                       finished_at = ?
                 WHERE export_id = ?
                RETURNING export_id, requested_by_user_id, requested_by_username, export_type,
                          status, format, query_params, file_name, content_type, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at, expires_at
                """, this::toJob,
                fileName,
                contentType,
                rowCount,
                byteSize,
                csv,
                Timestamp.from(now),
                exportId);
    }

    public AdminExportJobResponse markFailed(long exportId, String errorMessage, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_export_jobs
                   SET status = 'FAILED',
                       error_message = ?,
                       finished_at = ?
                 WHERE export_id = ?
                RETURNING export_id, requested_by_user_id, requested_by_username, export_type,
                          status, format, query_params, file_name, content_type, row_count,
                          byte_size, error_message, requested_at, started_at, finished_at, expires_at
                """, this::toJob, truncate(errorMessage, 2048), Timestamp.from(now), exportId);
    }

    public Optional<AdminExportDownload> download(long exportId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT export_id, file_name, content_type, result_content
                      FROM gateway_admin_export_jobs
                     WHERE export_id = ?
                       AND status = 'SUCCEEDED'
                       AND expires_at > now()
                    """, (rs, rowNum) -> new AdminExportDownload(
                    rs.getLong("export_id"),
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getString("result_content").getBytes(StandardCharsets.UTF_8)), exportId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private AdminExportJobResponse toJob(ResultSet rs, int rowNum) throws SQLException {
        return new AdminExportJobResponse(
                rs.getLong("export_id"),
                rs.getLong("requested_by_user_id"),
                rs.getString("requested_by_username"),
                rs.getString("export_type"),
                rs.getString("status"),
                rs.getString("format"),
                rs.getString("query_params"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getInt("row_count"),
                rs.getLong("byte_size"),
                rs.getString("error_message"),
                rs.getTimestamp("requested_at").toInstant(),
                nullableInstant(rs, "started_at"),
                nullableInstant(rs, "finished_at"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalizeExportType(String value) {
        String normalized = normalizeNullableExportType(value);
        if (normalized == null) {
            throw new IllegalArgumentException("exportType is required");
        }
        return normalized;
    }

    private String normalizeNullableExportType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_EXPORT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported exportType: " + value);
        }
        return normalized;
    }

    private String normalizeNullableStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED").contains(normalized)) {
            throw new IllegalArgumentException("unsupported export status: " + value);
        }
        return normalized;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record AdminExportDownload(
            long exportId,
            String fileName,
            String contentType,
            byte[] content) {
    }
}
