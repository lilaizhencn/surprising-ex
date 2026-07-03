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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminMarketHealthControllerTest {

    @Test
    void healthAggregatesSymbolAndSourceState() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.now();
        when(authService.requireAdminPermission("Bearer admin", "admin.market.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        AdminMarketHealthController controller = new AdminMarketHealthController(authService, new FakeJdbcTemplate(now));

        var response = controller.health("Bearer admin", "btc-usdt", "1m", 120, 20);

        assertThat(response.period()).isEqualTo("1m");
        assertThat(response.summary().totalSymbols()).isEqualTo(1);
        assertThat(response.summary().sourceIssueSymbols()).isEqualTo(1);
        assertThat(response.summary().maxMarkIndexDeviationPpm()).isEqualTo(10_000);
        assertThat(response.symbols()).hasSize(1);
        assertThat(response.symbols().get(0).symbol()).isEqualTo("BTC-USDT");
        assertThat(response.symbols().get(0).markIndexDeviationPpm()).isEqualTo(10_000);
        assertThat(response.symbols().get(0).unhealthySources()).isEqualTo(1);
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources()).extracting(AdminMarketHealthController.MarketSourceHealth::status)
                .containsExactly("ERROR", "HEALTHY");
        assertThat(response.warnings()).isEmpty();
        verify(authService).requireAdminPermission("Bearer admin", "admin.market.read");
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final Instant now;

        private FakeJdbcTemplate(Instant now) {
            this.now = now;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("current_instruments")) {
                return List.of(row(
                        "symbol", "BTC-USDT",
                        "instrument_status", "TRADING",
                        "instrument_type", "PERPETUAL",
                        "contract_type", "LINEAR_PERPETUAL",
                        "index_price", new BigDecimal("100"),
                        "index_status", "HEALTHY",
                        "component_count", 3,
                        "valid_component_count", 2,
                        "index_event_time", Timestamp.from(now.minusSeconds(10)),
                        "mark_price", new BigDecimal("101"),
                        "mark_index_price", new BigDecimal("100"),
                        "mark_price_units", 10_100L,
                        "funding_rate", new BigDecimal("0.0001"),
                        "mark_status", "HEALTHY",
                        "mark_event_time", Timestamp.from(now.minusSeconds(8)),
                        "candle_period", "1m",
                        "candle_status", "CLOSED",
                        "candle_close_time", Timestamp.from(now.minusSeconds(60)),
                        "candle_updated_at", Timestamp.from(now.minusSeconds(5)),
                        "candle_trade_count", 42L,
                        "source_count", 3L,
                        "healthy_sources", 2L,
                        "unhealthy_sources", 1L,
                        "max_source_latency_millis", 250L,
                        "avg_source_latency_millis", 120L,
                        "source_statuses", "BINANCE:HEALTHY,COINBASE:ERROR"));
            }
            if (sql.contains("price_index_components")) {
                return List.of(
                        row(
                                "symbol", "BTC-USDT",
                                "source", "COINBASE",
                                "source_symbol", "BTC-USD",
                                "status", "ERROR",
                                "reason", "timeout",
                                "price", null,
                                "bid_price", null,
                                "ask_price", null,
                                "configured_weight", new BigDecimal("1.0"),
                                "effective_weight", new BigDecimal("0"),
                                "source_time", Timestamp.from(now.minusSeconds(20)),
                                "received_at", Timestamp.from(now.minusSeconds(18)),
                                "latency_millis", 250L),
                        row(
                                "symbol", "BTC-USDT",
                                "source", "BINANCE",
                                "source_symbol", "BTCUSDT",
                                "status", "HEALTHY",
                                "reason", null,
                                "price", new BigDecimal("100"),
                                "bid_price", new BigDecimal("99.9"),
                                "ask_price", new BigDecimal("100.1"),
                                "configured_weight", new BigDecimal("1.0"),
                                "effective_weight", new BigDecimal("1.0"),
                                "source_time", Timestamp.from(now.minusSeconds(12)),
                                "received_at", Timestamp.from(now.minusSeconds(11)),
                                "latency_millis", 30L));
            }
            throw new IllegalArgumentException(sql);
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
