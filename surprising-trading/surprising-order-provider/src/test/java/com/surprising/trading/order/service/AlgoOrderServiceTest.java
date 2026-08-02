package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class AlgoOrderServiceTest {

    @TempDir
    Path directory;

    @Test
    void twapRejectsChildQuantityThatCannotFinishInsideDuration() throws Exception {
        try (Fixture fixture = fixture(ProductLine.LINEAR_PERPETUAL, mock(OrderService.class))) {
            assertThatThrownBy(() -> fixture.service.place(new PlaceAlgoOrderRequest(
                    1001L, "twap-small-child", "BTC-USDT", AlgoOrderType.TWAP, OrderSide.BUY,
                    0L, 100L, 10L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                    false, false, TimeInForce.IOC, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("childQuantitySteps is too small");
        }
    }

    @Test
    void reusedClientAlgoIdWithDifferentParametersIsRejected() throws Exception {
        try (Fixture fixture = fixture(ProductLine.LINEAR_PERPETUAL, mock(OrderService.class))) {
            fixture.userState.placeAlgo(twapRecord());
            assertThatThrownBy(() -> fixture.service.place(new PlaceAlgoOrderRequest(
                    1001L, "twap-1", "BTC-USDT", AlgoOrderType.TWAP, OrderSide.BUY,
                    1L, 100L, 50L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                    false, false, TimeInForce.IOC, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clientAlgoOrderId already used with different algo parameters");
        }
    }

    @Test
    void dueTwapPlacesIocChildOrderThroughOrderService() throws Exception {
        OrderService orderService = mock(OrderService.class);
        try (Fixture fixture = fixture(ProductLine.LINEAR_PERPETUAL, orderService)) {
            AlgoOrderRecord record = twapRecord();
            fixture.userState.placeAlgo(record);
            when(orderService.place(any())).thenReturn(orderResponse(9001L, "algo-77-0", TimeInForce.IOC));

            fixture.service.executeDue(record, Instant.parse("2026-07-05T00:00:00Z"));

            verify(orderService).place(any());
            assertThat(fixture.userState.algoChildren(1001L, record.algoOrderId()))
                    .extracting(AlgoOrderChild::orderId).containsExactly(9001L);
        }
    }

    @Test
    void icebergWaitsForActiveVisibleChildBeforePlacingNextSlice() throws Exception {
        OrderService orderService = mock(OrderService.class);
        try (Fixture fixture = fixture(ProductLine.LINEAR_PERPETUAL, orderService)) {
            AlgoOrderRecord record = icebergRecord();
            fixture.userState.placeAlgo(record);
            fixture.userState.place(childRecord());
            fixture.userState.linkAlgoChild(record, new AlgoOrderChild(record.algoOrderId(), 1, 9001L, 50L));

            fixture.service.executeDue(record, Instant.parse("2026-07-05T00:00:00Z"));

            verify(orderService, never()).place(any());
        }
    }

    @Test
    void cancelAlgoCancelsActiveChildrenAndStopsFutureSlices() throws Exception {
        OrderService orderService = mock(OrderService.class);
        try (Fixture fixture = fixture(ProductLine.LINEAR_PERPETUAL, orderService)) {
            AlgoOrderRecord record = icebergRecord();
            fixture.userState.placeAlgo(record);
            fixture.userState.place(childRecord());
            fixture.userState.linkAlgoChild(record, new AlgoOrderChild(record.algoOrderId(), 1, 9001L, 50L));

            var response = fixture.service.cancel(new CancelAlgoOrderRequest(record.userId(), record.algoOrderId()));

            assertThat(response.status()).isEqualTo(AlgoOrderStatus.CANCELED);
            verify(orderService).cancel(any());
        }
    }

    @Test
    void nonPerpetualAlgoOrderFailsClosedWithoutDatabaseFallback() throws Exception {
        try (Fixture fixture = fixture(ProductLine.OPTION, mock(OrderService.class))) {
            assertThatThrownBy(() -> fixture.service.place(new PlaceAlgoOrderRequest(
                    1001L, "option-1", "BTC-OPT", AlgoOrderType.TWAP, OrderSide.BUY,
                    0L, 100L, 50L, 10L, 20L, MarginMode.CROSS, PositionSide.NET,
                    false, false, TimeInForce.IOC, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("尚未接入本地订单事实流");
        }
    }

    private Fixture fixture(ProductLine productLine, OrderService orderService) throws Exception {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getAlgo().setMinDurationSeconds(1L);
        properties.getAlgo().setMinIntervalSeconds(1L);
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(productLine);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        Path root = Files.createTempDirectory(directory, "algo-");
        UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
        UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"));
        OrderUserStateService user = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                new UserPartitionCommandLane(), kafka);
        AlgoOrderService service = new AlgoOrderService(properties, orderService, user,
                OrderScheduleIndex.disabled());
        return new Fixture(service, user, wal, state);
    }

    private AlgoOrderRecord twapRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new AlgoOrderRecord(77L, ProductLine.LINEAR_PERPETUAL, 1001L, "twap-1", "BTC-USDT", AlgoOrderType.TWAP,
                OrderSide.BUY, 0L, 100L, 50L, 10L, 20L, MarginMode.CROSS,
                PositionSide.NET, false, false, TimeInForce.IOC, AlgoOrderStatus.PENDING,
                null, null, "trace-1", now, now, null, now, now);
    }

    private AlgoOrderRecord icebergRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new AlgoOrderRecord(78L, ProductLine.LINEAR_PERPETUAL, 1001L, "ice-1", "BTC-USDT", AlgoOrderType.ICEBERG,
                OrderSide.SELL, 600_000L, 100L, 50L, 10L, 20L, MarginMode.CROSS,
                PositionSide.NET, false, true, TimeInForce.GTX, AlgoOrderStatus.RUNNING,
                9001L, null, "trace-1", now, now, null, now, now);
    }

    private OrderResponse orderResponse(long orderId, String clientOrderId, TimeInForce timeInForce) {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new OrderResponse(orderId, 1001L, clientOrderId, "BTC-USDT", 1L,
                OrderSide.BUY, OrderType.MARKET, timeInForce, 0L, 50L, 0L, 50L,
                MarginMode.CROSS, PositionSide.NET, 0L, 0L, false, false,
                OrderStatus.ACCEPTED, null, now, now);
    }

    private OrderRecord childRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new OrderRecord(9001L, ProductLine.LINEAR_PERPETUAL, 1001L, "algo-78-1", "BTC-USDT", 1L,
                OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTX, 600_000L,
                50L, 0L, 50L, MarginMode.CROSS, PositionSide.NET, 0L, 0L,
                false, true, null, null, 0L, OrderStatus.ACCEPTED, null, now, now, 1L);
    }

    private record Fixture(AlgoOrderService service,
                           OrderUserStateService userState,
                           UserPartitionWal wal,
                           UserPartitionStateStore state) implements AutoCloseable {
        @Override
        public void close() {
            wal.close();
            state.close();
        }
    }
}
