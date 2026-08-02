package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class OrderUserStateServiceTest {

    @Test
    void placeAppliesToLocalStateBeforeReturningAndClientIdIsIdempotent() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-");
        TradingOrderProperties properties = properties();
        KafkaTemplate<String, String> kafka = kafka();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka);
            OrderRecord order = order("client-1", 9001L, 10L);

            assertThat(service.place(order).status()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(service.place(order).orderId()).isEqualTo(order.orderId());
            assertThat(wal.replay(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L))).hasSize(1);
            assertThat(service.get(1001L, order.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
        }
    }

    @Test
    void conflictingClientIntentIsRejectedByTheSameUserLane() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-conflict-");
        TradingOrderProperties properties = properties();
        KafkaTemplate<String, String> kafka = kafka();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka);
            service.place(order("client-1", 9001L, 10L));

            assertThatThrownBy(() -> service.place(order("client-1", 9002L, 20L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("clientOrderId");
        }
    }

    @Test
    void stateSurvivesRestartWithoutReadingTheDatabase() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-restart-");
        TradingOrderProperties properties = properties();
        OrderRecord order = order("client-1", 9001L, 10L);
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka()).place(order);
        }
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService restarted = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            assertThat(restarted.get(1001L, order.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(wal.lastSequence(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L))).isEqualTo(1L);
        }
    }

    @Test
    void repeatedTradeIdDoesNotIncreaseExecutedQuantityAgain() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-trade-idempotency-");
        TradingOrderProperties properties = properties();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            OrderRecord order = order("client-trade", 9101L, 10L);
            service.place(order);
            service.place(orderFor(2002L, "maker-trade", 9901L, 10L));
            Instant tradeTime = Instant.parse("2026-07-01T00:00:01Z");
            MatchTradeEvent trade = new MatchTradeEvent(7001L, 8001L, "BTC-USDT", 9101L, 1L, 1001L,
                    OrderSide.BUY, 9901L, 1L, 2002L, 0L, 0L, 100L, 2L, false, false, tradeTime, "trace");
            MatchResultEvent first = new MatchResultEvent(8001L, 9101L, 1001L, "BTC-USDT", 1L,
                    OrderCommandType.PLACE, "MATCHED", 2L, OrderStatus.PARTIALLY_FILLED, tradeTime,
                    java.util.List.of(trade), "trace");
            MatchResultEvent retryWithNewCommand = new MatchResultEvent(8002L, 9101L, 1001L, "BTC-USDT", 1L,
                    OrderCommandType.PLACE, "MATCHED", 2L, OrderStatus.PARTIALLY_FILLED, tradeTime.plusSeconds(1),
                    java.util.List.of(trade), "trace-retry");

            service.processMatchResults(java.util.List.of(first));
            service.processMatchResults(java.util.List.of(retryWithNewCommand));

            assertThat(service.get(1001L, 9101L).executedQuantitySteps()).isEqualTo(2L);
            assertThat(service.get(1001L, 9101L).remainingQuantitySteps()).isEqualTo(8L);
        }
    }

    private TradingOrderProperties properties() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafka() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafka;
    }

    private OrderRecord order(String clientOrderId, long orderId, long quantity) {
        return orderFor(1001L, clientOrderId, orderId, quantity);
    }

    private OrderRecord orderFor(long userId, String clientOrderId, long orderId, long quantity) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new OrderRecord(orderId, ProductLine.LINEAR_PERPETUAL, userId, clientOrderId, "BTC-USDT", 1L,
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 100L, quantity, 0L, quantity, MarginMode.CROSS,
                PositionSide.NET, 100L, 200L, false, false, null, null, 0L, OrderStatus.ACCEPTED, null, now, now, 1L);
    }
}
