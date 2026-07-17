package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskLimitsResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminQueryTaskService {

    private final AdminQueryTaskRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final GatewayProperties.QueryTasks queryTaskProperties;

    @Autowired
    public AdminQueryTaskService(AdminQueryTaskRepository repository,
                                 JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper,
                                 @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
                                 TaskExecutor taskExecutor,
                                 GatewayProperties gatewayProperties) {
        this(repository, jdbcTemplate, objectMapper,
                taskExecutor,
                gatewayProperties == null ? new GatewayProperties().getQueryTasks() : gatewayProperties.getQueryTasks());
    }

    AdminQueryTaskService(AdminQueryTaskRepository repository,
                          JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper,
                          TaskExecutor taskExecutor) {
        this(repository, jdbcTemplate, objectMapper, taskExecutor, new GatewayProperties().getQueryTasks());
    }

    AdminQueryTaskService(AdminQueryTaskRepository repository,
                          JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper,
                          TaskExecutor taskExecutor,
                          GatewayProperties.QueryTasks queryTaskProperties) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.queryTaskProperties = queryTaskProperties == null
                ? new GatewayProperties().getQueryTasks()
                : queryTaskProperties;
    }

    public AdminQueryTaskResponse create(JwtPrincipal principal,
                                         AdminQueryTaskCreateRequest request,
                                         Instant now) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (queryTaskProperties.isArchiveExpiredOnCreate()) {
            repository.archiveExpired(now, queryTaskProperties.getArchiveBatchSize(), "AUTO_EXPIRED_BEFORE_CREATE");
        }
        enforceQuotas(principal, now);
        Map<String, String> params = normalizeParams(request.params());
        AdminQueryTaskResponse task = repository.create(principal,
                request.queryType(), writeParams(params), now);
        taskExecutor.execute(() -> run(task.queryTaskId(), task.queryType(), params));
        return task;
    }

    public AdminQueryTaskLimitsResponse limits(JwtPrincipal principal, Instant now) {
        Instant windowStart = now.minus(queryTaskProperties.getCreationWindow());
        return new AdminQueryTaskLimitsResponse(
                repository.countActiveForUser(principal.userId()),
                queryTaskProperties.getMaxActivePerUser(),
                repository.countActiveGlobal(),
                queryTaskProperties.getMaxActiveGlobal(),
                repository.countCreatedForUserSince(principal.userId(), windowStart),
                queryTaskProperties.getMaxCreatedPerUserInWindow(),
                queryTaskProperties.getCreationWindow().toSeconds(),
                repository.retainedResultBytes(),
                queryTaskProperties.getMaxRetainedResultBytes(),
                repository.countExpiredReadyToArchive(now));
    }

    public AdminQueryTaskArchiveResponse archiveExpired(AdminQueryTaskArchiveRequest request, Instant now) {
        AdminQueryTaskArchiveRequest safeRequest = request == null
                ? new AdminQueryTaskArchiveRequest(null, null, null)
                : request;
        int limit = intRequestParam(safeRequest.limit(), queryTaskProperties.getArchiveBatchSize(), 1, 10_000);
        String reason = safeRequest.reason() == null || safeRequest.reason().isBlank()
                ? "MANUAL_ARCHIVE"
                : safeRequest.reason().trim();
        int archivedCount;
        Instant archivedBefore;
        if (safeRequest.olderThanDays() == null) {
            archivedBefore = now;
            archivedCount = repository.archiveExpired(now, limit, reason);
        } else {
            int days = intRequestParam(safeRequest.olderThanDays(), 3, 1, 3650);
            archivedBefore = now.minus(Duration.ofDays(days));
            archivedCount = repository.archiveFinishedBefore(archivedBefore, now, limit, reason);
        }
        return new AdminQueryTaskArchiveResponse(archivedCount, archivedBefore, limit, reason);
    }

    void run(long queryTaskId, String queryType, Map<String, String> params) {
        try {
            repository.markRunning(queryTaskId, Instant.now());
            QueryResult result = switch (normalizeQueryType(queryType)) {
                case "SYSTEM_OPERATION_LATENCY" -> systemOperationLatency(params);
                case "OUTBOX_BACKLOG" -> outboxBacklog(params);
                case "APPROVAL_BACKLOG" -> approvalBacklog(params);
                case "ALERT_DELIVERY_FAILURES" -> alertDeliveryFailures(params);
                case "ORDER_AUDIT_SEARCH" -> orderAuditSearch(params);
                case "TRIGGER_ORDER_AUDIT_SEARCH" -> triggerOrderAuditSearch(params);
                case "MATCH_TRADE_AUDIT_SEARCH" -> matchTradeAuditSearch(params);
                default -> throw new IllegalArgumentException("unsupported queryType: " + queryType);
            };
            String resultJson = writeResult(queryType, params, result);
            repository.markSucceeded(queryTaskId, resultJson, result.rows().size(), Instant.now());
        } catch (Exception ex) {
            repository.markFailed(queryTaskId, ex.getMessage(), Instant.now());
        }
    }

    private QueryResult systemOperationLatency(Map<String, String> params) {
        int windowMinutes = intParam(params.get("windowMinutes"), 1440, 1, 10_080);
        int limit = intParam(params.get("limit"), 100, 1, 500);
        String service = lowerOrNull(params.get("service"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT service,
                       COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE success = FALSE) AS failed,
                       CASE WHEN COUNT(*) = 0 THEN 0
                            ELSE ROUND(COUNT(*) FILTER (WHERE success = FALSE) * 1000000.0 / COUNT(*))
                       END AS failure_rate_ppm,
                       COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL), 0) AS p50_duration_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95_duration_ms,
                       COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL), 0) AS p99_duration_ms,
                       MAX(created_at) AS last_seen_at
                  FROM gateway_admin_operation_logs
                 WHERE created_at >= ?
                   AND (CAST(? AS text) IS NULL OR service = ?)
                 GROUP BY service
                 ORDER BY p95_duration_ms DESC, failed DESC, total DESC, service
                 LIMIT ?
                """, Timestamp.from(Instant.now().minusSeconds(windowMinutes * 60L)),
                service, service, limit);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("windowMinutes", windowMinutes);
        summary.put("service", service);
        return new QueryResult(summary, normalizeRows(rows));
    }

    private QueryResult outboxBacklog(Map<String, String> params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT *
                  FROM (
                        SELECT 'trading', 'trading_outbox_events',
                               COUNT(*),
                               COUNT(*) FILTER (WHERE published_at IS NULL),
                               COUNT(*) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL),
                               COALESCE(MAX(attempts) FILTER (WHERE published_at IS NULL), 0),
                               MIN(next_attempt_at) FILTER (WHERE published_at IS NULL),
                               MAX(last_error) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL)
                          FROM trading_outbox_events
                        UNION ALL
                        SELECT 'account', 'account_outbox_events',
                               COUNT(*),
                               COUNT(*) FILTER (WHERE published_at IS NULL),
                               COUNT(*) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL),
                               COALESCE(MAX(attempts) FILTER (WHERE published_at IS NULL), 0),
                               MIN(next_attempt_at) FILTER (WHERE published_at IS NULL),
                               MAX(last_error) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL)
                          FROM account_outbox_events
                        UNION ALL
                        SELECT 'risk', 'risk_outbox_events',
                               COUNT(*),
                               COUNT(*) FILTER (WHERE published_at IS NULL),
                               COUNT(*) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL),
                               COALESCE(MAX(attempts) FILTER (WHERE published_at IS NULL), 0),
                               MIN(next_attempt_at) FILTER (WHERE published_at IS NULL),
                               MAX(last_error) FILTER (WHERE published_at IS NULL AND last_error IS NOT NULL)
                          FROM risk_outbox_events
                  ) rows
                 ORDER BY pending DESC, failed DESC, module
                """);
        return new QueryResult(Map.of(), normalizeRows(rows));
    }

    private QueryResult approvalBacklog(Map<String, String> params) {
        String service = lowerOrNull(params.get("service"));
        int limit = intParam(params.get("limit"), 100, 1, 1000);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT approval_id, requester_user_id, requester_username, service, http_method,
                       request_path, query_string, reason, status, requested_at, expires_at,
                       EXTRACT(EPOCH FROM (now() - requested_at))::bigint AS age_seconds
                  FROM gateway_admin_approval_requests
                 WHERE status = 'PENDING'
                   AND (CAST(? AS text) IS NULL OR service = ?)
                 ORDER BY requested_at ASC, approval_id ASC
                 LIMIT ?
                """, service, service, limit);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("service", service);
        summary.put("limit", limit);
        return new QueryResult(summary, normalizeRows(rows));
    }

    private QueryResult orderAuditSearch(Map<String, String> params) {
        Long userId = longParam(params.get("userId"), "userId");
        Long orderId = longParam(params.get("orderId"), "orderId");
        String clientOrderId = blankToNull(params.get("clientOrderId"));
        String symbol = upperOrNull(params.get("symbol"));
        String status = upperOrNull(params.get("status"));
        String side = upperOrNull(params.get("side"));
        String marginMode = upperOrNull(params.get("marginMode"));
        String orderType = upperOrNull(params.get("orderType"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"), "createdAfter");
        Timestamp createdBefore = timestampParam(params.get("createdBefore"), "createdBefore");
        int limit = intParam(params.get("limit"), 1000, 1, 10_000);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT order_id, user_id, client_order_id, symbol, instrument_version, side,
                       order_type, time_in_force, price_ticks, quantity_steps, executed_quantity_steps,
                       remaining_quantity_steps, margin_mode, maker_fee_rate_ppm, taker_fee_rate_ppm,
                       reduce_only, post_only, status, reject_reason, created_at, updated_at
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR order_id = ?)
                   AND (CAST(? AS text) IS NULL OR client_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR side = ?)
                   AND (CAST(? AS text) IS NULL OR margin_mode = ?)
                   AND (CAST(? AS text) IS NULL OR order_type = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, order_id DESC
                 LIMIT ?
                """, userId, userId, orderId, orderId, clientOrderId, clientOrderId,
                symbol, symbol, status, status, side, side, marginMode, marginMode, orderType, orderType,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        Map<String, Object> summary = auditSummary(params, limit);
        summary.put("table", "trading_orders");
        return new QueryResult(summary, normalizeRows(rows));
    }

    private QueryResult triggerOrderAuditSearch(Map<String, String> params) {
        Long userId = longParam(params.get("userId"), "userId");
        Long triggerOrderId = longParam(params.get("triggerOrderId"), "triggerOrderId");
        String clientTriggerOrderId = blankToNull(params.get("clientTriggerOrderId"));
        String ocoGroupId = blankToNull(params.get("ocoGroupId"));
        String symbol = upperOrNull(params.get("symbol"));
        String status = upperOrNull(params.get("status"));
        String side = upperOrNull(params.get("side"));
        String triggerType = upperOrNull(params.get("triggerType"));
        String marginMode = upperOrNull(params.get("marginMode"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"), "createdAfter");
        Timestamp createdBefore = timestampParam(params.get("createdBefore"), "createdBefore");
        int limit = intParam(params.get("limit"), 1000, 1, 10_000);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT trigger_order_id, user_id, client_trigger_order_id, oco_group_id, symbol,
                       side, trigger_type, trigger_condition, trigger_price_ticks,
                       order_type, time_in_force, price_ticks, quantity_steps, margin_mode,
                       status, placed_order_id, trigger_sequence, triggered_price_ticks, reject_reason,
                       trace_id, expires_at, triggered_at, created_at, updated_at
                  FROM trading_trigger_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR trigger_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR client_trigger_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR oco_group_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR side = ?)
                   AND (CAST(? AS text) IS NULL OR trigger_type = ?)
                   AND (CAST(? AS text) IS NULL OR margin_mode = ?)
                   AND (CAST(? AS text) IS NULL OR created_at >= ?)
                   AND (CAST(? AS text) IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, trigger_order_id DESC
                 LIMIT ?
                """, userId, userId, triggerOrderId, triggerOrderId, clientTriggerOrderId, clientTriggerOrderId,
                ocoGroupId, ocoGroupId, symbol, symbol, status, status, side, side, triggerType, triggerType,
                marginMode, marginMode, createdAfter, createdAfter, createdBefore, createdBefore, limit);
        Map<String, Object> summary = auditSummary(params, limit);
        summary.put("table", "trading_trigger_orders");
        return new QueryResult(summary, normalizeRows(rows));
    }

    private QueryResult matchTradeAuditSearch(Map<String, String> params) {
        Long userId = longParam(params.get("userId"), "userId");
        Long orderId = longParam(params.get("orderId"), "orderId");
        Long tradeId = longParam(params.get("tradeId"), "tradeId");
        String symbol = upperOrNull(params.get("symbol"));
        String takerSide = upperOrNull(params.get("takerSide"));
        String traceId = blankToNull(params.get("traceId"));
        Timestamp createdAfter = timestampParam(params.get("createdAfter"), "createdAfter");
        Timestamp createdBefore = timestampParam(params.get("createdBefore"), "createdBefore");
        int limit = intParam(params.get("limit"), 1000, 1, 10_000);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT symbol, trade_id, command_id, taker_order_id, taker_user_id, taker_side,
                       taker_margin_mode, maker_order_id, maker_user_id, maker_margin_mode, price_ticks,
                       quantity_steps, taker_order_completed, maker_order_completed, trace_id, event_time,
                       created_at
                  FROM trading_match_trades
                 WHERE (CAST(? AS text) IS NULL OR taker_user_id = ? OR maker_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR taker_order_id = ? OR maker_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR trade_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR taker_side = ?)
                   AND (CAST(? AS text) IS NULL OR trace_id = ?)
                   AND (CAST(? AS text) IS NULL OR event_time >= ?)
                   AND (CAST(? AS text) IS NULL OR event_time < ?)
                 ORDER BY event_time DESC, symbol, trade_id DESC
                 LIMIT ?
                """, userId, userId, userId, orderId, orderId, orderId, tradeId, tradeId,
                symbol, symbol, takerSide, takerSide, traceId, traceId,
                createdAfter, createdAfter, createdBefore, createdBefore, limit);
        Map<String, Object> summary = auditSummary(params, limit);
        summary.put("table", "trading_match_trades");
        return new QueryResult(summary, normalizeRows(rows));
    }

    private QueryResult alertDeliveryFailures(Map<String, String> params) {
        String status = upperOrNull(params.get("status"));
        if (status != null && !List.of("FAILED", "SKIPPED").contains(status)) {
            throw new IllegalArgumentException("status must be FAILED or SKIPPED");
        }
        int limit = intParam(params.get("limit"), 100, 1, 1000);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.alert_delivery_id, d.alert_event_id, d.alert_channel_id, d.channel_code,
                       d.channel_type, d.delivery_status, d.attempt_count, d.next_attempt_at,
                       d.last_attempt_at, d.delivered_at, d.error_message, d.updated_at,
                       e.rule_code, e.domain, e.severity, e.status AS event_status, e.message
                  FROM gateway_admin_alert_deliveries d
                  JOIN gateway_admin_alert_events e ON e.alert_event_id = d.alert_event_id
                 WHERE d.delivery_status IN ('FAILED', 'SKIPPED')
                   AND (CAST(? AS text) IS NULL OR d.delivery_status = ?)
                 ORDER BY d.updated_at DESC, d.alert_delivery_id DESC
                 LIMIT ?
                """, status, status, limit);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("limit", limit);
        return new QueryResult(summary, normalizeRows(rows));
    }

    private String writeResult(String queryType, Map<String, String> params, QueryResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queryType", normalizeQueryType(queryType));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("params", params);
        payload.put("summary", stripNullValues(result.summary()));
        payload.put("rows", result.rows());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("failed to serialize query result", ex);
        }
    }

    private String writeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid query params", ex);
        }
    }

    private Map<String, String> normalizeParams(Map<String, String> params) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (params == null) {
            return normalized;
        }
        params.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                normalized.put(key.trim(), value.trim());
            }
        });
        return normalized;
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key, normalizeValue(value)));
            return normalized;
        }).toList();
    }

    private Map<String, Object> stripNullValues(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                normalized.put(key, value);
            }
        });
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private int intParam(String value, int defaultValue, int min, int max) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(parsed, max));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid integer param: " + value, ex);
        }
    }

    private Long longParam(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid long param " + field + ": " + value, ex);
        }
    }

    private Timestamp timestampParam(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(value.trim()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid timestamp param " + field + ": " + value, ex);
        }
    }

    private int intRequestParam(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return Math.max(min, Math.min(defaultValue, max));
        }
        return Math.max(min, Math.min(value, max));
    }

    private void enforceQuotas(JwtPrincipal principal, Instant now) {
        long activeForUser = repository.countActiveForUser(principal.userId());
        if (activeForUser >= queryTaskProperties.getMaxActivePerUser()) {
            throw new QueryTaskQuotaExceededException("active query task quota exceeded for admin user");
        }
        long activeGlobal = repository.countActiveGlobal();
        if (activeGlobal >= queryTaskProperties.getMaxActiveGlobal()) {
            throw new QueryTaskQuotaExceededException("global active query task quota exceeded");
        }
        long createdInWindow = repository.countCreatedForUserSince(
                principal.userId(), now.minus(queryTaskProperties.getCreationWindow()));
        if (createdInWindow >= queryTaskProperties.getMaxCreatedPerUserInWindow()) {
            throw new QueryTaskQuotaExceededException("query task creation rate quota exceeded for admin user");
        }
        long maxRetainedBytes = queryTaskProperties.getMaxRetainedResultBytes();
        if (maxRetainedBytes > 0 && repository.retainedResultBytes() >= maxRetainedBytes) {
            throw new QueryTaskQuotaExceededException("query task retained result storage quota exceeded");
        }
    }

    private String lowerOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> auditSummary(Map<String, String> params, int limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("limit", limit);
        summary.put("filters", params);
        return summary;
    }

    private String normalizeQueryType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("queryType is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record QueryResult(
            Map<String, Object> summary,
            List<Map<String, Object>> rows) {
    }

    public static class QueryTaskQuotaExceededException extends RuntimeException {
        public QueryTaskQuotaExceededException(String message) {
            super(message);
        }
    }
}
