package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemMetricsController {

    private static final List<OutboxTable> OUTBOX_TABLES = List.of(
            new OutboxTable("funding", "funding_outbox_events"),
            new OutboxTable("trading", "trading_outbox_events"),
            new OutboxTable("account", "account_outbox_events"),
            new OutboxTable("risk", "risk_outbox_events"));

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    public AdminSystemMetricsController(AuthService authService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/metrics")
    public SystemMetricsResponse metrics(@RequestHeader("Authorization") String authorization,
                                         @RequestParam(value = "windowMinutes", defaultValue = "60") int windowMinutes) {
        try {
            authService.requireAdminPermission(authorization, "admin.system.read");
            int boundedWindow = Math.max(1, Math.min(windowMinutes, 1440));
            Instant now = Instant.now();
            Instant since = now.minus(Duration.ofMinutes(boundedWindow));
            List<SystemMetricWarning> warnings = new ArrayList<>();
            return new SystemMetricsResponse(
                    now,
                    boundedWindow,
                    outboxMetrics(now, warnings),
                    adminOperationMetrics(since, warnings),
                    loginMetrics(since, warnings),
                    approvalMetrics(now, warnings),
                    warnings);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private OutboxMetrics outboxMetrics(Instant now, List<SystemMetricWarning> warnings) {
        List<OutboxMetric> rows = new ArrayList<>();
        long totalPending = 0;
        long totalFailed = 0;
        long totalRows = 0;
        long maxAttempts = 0;
        for (OutboxTable table : OUTBOX_TABLES) {
            try {
                Map<String, Object> result = jdbcTemplate.queryForMap("""
                        SELECT COUNT(*) AS total,
                               COUNT(*) FILTER (WHERE published_at IS NULL) AS pending,
                               COUNT(*) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL) AS failed,
                               COALESCE(MAX(attempts) FILTER (WHERE published_at IS NULL), 0) AS max_attempts,
                               MIN(next_attempt_at) FILTER (WHERE published_at IS NULL) AS oldest_pending_at,
                               MAX(last_error) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL) AS last_error
                          FROM %s
                        """.formatted(table.tableName()));
                long total = longValue(result.get("total"));
                long pending = longValue(result.get("pending"));
                long failed = longValue(result.get("failed"));
                long attempts = longValue(result.get("max_attempts"));
                Instant oldest = instantValue(result.get("oldest_pending_at"));
                rows.add(new OutboxMetric(
                        table.module(),
                        table.tableName(),
                        total,
                        pending,
                        failed,
                        attempts,
                        oldest,
                        oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()),
                        stringValue(result.get("last_error")),
                        null));
                totalRows += total;
                totalPending += pending;
                totalFailed += failed;
                maxAttempts = Math.max(maxAttempts, attempts);
            } catch (DataAccessException ex) {
                warnings.add(new SystemMetricWarning(table.module(), table.tableName(), ex.getMessage()));
                rows.add(new OutboxMetric(table.module(), table.tableName(), 0, 0, 0,
                        0, null, 0, null, ex.getMessage()));
            }
        }
        return new OutboxMetrics(totalRows, totalPending, totalFailed, maxAttempts, rows);
    }

    private AdminOperationMetrics adminOperationMetrics(Instant since, List<SystemMetricWarning> warnings) {
        try {
            Map<String, Object> aggregate = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE success = FALSE) AS failed,
                           COUNT(*) FILTER (WHERE http_method NOT IN ('GET', 'HEAD', 'OPTIONS')) AS writes,
                           COUNT(DISTINCT admin_user_id) AS unique_admins,
                           COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p50_duration_ms,
                           COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95_duration_ms,
                           COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p99_duration_ms
                      FROM gateway_admin_operation_logs
                     WHERE created_at >= ?
                    """, Timestamp.from(since));
            long total = longValue(aggregate.get("total"));
            long failed = longValue(aggregate.get("failed"));
            long writes = longValue(aggregate.get("writes"));
            long uniqueAdmins = longValue(aggregate.get("unique_admins"));
            long p50DurationMs = longValue(aggregate.get("p50_duration_ms"));
            long p95DurationMs = longValue(aggregate.get("p95_duration_ms"));
            long p99DurationMs = longValue(aggregate.get("p99_duration_ms"));
            List<ServiceOperationMetric> services = jdbcTemplate.queryForList("""
                    SELECT service,
                           COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE success = FALSE) AS failed,
                           MAX(created_at) AS last_seen_at,
                           COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p50_duration_ms,
                           COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95_duration_ms,
                           COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms)
                               FILTER (WHERE duration_ms IS NOT NULL), 0) AS p99_duration_ms
                      FROM gateway_admin_operation_logs
                     WHERE created_at >= ?
                     GROUP BY service
                     ORDER BY p95_duration_ms DESC, failed DESC, total DESC, service
                     LIMIT 20
                    """, Timestamp.from(since)).stream()
                    .map(row -> {
                        long serviceTotal = longValue(row.get("total"));
                        long serviceFailed = longValue(row.get("failed"));
                        return new ServiceOperationMetric(
                                stringValue(row.get("service")),
                                serviceTotal,
                                serviceFailed,
                                failureRatePpm(serviceTotal, serviceFailed),
                                longValue(row.get("p50_duration_ms")),
                                longValue(row.get("p95_duration_ms")),
                                longValue(row.get("p99_duration_ms")),
                                instantValue(row.get("last_seen_at")));
                    })
                    .toList();
            return new AdminOperationMetrics(total, failed, failureRatePpm(total, failed),
                    writes, uniqueAdmins, p50DurationMs, p95DurationMs, p99DurationMs, services, null);
        } catch (DataAccessException ex) {
            warnings.add(new SystemMetricWarning("gateway", "gateway_admin_operation_logs", ex.getMessage()));
            return new AdminOperationMetrics(0, 0, 0, 0, 0, 0, 0, 0, List.of(), ex.getMessage());
        }
    }

    private LoginMetrics loginMetrics(Instant since, List<SystemMetricWarning> warnings) {
        try {
            Map<String, Object> result = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE result = 'FAILED') AS failed
                      FROM gateway_login_logs
                     WHERE created_at >= ?
                    """, Timestamp.from(since));
            long total = longValue(result.get("total"));
            long failed = longValue(result.get("failed"));
            return new LoginMetrics(total, failed, failureRatePpm(total, failed), null);
        } catch (DataAccessException ex) {
            warnings.add(new SystemMetricWarning("gateway", "gateway_login_logs", ex.getMessage()));
            return new LoginMetrics(0, 0, 0, ex.getMessage());
        }
    }

    private ApprovalMetrics approvalMetrics(Instant now, List<SystemMetricWarning> warnings) {
        try {
            Map<String, Object> result = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                           COUNT(*) FILTER (WHERE status = 'PENDING' AND expires_at <= ?) AS expired_pending,
                           COUNT(*) FILTER (WHERE status = 'APPROVED') AS approved_not_consumed
                      FROM gateway_admin_approval_requests
                    """, Timestamp.from(now));
            return new ApprovalMetrics(
                    longValue(result.get("pending")),
                    longValue(result.get("expired_pending")),
                    longValue(result.get("approved_not_consumed")),
                    null);
        } catch (DataAccessException ex) {
            warnings.add(new SystemMetricWarning("gateway", "gateway_admin_approval_requests", ex.getMessage()));
            return new ApprovalMetrics(0, 0, 0, ex.getMessage());
        }
    }

    private long failureRatePpm(long total, long failed) {
        if (total <= 0 || failed <= 0) {
            return 0;
        }
        return Math.round((failed * 1_000_000.0d) / total);
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant instantValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private record OutboxTable(
            String module,
            String tableName) {
    }

    public record SystemMetricsResponse(
            Instant generatedAt,
            int windowMinutes,
            OutboxMetrics outbox,
            AdminOperationMetrics adminOperations,
            LoginMetrics logins,
            ApprovalMetrics approvals,
            List<SystemMetricWarning> warnings) {
    }

    public record OutboxMetrics(
            long totalRows,
            long totalPending,
            long totalFailed,
            long maxAttempts,
            List<OutboxMetric> modules) {
    }

    public record OutboxMetric(
            String module,
            String tableName,
            long total,
            long pending,
            long failed,
            long maxAttempts,
            Instant oldestPendingAt,
            long oldestPendingAgeSeconds,
            String lastError,
            String error) {
    }

    public record AdminOperationMetrics(
            long total,
            long failed,
            long failureRatePpm,
            long writes,
            long uniqueAdmins,
            long p50DurationMs,
            long p95DurationMs,
            long p99DurationMs,
            List<ServiceOperationMetric> services,
            String error) {
    }

    public record ServiceOperationMetric(
            String service,
            long total,
            long failed,
            long failureRatePpm,
            long p50DurationMs,
            long p95DurationMs,
            long p99DurationMs,
            Instant lastSeenAt) {
    }

    public record LoginMetrics(
            long total,
            long failed,
            long failureRatePpm,
            String error) {
    }

    public record ApprovalMetrics(
            long pending,
            long expiredPending,
            long approvedNotConsumed,
            String error) {
    }

    public record SystemMetricWarning(
            String module,
            String source,
            String message) {
    }
}
