package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatchingResultRepositoryTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void cancelSuccessReleasesMarginBeforeClearingRemainingQuantity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository marginRepository = mock(MatchingMarginRepository.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate, marginRepository);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        repository.applyActiveOrderStatus(new MatchResultEvent(11L, 101L, 1001L, "BTC-USDT", 1L,
                OrderCommandType.CANCEL, "SUCCESS", 0L, OrderStatus.CANCELED, EVENT_TIME, List.of()));

        InOrder order = inOrder(jdbcTemplate, marginRepository);
        order.verify(jdbcTemplate).update(contains("SET status = ?"), any(Object[].class));
        order.verify(marginRepository).releaseUnused(eq(101L), eq("ORDER_CANCELED"), eq(EVENT_TIME));
        order.verify(jdbcTemplate).update(contains("remaining_quantity_steps = 0"), any(Object[].class));
    }

    @Test
    void terminalImmediateOrderReleasesUnfilledMarginBeforeClearingRemainingQuantity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository marginRepository = mock(MatchingMarginRepository.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate, marginRepository);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        repository.applyActiveOrderStatus(new MatchResultEvent(12L, 102L, 1002L, "BTC-USDT", 1L,
                OrderCommandType.PLACE, "SUCCESS", 3L, OrderStatus.CANCELED, EVENT_TIME, List.of()));

        InOrder order = inOrder(jdbcTemplate, marginRepository);
        order.verify(jdbcTemplate).update(contains("executed_quantity_steps"), any(Object[].class));
        order.verify(marginRepository).releaseUnused(eq(102L), eq("ORDER_TERMINAL"), eq(EVENT_TIME));
        order.verify(jdbcTemplate).update(contains("remaining_quantity_steps = 0"), any(Object[].class));
    }

    @Test
    void matchResultInsertConflictReturnsFalseForReplayIdempotency() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate, mock(MatchingMarginRepository.class));
        when(jdbcTemplate.update(contains("INSERT INTO trading_match_results"), any(Object[].class)))
                .thenReturn(0);

        assertThat(repository.saveResult(new MatchResultEvent(11L, 101L, 1001L,
                "BTC-USDT", 1L, OrderCommandType.PLACE, "SUCCESS", 0L, OrderStatus.ACCEPTED,
                EVENT_TIME, List.of()))).isFalse();
    }

    @Test
    void matchTradeIdempotencyIsScopedBySymbol() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate,
                mock(MatchingMarginRepository.class));
        when(jdbcTemplate.update(contains("INSERT INTO trading_match_trades"), any(Object[].class)))
                .thenReturn(1);

        assertThat(repository.saveTrade(new MatchTradeEvent(91L, 11L, "BTC-USDT",
                101L, 1L, 1001L, OrderSide.BUY, 202L, 1L, 2002L, 65_000L, 3L,
                false, false, EVENT_TIME))).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("ON CONFLICT (symbol, trade_id) DO NOTHING");
    }

    @Test
    void matchTradeInsertConflictReturnsFalseForReplayIdempotency() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate, mock(MatchingMarginRepository.class));
        when(jdbcTemplate.update(contains("INSERT INTO trading_match_trades"), any(Object[].class)))
                .thenReturn(0);

        assertThat(repository.saveTrade(new MatchTradeEvent(91L, 11L, "BTC-USDT",
                101L, 1L, 1001L, OrderSide.BUY, 202L, 1L, 2002L, 65_000L, 3L,
                false, false, EVENT_TIME))).isFalse();
    }

    @Test
    void orderFillUpdateIsGuardedInsteadOfClamped() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate,
                mock(MatchingMarginRepository.class));
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        repository.applyMakerFill(new MatchTradeEvent(91L, 11L, "BTC-USDT",
                101L, 1L, 1001L, OrderSide.BUY, 202L, 1L, 2002L, 65_000L, 3L,
                false, false, EVENT_TIME));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(3L), eq(3L), eq(OrderStatus.PARTIALLY_FILLED.name()),
                any(Timestamp.class), eq(202L), eq(3L));
        assertThat(sql.getValue())
                .contains("remaining_quantity_steps >= ?")
                .contains("quantity_steps = executed_quantity_steps + remaining_quantity_steps")
                .contains("status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')")
                .doesNotContain("LEAST")
                .doesNotContain("GREATEST");
    }

    @Test
    void failsWhenOrderFillGuardRejectsUpdate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate,
                mock(MatchingMarginRepository.class));
        when(jdbcTemplate.update(contains("remaining_quantity_steps >= ?"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.applyMakerFill(new MatchTradeEvent(91L, 11L, "BTC-USDT",
                101L, 1L, 1001L, OrderSide.BUY, 202L, 1L, 2002L, 65_000L, 3L,
                false, false, EVENT_TIME)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order fill update");
    }

    @Test
    void matchingMarginReleaseFailsInsteadOfCreditingAvailableWhenLockedBalanceIsInsufficient() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_balances"), eq(100L), eq(100L), any(Timestamp.class),
                eq(1001L), eq("USDT"), eq(100L))).thenReturn(0);

        assertThatThrownBy(() -> repository.releaseAll(101L, "ORDER_REJECTED", EVENT_TIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient locked balance");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(100L), eq(100L), any(Timestamp.class),
                eq(1001L), eq("USDT"), eq(100L));
        assertThat(sql.getValue()).contains("locked_units >= ?")
                .doesNotContain("GREATEST");
    }

    @Test
    void coinPerpetualMarginReleaseCreditsProductAccountBalance() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("account_type")).thenReturn("COIN_PERPETUAL");
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("BTC");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getLong("released_units")).thenReturn(20L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), eq(80L), eq(80L),
                any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(80L))).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);

        repository.releaseAll(101L, "ORDER_REJECTED", EVENT_TIME);

        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"), eq(80L), eq(80L),
                any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(80L));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void missingMarginReservationFailsForNonReduceOnlyOrders() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_spot_order_reservations"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("SELECT reduce_only"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.releaseAll(101L, "ORDER_REJECTED", EVENT_TIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing margin reservation for non-reduce-only order 101");
    }

    @Test
    void missingMarginReservationIsAllowedForReduceOnlyOrders() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_spot_order_reservations"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("SELECT reduce_only"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("reduce_only")).thenReturn(true);
                    return List.of(mapper.mapRow(rs, 0));
                });

        repository.releaseUnused(101L, "ORDER_TERMINAL", EVENT_TIME);

        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void missingMarginReservationReleasesSpotReservationInsteadOfFailing() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_spot_order_reservations"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(1_000L);
                    when(rs.getLong("settled_units")).thenReturn(0L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), eq(1_000L), eq(1_000L),
                any(Timestamp.class), eq(1001L), eq("USDT"), eq(1_000L))).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_spot_order_reservations"), any(Object[].class)))
                .thenReturn(1);

        repository.releaseAll(101L, "ORDER_REJECTED", EVENT_TIME);

        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"), eq(1_000L), eq(1_000L),
                any(Timestamp.class), eq(1001L), eq("USDT"), eq(1_000L));
        verify(jdbcTemplate, never()).query(contains("SELECT reduce_only"), anyRowMapper(), eq(101L));
    }

    @Test
    void marginReleaseFailsWhenOrderRowIsMissingAfterReservationLock() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MatchingMarginRepository repository = new MatchingMarginRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("SELECT quantity_steps, remaining_quantity_steps"), anyRowMapper(), eq(101L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> repository.releaseUnused(101L, "ORDER_TERMINAL", EVENT_TIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order not found for matching margin release 101");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
