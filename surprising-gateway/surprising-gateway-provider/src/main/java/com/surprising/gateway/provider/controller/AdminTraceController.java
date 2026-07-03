package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/traces")
public class AdminTraceController {

    private static final String TRACE_ID_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    public AdminTraceController(AuthService authService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/{traceId}")
    public TraceQueryResponse trace(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                    @PathVariable String traceId,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            authService.requireAdminPermission(authorization, "admin.traces.read");
            String normalizedTraceId = normalizeTraceId(traceId);
            int boundedLimit = Math.max(1, Math.min(limit, 200));
            List<TraceWarning> warnings = new ArrayList<>();
            List<TraceSection> sections = new ArrayList<>();
            sections.add(tradingTriggerOrders(normalizedTraceId, boundedLimit, warnings));
            sections.add(tradingOrderEvents(normalizedTraceId, boundedLimit, warnings));
            sections.add(tradingMatchResults(normalizedTraceId, boundedLimit, warnings));
            sections.add(tradingMatchTrades(normalizedTraceId, boundedLimit, warnings));
            sections.add(tradingOutbox(normalizedTraceId, boundedLimit, warnings));
            sections.add(accountOutbox(normalizedTraceId, boundedLimit, warnings));
            sections.add(riskOutbox(normalizedTraceId, boundedLimit, warnings));
            sections.add(adminOperations(normalizedTraceId, boundedLimit, warnings));
            sections.add(adminApprovals(normalizedTraceId, boundedLimit, warnings));
            List<TraceEvent> timeline = sections.stream()
                    .flatMap(section -> section.events().stream())
                    .sorted(Comparator.comparing(TraceEvent::eventTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TraceEvent::source)
                            .thenComparing(TraceEvent::recordId))
                    .toList();
            return new TraceQueryResponse(Instant.now(), normalizedTraceId, timeline.size(), sections, timeline, warnings);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private TraceSection tradingTriggerOrders(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("TRADING_TRIGGER_ORDER", "trading_trigger_orders", warnings, """
                SELECT trigger_order_id, user_id, client_trigger_order_id, oco_group_id, symbol, side,
                       trigger_type, trigger_price_type, trigger_condition, order_type, time_in_force,
                       quantity_steps, margin_mode, status, placed_order_id, reject_reason,
                       trace_id, expires_at, triggered_at, created_at, updated_at
                  FROM trading_trigger_orders
                 WHERE trace_id = ?
                 ORDER BY created_at, trigger_order_id
                 LIMIT ?
                """, traceId, limit, "trigger_order_id", "created_at",
                row -> stringValue(row.get("symbol")),
                row -> "trigger order " + row.get("status") + " " + row.get("side") + " " + row.get("quantity_steps"));
    }

    private TraceSection tradingOrderEvents(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("TRADING_ORDER_EVENT", "trading_order_events", warnings, """
                SELECT event_id, order_id, user_id, symbol, event_type, status, reason, trace_id,
                       event_time, created_at
                  FROM trading_order_events
                 WHERE trace_id = ?
                 ORDER BY event_time, event_id
                 LIMIT ?
                """, traceId, limit, "event_id", "event_time",
                row -> "order:" + row.get("order_id"),
                row -> row.get("event_type") + " -> " + row.get("status")
                        + (row.get("reason") == null ? "" : " (" + row.get("reason") + ")"));
    }

    private TraceSection tradingMatchResults(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("TRADING_MATCH_RESULT", "trading_match_results", warnings, """
                SELECT command_id, order_id, user_id, symbol, instrument_version, command_type,
                       result_code, filled_quantity_steps, order_status, trace_id, event_time, created_at
                  FROM trading_match_results
                 WHERE trace_id = ?
                 ORDER BY event_time, command_id
                 LIMIT ?
                """, traceId, limit, "command_id", "event_time",
                row -> "order:" + row.get("order_id"),
                row -> "match " + row.get("command_type") + " " + row.get("result_code")
                        + " status=" + row.get("order_status"));
    }

    private TraceSection tradingMatchTrades(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("TRADING_MATCH_TRADE", "trading_match_trades", warnings, """
                SELECT trade_id, command_id, symbol, taker_order_id, taker_user_id, taker_side,
                       taker_margin_mode, maker_order_id, maker_user_id, maker_margin_mode,
                       price_ticks, quantity_steps, taker_order_completed, maker_order_completed,
                       trace_id, event_time, created_at
                  FROM trading_match_trades
                 WHERE trace_id = ?
                 ORDER BY event_time, trade_id
                 LIMIT ?
                """, traceId, limit, "trade_id", "event_time",
                row -> "trade:" + row.get("trade_id"),
                row -> "trade " + row.get("symbol") + " qty=" + row.get("quantity_steps")
                        + " price=" + row.get("price_ticks"));
    }

    private TraceSection tradingOutbox(String traceId, int limit, List<TraceWarning> warnings) {
        return outboxSection("TRADING_OUTBOX", "trading_outbox_events", traceId, limit, warnings);
    }

    private TraceSection accountOutbox(String traceId, int limit, List<TraceWarning> warnings) {
        return outboxSection("ACCOUNT_OUTBOX", "account_outbox_events", traceId, limit, warnings);
    }

    private TraceSection riskOutbox(String traceId, int limit, List<TraceWarning> warnings) {
        return outboxSection("RISK_OUTBOX", "risk_outbox_events", traceId, limit, warnings);
    }

    private TraceSection outboxSection(String source,
                                       String tableName,
                                       String traceId,
                                       int limit,
                                       List<TraceWarning> warnings) {
        String aggregateColumns = "risk_outbox_events".equals(tableName)
                ? "NULL::text AS aggregate_type, NULL::bigint AS aggregate_id,"
                : "aggregate_type, aggregate_id,";
        return querySection(source, tableName, warnings, """
                SELECT id, %s topic, event_key, event_type, payload::text AS payload,
                       published_at, attempts, next_attempt_at, last_error, created_at, updated_at
                  FROM %s
                 WHERE payload ->> 'traceId' = ?
                 ORDER BY created_at, id
                 LIMIT ?
                """.formatted(aggregateColumns, tableName), traceId, limit, "id", "created_at",
                row -> stringValue(row.get("topic")),
                row -> "outbox " + row.get("event_type") + " key=" + row.get("event_key")
                        + (row.get("published_at") == null ? " pending" : " published"));
    }

    private TraceSection adminOperations(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("GATEWAY_ADMIN_OPERATION", "gateway_admin_operation_logs", warnings, """
                SELECT operation_id, admin_user_id, admin_username, admin_roles, service, http_method,
                       request_path, query_string, target_uri, request_body_sha256, response_status,
                       success, error_message, trace_id, ip_address, created_at
                  FROM gateway_admin_operation_logs
                 WHERE trace_id = ?
                 ORDER BY created_at, operation_id
                 LIMIT ?
                """, traceId, limit, "operation_id", "created_at",
                row -> row.get("service") + ":" + row.get("http_method"),
                row -> row.get("http_method") + " " + row.get("request_path")
                        + " status=" + row.get("response_status") + " success=" + row.get("success"));
    }

    private TraceSection adminApprovals(String traceId, int limit, List<TraceWarning> warnings) {
        return querySection("GATEWAY_ADMIN_APPROVAL", "gateway_admin_approval_requests", warnings, """
                SELECT approval_id, requester_user_id, requester_username, approver_user_id, approver_username,
                       service, http_method, request_path, query_string, request_body_sha256, reason,
                       decision_reason, status, requested_at, expires_at, decided_at, consumed_at,
                       consumed_trace_id
                  FROM gateway_admin_approval_requests
                 WHERE consumed_trace_id = ?
                 ORDER BY COALESCE(consumed_at, decided_at, requested_at), approval_id
                 LIMIT ?
                """, traceId, limit, "approval_id", "consumed_at",
                row -> "approval:" + row.get("approval_id"),
                row -> "approval " + row.get("status") + " " + row.get("http_method") + " " + row.get("request_path"));
    }

    private TraceSection querySection(String source,
                                      String tableName,
                                      List<TraceWarning> warnings,
                                      String sql,
                                      String traceId,
                                      int limit,
                                      String idColumn,
                                      String eventTimeColumn,
                                      Function<Map<String, Object>, String> subject,
                                      Function<Map<String, Object>, String> summary) {
        try {
            List<TraceEvent> events = jdbcTemplate.queryForList(sql, traceId, limit).stream()
                    .map(row -> toTraceEvent(source, tableName, row, idColumn, eventTimeColumn, subject, summary))
                    .toList();
            return new TraceSection(source, tableName, events.size(), events, null);
        } catch (DataAccessException ex) {
            String message = ex.getMessage();
            warnings.add(new TraceWarning(source, tableName, message));
            return new TraceSection(source, tableName, 0, List.of(), message);
        }
    }

    private TraceEvent toTraceEvent(String source,
                                    String tableName,
                                    Map<String, Object> row,
                                    String idColumn,
                                    String eventTimeColumn,
                                    Function<Map<String, Object>, String> subject,
                                    Function<Map<String, Object>, String> summary) {
        Map<String, Object> data = sanitize(row);
        Object id = row.get(idColumn);
        return new TraceEvent(
                source,
                tableName,
                id == null ? "" : String.valueOf(id),
                instantValue(row.get(eventTimeColumn)),
                nullToEmpty(subject.apply(row)),
                nullToEmpty(summary.apply(row)),
                data);
    }

    private String normalizeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        String normalized = traceId.trim();
        if (!normalized.matches(TRACE_ID_PATTERN)) {
            throw new IllegalArgumentException("invalid traceId");
        }
        return normalized;
    }

    private Map<String, Object> sanitize(Map<String, Object> row) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        row.forEach((key, value) -> sanitized.put(key, sanitizeValue(value)));
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value;
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record TraceQueryResponse(
            Instant generatedAt,
            String traceId,
            int totalEvents,
            List<TraceSection> sections,
            List<TraceEvent> timeline,
            List<TraceWarning> warnings) {
    }

    public record TraceSection(
            String source,
            String tableName,
            int count,
            List<TraceEvent> events,
            String error) {
    }

    public record TraceEvent(
            String source,
            String tableName,
            String recordId,
            Instant eventTime,
            String subject,
            String summary,
            Map<String, Object> data) {
    }

    public record TraceWarning(
            String source,
            String tableName,
            String message) {
    }
}
