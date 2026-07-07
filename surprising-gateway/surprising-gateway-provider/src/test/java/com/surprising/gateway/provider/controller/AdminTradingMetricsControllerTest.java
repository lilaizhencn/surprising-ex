package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminTradingMetricsControllerTest {

    @Test
    void metricsAggregatesTradingOperations() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        when(authService.requireAdminPermission("Bearer admin", "admin.trading.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        AdminTradingMetricsController controller = new AdminTradingMetricsController(authService, new FakeJdbcTemplate(now));

        var response = controller.metrics("Bearer admin", null, null, 1440, 10);

        assertThat(response.windowMinutes()).isEqualTo(1440);
        assertThat(response.orders().submitted()).isEqualTo(100);
        assertThat(response.orders().rejected()).isEqualTo(5);
        assertThat(response.orders().rejectRatePpm()).isEqualTo(50_000);
        assertThat(response.orders().openOrders()).isEqualTo(12);
        assertThat(response.orders().statuses()).extracting(AdminTradingMetricsController.OrderStatusMetric::status)
                .containsExactly("FILLED", "REJECTED");
        assertThat(response.trades().trades()).isEqualTo(80);
        assertThat(response.trades().notionalTicksSteps()).isEqualByComparingTo("1234567890");
        assertThat(response.trades().uniqueParticipants()).isEqualTo(44);
        assertThat(response.matching().rejectRatePpm()).isEqualTo(100_000);
        assertThat(response.triggerOrders().pending()).isEqualTo(9);
        assertThat(response.positions().openPositions()).isEqualTo(21);
        assertThat(response.positions().symbols()).hasSize(1);
        assertThat(response.symbols()).hasSize(1);
        assertThat(response.symbols().get(0).symbol()).isEqualTo("BTC-USDT");
        assertThat(response.warnings()).isEmpty();
        verify(authService).requireAdminPermission("Bearer admin", "admin.trading.read");
    }

    @Test
    void metricsFilterByProductLine() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        when(authService.requireAdminPermission("Bearer admin", "admin.trading.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(now);
        AdminTradingMetricsController controller = new AdminTradingMetricsController(authService, jdbcTemplate);

        controller.metrics("Bearer admin", null, "LINEAR_DELIVERY", 60, 5);

        assertThat(jdbcTemplate.sqls)
                .anySatisfy(sql -> assertThat(sql).contains("i.contract_type = ?"));
        assertThat(jdbcTemplate.arguments)
                .anySatisfy(args -> assertThat(args).contains("LINEAR_DELIVERY"));
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final Instant now;
        private final List<String> sqls = new ArrayList<>();
        private final List<List<Object>> arguments = new ArrayList<>();

        private FakeJdbcTemplate(Instant now) {
            this.now = now;
        }

        @Override
        public Map<String, Object> queryForMap(String sql) {
            return queryForMap(sql, new Object[0]);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            record(sql, args);
            if (sql.contains("unique_participants")) {
                return row("unique_participants", 44L);
            }
            if (sql.contains("FROM trading_orders")) {
                return row(
                        "submitted", 100L,
                        "unique_users", 38L,
                        "accepted", 20L,
                        "partially_filled", 10L,
                        "filled", 55L,
                        "cancel_requested", 2L,
                        "canceled", 8L,
                        "rejected", 5L,
                        "open_orders", 12L,
                        "open_quantity_steps", 4_200L,
                        "last_submitted_at", Timestamp.from(now.minusSeconds(60)),
                        "last_open_updated_at", Timestamp.from(now.minusSeconds(30)));
            }
            if (sql.contains("FROM trading_match_trades")) {
                return row(
                        "trades", 80L,
                        "volume_steps", 33_000L,
                        "notional_ticks_steps", new BigDecimal("1234567890"),
                        "taker_users", 31L,
                        "maker_users", 27L,
                        "last_trade_at", Timestamp.from(now.minusSeconds(20)));
            }
            if (sql.contains("FROM trading_match_results")) {
                return row(
                        "commands", 120L,
                        "successful_commands", 108L,
                        "rejected_commands", 12L,
                        "place_commands", 95L,
                        "cancel_commands", 25L,
                        "last_result_at", Timestamp.from(now.minusSeconds(10)));
            }
            if (sql.contains("FROM trading_trigger_orders")) {
                return row(
                        "created", 30L,
                        "pending", 9L,
                        "triggering", 1L,
                        "triggered", 12L,
                        "failed", 2L,
                        "expired", 3L,
                        "canceled", 4L,
                        "expired_pending", 1L,
                        "last_updated_at", Timestamp.from(now.minusSeconds(5)));
            }
            if (sql.contains("FROM account_positions")) {
                return row(
                        "open_positions", 21L,
                        "users_with_positions", 18L,
                        "long_positions", 11L,
                        "short_positions", 10L,
                        "long_quantity_steps", 5_500L,
                        "short_quantity_steps", 4_900L,
                        "last_position_updated_at", Timestamp.from(now.minusSeconds(15)));
            }
            throw new IllegalArgumentException(sql);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            return queryForList(sql, new Object[0]);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            record(sql, args);
            if (sql.contains("GROUP BY status")) {
                return List.of(
                        row("status", "FILLED", "total", 55L),
                        row("status", "REJECTED", "total", 5L));
            }
            if (sql.contains("WITH order_stats")) {
                return List.of(row(
                        "symbol", "BTC-USDT",
                        "submitted_orders", 70L,
                        "order_users", 25L,
                        "rejected_orders", 3L,
                        "open_orders", 8L,
                        "open_order_quantity_steps", 2_000L,
                        "trades", 50L,
                        "volume_steps", 21_000L,
                        "notional_ticks_steps", new BigDecimal("987654321"),
                        "last_trade_at", Timestamp.from(now.minusSeconds(20)),
                        "last_trade_price_ticks", 62_000_000L,
                        "open_interest_long_steps", 12_000L,
                        "open_interest_short_steps", 11_000L,
                        "open_interest_steps", 12_000L));
            }
            if (sql.contains("FROM account_positions") && sql.contains("GROUP BY symbol")) {
                return List.of(row(
                        "symbol", "BTC-USDT",
                        "open_positions", 12L,
                        "users", 10L,
                        "long_positions", 7L,
                        "short_positions", 5L,
                        "long_quantity_steps", 3_000L,
                        "short_quantity_steps", 2_500L,
                        "last_updated_at", Timestamp.from(now.minusSeconds(15))));
            }
            throw new IllegalArgumentException(sql);
        }

        private void record(String sql, Object... args) {
            sqls.add(sql);
            arguments.add(Arrays.asList(args));
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
