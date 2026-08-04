package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionResultStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandType;
import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserState;
import com.surprising.trading.order.model.OrderUserStateSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.mockito.ArgumentCaptor;
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
    void preservesTraceIdWhenPublishingOrderCommand() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-trace-");
        TradingOrderProperties properties = properties();
        KafkaTemplate<String, String> kafka = kafka();
        String traceId = "trace-order-1";
        OrderRecord order = new OrderRecord(9002L, ProductLine.LINEAR_PERPETUAL, 1001L, "trace-1", "BTC-USDT",
                1L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 100L, 1L, 0L, 1L, MarginMode.CROSS,
                PositionSide.NET, 100L, 200L, false, false, null, null, 0L, OrderStatus.ACCEPTED, null,
                Instant.now(), Instant.now(), 1L, traceId);
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka);
            service.place(order);
        }

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, atLeastOnce()).send(anyString(), anyString(), payloads.capture());
        assertThat(payloads.getAllValues()).anyMatch(payload -> payload.contains("\"traceId\":\"" + traceId + "\""));
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
    void compactedSnapshotRestoresStateOnAnotherNodeWithoutReplayingTheDatabase() throws Exception {
        Path sourceRoot = Files.createTempDirectory("order-user-state-source-");
        Path targetRoot = Files.createTempDirectory("order-user-state-target-");
        TradingOrderProperties properties = properties();
        OrderRecord order = order("migration-1", 9051L, 10L);
        OrderUserStateSnapshot snapshot;
        try (UserPartitionWal wal = new UserPartitionWal(sourceRoot.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(sourceRoot.resolve("state"))) {
            OrderUserStateService source = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            source.place(order);
            OrderUserState sourceState = new ObjectMapper().readValue(
                    state.read(new com.surprising.eventstore.UserPartitionKey(
                            ProductLine.LINEAR_PERPETUAL, order.userId())).orElseThrow().state(),
                    OrderUserState.class);
            snapshot = new OrderUserStateSnapshot(OrderUserStateSnapshot.CURRENT_SCHEMA_VERSION,
                    ProductLine.LINEAR_PERPETUAL, order.userId(), sourceState.revision(), sourceState,
                    Instant.now());
        }
        try (UserPartitionWal wal = new UserPartitionWal(targetRoot.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(targetRoot.resolve("state"))) {
            OrderUserStateService target = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            target.initializeSnapshot(snapshot);

            assertThat(target.get(order.userId(), order.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(state.lastAppliedSequence(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, order.userId()))).isZero();
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
            MatchTradeEvent retryTrade = new MatchTradeEvent(7001L, 8002L, "BTC-USDT", 9101L, 1L, 1001L,
                    OrderSide.BUY, 9901L, 1L, 2002L, 0L, 0L, 100L, 2L, false, false,
                    tradeTime.plusSeconds(1), "trace-retry");
            MatchResultEvent retryWithNewCommand = new MatchResultEvent(8002L, 9101L, 1001L, "BTC-USDT", 1L,
                    OrderCommandType.PLACE, "MATCHED", 2L, OrderStatus.PARTIALLY_FILLED, tradeTime.plusSeconds(1),
                    java.util.List.of(retryTrade), "trace-retry");

            service.processMatchResultForUser(1001L, first);
            service.processMatchResultForUser(2002L, first);
            service.processMatchResultForUser(1001L, retryWithNewCommand);
            service.processMatchResultForUser(2002L, retryWithNewCommand);

            assertThat(service.get(1001L, 9101L).executedQuantitySteps()).isEqualTo(2L);
            assertThat(service.get(1001L, 9101L).remainingQuantitySteps()).isEqualTo(8L);
        }
    }

    @Test
    void repeatedTradeIdAfterRestartDoesNotIncreaseExecutedQuantityAgain() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-trade-id-restart-");
        TradingOrderProperties properties = properties();
        Instant tradeTime = Instant.parse("2026-07-01T00:00:01Z");
        MatchTradeEvent trade = new MatchTradeEvent(7101L, 8101L, "BTC-USDT", 9201L, 1L, 1001L,
                OrderSide.BUY, 991L, 1L, 2002L, 0L, 0L, 100L, 2L, false, false, tradeTime, "trace");
        MatchResultEvent result = new MatchResultEvent(8101L, 9201L, 1001L, "BTC-USDT", 1L,
                OrderCommandType.PLACE, "MATCHED", 2L, OrderStatus.PARTIALLY_FILLED, tradeTime,
                java.util.List.of(trade), "trace");
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            service.place(order("client-restart", 9201L, 10L));
            service.place(orderFor(2002L, "maker-restart", 991L, 10L));
            service.processMatchResultForUser(1001L, result);
            service.processMatchResultForUser(2002L, result);
        }
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService restarted = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            restarted.processMatchResultForUser(1001L, result);
            restarted.processMatchResultForUser(2002L, result);

            assertThat(restarted.get(1001L, 9201L).executedQuantitySteps()).isEqualTo(2L);
            assertThat(restarted.get(1001L, 9201L).remainingQuantitySteps()).isEqualTo(8L);
            assertThat(restarted.get(2002L, 991L).executedQuantitySteps()).isEqualTo(2L);
        }
    }

    @Test
    void lateCancelResultCannotDowngradeFilledOrder() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-late-cancel-");
        TradingOrderProperties properties = properties();
        Instant time = Instant.parse("2026-07-01T00:00:01Z");
        MatchTradeEvent trade = new MatchTradeEvent(7201L, 8201L, "BTC-USDT", 9301L, 1L, 1001L,
                OrderSide.BUY, 9401L, 1L, 2002L, 0L, 0L, 100L, 1L, true, false, time, "trace");
        MatchResultEvent fill = new MatchResultEvent(8201L, 9301L, 1001L, "BTC-USDT", 1L,
                OrderCommandType.PLACE, "SUCCESS", 1L, OrderStatus.FILLED, time, java.util.List.of(trade), "trace");
        MatchResultEvent lateCancel = new MatchResultEvent(8202L, 9301L, 1001L, "BTC-USDT", 1L,
                OrderCommandType.CANCEL, "MATCHING_UNKNOWN_ORDER_ID", 0L, OrderStatus.CANCEL_REQUESTED,
                time.plusSeconds(1), java.util.List.of(), "trace-cancel");
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            service.place(order("late-cancel", 9301L, 1L));
            service.cancel(1001L, 9301L, "position update");
            service.processMatchResultForUser(1001L, fill);
            service.processMatchResultForUser(1001L, lateCancel);

            var result = service.get(1001L, 9301L);
            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.executedQuantitySteps()).isEqualTo(1L);
            assertThat(result.remainingQuantitySteps()).isZero();
        }
    }

    @Test
    void rejectsMatchResultWhenFilledQuantityHasNoMatchingTradeFacts() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-invalid-match-");
        TradingOrderProperties properties = properties();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka());
            MatchResultEvent invalid = new MatchResultEvent(8201L, 9301L, 1001L, "BTC-USDT", 1L,
                    OrderCommandType.PLACE, "MATCHED", 1L, OrderStatus.PARTIALLY_FILLED,
                    Instant.parse("2026-07-01T00:00:01Z"), java.util.List.of(), "trace");

            assertThatThrownBy(() -> service.processMatchResultForUser(9301L, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("缺少成交事实");
        }
    }

    @Test
    void rejectsMatchResultForUserThatIsNotATakerOrMaker() throws Exception {
        Path root = Files.createTempDirectory("order-user-state-invalid-user-");
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties(), wal, state,
                    new UserPartitionCommandLane(), kafka());
            Instant time = Instant.parse("2026-07-01T00:00:01Z");
            MatchTradeEvent trade = new MatchTradeEvent(8301L, 8401L, "BTC-USDT", 9301L, 1L, 1001L,
                    OrderSide.BUY, 9401L, 1L, 2002L, 0L, 0L, 100L, 2L, false, false, time, "trace");
            MatchResultEvent result = new MatchResultEvent(8401L, 9301L, 1001L, "BTC-USDT", 1L,
                    OrderCommandType.PLACE, "MATCHED", 1L, OrderStatus.PARTIALLY_FILLED, time,
                    java.util.List.of(trade), "trace");

            assertThatThrownBy(() -> service.processMatchResultForUser(3003L, result))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不属于目标用户分区");
        }
    }

    @Test
    void userCommandResultIsPartitionedAndConflictingPayloadIsRejected() throws Exception {
        Path root = Files.createTempDirectory("order-user-command-result-");
        TradingOrderProperties properties = properties();
        OrderRecord order = order("command-1", 9401L, 10L);
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"));
             UserPartitionResultStore results = new UserPartitionResultStore(root.resolve("results"))) {
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka(), null, null, null, results);
            OrderUserCommand command = new OrderUserCommand(OrderUserCommand.CURRENT_SCHEMA_VERSION,
                    "ORDER_PLACE:9401", ProductLine.LINEAR_PERPETUAL, order.userId(), OrderUserCommandType.PLACE,
                    new ObjectMapper().writeValueAsString(order), Instant.now(), null);

            var first = service.executeUserCommand(command);
            var retry = service.executeUserCommand(command);
            var retryWithNewMetadata = new OrderUserCommand(OrderUserCommand.CURRENT_SCHEMA_VERSION,
                    command.commandId(), command.productLine(), command.userId(), command.commandType(),
                    command.payload(), Instant.now().plusSeconds(1), "转发节点-2");

            assertThat(retry).isEqualTo(first);
            assertThat(service.executeUserCommand(retryWithNewMetadata)).isEqualTo(first);
            assertThat(wal.replay(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, order.userId()))).hasSize(1);
            OrderRecord conflicting = orderFor(order.userId(), "command-2", 9402L, 10L);
            OrderUserCommand conflict = new OrderUserCommand(OrderUserCommand.CURRENT_SCHEMA_VERSION,
                    command.commandId(), command.productLine(), command.userId(), command.commandType(),
                    new ObjectMapper().writeValueAsString(conflicting), Instant.now(), null);
            assertThatThrownBy(() -> service.executeUserCommand(conflict))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("载荷指纹冲突");
        }
    }

    @Test
    void cancelBeforeReservationResultDoesNotPublishPlaceAndReleasesAfterReserveAccepted() throws Exception {
        Path root = Files.createTempDirectory("order-user-cancel-pending-");
        TradingOrderProperties properties = properties();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            KafkaTemplate<String, String> kafka = kafka();
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka);
            OrderRecord pending = new OrderRecord(9501L, ProductLine.LINEAR_PERPETUAL, 1001L, "pending-1",
                    "BTC-USDT", 1L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 100L, 10L, 0L, 10L,
                    MarginMode.CROSS, PositionSide.NET, 100L, 200L, false, false, "USDT_PERPETUAL", "USDT",
                    50L, OrderStatus.PENDING_RESERVE, null, Instant.now(), Instant.now(), 1L);
            service.place(pending);

            assertThat(service.cancel(1001L, pending.orderId(), "cancel while reserving").status())
                    .isEqualTo(OrderStatus.CANCEL_REQUESTED);
            AccountCommandResultEvent accepted = new AccountCommandResultEvent(
                    9601L, OrderUserStateService.reservationCommandId(pending.productLine(), pending.orderId()),
                    pending.productLine(), pending.userId(), AccountUserCommandType.ORDER_RESERVE,
                    AccountCommandStatus.APPLIED, "ORDER", String.valueOf(pending.orderId()), "{}", null, null,
                    Instant.now(), "trace");
            service.processAccountCommandResultForUser(accepted);

            assertThat(service.get(1001L, pending.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
            org.mockito.Mockito.verify(kafka, org.mockito.Mockito.atLeastOnce())
                    .send(anyString(), anyString(), anyString());
        }
    }

    @Test
    void cancelAcceptedOrderPublishesCancelAndAdvancesUserState() throws Exception {
        Path root = Files.createTempDirectory("order-user-cancel-accepted-");
        TradingOrderProperties properties = properties();
        try (UserPartitionWal wal = new UserPartitionWal(root.resolve("wal"));
             UserPartitionStateStore state = new UserPartitionStateStore(root.resolve("state"))) {
            KafkaTemplate<String, String> kafka = kafka();
            OrderUserStateService service = new OrderUserStateService(new ObjectMapper(), properties, wal, state,
                    new UserPartitionCommandLane(), kafka);
            OrderRecord accepted = order("cancel-accepted", 9701L, 10L);

            service.place(accepted);
            assertThat(service.cancel(accepted.userId(), accepted.orderId(), "user cancel").status())
                    .isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(service.get(accepted.userId(), accepted.orderId()).status())
                    .isEqualTo(OrderStatus.CANCEL_REQUESTED);
            org.mockito.Mockito.verify(kafka, org.mockito.Mockito.atLeastOnce())
                    .send(anyString(), anyString(), anyString());
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
