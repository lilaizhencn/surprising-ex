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

class AdminSystemMetricsControllerTest {

    @Test
    void metricsAggregatesOutboxAdminOperationsLoginsAndApprovals() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        when(authService.requireAdminPermission("Bearer admin", "admin.system.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(now.minusSeconds(120));
        AdminSystemMetricsController controller = new AdminSystemMetricsController(authService, jdbcTemplate);

        var response = controller.metrics("Bearer admin", 60);

        assertThat(response.windowMinutes()).isEqualTo(60);
        assertThat(response.outbox().totalPending()).isEqualTo(5);
        assertThat(response.outbox().totalFailed()).isEqualTo(2);
        assertThat(response.outbox().maxAttempts()).isEqualTo(4);
        assertThat(response.outbox().modules()).hasSize(3);
        assertThat(response.adminOperations().total()).isEqualTo(10);
        assertThat(response.adminOperations().failed()).isEqualTo(2);
        assertThat(response.adminOperations().failureRatePpm()).isEqualTo(200_000);
        assertThat(response.adminOperations().p50DurationMs()).isEqualTo(75);
        assertThat(response.adminOperations().p95DurationMs()).isEqualTo(180);
        assertThat(response.adminOperations().p99DurationMs()).isEqualTo(240);
        assertThat(response.adminOperations().services().get(0).service()).isEqualTo("account");
        assertThat(response.adminOperations().services().get(0).failureRatePpm()).isEqualTo(250_000);
        assertThat(response.adminOperations().services().get(0).p95DurationMs()).isEqualTo(210);
        assertThat(response.logins().failed()).isEqualTo(3);
        assertThat(response.approvals().pending()).isEqualTo(5);
        assertThat(response.warnings()).isEmpty();
        verify(authService).requireAdminPermission("Bearer admin", "admin.system.read");
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final Instant oldestPendingAt;

        private FakeJdbcTemplate(Instant oldestPendingAt) {
            this.oldestPendingAt = oldestPendingAt;
        }

        @Override
        public Map<String, Object> queryForMap(String sql) {
            return queryForMap(sql, new Object[0]);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (sql.contains("trading_outbox_events")) {
                return outbox(20, 2, 1, 4);
            }
            if (sql.contains("account_outbox_events")) {
                return outbox(30, 3, 1, 3);
            }
            if (sql.contains("risk_outbox_events")) {
                return outbox(0, 0, 0, 0);
            }
            if (sql.contains("gateway_admin_operation_logs")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("total", 10L);
                row.put("failed", 2L);
                row.put("writes", 4L);
                row.put("unique_admins", 3L);
                row.put("p50_duration_ms", 75L);
                row.put("p95_duration_ms", 180L);
                row.put("p99_duration_ms", 240L);
                return row;
            }
            if (sql.contains("gateway_login_logs")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("total", 20L);
                row.put("failed", 3L);
                return row;
            }
            if (sql.contains("gateway_admin_approval_requests")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pending", 5L);
                row.put("expired_pending", 1L);
                row.put("approved_not_consumed", 2L);
                return row;
            }
            throw new IllegalArgumentException(sql);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", "account");
            row.put("total", 8L);
            row.put("failed", 2L);
            row.put("p50_duration_ms", 80L);
            row.put("p95_duration_ms", 210L);
            row.put("p99_duration_ms", 260L);
            row.put("last_seen_at", Timestamp.from(Instant.parse("2026-07-03T00:00:00Z")));
            return List.of(row);
        }

        private Map<String, Object> outbox(long total, long pending, long failed, long maxAttempts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("total", total);
            row.put("pending", pending);
            row.put("failed", failed);
            row.put("max_attempts", maxAttempts);
            row.put("oldest_pending_at", pending == 0 ? null : Timestamp.from(oldestPendingAt));
            row.put("last_error", failed == 0 ? null : "broker unavailable");
            return row;
        }
    }
}
