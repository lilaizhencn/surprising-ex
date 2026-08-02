package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.BalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountUserStateReducerTest {

    @Test
    void accountApiAdjustmentsUseTheSameOrderedLocalState() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-api-");
        ObjectMapper objectMapper = new ObjectMapper();
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane());
            reducer.initialize(snapshot());

            AccountUserCommand adjustment = command("balance-adjust-1", AccountUserCommandType.BALANCE_ADJUST,
                    objectMapper.writeValueAsString(new BalanceAdjustmentAccountCommand(
                            new BalanceAdjustmentRequest(1001L, "USDT", -200L, "admin-ref-1", "测试扣减"),
                            "admin-1", "管理员")));
            AccountUserStateReducer.Reduction adjusted = reducer.apply(adjustment, 1L);

            assertThat(adjusted.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.state(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L))
                    .orElseThrow().snapshot().balances())
                    .containsExactly(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 800L, 0L));

            AccountUserCommand mode = command("position-mode-1", AccountUserCommandType.POSITION_MODE_UPDATE,
                    objectMapper.writeValueAsString(new PositionModeUpdateRequest(
                            1001L, ProductLine.LINEAR_PERPETUAL,
                            PositionMode.HEDGE, "mode-ref-1")));
            AccountUserStateReducer.Reduction switched = reducer.apply(mode, 2L);

            assertThat(switched.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.state(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L))
                    .orElseThrow().snapshot().positionMode()).isEqualTo(PositionMode.HEDGE);
            assertThat(store.lastAppliedSequence(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L)))
                    .isEqualTo(2L);
        }
    }

    @Test
    void reserveAndReleaseOnlyChangeTheLocalUserStateInOrder() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-");
        ObjectMapper objectMapper = new ObjectMapper();
        PerpetualAccountStateUpdatedEvent initial = snapshot();
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane());
            reducer.initialize(initial);
            AccountUserCommand reserve = command("reserve-1", AccountUserCommandType.ORDER_RESERVE,
                    objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                            AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                            100L, false, 300L, 1L)));

            AccountUserStateReducer.Reduction reserved = reducer.apply(reserve, 1L);

            assertThat(reserved.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState state = reducer.state(new com.surprising.eventstore.UserPartitionKey(
                    reserve.productLine(), reserve.userId())).orElseThrow();
            assertThat(state.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 700L, 300L));
            assertThat(state.snapshot().orderLocks()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.OrderLock("USDT", 300L));

            AccountUserCommand release = command("release-1", AccountUserCommandType.ORDER_RELEASE,
                    objectMapper.writeValueAsString(new OrderReleaseAccountCommand(
                            9001L, true, 100L, 0L, true, AccountType.USDT_PERPETUAL, "USDT", 300L,
                            "USER_CANCEL", Instant.now())));
            AccountUserStateReducer.Reduction released = reducer.apply(release, 2L);

            assertThat(released.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.state(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L)).orElseThrow().snapshot().balances())
                    .containsExactly(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1000L, 0L));
            assertThat(store.lastAppliedSequence(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L))).isEqualTo(2L);
        }
    }

    @Test
    void missingSnapshotFailsClosedWithoutDatabaseFallback() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-missing-");
        AccountUserCommand command = command("reserve-missing", AccountUserCommandType.ORDER_RESERVE, "{}");
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    new ObjectMapper(), store, new UserPartitionCommandLane());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reducer.apply(command, 1L))
                    .isInstanceOf(AccountStateUnavailableException.class);
        }
    }

    @Test
    void perpetualOpeningTradeConsumesOnlyAllocatedOrderMargin() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-trade-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.LINEAR_PERPETUAL,
                List.of(AccountSettlementServiceTestFactory.instrument("BTC-USDT", 1L,
                        ProductLine.LINEAR_PERPETUAL)),
                java.util.Map.of("USDT", 1L));
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(snapshot());
            AccountUserCommand reserve = command("trade-reserve", AccountUserCommandType.ORDER_RESERVE,
                    objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9002L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                            AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                            100L, false, 300L, 1L)));
            reducer.apply(reserve, 1L);
            MatchTradeEvent trade = new MatchTradeEvent(
                    8002L, 7002L, "BTC-USDT", 9002L, 1L, 1001L, OrderSide.BUY,
                    9003L, 1L, 2002L, 0L, 0L, 100L, 10L, true, false,
                    Instant.parse("2026-08-02T00:00:01Z"), "trade-8002");
            AccountUserCommand settlement = command("trade-settle", AccountUserCommandType.TRADE_SIDE_SETTLE,
                    objectMapper.writeValueAsString(new TradeSideSettlementCommand(
                            trade, TradeParticipantRole.TAKER, 100L, false,
                            AccountType.USDT_PERPETUAL, "USDT", 300L)));

            AccountUserStateReducer.Reduction result = reducer.apply(settlement, 2L);

            assertThat(result.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState state = reducer.state(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L)).orElseThrow();
            assertThat(state.snapshot().balances()).containsExactly(
                    // 订单总预占 300，成交只占订单数量的 10%，其中 20 释放回可用余额，剩余未成交预占仍保持锁定。
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 720L, 280L));
            assertThat(state.snapshot().positions()).singleElement()
                    .satisfies(position -> {
                        assertThat(position.signedQuantitySteps()).isEqualTo(10L);
                        assertThat(position.entryPriceTicks()).isEqualTo(100L);
                    });
            assertThat(state.snapshot().positionMargins()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.PositionMargin(
                            "BTC-USDT", "USDT", MarginMode.CROSS, PositionSide.NET, 10L));
        }
    }

    @Test
    void fundingDebitConsumesAvailableThenPositionMarginAndRecordsDeficitIdempotently() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-funding-");
        ObjectMapper objectMapper = new ObjectMapper();
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane());
            PerpetualAccountStateUpdatedEvent initial = new PerpetualAccountStateUpdatedEvent(
                    PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                    ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                    List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 10L, 20L)),
                    List.of(),
                    List.of(new PerpetualAccountStateUpdatedEvent.Position("BTC-USDT", 1L,
                            MarginMode.CROSS, PositionSide.NET, 10L, 100L, 1_000L, 0L,
                            Instant.parse("2026-08-02T00:00:00Z"))),
                    List.of(new PerpetualAccountStateUpdatedEvent.PositionMargin(
                            "BTC-USDT", "USDT", MarginMode.CROSS, PositionSide.NET, 20L)),
                    List.of(), PositionMode.ONE_WAY, Instant.parse("2026-08-02T00:00:00Z"), "test");
            reducer.initialize(initial);
            AccountUserCommand funding = command("funding-1", AccountUserCommandType.FUNDING_SETTLE,
                    objectMapper.writeValueAsString(new FundingSettlementAccountCommand(
                            7001L, 7101L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                            "USDT", 10L, 1_000L, 10_000L, -50L)));

            AccountUserStateReducer.Reduction first = reducer.apply(funding, 1L);
            AccountUserStateReducer.Reduction duplicate = reducer.apply(funding, 2L);

            assertThat(first.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(duplicate.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState state = reducer.state(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L)).orElseThrow();
            assertThat(state.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 0L, 0L));
            assertThat(state.snapshot().deficits()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Deficit("USDT", 20L, 0L));
            assertThat(state.snapshot().positionMargins()).isEmpty();
            assertThat(state.settledFundingPaymentIds()).containsExactly(7101L);
            assertThat(store.lastAppliedSequence(new com.surprising.eventstore.UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L))).isEqualTo(2L);
        }
    }

    private PerpetualAccountStateUpdatedEvent snapshot() {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1000L, 0L)),
                List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-02T00:00:00Z"), "test");
    }

    private AccountUserCommand command(String commandId,
                                       AccountUserCommandType type,
                                       String payload) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                ProductLine.LINEAR_PERPETUAL, 1001L, type, "TEST", commandId, null,
                payload, Instant.parse("2026-08-02T00:00:00Z"), "trace-" + commandId);
    }
}
