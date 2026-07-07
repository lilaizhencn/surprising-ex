package com.surprising.trading.trigger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.api.model.TriggerOrderStatus;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TriggerOrderRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void markPriceTicksUsesPersistedMarkUnitsAndCurrentTickSize() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("price_mark_ticks"), any(RowMapper.class), eq("BTC-USDT"), eq(42L)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("mark_ticks")).thenReturn(65_001L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        OptionalLong markTicks = repository.markPriceTicks("BTC-USDT", 42L);

        assertThat(markTicks).hasValue(65_001L);
        verify(jdbcTemplate).query(contains("i.price_tick_units"), any(RowMapper.class),
                eq("BTC-USDT"), eq(42L));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void indexPriceTicksUsesDecimalPriceAndCurrentTickSize() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("price_index_ticks"), any(RowMapper.class), eq("BTC-USDT"), eq(77L)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("index_ticks")).thenReturn(650_001L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        OptionalLong indexTicks = repository.indexPriceTicks("BTC-USDT", 77L);

        assertThat(indexTicks).hasValue(650_001L);
        verify(jdbcTemplate).query(contains("p.index_price * qs.scale_units"), any(RowMapper.class),
                eq("BTC-USDT"), eq(77L));
    }

    @Test
    void hasPendingOrdersForPriceTypeChecksSymbolStatusAndTriggerSource() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("trading_trigger_orders"), eq(Boolean.class),
                eq("ETH-USDT"), eq("INDEX_PRICE"))).thenReturn(true);

        boolean result = repository.hasPendingOrdersForPriceType("ETH-USDT", TriggerPriceType.INDEX_PRICE);

        assertThat(result).isTrue();
        verify(jdbcTemplate).queryForObject(contains("status = 'PENDING'"), eq(Boolean.class),
                eq("ETH-USDT"), eq("INDEX_PRICE"));
    }

    @Test
    void hasPendingOrdersForPriceTypeFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class),
                eq("ETH-USDT"), eq("INDEX_PRICE"), eq("LINEAR_DELIVERY"))).thenReturn(true);

        boolean result = repository.hasPendingOrdersForPriceType("ETH-USDT", TriggerPriceType.INDEX_PRICE,
                "LINEAR_DELIVERY");

        assertThat(result).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class),
                eq("ETH-USDT"), eq("INDEX_PRICE"), eq("LINEAR_DELIVERY"));
        assertThat(sql.getValue())
                .contains("o.product_line = ?");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findByClientTriggerOrderIdScopesByProductLine() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findByClientTriggerOrderId(ProductLine.INVERSE_DELIVERY, 1001L, "trigger-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("WHERE product_line = ?")
                .contains("AND user_id = ?")
                .contains("AND client_trigger_order_id = ?");
        assertThat(args.getValue()).containsExactly("INVERSE_DELIVERY", 1001L, "trigger-1");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimTriggeredUsesRowLocksAndSymbolPriceCondition() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class),
                eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any(), any())).thenReturn(List.of());

        var rows = repository.claimTriggered("BTC-USDT", TriggerPriceType.MARK_PRICE, 70_000L, 9L,
                Instant.parse("2026-07-01T00:00:00Z"), 100, Instant.parse("2026-07-01T00:00:01Z"));

        assertThat(rows).isEmpty();
        verify(jdbcTemplate).query(contains("trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')"), any(RowMapper.class),
                eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any(), any());
        verify(jdbcTemplate).query(contains("trigger_condition = 'GREATER_OR_EQUAL'"), any(RowMapper.class),
                eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any(), any());
        verify(jdbcTemplate).query(contains("canceled_oco"), any(RowMapper.class),
                eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any(), any());
        verify(jdbcTemplate).query(contains("PARTITION BY claim_group_key"), any(RowMapper.class),
                eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimTriggeredFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var rows = repository.claimTriggered("BTC-USDT", TriggerPriceType.MARK_PRICE, 70_000L, 9L,
                Instant.parse("2026-07-01T00:00:00Z"), 100,
                Instant.parse("2026-07-01T00:00:01Z"), "LINEAR_DELIVERY");

        assertThat(rows).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')")
                .contains("AND product_line = ?");
        assertThat(args.getValue()).contains("LINEAR_DELIVERY");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimTrailingTriggeredTracksExtremaAndUsesRowLocks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("trigger_type = 'TRAILING_STOP'"), any(RowMapper.class),
                eq(60_100L), eq(60_100L), eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(100),
                eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L),
                any(), any(), eq(52L), eq(60_100L), any(), any(), any(), any())).thenReturn(List.of());

        var rows = repository.claimTrailingTriggered("BTC-USDT", TriggerPriceType.MARK_PRICE, 60_100L, 52L,
                Instant.parse("2026-07-01T00:00:00Z"), 100, Instant.parse("2026-07-01T00:00:01Z"));

        assertThat(rows).isEmpty();
        verify(jdbcTemplate).query(contains("highest_price_ticks"), any(RowMapper.class),
                eq(60_100L), eq(60_100L), eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(100),
                eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L),
                any(), any(), eq(52L), eq(60_100L), any(), any(), any(), any());
        verify(jdbcTemplate).query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class),
                eq(60_100L), eq(60_100L), eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(100),
                eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L),
                any(), any(), eq(52L), eq(60_100L), any(), any(), any(), any());
        verify(jdbcTemplate).query(contains("PARTITION BY claim_group_key"), any(RowMapper.class),
                eq(60_100L), eq(60_100L), eq("BTC-USDT"), eq("MARK_PRICE"), any(), any(), eq(100),
                eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L), eq(60_100L),
                any(), any(), eq(52L), eq(60_100L), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimTrailingTriggeredFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var rows = repository.claimTrailingTriggered("BTC-USDT", TriggerPriceType.MARK_PRICE, 60_100L, 52L,
                Instant.parse("2026-07-01T00:00:00Z"), 100,
                Instant.parse("2026-07-01T00:00:01Z"), "LINEAR_DELIVERY");

        assertThat(rows).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("trigger_type = 'TRAILING_STOP'")
                .contains("AND product_line = ?");
        assertThat(args.getValue()).contains("LINEAR_DELIVERY");
    }

    @Test
    void expirePendingFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        repository.expirePending(now, 100, "LINEAR_DELIVERY");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("o.status = 'PENDING'")
                .contains("o.product_line = ?");
        assertThat(args.getValue()).contains("LINEAR_DELIVERY");
    }

    @Test
    void resetStaleTriggeringFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        repository.resetStaleTriggering(now.minusSeconds(30), now, 100, "LINEAR_DELIVERY");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("o.status = 'TRIGGERING'")
                .contains("o.product_line = ?");
        assertThat(args.getValue()).contains("LINEAR_DELIVERY");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockUserSymbolMarginScopeUsesSharedAdvisoryLockKey() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);

        repository.lockUserSymbolMarginScope(1001L, "BTC-USDT");

        verify(jdbcTemplate).query(contains("pg_advisory_xact_lock"),
                any(ResultSetExtractor.class), eq("LINEAR_PERPETUAL:1001:BTC-USDT"));
    }

    @Test
    void activeMarginModeConflictChecksPositionsOrdersAndTriggers() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("account_positions"), eq(Boolean.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS")))
                .thenReturn(true);

        boolean conflict = repository.hasActiveMarginModeConflict(1001L, "BTC-USDT", MarginMode.CROSS);

        assertThat(conflict).isTrue();
        verify(jdbcTemplate).queryForObject(contains("trading_trigger_orders"), eq(Boolean.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"));
    }

    @Test
    void triggerOrderMatchesContractTypeUsesOrderProductLine() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(), any()))
                .thenReturn(true);

        boolean matched = repository.triggerOrderMatchesContractType(501L, "COIN_PERPETUAL");

        assertThat(matched).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> productLine = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(Class.class), eq(501L), productLine.capture());
        assertThat(sql.getValue())
                .contains("FROM trading_trigger_orders o")
                .contains("o.product_line = ?");
        assertThat(productLine.getValue()).isEqualTo("INVERSE_PERPETUAL");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void openOrdersFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.openOrders(1001L, "BTC-USDT", 50, "linear-delivery");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "LINEAR_DELIVERY", "LINEAR_DELIVERY", 50);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pendingCancelableOrdersFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.pendingCancelableOrders(1001L, "BTC-USDT", 25, "VANILLA_OPTION");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("status = 'PENDING'");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "OPTION", "OPTION", 25);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adminOrderPageFiltersByContractTypeWhenProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.adminOrderPage(1001L, "BTC-USDT", TriggerOrderStatus.PENDING, 501L, 25,
                "LINEAR_DELIVERY", null, "createdAt.asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("product_line = ?");
    }

    @Test
    void pendingTriggerCloseStepsCountsOcoGroupByMaximumQuantity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("MAX(quantity_steps)"), eq(Long.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"), eq("SELL")))
                .thenReturn(6L);

        long steps = repository.pendingTriggerCloseSteps(1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.LONG, OrderSide.SELL);

        assertThat(steps).isEqualTo(6L);
        verify(jdbcTemplate).queryForObject(contains("GROUP BY capacity_group"), eq(Long.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"), eq("SELL"));
    }

    @Test
    void pendingTriggerOcoGroupMaxStepsReadsOnlySameCloseBucket() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("oco_group_id = ?"), eq(Long.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"), eq("SELL"), eq("oco-1")))
                .thenReturn(4L);

        long steps = repository.pendingTriggerOcoGroupMaxSteps(1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.LONG, OrderSide.SELL, "oco-1");

        assertThat(steps).isEqualTo(4L);
        verify(jdbcTemplate).queryForObject(contains("status IN ('PENDING', 'TRIGGERING')"), eq(Long.class),
                eq("LINEAR_PERPETUAL"),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"), eq("SELL"), eq("oco-1"));
    }
}
