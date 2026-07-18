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
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
                eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(8L), eq("SELL")))
                .thenAnswer(invocation -> {
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
        ArgumentCaptor<String> orderUpdate = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(orderUpdate.capture(), eq("LIQUIDATION_PREEMPTED_REDUCE_ONLY"),
                any(java.sql.Timestamp.class), eq(101L));
        assertThat(orderUpdate.getValue()).contains("revision = revision + 1");
        verify(jdbcTemplate, times(1)).update(contains("INSERT INTO trading_order_events"), any(Object[].class));
        ArgumentCaptor<Object[]> outboxArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(3)).update(contains("INSERT INTO trading_outbox_events"), outboxArgs.capture());
        assertThat(outboxArgs.getAllValues())
                .extracting(args -> args[1] + ":" + args[5])
                .containsExactly("ORDER:CANCEL_REQUESTED", "ORDER:CANCEL", "ORDER:CANCEL");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void preemptOpenReduceOnlyCloseOrdersFiltersByCurrentProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationOrderRepository repository = new LiquidationOrderRepository(jdbcTemplate, sequenceRepository,
                properties);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(2002L),
                eq("LINEAR_DELIVERY"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(8L), eq("SELL")))
                .thenReturn(java.util.List.of());

        int preempted = repository.cancelOpenReduceOnlyCloseOrders(2002L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET, 8L, OrderSide.SELL, Instant.parse("2026-07-01T00:00:00Z"), value -> "{}");

        assertThat(preempted).isZero();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(2002L),
                eq("LINEAR_DELIVERY"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(8L), eq("SELL"));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("FOR UPDATE");
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
    void createsLiquidationMarketOrderWithPositionSideAndFeeRates() {
        LiquidationOrderRepository repository = repository();
        givenFeeSnapshot();
        when(sequenceRepository.nextTradingSequence("order")).thenReturn(7001L);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7002L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7003L);
        when(sequenceRepository.nextTradingSequence("outbox")).thenReturn(7004L, 7005L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);

        repository.createReduceOnlyMarketOrder(9401L, 2002L, "BTC-USDT", MarginMode.ISOLATED,
                PositionSide.LONG, 8L, OrderSide.SELL, 3L, Instant.parse("2026-07-01T00:00:00Z"),
                Object::toString);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO trading_orders"), args.capture());
        assertThat(args.getValue()).hasSize(15);
        assertThat(args.getValue()[1]).isEqualTo("LINEAR_PERPETUAL");
        assertThat(args.getValue()[9]).isEqualTo("ISOLATED");
        assertThat(args.getValue()[10]).isEqualTo("LONG");
        assertThat(args.getValue()[11]).isEqualTo(200L);
        assertThat(args.getValue()[12]).isEqualTo(500L);
    }

    @Test
    void liquidationFeeSnapshotFiltersUserFeeByInstrumentProductLine() {
        LiquidationOrderRepository repository = repository();
        givenFeeSnapshot(ProductLine.OPTION);
        when(sequenceRepository.nextTradingSequence("order")).thenReturn(7001L);
        when(sequenceRepository.nextTradingSequence("event")).thenReturn(7002L);
        when(sequenceRepository.nextTradingSequence("command")).thenReturn(7003L);
        when(sequenceRepository.nextTradingSequence("outbox")).thenReturn(7004L, 7005L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);

        repository.createReduceOnlyMarketOrder(9401L, 2002L, "BTC-USDT-260925-70000-C", MarginMode.CROSS,
                PositionSide.SHORT, 8L, OrderSide.BUY, 3L, Instant.parse("2026-07-01T00:00:00Z"),
                Object::toString);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq("BTC-USDT-260925-70000-C"), eq(8L),
                eq("BTC-USDT-260925-70000-C"), eq(2002L), eq("BTC-USDT-260925-70000-C"), any(), any());
        assertThat(sql.getValue())
                .contains("WHEN 'VANILLA_OPTION' THEN 'OPTION'")
                .contains("AND product_line = (SELECT product_line FROM instrument_fee)")
                .contains("source_priority")
                .contains("ORDER BY priority ASC, source_priority ASC");
        ArgumentCaptor<Object[]> orderArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO trading_orders"), orderArgs.capture());
        assertThat(orderArgs.getValue()[1]).isEqualTo("OPTION");
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
    void claimPendingOnlyClaimsEarliestLiquidationOrderOutboxRowsByTopicAndKey() {
        LiquidationOrderRepository repository = repository();
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("DISTINCT ON (topic, event_key)")
                .contains("aggregate_type = 'LIQUIDATION_ORDER'")
                .contains("pg_try_advisory_xact_lock")
                .contains("FOR UPDATE OF e SKIP LOCKED")
                .contains("SET next_attempt_at = ?")
                .doesNotContain("ORDER_BOOK_DEPTH")
                .doesNotContain("MATCH_RESULT")
                .doesNotContain("MATCH_TRADE");
    }

    @Test
    void claimPendingFiltersByProductTopicsWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationOrderRepository repository = new LiquidationOrderRepository(jdbcTemplate, sequenceRepository,
                properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.claimPending(100, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("e.topic IN (?, ?)")
                .contains("aggregate_type = 'LIQUIDATION_ORDER'")
                .doesNotContain("ORDER_BOOK_DEPTH")
                .doesNotContain("MATCH_RESULT")
                .doesNotContain("MATCH_TRADE");
    }

    @Test
    void enqueueOutboxAllowsCurrentProductTopicWhenProductTopicsAreEnabled() throws Exception {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationOrderRepository repository = new LiquidationOrderRepository(jdbcTemplate, sequenceRepository,
                properties);
        when(sequenceRepository.nextTradingSequence("outbox")).thenReturn(9301L);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);

        invokeEnqueue(repository, properties.getKafka().getOrderCommandsTopic());

        verify(sequenceRepository).nextTradingSequence("outbox");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO trading_outbox_events"), args.capture());
        assertThat(args.getValue()[3]).isEqualTo("surprising.linear-delivery.order.commands.v1");
    }

    @Test
    void enqueueOutboxRejectsOtherProductTopicBeforeWritingWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationOrderRepository repository = new LiquidationOrderRepository(jdbcTemplate, sequenceRepository,
                properties);

        assertThatThrownBy(() -> invokeEnqueue(repository, "surprising.linear-delivery.order.commands.v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation trading outbox topic must match current product line")
                .hasMessageContaining("surprising.option.order.commands.v1");

        verify(sequenceRepository, never()).nextTradingSequence("outbox");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_outbox_events"), any(Object[].class));
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

    @Test
    void deletesOnlyPublishedLiquidationRowsInLockedBatches() {
        LiquidationOrderRepository repository = repository();
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(repository.deletePublishedBefore(Instant.parse("2026-07-01T00:00:00Z"), 100)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("aggregate_type = 'LIQUIDATION_ORDER'")
                .contains("published_at < ?")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("DELETE FROM trading_outbox_events");
    }

    private LiquidationOrderRepository repository() {
        return new LiquidationOrderRepository(jdbcTemplate, sequenceRepository, new LiquidationProperties());
    }

    private void invokeEnqueue(LiquidationOrderRepository repository, String topic) throws Exception {
        Method enqueue = LiquidationOrderRepository.class.getDeclaredMethod("enqueue", String.class, long.class,
                String.class, String.class, String.class, String.class, Instant.class);
        enqueue.setAccessible(true);
        try {
            enqueue.invoke(repository, "LIQUIDATION_ORDER", 9401L, topic, "BTC-USDT", "PLACE", "{}",
                    Instant.parse("2026-07-01T00:00:00Z"));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenFeeSnapshot() {
        givenFeeSnapshot(ProductLine.LINEAR_PERPETUAL);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenFeeSnapshot(ProductLine productLine) {
        when(jdbcTemplate.query(contains("WITH instrument_fee"), any(RowMapper.class),
                any(String.class), eq(8L), any(String.class), eq(2002L), any(String.class), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn(productLine.name());
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
