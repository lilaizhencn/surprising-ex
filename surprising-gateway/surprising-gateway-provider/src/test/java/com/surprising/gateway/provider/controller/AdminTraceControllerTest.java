package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminTraceControllerTest {

    @Test
    void traceAggregatesTimelineAcrossTradingOutboxAndAdminSources() {
        AuthService authService = mock(AuthService.class);
        when(authService.requireAdminPermission("Bearer admin", "admin.traces.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                        Instant.parse("2026-07-03T00:00:00Z")));
        AdminTraceController controller = new AdminTraceController(authService, new FakeJdbcTemplate());

        var response = controller.trace("Bearer admin", "trace-abc.123", 50);

        assertThat(response.traceId()).isEqualTo("trace-abc.123");
        assertThat(response.totalEvents()).isEqualTo(4);
        assertThat(response.sections()).hasSize(9);
        assertThat(response.timeline()).extracting(AdminTraceController.TraceEvent::source)
                .containsExactly(
                        "GATEWAY_ADMIN_OPERATION",
                        "TRADING_ORDER_EVENT",
                        "TRADING_MATCH_RESULT",
                        "ACCOUNT_OUTBOX");
        assertThat(response.timeline().get(1).subject()).isEqualTo("order:101");
        assertThat(response.timeline().get(3).summary()).contains("POSITION_UPDATED");
        assertThat(response.warnings()).isEmpty();
        verify(authService).requireAdminPermission("Bearer admin", "admin.traces.read");
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("trading_order_events")) {
                Map<String, Object> row = row("event_id", 11L,
                        "order_id", 101L,
                        "user_id", 1001L,
                        "symbol", "BTC-USDT",
                        "event_type", "ACCEPTED",
                        "status", "ACCEPTED",
                        "trace_id", args[0],
                        "event_time", Timestamp.from(Instant.parse("2026-07-03T00:00:02Z")),
                        "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:02Z")));
                return List.of(row);
            }
            if (sql.contains("trading_match_results")) {
                return List.of(row("command_id", 21L,
                        "order_id", 101L,
                        "user_id", 1001L,
                        "symbol", "BTC-USDT",
                        "command_type", "PLACE",
                        "result_code", "SUCCESS",
                        "filled_quantity_steps", 5L,
                        "order_status", "PARTIALLY_FILLED",
                        "trace_id", args[0],
                        "event_time", Timestamp.from(Instant.parse("2026-07-03T00:00:03Z")),
                        "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:03Z"))));
            }
            if (sql.contains("account_outbox_events")) {
                return List.of(row("id", 31L,
                        "aggregate_type", "POSITION",
                        "aggregate_id", 101L,
                        "topic", "surprising.account.position.events.v1",
                        "event_key", "BTC-USDT",
                        "event_type", "POSITION_UPDATED",
                        "payload", "{\"traceId\":\"" + args[0] + "\"}",
                        "published_at", Timestamp.from(Instant.parse("2026-07-03T00:00:04Z")),
                        "attempts", 1,
                        "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:04Z")),
                        "updated_at", Timestamp.from(Instant.parse("2026-07-03T00:00:04Z"))));
            }
            if (sql.contains("gateway_admin_operation_logs")) {
                return List.of(row("operation_id", 41L,
                        "admin_user_id", 7L,
                        "admin_username", "admin",
                        "service", "trading-orders",
                        "http_method", "POST",
                        "request_path", "/api/v1/admin/gateway/trading-orders/101/cancel",
                        "response_status", 200,
                        "success", true,
                        "trace_id", args[0],
                        "created_at", Timestamp.from(Instant.parse("2026-07-03T00:00:01Z"))));
            }
            return List.of();
        }

        private Map<String, Object> row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i += 2) {
                row.put(String.valueOf(values[i]), values[i + 1]);
            }
            return row;
        }
    }
}
