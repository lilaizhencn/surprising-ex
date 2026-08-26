package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreLifecycleStateTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void linearFundingIsZeroSumAndSettlementIdSurvivesSnapshot() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));

        long totalBefore = total(state, "USDT");
        TradingCoreState funded = reducer.applyFunding(state,
                new ApplyFundingCommand(91, "BTC-USDT", 1, 10_000));

        assertThat(total(funded, "USDT")).isEqualTo(totalBefore);
        assertThat(funded.user(1).totalUnits("USDT")).isEqualTo(990);
        assertThat(funded.user(2).totalUnits("USDT")).isEqualTo(1_010);
        assertThat(funded.treasuryState().insuranceBalances()).isEmpty();
        assertThatThrownBy(() -> reducer.applyFunding(funded,
                new ApplyFundingCommand(91, "BTC-USDT", 1, 10_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_SETTLEMENT_ID"));

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(funded), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(funded);
        assertThat(restored.treasuryState().fundingSettlements()).containsEntry("BTC-USDT", 91L);
    }

    @Test
    void fundingApplicationEmitsActualZeroSumPaymentFacts() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));

        TradingCoreReducer.FundingApplication application = reducer.applyFundingWithFacts(state,
                new ApplyFundingCommand(92, "BTC-USDT", 1, 10_000));

        assertThat(application.payments()).hasSize(2);
        assertThat(application.payments()).extracting(payment -> payment.userId())
                .containsExactly(1L, 2L);
        assertThat(application.payments()).extracting(payment -> payment.amountUnits())
                .containsExactly(-10L, 10L);
        assertThat(application.payments().stream().mapToLong(payment -> payment.amountUnits()).sum()).isZero();
        assertThat(application.state().user(1).totalUnits("USDT") - state.user(1).totalUnits("USDT"))
                .isEqualTo(-10);
        assertThat(application.state().user(2).totalUnits("USDT") - state.user(2).totalUnits("USDT"))
                .isEqualTo(10);
    }

    @Test
    void fundingFactsPreserveHedgedLegsAndCapDebitsAtAvailableCash() {
        TradingCoreState base = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 0);
        CoreUserState current = base.user(1);
        Map<String, CorePositionState> hedgedPositions = new TreeMap<>();
        hedgedPositions.put("BTC-USDT:LONG", new CorePositionState("BTC-USDT", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.LONG, 1, 10, 100, 1_000, 0, 0));
        hedgedPositions.put("BTC-USDT:SHORT", new CorePositionState("BTC-USDT", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.SHORT, 1, -10, 100, 1_000, 0, 0));
        CoreUserState hedgedUser = new CoreUserState(base.productLine(), 1, current.revision() + 1,
                current.balances(), current.reservations(), hedgedPositions,
                com.surprising.aeron.protocol.CorePositionMode.HEDGE);
        Map<Long, CoreUserState> users = new TreeMap<>(base.users());
        users.put(1L, hedgedUser);
        TradingCoreState hedged = new TradingCoreState(base.productLine(), base.revision() + 1, users,
                base.orders(), base.instruments(), base.riskState(), base.treasuryState());
        hedged = reducer.applyMarkPrice(hedged, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));

        TradingCoreReducer.FundingApplication netZero = reducer.applyFundingWithFacts(hedged,
                new ApplyFundingCommand(93, "BTC-USDT", 1, 10_000));

        assertThat(netZero.payments()).extracting(payment -> payment.positionSide())
                .containsExactly(com.surprising.aeron.protocol.CorePositionSide.LONG,
                        com.surprising.aeron.protocol.CorePositionSide.SHORT);
        assertThat(netZero.payments()).extracting(payment -> payment.amountUnits())
                .containsExactly(-10L, 10L);
        assertThat(netZero.state().user(1)).isEqualTo(hedged.user(1));

        TradingCoreState lowCash = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 5, 0);
        lowCash = reducer.applyMarkPrice(lowCash, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));
        TradingCoreReducer.FundingApplication capped = reducer.applyFundingWithFacts(lowCash,
                new ApplyFundingCommand(94, "BTC-USDT", 1, 10_000));

        assertThat(capped.payments()).singleElement().satisfies(payment ->
                assertThat(payment.amountUnits()).isEqualTo(-5));
        assertThat(capped.state().user(1).totalUnits("USDT")).isZero();
        assertThat(capped.state().treasuryState().insuranceBalances()).doesNotContainKey("USDT");
        assertThat(capped.state().treasuryState().fundingResidualBalances()).containsEntry("USDT", 5L);
    }

    @Test
    void fundingCursorPersistsAcrossSnapshotAndCompletesExactlyOnce() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));
        ApplyFundingCommand firstCommand = new ApplyFundingCommand(95, "BTC-USDT", 1, 10_000, 0, 1);

        TradingCoreReducer.FundingApplication first = reducer.applyFundingWithFacts(state, firstCommand,
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000095"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().nextCursorUserId()).isEqualTo(1);
        assertThat(first.state().treasuryState().fundingProgress("BTC-USDT")).isNotNull();
        assertThat(first.state().treasuryState().fundingSettlements()).doesNotContainKey("BTC-USDT");
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(first.state());

        TradingCoreReducer.FundingApplication second = reducer.applyFundingWithFacts(restored,
                new ApplyFundingCommand(95, "BTC-USDT", 1, 10_000, 1, 1), List.of(1L, 2L),
                UUID.fromString("00000000-0000-0000-0000-000000000096"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().fundingProgress("BTC-USDT")).isNull();
        assertThat(second.state().treasuryState().fundingSettlements()).containsEntry("BTC-USDT", 95L);
        assertThat(second.payments()).extracting(payment -> payment.userId()).containsExactly(2L);
    }

    @Test
    void runtimeFundingMatchesAuthoritativeStateForNetZeroCappedAndChunkedSettlements() {
        TradingCoreState netZero = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        netZero = reducer.applyMarkPrice(netZero, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));
        ApplyFundingCommand netZeroCommand = new ApplyFundingCommand(101, "BTC-USDT", 1, 10_000);
        TradingCoreReducer.FundingApplication netZeroExpected = reducer.applyFundingWithFacts(netZero,
                netZeroCommand);
        RuntimeIdentityRegistry netZeroIdentities = new RuntimeIdentityRegistry();
        TradingRuntimeState netZeroRuntime = RuntimeStateProjector.project(netZero, netZeroIdentities);
        var netZeroActual = RuntimePerpetualFundingProcessor.apply(netZero, netZeroCommand,
                null, null, netZeroRuntime, netZeroIdentities);
        RuntimeStateParityChecker.assertMatches(netZeroExpected.state(), netZeroIdentities, netZeroActual.state());
        assertThat(netZeroActual.payments()).isEqualTo(netZeroExpected.payments());
        assertThat(netZeroActual.progress()).isEqualTo(netZeroExpected.progress());

        TradingCoreState capped = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 5, 0);
        capped = reducer.applyMarkPrice(capped, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1,
                1_700_000_000_000L));
        ApplyFundingCommand cappedCommand = new ApplyFundingCommand(102, "BTC-USDT", 1, 10_000);
        TradingCoreReducer.FundingApplication cappedExpected = reducer.applyFundingWithFacts(capped, cappedCommand);
        RuntimeIdentityRegistry cappedIdentities = new RuntimeIdentityRegistry();
        var cappedActual = RuntimePerpetualFundingProcessor.simulate(capped, cappedCommand,
                null, null, cappedIdentities);
        RuntimeStateParityChecker.assertMatches(cappedExpected.state(), cappedIdentities, cappedActual.state());
        assertThat(cappedActual.payments()).isEqualTo(cappedExpected.payments());

        TradingCoreState chunked = netZero;
        UUID firstCommandId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        ApplyFundingCommand firstCommand = new ApplyFundingCommand(103, "BTC-USDT", 1, 10_000, 0, 1);
        TradingCoreReducer.FundingApplication firstExpected = reducer.applyFundingWithFacts(chunked, firstCommand,
                List.of(1L, 2L), firstCommandId);
        RuntimeIdentityRegistry chunkedIdentities = new RuntimeIdentityRegistry();
        TradingRuntimeState chunkedRuntime = RuntimeStateProjector.project(chunked, chunkedIdentities);
        var firstActual = RuntimePerpetualFundingProcessor.apply(chunked, firstCommand,
                List.of(1L, 2L), firstCommandId, chunkedRuntime, chunkedIdentities);
        RuntimeStateParityChecker.assertMatches(firstExpected.state(), chunkedIdentities, firstActual.state());
        assertThat(firstActual.payments()).isEqualTo(firstExpected.payments());
        assertThat(firstActual.progress()).isEqualTo(firstExpected.progress());

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(firstExpected.state()), ProductLine.LINEAR_PERPETUAL);
        UUID secondCommandId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        ApplyFundingCommand secondCommand = new ApplyFundingCommand(103, "BTC-USDT", 1, 10_000, 1, 1);
        TradingCoreReducer.FundingApplication secondExpected = reducer.applyFundingWithFacts(restored, secondCommand,
                List.of(1L, 2L), secondCommandId);
        var secondActual = RuntimePerpetualFundingProcessor.apply(restored, secondCommand,
                List.of(1L, 2L), secondCommandId, firstActual.state(), chunkedIdentities);
        RuntimeStateParityChecker.assertMatches(secondExpected.state(), chunkedIdentities, secondActual.state());
        assertThat(secondActual.payments()).isEqualTo(secondExpected.payments());
        assertThat(secondActual.progress()).isEqualTo(secondExpected.progress());
    }

    @Test
    void runtimeFundingRejectsMissingMarkStaleSettlementAndInvalidCursor() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        ApplyFundingCommand command = new ApplyFundingCommand(104, "BTC-USDT", 1, 10_000);
        assertThatThrownBy(() -> RuntimePerpetualFundingProcessor.simulate(state, command,
                null, null, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MARK_PRICE_NOT_FOUND"));

        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_700_000_000_000L));
        TradingCoreState funded = reducer.applyFunding(marked, command);
        assertThatThrownBy(() -> RuntimePerpetualFundingProcessor.simulate(funded, command,
                null, null, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_SETTLEMENT_ID"));

        ApplyFundingCommand invalidCursor = new ApplyFundingCommand(105, "BTC-USDT", 1, 10_000, 1, 1);
        assertThatThrownBy(() -> RuntimePerpetualFundingProcessor.simulate(marked, invalidCursor,
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000105"),
                new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_COMMAND"));
    }

    @Test
    void deliveryAndOptionSettlementReleaseMarginFlattenPositionsAndConserveFunds() {
        TradingCoreState delivery = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        long deliveryBefore = total(delivery, "USDT");
        TradingCoreState delivered = reducer.settleInstrument(delivery,
                new SettleInstrumentCommand(71, "BTC-USDT", 1, 120, 0));

        assertThat(total(delivered, "USDT")).isEqualTo(deliveryBefore);
        assertThat(delivered.users().values()).allSatisfy(user -> {
            assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero();
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
        });

        TradingCoreState option = stateWithOppositePositions(ProductLine.OPTION,
                ContractType.VANILLA_OPTION, 10, 2, 30);
        long optionBefore = total(option, "USDT");
        TradingCoreState exercised = reducer.settleInstrument(option,
                new SettleInstrumentCommand(72, "BTC-USDT", 1, 120, 25));

        assertThat(total(exercised, "USDT")).isEqualTo(optionBefore);
        assertThat(exercised.user(1).totalUnits("USDT")).isEqualTo(1_040);
        assertThat(exercised.user(2).totalUnits("USDT")).isEqualTo(960);
        assertThat(exercised.users().values()).allSatisfy(user ->
                assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero());
    }

    @Test
    void settlementAcceptsNewerInstrumentLifecycleVersionWithoutReplacingOpenExecutionVersion() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);

        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(74, "BTC-USDT", 3, 120, 0));

        assertThat(settled.instruments().get("BTC-USDT").version()).isEqualTo(1);
        assertThat(settled.users().values()).allSatisfy(user ->
                assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero());
    }

    @Test
    void duplicateSettlementIsIdempotentAfterPositionsAndOrdersAreClosed() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(73, "BTC-USDT", 1, 120, 999));

        TradingCoreState duplicate = reducer.settleInstrument(settled,
                new SettleInstrumentCommand(73, "BTC-USDT", 1, 1, 1));

        assertThat(duplicate).isSameAs(settled);
        assertThat(duplicate.treasuryState().lifecycleSettlements()).containsEntry("BTC-USDT", 73L);
        assertThat(duplicate.users().values()).allSatisfy(user -> {
            assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero();
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
        });
    }

    @Test
    void lifecycleSettlementCursorPersistsAcrossSnapshotAndCompletesExactlyOnce() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(96, "BTC-USDT", 1,
                120, 0, 0, 1);

        TradingCoreReducer.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                firstCommand, List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000096"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().nextCursorUserId()).isEqualTo(1);
        assertThat(first.state().treasuryState().lifecycleProgress("BTC-USDT")).isNotNull();
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), ProductLine.LINEAR_DELIVERY);
        assertThat(restored).isEqualTo(first.state());

        TradingCoreReducer.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                new SettleInstrumentCommand(96, "BTC-USDT", 1, 120, 0, 1, 1), List.of(1L, 2L),
                UUID.fromString("00000000-0000-0000-0000-000000000097"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().lifecycleProgress("BTC-USDT")).isNull();
        assertThat(second.state().treasuryState().lifecycleSettlements()).containsEntry("BTC-USDT", 96L);
        assertThat(second.state().users().values()).allSatisfy(user ->
                assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero());
    }

    @Test
    void lifecycleSettlementProcessesOpenOrdersInBoundedOrderChunks() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_700_000_000_000L));
        state = reducer.placeOrder(state, 1, lifecycleOrder(101));
        state = reducer.placeOrder(state, 2, lifecycleOrder(102));

        TradingCoreReducer.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                new SettleInstrumentCommand(97, "BTC-USDT", 1, 120, 0, 0, 256, 0, 1),
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000097"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().ordersComplete()).isFalse();
        assertThat(first.progress().nextCursorOrderId()).isEqualTo(102);
        assertThat(first.state().order(102).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(first.state().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);

        TradingCoreReducer.SettlementApplication second = reducer.settleInstrumentWithProgress(first.state(),
                new SettleInstrumentCommand(97, "BTC-USDT", 1, 120, 0, 0, 256, 102, 1),
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000098"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().order(102).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(second.state().treasuryState().lifecycleSettlements()).containsEntry("BTC-USDT", 97L);
    }

    @Test
    void liquidationCreatesExplicitDeficitAndInsuranceReceiptClosesIt() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1,
                1_700_000_000_000L));
        CoreLiquidationState plan = state.riskState().liquidations().get(1L);
        assertThat(plan.status()).isEqualTo(CoreLiquidationState.Status.PLANNED);

        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));

        assertThat(liquidated.user(1).positions().get("BTC-USDT").signedQuantitySteps()).isZero();
        assertThat(liquidated.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        assertThat(deficit).isPositive();

        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", deficit));
        TradingCoreState resolved = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, deficit));
        assertThat(resolved.treasuryState().insuranceBalances()).containsEntry("USDT", 100L);
        assertThat(resolved.user(1).totalUnits("USDT")).isEqualTo(deficit);
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
    }

    @Test
    void liquidationCannotCompleteWhileDeficitRemains() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1,
                1_700_000_000_000L));
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(
                1, ResolveLiquidationCommand.Resolution.COMPLETED, 0);

        assertThatThrownBy(() -> reducer.resolveLiquidation(liquidated, command))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIQUIDATION_DEFICIT_REMAINS"));
        assertThatThrownBy(() -> RuntimePerpetualLiquidationProcessor.simulateResolution(
                liquidated, command, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIQUIDATION_DEFICIT_REMAINS"));
        assertThat(liquidated.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
    }

    @Test
    void liquidationRejectsStaleTriggerSequenceWithoutMutation() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1,
                1_700_000_000_000L));
        long hash = state.businessStateHash();
        TradingCoreState planned = state;

        assertThatThrownBy(() -> reducer.executeLiquidation(planned,
                new ExecuteLiquidationCommand(1, 2, 90, 100_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));
        assertThat(planned.businessStateHash()).isEqualTo(hash);
        assertThat(planned.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.PLANNED);
    }

    @Test
    void riskRecoveryCancelsPlanWithoutClosingPosition() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1,
                1_700_000_000_000L));

        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand(
                "BTC-USDT", 1, 100, 2, 1_700_000_000_000L);
        TradingCoreState recovered = reducer.applyMarkPrice(state, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        RuntimeStateParityChecker.assertMatches(recovered, identities,
                RuntimePerpetualRiskProcessor.simulateMarkPrice(
                        state, command, state.users().keySet(), identities));

        assertThat(recovered.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.CANCELED);
        assertThat(recovered.user(1).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(10);
    }

    @Test
    void liquidationFeeIsCappedByCollateralAndCreditedToInsurance() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1,
                1_700_000_000_000L));
        long before = total(state, "USDT");

        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 90, 100_000));

        CoreLiquidationState result = liquidated.riskState().liquidations().get(1L);
        assertThat(result.status()).isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(result.executionPriceTicks()).isEqualTo(90);
        assertThat(result.liquidationFeeRatePpm()).isEqualTo(100_000);
        assertThat(result.liquidationFeeUnits()).isEqualTo(80);
        assertThat(liquidated.treasuryState().insuranceBalances()).containsEntry("USDT", 180L);
        assertThat(liquidated.user(1).totalUnits("USDT")).isZero();
        assertThat(total(liquidated, "USDT")).isEqualTo(before);
        assertThat(reducer.riskSnapshots(liquidated, 1)).isEmpty();
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(liquidated), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(liquidated);
        assertThat(restored.businessStateHash()).isEqualTo(liquidated.businessStateHash());
    }

    @Test
    void partialInsuranceCoverageLeavesOnlyResidualForAdl() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1,
                1_700_000_000_000L));
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));

        TradingCoreState resolved = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, 25));

        assertThat(resolved.treasuryState().insuranceBalances()).containsEntry("USDT", 100L);
        assertThat(resolved.riskState().liquidations().get(1L).deficitUnits()).isEqualTo(deficit - 25);
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
    }

    @Test
    void adlAtomicallyDeleveragesProfitableCounterpartyAndCoversResidual() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = withPositionAndBalance(state, 2, -10, 200, 1_000, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1,
                1_700_000_000_000L));
        state = reducer.executeLiquidation(state, new ExecuteLiquidationCommand(1, 1, 1, 0));
        long deficit = state.riskState().liquidations().get(1L).deficitUnits();
        state = reducer.adjustInsuranceFund(state,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));
        state = reducer.resolveLiquidation(state,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, 25));
        long before = totalEconomicEquity(state, "USDT");
        TradingCoreState beforeAdl = state;

        var command = new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "BTC-USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                -10, 200, 1, 5, deficit - 25);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtimeResolved = RuntimePerpetualLiquidationProcessor.simulateAdl(
                state, command, identities);
        TradingCoreState resolved = reducer.executeAdl(state, command);
        RuntimeStateParityChecker.assertMatches(resolved, identities, runtimeResolved);

        assertThat(resolved.user(2).positions().get("BTC-USDT").signedQuantitySteps()).isEqualTo(-5);
        assertThat(resolved.riskState().liquidations().get(1L).deficitUnits()).isZero();
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(totalEconomicEquity(resolved, "USDT")).isEqualTo(before);
        assertThatCode(() -> FundsDelta.between(beforeAdl, resolved, Set.of(2L), Set.of("USDT"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void runtimeAdlRejectsStaleMarkChangedPositionAndInsufficientProfit() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = withPositionAndBalance(state, 2, -10, 200, 1_000, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1,
                1_700_000_000_000L));
        state = reducer.executeLiquidation(state, new ExecuteLiquidationCommand(1, 1, 1, 0));
        state = reducer.adjustInsuranceFund(state,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));
        TradingCoreState beforeAdl = reducer.resolveLiquidation(state,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, 25));
        long residual = beforeAdl.riskState().liquidations().get(1L).deficitUnits();

        assertThatThrownBy(() -> RuntimePerpetualLiquidationProcessor.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "BTC-USDT",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -9, 200, 1, 5, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ADL_POSITION_CONFLICT"));

        assertThatThrownBy(() -> RuntimePerpetualLiquidationProcessor.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "BTC-USDT",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -10, 200, 2, 5, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));

        assertThatThrownBy(() -> RuntimePerpetualLiquidationProcessor.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "BTC-USDT",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -10, 200, 1, 1, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ADL_PROFIT_INSUFFICIENT"));
    }

    private TradingCoreState stateWithOppositePositions(
            ProductLine productLine,
            ContractType type,
            long entryPrice,
            long quantity,
            long margin) {
        TradingCoreState state = stateWithUser(productLine, type, 1, quantity, entryPrice, 1_000, margin);
        return withPositionAndBalance(state, 2, -quantity, entryPrice, 1_000, margin);
    }

    private TradingCoreState stateWithUser(
            ProductLine productLine,
            ContractType type,
            long userId,
            long quantity,
            long entryPrice,
            long wallet,
            long margin) {
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(productLine),
                new com.surprising.aeron.protocol.UpsertInstrumentCommand("BTC-USDT", 1, type.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0,
                        expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0));
        return withPositionAndBalance(state, userId, quantity, entryPrice, wallet, margin);
    }

    private TradingCoreState withPositionAndBalance(
            TradingCoreState state,
            long userId,
            long quantity,
            long entryPrice,
            long wallet,
            long margin) {
        TradingCoreState funded = reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand("USDT", wallet));
        CoreUserState current = funded.user(userId);
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put("USDT", new AssetBalance("USDT", wallet - margin, margin));
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put("BTC-USDT", new CorePositionState("BTC-USDT", "USDT", 1, quantity,
                entryPrice, Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, margin));
        CoreUserState user = new CoreUserState(funded.productLine(), userId, current.revision() + 1,
                balances, current.reservations(), positions);
        Map<Long, CoreUserState> users = new TreeMap<>(funded.users());
        users.put(userId, user);
        return new TradingCoreState(funded.productLine(), funded.revision() + 1, users, funded.orders(),
                funded.instruments(), funded.riskState(), funded.treasuryState());
    }

    private static PlaceOrderCommand lifecycleOrder(long orderId) {
        return new PlaceOrderCommand(orderId, "BTC-USDT", 1, CoreOrderSide.BUY, 10, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
    }

    private static long total(TradingCoreState state, String asset) {
        long users = state.users().values().stream().mapToLong(user -> user.totalUnits(asset)).sum();
        long fee = state.treasuryState().feeBalances().getOrDefault(asset, 0L);
        long insurance = state.treasuryState().insuranceBalances().getOrDefault(asset, 0L);
        long deficit = state.treasuryState().insuranceDeficits().getOrDefault(asset, 0L);
        return Math.subtractExact(Math.addExact(Math.addExact(users, fee), insurance), deficit);
    }

    private static long totalEconomicEquity(TradingCoreState state, String asset) {
        long unrealized = 0;
        for (CoreUserState user : state.users().values()) {
            for (CorePositionState position : user.positions().values()) {
                CoreInstrumentState instrument = state.instruments().get(position.symbol());
                CoreMarkPriceState mark = state.riskState().markPrices().get(position.symbol());
                if (position.signedQuantitySteps() != 0 && mark != null && instrument.settleAsset().equals(asset)) {
                    unrealized = Math.addExact(unrealized, CoreContractMath.pnlUnits(instrument,
                            position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks()));
                }
            }
        }
        long unresolved = state.riskState().liquidations().values().stream()
                .filter(liquidation -> state.instruments().get(liquidation.symbol()).settleAsset().equals(asset))
                .mapToLong(CoreLiquidationState::deficitUnits).sum();
        return Math.subtractExact(Math.addExact(total(state, asset), unrealized), unresolved);
    }
}
