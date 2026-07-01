package com.surprising.liquidation.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.trading.api.model.OrderSide;
import java.sql.ResultSet;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiquidationOrderRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LiquidationSequenceRepository sequenceRepository;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void preemptsOpenReduceOnlyCloseOrdersBeforeLiquidationOrder() throws Exception {
        LiquidationOrderRepository repository = repository();
        when(jdbcTemplate.query(contains("FROM trading_orders"), any(RowMapper.class), eq(2002L),
                eq("BTC-USDT"), eq("CROSS"), eq(8L), eq("SELL"))).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return java.util.List.of(
                            mapper.mapRow(orderRow(101L, "reduce-101", "ACCEPTED"), 0),
                            mapper.mapRow(orderRow(102L, "reduce-102", "CANCEL_REQUESTED"), 1));
                });
        when(jdbcTemplate.update(contains("UPDATE trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7101L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7201L, 7202L);
        when(sequenceRepository.nextTradingSequence("outbox")).thenReturn(7301L, 7302L, 7303L);

        int preempted = repository.cancelOpenReduceOnlyCloseOrders(2002L, "BTC-USDT", 8L, OrderSide.SELL,
                Instant.parse("2026-07-01T00:00:00Z"), value -> "{}");

        assertThat(preempted).isEqualTo(2);
        verify(jdbcTemplate, times(1)).update(contains("UPDATE trading_orders"), any(Object[].class));
        verify(jdbcTemplate, times(1)).update(contains("INSERT INTO trading_order_events"), any(Object[].class));
        ArgumentCaptor<Object[]> outboxArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(3)).update(contains("INSERT INTO trading_outbox_events"), outboxArgs.capture());
        assertThat(outboxArgs.getAllValues())
                .extracting(args -> args[1] + ":" + args[5])
                .containsExactly("ORDER:CANCEL_REQUESTED", "ORDER:CANCEL", "ORDER:CANCEL");
    }

    @Test
    void failsWhenTradingOrderInsertReturnsNoRowAndDoesNotSuppressConflicts() {
        LiquidationOrderRepository repository = repository();
        givenFeeSnapshot();
        when(sequenceRepository.nextTradingSequence("order")).thenReturn(7001L);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7002L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7003L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_orders"), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.createReduceOnlyMarketOrder(9401L, 2002L, "BTC-USDT",
                8L, OrderSide.SELL, 3L, Instant.parse("2026-07-01T00:00:00Z"), Object::toString))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation trading order");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("ON CONFLICT");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_order_events"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_outbox_events"), any(Object[].class));
    }

    @Test
    void failsWhenOrderEventInsertIsSkippedByConflict() {
        LiquidationOrderRepository repository = repository();
        givenFeeSnapshot();
        when(sequenceRepository.nextTradingSequence("order")).thenReturn(7001L);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7002L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7003L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.createReduceOnlyMarketOrder(9401L, 2002L, "BTC-USDT",
                8L, OrderSide.SELL, 3L, Instant.parse("2026-07-01T00:00:00Z"), Object::toString))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation order event");

        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_outbox_events"), any(Object[].class));
    }

    @Test
    void failsWhenOutboxInsertIsSkipped() {
        LiquidationOrderRepository repository = repository();
        givenFeeSnapshot();
        when(sequenceRepository.nextTradingSequence("order")).thenReturn(7001L);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7002L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7003L);
        when(sequenceRepository.nextTradingSequence("outbox")).thenReturn(7004L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.createReduceOnlyMarketOrder(9401L, 2002L, "BTC-USDT",
                8L, OrderSide.SELL, 3L, Instant.parse("2026-07-01T00:00:00Z"), Object::toString))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation outbox enqueue");
    }

    @Test
    void markPublishedFailsWhenNoRowIsUpdated() {
        LiquidationOrderRepository repository = repository();
        when(jdbcTemplate.update(contains("SET published_at"), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markPublished(901L, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation outbox publish mark");
    }

    @Test
    void markFailedFailsWhenNoRowIsUpdated() {
        LiquidationOrderRepository repository = repository();
        when(jdbcTemplate.update(contains("SET attempts = attempts + 1"), any(), any(), any(), eq(901L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markFailed(901L, "kafka unavailable",
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation outbox failure mark");
    }

    private LiquidationOrderRepository repository() {
        return new LiquidationOrderRepository(jdbcTemplate, sequenceRepository, new LiquidationProperties());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenFeeSnapshot() {
        when(jdbcTemplate.query(contains("WITH instrument_fee"), any(RowMapper.class),
                eq("BTC-USDT"), eq(8L), eq("BTC-USDT"), eq(2002L), eq("BTC-USDT"), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getLong("maker_fee_rate_ppm")).thenReturn(200L);
                    when(rs.getLong("taker_fee_rate_ppm")).thenReturn(500L);
                    return java.util.List.of(mapper.mapRow(rs, 0));
                });
    }

    private ResultSet orderRow(long orderId, String clientOrderId, String status) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("order_id")).thenReturn(orderId);
        when(rs.getLong("user_id")).thenReturn(2002L);
        when(rs.getString("client_order_id")).thenReturn(clientOrderId);
        when(rs.getString("symbol")).thenReturn("BTC-USDT");
        when(rs.getLong("instrument_version")).thenReturn(8L);
        when(rs.getString("side")).thenReturn("SELL");
        when(rs.getString("order_type")).thenReturn("LIMIT");
        when(rs.getString("time_in_force")).thenReturn("GTC");
        when(rs.getLong("price_ticks")).thenReturn(88_000L);
        when(rs.getLong("quantity_steps")).thenReturn(3L);
        when(rs.getString("margin_mode")).thenReturn("CROSS");
        when(rs.getString("status")).thenReturn(status);
        when(rs.getBoolean("post_only")).thenReturn(false);
        return rs;
    }
}
