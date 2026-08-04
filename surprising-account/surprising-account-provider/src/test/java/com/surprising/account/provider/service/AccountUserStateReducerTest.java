package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
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
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
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
    void replayedPublicSnapshotCannotOverwriteLocalReservationAndSettlementIndexes() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-snapshot-replay-");
        ObjectMapper objectMapper = new ObjectMapper();
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane());
            reducer.initialize(snapshot());
            AccountUserCommand adjustment = command("snapshot-replay-adjust", AccountUserCommandType.BALANCE_ADJUST,
                    objectMapper.writeValueAsString(new BalanceAdjustmentAccountCommand(
                            new BalanceAdjustmentRequest(1001L, "USDT", -200L, "snapshot-replay", "测试调整"),
                            "admin-1", "管理员")));
            reducer.apply(adjustment, 1L);

            reducer.initialize(new PerpetualAccountStateUpdatedEvent(
                    PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 99L, 99L,
                    ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                    List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 9_999L, 0L)),
                    List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                    Instant.parse("2026-08-02T00:00:00Z"), "replayed-public-snapshot"));

            assertThat(reducer.state(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 1001L))
                    .orElseThrow().snapshot().balances())
                    .containsExactly(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 800L, 0L));
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
    void spotReservationUsesSpotAssetAccountAndNeverCreatesPosition() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-spot-");
        ObjectMapper objectMapper = new ObjectMapper();
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane());
            reducer.initialize(new PerpetualAccountStateUpdatedEvent(
                    PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                    ProductLine.SPOT, 1001L, AccountType.SPOT.name(),
                    List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 2_000L, 0L)),
                    List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                    Instant.parse("2026-08-02T00:00:00Z"), "spot-test"));

            AccountUserCommand reserve = new AccountUserCommand(
                    AccountUserCommand.CURRENT_SCHEMA_VERSION, "spot-reserve-1", ProductLine.SPOT, 1001L,
                    AccountUserCommandType.ORDER_RESERVE, "TEST", "spot-reserve-1", null,
                    objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9101L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.SPOT_ASSET,
                            AccountType.SPOT, "USDT", MarginMode.CROSS, PositionSide.NET,
                            100L, false, 1_000L, 1L)),
                    Instant.parse("2026-08-02T00:00:00Z"), "trace-spot-reserve");

            AccountUserStateReducer.Reduction result = reducer.apply(reserve, 1L);

            assertThat(result.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState state = reducer.state(new UserPartitionKey(ProductLine.SPOT, 1001L))
                    .orElseThrow();
            assertThat(state.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_000L, 1_000L));
            assertThat(state.snapshot().positions()).isEmpty();
            assertThat(state.snapshot().positionMargins()).isEmpty();
            assertThat(state.snapshot().accountType()).isEqualTo(AccountType.SPOT.name());
        }
    }

    @Test
    void spotTradeSettlesQuoteAndBaseLocallyAndDuplicateTradeIsIdempotent() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-spot-trade-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.SPOT, List.of(spotInstrument("BTC-USDT", 1L)),
                java.util.Map.of("BTC", 1L, "USDT", 1L));
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(
                    objectMapper, store, new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(new PerpetualAccountStateUpdatedEvent(
                    PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                    ProductLine.SPOT, 1001L, AccountType.SPOT.name(),
                    List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_000L, 0L),
                            new PerpetualAccountStateUpdatedEvent.Balance("BTC", 0L, 0L)),
                    List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                    Instant.parse("2026-08-02T00:00:00Z"), "spot-trade-test"));
            AccountUserCommand reserve = new AccountUserCommand(
                    AccountUserCommand.CURRENT_SCHEMA_VERSION, "spot-trade-reserve", ProductLine.SPOT, 1001L,
                    AccountUserCommandType.ORDER_RESERVE, "TEST", "spot-trade-reserve", null,
                    objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9201L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.SPOT_ASSET,
                            AccountType.SPOT, "USDT", MarginMode.CROSS, PositionSide.NET,
                            2L, false, 202L, 1L)),
                    Instant.parse("2026-08-02T00:00:00Z"), "trace-spot-trade-reserve");
            reducer.apply(reserve, 1L);
            MatchTradeEvent trade = new MatchTradeEvent(
                    8201L, 7201L, "BTC-USDT", 9201L, 1L, 1001L, OrderSide.BUY,
                    9202L, 1L, 2002L, 10_000L, 10_000L, 100L, 2L,
                    true, true, Instant.parse("2026-08-02T00:00:01Z"), "spot-trade-1");
            AccountUserCommand settlement = new AccountUserCommand(
                    AccountUserCommand.CURRENT_SCHEMA_VERSION, "spot-trade-settle", ProductLine.SPOT, 1001L,
                    AccountUserCommandType.TRADE_SIDE_SETTLE, "TEST", "spot-trade-settle", null,
                    objectMapper.writeValueAsString(new TradeSideSettlementCommand(
                            trade, TradeParticipantRole.TAKER, 2L, false,
                            AccountType.SPOT, "USDT", 202L)),
                    Instant.parse("2026-08-02T00:00:02Z"), "trace-spot-trade-settle");

            assertThat(reducer.apply(settlement, 2L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.apply(settlement, 3L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState state = reducer.state(new UserPartitionKey(ProductLine.SPOT, 1001L))
                    .orElseThrow();
            assertThat(state.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 798L, 0L),
                    new PerpetualAccountStateUpdatedEvent.Balance("BTC", 2L, 0L));
            assertThat(state.snapshot().orderLocks()).isEmpty();
            assertThat(state.snapshot().positions()).isEmpty();
            assertThat(state.settledTradeIds()).containsExactly(8201L);
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
                List.of(instrument("BTC-USDT", 1L)),
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
            AccountUserReducerState.Reservation reservation = state.reservations().stream()
                    .filter(value -> value.orderId() == 9002L)
                    .findFirst().orElseThrow();
            long expectedOrderLock = reservation.reservedUnits() - reservation.releasedUnits()
                    - reservation.consumedUnits();
            assertThat(state.snapshot().orderLocks()).singleElement().satisfies(lock -> {
                assertThat(lock.asset()).isEqualTo("USDT");
                assertThat(lock.lockedUnits()).isEqualTo(expectedOrderLock);
            });

            AccountUserCommand release = command("trade-release", AccountUserCommandType.ORDER_RELEASE,
                    objectMapper.writeValueAsString(new OrderReleaseAccountCommand(
                            9002L, true, 100L, 0L, true, AccountType.USDT_PERPETUAL, "USDT", 300L,
                            "ORDER_FILLED", Instant.now())));
            assertThat(reducer.apply(release, 3L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState afterRelease = reducer.state(new UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L)).orElseThrow();
            assertThat(afterRelease.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 990L, 10L));
            assertThat(afterRelease.snapshot().orderLocks()).isEmpty();
            assertThat(afterRelease.reservations().stream()
                    .filter(value -> value.orderId() == 9002L)
                    .findFirst().orElseThrow().releasedUnits()).isEqualTo(290L);
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
            AccountUserCommand conflictingFunding = command("funding-conflict", AccountUserCommandType.FUNDING_SETTLE,
                    objectMapper.writeValueAsString(new FundingSettlementAccountCommand(
                            7001L, 7101L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                            "USDT", 10L, 1_000L, 10_000L, -40L)));

            assertThat(first.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(duplicate.status()).isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reducer.apply(conflictingFunding, 3L))
                    .isInstanceOf(AccountCommandPoisonPillException.class)
                    .hasMessageContaining("不同资金事实");
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

    @Test
    void deliverySettlementReleasesMarginClosesPositionAndIsIdempotent() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-delivery-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.LINEAR_DELIVERY, List.of(deliveryInstrument("BTC-USDT-260327", 4L)),
                java.util.Map.of("USDT", 1L));
        PerpetualAccountStateUpdatedEvent initial = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.LINEAR_DELIVERY, 1001L, AccountType.USDT_DELIVERY.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 900L, 100L)), List.of(),
                List.of(new PerpetualAccountStateUpdatedEvent.Position("BTC-USDT-260327", 4L,
                        MarginMode.CROSS, PositionSide.NET, 10L, 100L, 1_000L, 0L,
                        Instant.parse("2026-08-02T00:00:00Z"))),
                List.of(new PerpetualAccountStateUpdatedEvent.PositionMargin("BTC-USDT-260327", "USDT",
                        MarginMode.CROSS, PositionSide.NET, 100L)), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-02T00:00:00Z"), "delivery");
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(objectMapper, store,
                    new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(initial);
            AccountUserCommand command = command(ProductLine.LINEAR_DELIVERY, "delivery-settle-1",
                    AccountUserCommandType.DELIVERY_SETTLE, objectMapper.writeValueAsString(
                            new com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand(
                                    "BTC-USDT-260327", 4L, MarginMode.CROSS, PositionSide.NET, 130L, 0L,
                                    "DELIVERY_SETTLEMENT", "到期交割", Instant.now())));

            assertThat(reducer.apply(command, 1L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState settled = reducer.state(new UserPartitionKey(
                    ProductLine.LINEAR_DELIVERY, 1001L)).orElseThrow();
            assertThat(settled.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_300L, 0L));
            assertThat(settled.snapshot().positions()).singleElement()
                    .satisfies(position -> assertThat(position.signedQuantitySteps()).isZero());
            assertThat(settled.snapshot().positionMargins()).isEmpty();

            AccountUserCommand replay = command(ProductLine.LINEAR_DELIVERY, "delivery-settle-replay",
                    AccountUserCommandType.DELIVERY_SETTLE, command.payload());
            assertThat(reducer.apply(replay, 2L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.state(new UserPartitionKey(ProductLine.LINEAR_DELIVERY, 1001L))
                    .orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_300L, 0L));
        }
    }

    @Test
    void optionExercisePaysIntrinsicValueToLongAndChargesShortWithoutDoubleSettlement() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-option-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.OPTION, List.of(optionInstrument("BTC-USDT-260327-50000-C", 6L)),
                java.util.Map.of("USDT", 1L));
        PerpetualAccountStateUpdatedEvent initial = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.OPTION, 1001L, AccountType.OPTION.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 800L, 100L)), List.of(),
                List.of(new PerpetualAccountStateUpdatedEvent.Position("BTC-USDT-260327-50000-C", 6L,
                        MarginMode.CROSS, PositionSide.NET, -2L, 120L, 240L, 0L,
                        Instant.parse("2026-08-02T00:00:00Z"))),
                List.of(new PerpetualAccountStateUpdatedEvent.PositionMargin(
                        "BTC-USDT-260327-50000-C", "USDT", MarginMode.CROSS, PositionSide.NET, 100L)),
                List.of(), PositionMode.ONE_WAY, Instant.parse("2026-08-02T00:00:00Z"), "option");
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(objectMapper, store,
                    new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(initial);
            AccountUserCommand command = command(ProductLine.OPTION, "option-exercise-1",
                    AccountUserCommandType.OPTION_EXERCISE, objectMapper.writeValueAsString(
                            new com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand(
                                    "BTC-USDT-260327-50000-C", 6L, MarginMode.CROSS, PositionSide.NET, 0L, 30L,
                                    "OPTION_EXERCISE", "欧式自动行权", Instant.now())));

            assertThat(reducer.apply(command, 1L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState settled = reducer.state(new UserPartitionKey(
                    ProductLine.OPTION, 1001L)).orElseThrow();
            // 空头向买方支付 2 * 30，先释放 100 风险保证金后可用余额为 840。
            assertThat(settled.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 840L, 0L));
            assertThat(settled.snapshot().positions()).singleElement()
                    .satisfies(position -> assertThat(position.signedQuantitySteps()).isZero());
            assertThat(settled.snapshot().positionMargins()).isEmpty();

            AccountUserCommand replay = command(ProductLine.OPTION, "option-exercise-replay",
                    AccountUserCommandType.OPTION_EXERCISE, command.payload());
            assertThat(reducer.apply(replay, 2L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            assertThat(reducer.state(new UserPartitionKey(ProductLine.OPTION, 1001L))
                    .orElseThrow().snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 840L, 0L));
        }
    }

    @Test
    void optionTradeMovesPremiumBetweenBuyerAndSellerAndKeepsOnlySellerRiskMarginLocked() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-option-trade-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.OPTION, List.of(optionInstrument("BTC-USDT-260327-50000-C", 6L)),
                java.util.Map.of("USDT", 1L));
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(objectMapper, store,
                    new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(optionTradeSnapshot(1001L, 1_000L));
            reducer.initialize(optionTradeSnapshot(1002L, 1_000L));
            AccountUserCommand buyerReserve = commandForUser(ProductLine.OPTION, 1001L, "option-buyer-reserve",
                    AccountUserCommandType.ORDER_RESERVE, objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9101L, "BTC-USDT-260327-50000-C", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                            AccountType.OPTION, "USDT", MarginMode.CROSS, PositionSide.NET, 2L, false, 240L, 0L)));
            AccountUserCommand sellerReserve = commandForUser(ProductLine.OPTION, 1002L, "option-seller-reserve",
                    AccountUserCommandType.ORDER_RESERVE, objectMapper.writeValueAsString(new OrderReserveAccountCommand(
                            9102L, "BTC-USDT-260327-50000-C", OrderSide.SELL, OrderReservationKind.DERIVATIVE_MARGIN,
                            AccountType.OPTION, "USDT", MarginMode.CROSS, PositionSide.NET, 2L, false, 264L, 0L)));
            reducer.apply(buyerReserve, 1L);
            reducer.apply(sellerReserve, 1L);
            MatchTradeEvent trade = new MatchTradeEvent(8301L, 7301L, "BTC-USDT-260327-50000-C",
                    9101L, 6L, 1001L, OrderSide.BUY, MarginMode.CROSS, PositionSide.NET,
                    9102L, 6L, 1002L, MarginMode.CROSS, PositionSide.NET,
                    0L, 0L, 120L, 2L, true, true,
                    Instant.parse("2026-08-02T00:00:01Z"), "option-premium");
            AccountUserCommand buyerTrade = commandForUser(ProductLine.OPTION, 1001L, "option-buyer-trade",
                    AccountUserCommandType.TRADE_SIDE_SETTLE, objectMapper.writeValueAsString(
                            new TradeSideSettlementCommand(trade, TradeParticipantRole.TAKER, 2L, false,
                                    AccountType.OPTION, "USDT", 240L)));
            AccountUserCommand sellerTrade = commandForUser(ProductLine.OPTION, 1002L, "option-seller-trade",
                    AccountUserCommandType.TRADE_SIDE_SETTLE, objectMapper.writeValueAsString(
                            new TradeSideSettlementCommand(trade, TradeParticipantRole.MAKER, 2L, false,
                                    AccountType.OPTION, "USDT", 264L)));
            AccountUserStateReducer.Reduction buyerReduction = reducer.apply(buyerTrade, 2L);
            AccountUserStateReducer.Reduction sellerReduction = reducer.apply(sellerTrade, 2L);
            assertThat(buyerReduction.resultPayload()).contains("\"premiumUnits\":-240");
            assertThat(sellerReduction.resultPayload()).contains("\"premiumUnits\":240");

            assertThat(reducer.state(new UserPartitionKey(ProductLine.OPTION, 1001L)).orElseThrow()
                    .snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 760L, 0L));
            AccountUserReducerState seller = reducer.state(new UserPartitionKey(ProductLine.OPTION, 1002L))
                    .orElseThrow();
            assertThat(seller.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_216L, 24L));
            assertThat(seller.snapshot().positionMargins()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.PositionMargin(
                            "BTC-USDT-260327-50000-C", "USDT", MarginMode.CROSS, PositionSide.NET, 24L));

            MatchTradeEvent closeTrade = new MatchTradeEvent(8302L, 7302L, "BTC-USDT-260327-50000-C",
                    9103L, 6L, 1001L, OrderSide.SELL, MarginMode.CROSS, PositionSide.NET,
                    9104L, 6L, 1002L, MarginMode.CROSS, PositionSide.NET,
                    0L, 0L, 130L, 2L, true, true,
                    Instant.parse("2026-08-02T00:00:02Z"), "option-premium-close");
            AccountUserCommand buyerClose = commandForUser(ProductLine.OPTION, 1001L, "option-buyer-close",
                    AccountUserCommandType.TRADE_SIDE_SETTLE, objectMapper.writeValueAsString(
                            new TradeSideSettlementCommand(closeTrade, TradeParticipantRole.TAKER, 2L, true,
                                    null, null, 0L)));
            AccountUserCommand sellerClose = commandForUser(ProductLine.OPTION, 1002L, "option-seller-close",
                    AccountUserCommandType.TRADE_SIDE_SETTLE, objectMapper.writeValueAsString(
                            new TradeSideSettlementCommand(closeTrade, TradeParticipantRole.MAKER, 2L, true,
                                    null, null, 0L)));
            AccountUserStateReducer.Reduction buyerCloseReduction = reducer.apply(buyerClose, 3L);
            AccountUserStateReducer.Reduction sellerCloseReduction = reducer.apply(sellerClose, 3L);
            assertThat(buyerCloseReduction.resultPayload()).contains("\"realizedPnlUnits\":0");
            assertThat(sellerCloseReduction.resultPayload()).contains("\"realizedPnlUnits\":0");
            assertThat(reducer.state(new UserPartitionKey(ProductLine.OPTION, 1001L)).orElseThrow()
                    .snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 1_020L, 0L));
            assertThat(reducer.state(new UserPartitionKey(ProductLine.OPTION, 1002L)).orElseThrow()
                    .snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 980L, 0L));
        }
    }

    @Test
    void adlTargetSettlementClosesOnlyPlannedQuantityAndTransfersCoveredProfitLocally() throws Exception {
        Path directory = Files.createTempDirectory("account-state-reducer-adl-");
        ObjectMapper objectMapper = new ObjectMapper();
        InstrumentSnapshotCache instruments = new InstrumentSnapshotCache();
        instruments.replace(ProductLine.LINEAR_PERPETUAL, List.of(instrument("BTC-USDT", 1L)),
                java.util.Map.of("USDT", 1L));
        PerpetualAccountStateUpdatedEvent initial = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.LINEAR_PERPETUAL, 1001L, AccountType.USDT_PERPETUAL.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", 100L, 50L)), List.of(),
                List.of(new PerpetualAccountStateUpdatedEvent.Position("BTC-USDT", 1L,
                        MarginMode.CROSS, PositionSide.NET, 10L, 100L, 1_000L, 0L,
                        Instant.parse("2026-08-02T00:00:00Z"))),
                List.of(new PerpetualAccountStateUpdatedEvent.PositionMargin("BTC-USDT", "USDT",
                        MarginMode.CROSS, PositionSide.NET, 50L)), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-02T00:00:00Z"), "adl");
        try (UserPartitionStateStore store = new UserPartitionStateStore(directory)) {
            AccountUserStateReducer reducer = new AccountUserStateReducer(objectMapper, store,
                    new UserPartitionCommandLane(), instruments, new PositionCalculator());
            reducer.initialize(initial);
            AccountUserCommand command = command(ProductLine.LINEAR_PERPETUAL, "adl-target-1",
                    AccountUserCommandType.ADL_TARGET_SETTLE, objectMapper.writeValueAsString(
                            new AdlTargetSettlementAccountCommand(7001L, 2002L, "USDT", "BTC-USDT",
                                    MarginMode.CROSS, PositionSide.NET, 10L, 5L, 100L, 120L, 100L, 80L)));

            assertThat(reducer.apply(command, 1L).status())
                    .isEqualTo(AccountUserStateReducer.ApplyStatus.APPLIED);
            AccountUserReducerState settled = reducer.state(new UserPartitionKey(
                    ProductLine.LINEAR_PERPETUAL, 1001L)).orElseThrow();
            assertThat(settled.snapshot().balances()).containsExactly(
                    new PerpetualAccountStateUpdatedEvent.Balance("USDT", 145L, 25L));
            assertThat(settled.snapshot().positions()).singleElement()
                    .satisfies(position -> {
                        assertThat(position.signedQuantitySteps()).isEqualTo(5L);
                        assertThat(position.realizedPnlUnits()).isEqualTo(100L);
                    });
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
        return command(ProductLine.LINEAR_PERPETUAL, commandId, type, payload);
    }

    private AccountUserCommand command(ProductLine productLine,
                                       String commandId,
                                       AccountUserCommandType type,
                                       String payload) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                productLine, 1001L, type, "TEST", commandId, null, payload,
                Instant.parse("2026-08-02T00:00:00Z"), "trace-" + commandId);
    }

    private AccountUserCommand commandForUser(ProductLine productLine,
                                              long userId,
                                              String commandId,
                                              AccountUserCommandType type,
                                              String payload) {
        return new AccountUserCommand(AccountUserCommand.CURRENT_SCHEMA_VERSION, commandId,
                productLine, userId, type, "TEST", commandId, null, payload,
                Instant.parse("2026-08-02T00:00:00Z"), "trace-" + commandId);
    }

    private PerpetualAccountStateUpdatedEvent optionTradeSnapshot(long userId, long availableUnits) {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 1L, 1L,
                ProductLine.OPTION, userId, AccountType.OPTION.name(),
                List.of(new PerpetualAccountStateUpdatedEvent.Balance("USDT", availableUnits, 0L)), List.of(),
                List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-08-02T00:00:00Z"), "option-trade");
    }

    private InstrumentResponse instrument(String symbol, long version) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", 1_000_000L, "USDT",
                1L, 1L, 1L, 100_000L, 1L, 1_000_000_000L, 1L,
                1, 3, List.of("LIMIT"), List.of("GTC"), true, true, true,
                100_000_000L, 10_000L, 5_000L, 2L, 5L,
                500_000_000_000_000L, 300_000L, 25_000_000_000_000L,
                8, 0L, 100_000L, -100_000L, 1_000_000_000L, 1,
                null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }

    private InstrumentResponse spotInstrument(String symbol, long version) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.SPOT, ContractType.SPOT,
                "BTC", "USDT", "USDT", 1L, "USDT", 1L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 8, 8, List.of("LIMIT"), List.of("GTC"),
                false, false, true, 1_000_000L, 1_000_000L, 1_000_000L,
                10_000L, 10_000L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L, 1,
                null, null, null, null, null, null, null, InstrumentStatus.TRADING,
                now, now, now, List.of(), List.of());
    }

    private InstrumentResponse deliveryInstrument(String symbol, long version) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.DELIVERY,
                ContractType.LINEAR_DELIVERY, "BTC", "USDT", "USDT", 1L, "USDT",
                1L, 1L, 1L, 100_000L, 1L, 1_000_000L, 1L, 1, 3,
                List.of("LIMIT"), List.of("GTC"), true, true, true,
                100_000_000L, 100_000L, 50_000L, 0L, 0L, 1_000_000L,
                0L, 0L, 0, 0L, 0L, 0L, 0L, 1, now, now, null, null,
                null, null, null, InstrumentStatus.CLOSED, now, now, now, List.of(), List.of());
    }

    private InstrumentResponse optionInstrument(String symbol, long version) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.OPTION,
                ContractType.VANILLA_OPTION, "BTC", "USDT", "USDT", 1L, "USDT",
                1L, 1L, 1L, 100_000L, 1L, 1_000_000L, 1L, 1, 3,
                List.of("LIMIT"), List.of("GTC"), true, true, true,
                100_000_000L, 100_000L, 50_000L, 0L, 0L, 1_000_000L,
                0L, 0L, 0, 0L, 0L, 0L, 0L, 1, now, now, "BTC-USDT", 50_000L,
                com.surprising.instrument.api.model.OptionType.CALL,
                com.surprising.instrument.api.model.OptionExerciseStyle.EUROPEAN,
                com.surprising.instrument.api.model.ContractSettlementMethod.CASH,
                InstrumentStatus.CLOSED, now, now, now, List.of(), List.of());
    }
}
