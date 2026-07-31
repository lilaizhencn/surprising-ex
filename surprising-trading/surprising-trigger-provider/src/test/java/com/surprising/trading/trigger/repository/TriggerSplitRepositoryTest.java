package com.surprising.trading.trigger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TriggerSplitRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void positionModeRepositoryOnlyReadsPositionModeTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("account_position_modes"), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq("OPTION"), eq(1001L))).thenReturn(List.of("HEDGE"));

        PositionMode result = new TriggerPositionModeRepository(jdbcTemplate)
                .positionMode(ProductLine.OPTION, 1001L);

        assertThat(result).isEqualTo(PositionMode.HEDGE);
    }

    @Test
    void positionRepositoryOnlyChecksPositionTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("account_positions"), eq(Boolean.class),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("CROSS")))
                .thenReturn(true);

        boolean conflict = new TriggerPositionRepository(jdbcTemplate).hasActiveMarginModeConflict(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS);

        assertThat(conflict).isTrue();
    }

    @Test
    void openOrderRepositoryOnlySumsTradingOrders() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("trading_orders"), eq(Long.class),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"),
                eq(11L), eq("SELL"))).thenReturn(7L);

        long steps = new TriggerOpenOrderRepository(jdbcTemplate).openReduceOnlySteps(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.LONG, 11L, OrderSide.SELL);

        assertThat(steps).isEqualTo(7L);
    }

    @Test
    void sequenceRepositoryOnlyUsesNativeSequence() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("nextval"), eq(Number.class),
                eq("public.trading_trigger_order_seq"))).thenReturn(501L);

        long sequence = new TriggerSequenceRepository(jdbcTemplate).nextSequence("trigger-order");

        assertThat(sequence).isEqualTo(501L);
        verify(jdbcTemplate).queryForObject(contains("nextval"), eq(Number.class),
                eq("public.trading_trigger_order_seq"));
    }
}
