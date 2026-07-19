package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class MatchingProtectionRepositoryTest {

    @Test
    void leavesProtectionQueriesUnfilteredForLegacyTopics() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository = new MatchingProtectionRepository(jdbcTemplate);

        repository.wouldSelfTrade(1001L, "BTC-USDT", 1L, OrderSide.BUY, 65_000L);

        assertThat(jdbcTemplate.sql).doesNotContain("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(8);
    }

    @Test
    void filtersSelfTradeProtectionByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.OPTION));

        repository.wouldSelfTrade(1001L, "BTC-USDT-260626-C-65000", 1L, OrderSide.BUY, 65_000L);

        assertThat(jdbcTemplate.sql).contains("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(9);
        assertThat(jdbcTemplate.args[4]).isEqualTo("OPTION");
    }

    @Test
    void filtersVersionProtectionByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.LINEAR_DELIVERY));

        repository.hasOpenOrdersWithDifferentInstrumentVersion("BTC-USDT-260626", 2L, 101L);

        assertThat(jdbcTemplate.sql).contains("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(4);
        assertThat(jdbcTemplate.args[3]).isEqualTo("LINEAR_DELIVERY");
    }

    @Test
    void batchesSelfTradeProtectionIntoOneQuery() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.LINEAR_PERPETUAL));

        repository.commandsThatWouldSelfTrade(List.of(command(101L, 1001L, OrderSide.BUY),
                command(102L, 1002L, OrderSide.SELL)));

        assertThat(jdbcTemplate.sql)
                .contains("WITH input(command_id, user_id, symbol, instrument_version, side, effective_price_ticks)")
                .contains("orders.product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(13);
        assertThat(jdbcTemplate.args[12]).isEqualTo("LINEAR_PERPETUAL");
    }

    @Test
    void batchesInstrumentVersionProtectionIntoOneQuery() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.LINEAR_PERPETUAL));

        repository.commandsWithOpenOrdersAtDifferentInstrumentVersion(List.of(
                command(101L, 1001L, OrderSide.BUY), command(102L, 1002L, OrderSide.SELL)));

        assertThat(jdbcTemplate.sql)
                .contains("WITH input(command_id, symbol, instrument_version, order_id)")
                .contains("orders.product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(9);
        assertThat(jdbcTemplate.args[8]).isEqualTo("LINEAR_PERPETUAL");
    }

    private OrderCommandEvent command(long commandId, long userId, OrderSide side) {
        return new OrderCommandEvent(OrderCommandType.PLACE, commandId, commandId + 1000, userId,
                "client-" + commandId, "BTC-USDT", 1L, side, OrderType.LIMIT, TimeInForce.GTC,
                65_000L, 1L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private MatchingProperties properties(ProductLine productLine) {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(productLine);
        return properties;
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
            if (requiredType == Boolean.class) {
                return requiredType.cast(false);
            }
            throw new UnsupportedOperationException("unsupported required type " + requiredType);
        }

        @Override
        public void query(String sql, RowCallbackHandler rowCallbackHandler, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
        }
    }
}
