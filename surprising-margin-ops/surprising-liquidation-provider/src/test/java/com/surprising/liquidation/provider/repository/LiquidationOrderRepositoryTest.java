package com.surprising.liquidation.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.LiquidationInstrumentFeeRepository.InstrumentFee;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.NewLiquidationOrder;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.OpenReduceOnlyOrder;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.OrderScope;
import com.surprising.liquidation.provider.repository.LiquidationTradingOutboxRepository.NewOutboxEvent;
import com.surprising.liquidation.provider.repository.LiquidationUserFeeRepository.UserFee;
import com.surprising.liquidation.provider.service.LiquidationOrderPersistenceService;
import com.surprising.liquidation.provider.service.LiquidationOrderPersistenceService.LiquidationOrderRequest;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class LiquidationOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void orderRepositoryOnlyInsertsTradingOrders() {
        LiquidationOrderRepository repository = new LiquidationOrderRepository(
                jdbcTemplate, new LiquidationProperties());
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class))).thenReturn(new int[]{1});

        repository.insertAll(List.of(new NewLiquidationOrder(
                7001L, ProductLine.LINEAR_PERPETUAL, 2002L, "LIQ-9401", "BTC-USDT", 8L,
                OrderSide.SELL, 3L, MarginMode.ISOLATED, PositionSide.LONG, 200L, 500L, NOW)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), any(List.class));
        assertThat(sql.getValue())
                .contains("INSERT INTO trading_orders")
                .doesNotContain("trading_order_events")
                .doesNotContain("trading_outbox_events")
                .doesNotContain("ON CONFLICT");
    }

    @Test
    void orderRepositoryLocksCurrentProductLineOrders() {
        LiquidationProperties properties = properties(ProductLine.LINEAR_DELIVERY);
        LiquidationOrderRepository repository = new LiquidationOrderRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.lockOpenReduceOnlyCloseOrders(List.of(new OrderScope(
                2002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, 8L, OrderSide.SELL, NOW)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("JOIN trading_orders o")
                .contains("o.product_line = ?")
                .contains("FOR UPDATE OF o")
                .doesNotContain("trading_order_events")
                .doesNotContain("trading_outbox_events");
        assertThat(args.getValue()).contains("LINEAR_DELIVERY");
    }

    @Test
    void orderEventRepositoryRejectsConflictedInsert() {
        LiquidationOrderEventRepository repository = new LiquidationOrderEventRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class))).thenReturn(new int[]{0});
        var event = new com.surprising.trading.api.model.OrderEvent(
                7101L, 7001L, 2002L, "BTC-USDT",
                com.surprising.trading.api.model.OrderEventType.ACCEPTED,
                OrderStatus.ACCEPTED, "LIQUIDATION", NOW);

        assertThatThrownBy(() -> repository.insertAll(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("批量写入强平订单事件失败");
    }

    @Test
    void instrumentFeeRepositoryOnlyReadsInstrumentTable() throws Exception {
        LiquidationInstrumentFeeRepository repository = new LiquidationInstrumentFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("candidate_id")).thenReturn(9401L);
                    when(rs.getString("product_line")).thenReturn("OPTION");
                    when(rs.getLong("maker_fee_rate_ppm")).thenReturn(200L);
                    when(rs.getLong("taker_fee_rate_ppm")).thenReturn(500L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        repository.findAll(List.of(
                new LiquidationInstrumentFeeRepository.InstrumentFeeRequest(9401L, "BTC-C", 8L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("JOIN instruments i")
                .contains("WHEN 'VANILLA_OPTION' THEN 'OPTION'")
                .doesNotContain("trading_fee_schedules");
    }

    @Test
    void userFeeRepositoryOnlyReadsFeeScheduleTable() {
        LiquidationUserFeeRepository repository = new LiquidationUserFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.findBestActive(List.of(new LiquidationUserFeeRepository.UserFeeRequest(
                9401L, 2002L, "BTC-USDT", ProductLine.LINEAR_PERPETUAL, NOW)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("JOIN trading_fee_schedules f")
                .contains("WHEN 'RISK_OVERRIDE' THEN 0")
                .doesNotContain("JOIN instruments");
    }

    @Test
    void persistenceServiceAggregatesSingleTableRepositories() {
        LiquidationOrderRepository orders = mock(LiquidationOrderRepository.class);
        LiquidationOrderEventRepository events = mock(LiquidationOrderEventRepository.class);
        LiquidationTradingOutboxRepository outbox = mock(LiquidationTradingOutboxRepository.class);
        LiquidationInstrumentFeeRepository instrumentFees = mock(LiquidationInstrumentFeeRepository.class);
        LiquidationUserFeeRepository userFees = mock(LiquidationUserFeeRepository.class);
        LiquidationSequenceRepository sequences = mock(LiquidationSequenceRepository.class);
        LiquidationProperties properties = new LiquidationProperties();
        LiquidationOrderPersistenceService service = new LiquidationOrderPersistenceService(
                orders, events, outbox, instrumentFees, userFees, sequences, properties);
        when(instrumentFees.findAll(any())).thenReturn(Map.of(9401L,
                new InstrumentFee(9401L, ProductLine.LINEAR_PERPETUAL, 200L, 500L)));
        when(userFees.findBestActive(any())).thenReturn(Map.of(9401L, new UserFee(9401L, 100L, 300L)));
        when(sequences.nextTradingSequence("order")).thenReturn(7001L);
        when(sequences.nextTradingSequence("event")).thenReturn(7002L);
        when(sequences.nextTradingSequence("command")).thenReturn(7003L);
        when(sequences.nextTradingSequence("outbox")).thenReturn(7004L, 7005L);

        var submissions = service.createReduceOnlyMarketOrders(List.of(new LiquidationOrderRequest(
                9401L, 2002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, 8L,
                OrderSide.SELL, 3L, NOW)), value -> "{}");

        assertThat(submissions).singleElement().satisfies(submission -> {
            assertThat(submission.candidateId()).isEqualTo(9401L);
            assertThat(submission.command().orderId()).isEqualTo(7001L);
        });
        ArgumentCaptor<List<NewLiquidationOrder>> orderRows = listCaptor();
        verify(orders).insertAll(orderRows.capture());
        assertThat(orderRows.getValue()).singleElement().satisfies(order -> {
            assertThat(order.makerFeeRatePpm()).isEqualTo(100L);
            assertThat(order.takerFeeRatePpm()).isEqualTo(300L);
        });
        verify(events).insertAll(any());
        ArgumentCaptor<List<NewOutboxEvent>> outboxRows = listCaptor();
        verify(outbox).insertAll(outboxRows.capture());
        assertThat(outboxRows.getValue()).hasSize(2);
    }

    @Test
    void persistenceServicePreemptsOrdersThroughThreeRepositories() {
        LiquidationOrderRepository orders = mock(LiquidationOrderRepository.class);
        LiquidationOrderEventRepository events = mock(LiquidationOrderEventRepository.class);
        LiquidationTradingOutboxRepository outbox = mock(LiquidationTradingOutboxRepository.class);
        LiquidationSequenceRepository sequences = mock(LiquidationSequenceRepository.class);
        LiquidationOrderPersistenceService service = new LiquidationOrderPersistenceService(
                orders, events, outbox, mock(LiquidationInstrumentFeeRepository.class),
                mock(LiquidationUserFeeRepository.class), sequences, new LiquidationProperties());
        when(orders.lockOpenReduceOnlyCloseOrders(any())).thenReturn(List.of(new OpenReduceOnlyOrder(
                101L, 2002L, "reduce-101", "BTC-USDT", 8L, OrderSide.SELL, OrderType.LIMIT,
                TimeInForce.GTC, 88_000L, 3L, MarginMode.CROSS, PositionSide.NET, OrderStatus.ACCEPTED,
                200L, 500L, false, NOW)));
        when(sequences.nextTradingSequence("event")).thenReturn(7101L);
        when(sequences.nextTradingSequence("command")).thenReturn(7201L);
        when(sequences.nextTradingSequence("outbox")).thenReturn(7301L, 7302L);

        int count = service.cancelOpenReduceOnlyCloseOrders(List.of(new LiquidationOrderRequest(
                0L, 2002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, 8L,
                OrderSide.SELL, 0L, NOW)), value -> "{}");

        assertThat(count).isEqualTo(1);
        verify(orders).requestCancel(101L, "LIQUIDATION_PREEMPTED_REDUCE_ONLY", NOW);
        verify(events).insert(any());
        verify(outbox, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    void outboxRepositoryClaimsOnlyOwnedAggregateRows() {
        LiquidationTradingOutboxRepository repository =
                new LiquidationTradingOutboxRepository(jdbcTemplate, new LiquidationProperties());
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.claimPending(100, NOW.plusSeconds(30), NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("DISTINCT ON (topic, event_key)")
                .contains("aggregate_type = 'LIQUIDATION_ORDER'")
                .contains("FOR UPDATE OF e SKIP LOCKED");
    }

    @Test
    void outboxRepositoryRejectsOtherProductTopic() {
        LiquidationProperties properties = properties(ProductLine.OPTION);
        LiquidationTradingOutboxRepository repository =
                new LiquidationTradingOutboxRepository(jdbcTemplate, properties);

        assertThatThrownBy(() -> repository.insert(new NewOutboxEvent(
                9301L, "ORDER", 7001L, "surprising.linear-delivery.order.commands.v1",
                "BTC-USDT", "PLACE", "{}", NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic 与当前产品线不一致");
    }

    private LiquidationProperties properties(ProductLine productLine) {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
