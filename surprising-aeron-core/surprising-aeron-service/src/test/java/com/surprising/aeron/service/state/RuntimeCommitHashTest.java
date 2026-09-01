package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.matching.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeCommitHashTest {
    private static final ProductLine PRODUCT_LINE = ProductLine.LINEAR_PERPETUAL;
    private static final String SYMBOL = "BTC-USDT";
    private static final String ASSET = "USDT";
    private static final LaneTopology FOUR_LANES = new LaneTopology(
            LaneTopology.ROUTE_VERSION, 4, 1, 3, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED,
            LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE, LaneTopology.DEFAULT_QUEUE_CAPACITY,
            LaneTopology.DEFAULT_QUEUE_CAPACITY);

    @Test
    void randomizedRealFourLaneLifecycleMatchesCanonicalHashesAfterEveryPatchAndRestore() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = new ArrayList<>(oneUserPerLane());
        Collections.shuffle(users, new Random(0x5eed5eedL));
        TradingCoreState state = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities, FOUR_LANES);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(state, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(state, identities);
        long sequence = 0;

        Random random = new Random(0x51a7eL);
        for (int index = 0; index < 32; index++) {
            long userId = users.get(index % users.size());
            RuntimeCommandProcessor.adjustBalance(runtime, identities, userId,
                    new BalanceAdjustmentCommand(ASSET, random.nextLong(1, 1_001)));
            state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        }

        long makerUser = users.get(0);
        long takerUser = users.get(1);
        long cancelUser = users.get(2);
        long secondCancelUser = users.get(3);
        long makerOrder = 10_001;
        long takerOrder = 10_002;
        place(runtime, identities, makerUser, makerOrder, CoreOrderSide.SELL, 4);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        place(runtime, identities, takerUser, takerOrder, CoreOrderSide.BUY, 4);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        List<MatcherEvent> partial = List.of(trade(makerOrder, makerUser, 1_000, 1, false, false));
        TradingCoreState expectedPartial = reducer.applyMatches(state, takerOrder, "BTC", ASSET, partial);
        RuntimePerpetualMatchProcessor.applyTransition(
                state, expectedPartial, takerOrder, partial, runtime, identities);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        List<MatcherEvent> full = List.of(trade(makerOrder, makerUser, 1_000, 3, true, true));
        TradingCoreState expectedFull = reducer.applyMatches(state, takerOrder, "BTC", ASSET, full);
        RuntimePerpetualMatchProcessor.applyTransition(state, expectedFull, takerOrder, full, runtime, identities);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        place(runtime, identities, cancelUser, 10_003, CoreOrderSide.BUY, 2);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        RuntimeCommandProcessor.cancelOrder(runtime, cancelUser, 10_003);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        place(runtime, identities, secondCancelUser, 10_004, CoreOrderSide.SELL, 1);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        RuntimeCommandProcessor.cancelOrder(runtime, secondCancelUser, 10_004);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        RuntimePerpetualFundingProcessor.applyRuntime(
                new ApplyFundingCommand(7001, SYMBOL, 1, 10_000), users, null, runtime, identities);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);
        RuntimeCommandProcessor.adjustInsuranceFund(
                runtime, identities, new AdjustInsuranceFundCommand(ASSET, 7_777));
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        int symbolId = identities.symbolId(SYMBOL);
        long positionKey = identities.positionKey(takerUser, SYMBOL);
        PositionRuntime position = runtime.position(positionKey);
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, 1, 900, 2, 2));
        runtime.putRiskSnapshot(positionKey, new RiskSnapshotRuntime(
                takerUser, symbolId, CorePositionSide.NET, 2, 1, -400, 100, 2_000_000,
                CoreRiskStatus.LIQUIDATION));
        runtime.putLiquidation(new LiquidationRuntime(1, takerUser, symbolId, CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 2, position.signedQuantitySteps(),
                Math.absExact(position.signedQuantitySteps()), 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0));
        runtime.setNextLiquidationId(2);
        runtime.setMetadata(PRODUCT_LINE, Math.incrementExact(runtime.revision()));
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(1, 2, 900, 0);
        TradingCoreState expectedLiquidation = reducer.executeLiquidation(state, liquidation);
        RuntimePerpetualLiquidationProcessor.applyExecution(
                state, liquidation, List.of(), runtime, identities);
        state = captureAndAssert(++sequence, runtime, identities, state, business, funds);

        assertThat(runtime.topology().accountLaneCount()).isEqualTo(4);
        assertThat(users).extracting(runtime.topology()::accountLaneId)
                .containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(state.treasuryState().fundingSettlements()).containsEntry(SYMBOL, 7001L);
        assertThat(state).isEqualTo(expectedLiquidation);
        assertThat(state.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(state.treasuryState().insuranceBalances().get(ASSET)).isGreaterThan(7_777L);
        runtime.close();
    }

    @Test
    void preparedChangesDeriveAfterHashesWithoutMaterializationAndCommitAtomically() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        place(runtime, identities, users.getFirst(), 19_001, CoreOrderSide.BUY, 2);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(before, identities);
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), funds.value(), funds.value(), true);
        RuntimeCommitPatch.SealMetadata seal = captured.metadata();
        RuntimeCommitPatch.PreparedChanges changes = captured.builder().prepare(
                new RuntimeCommitPatch.PrepareMetadata(seal.beforeRevision(), seal.afterRevision(),
                        seal.beforeBusinessStateHash(), seal.beforeFundsStateHash(), seal.laneMask(),
                        seal.coreFactMetadata(), seal.externalAdjustment()), identities);

        RollingBusinessStateHash.HashTransition businessTransition = business.prepare(changes);
        RollingFundsStateHash.HashTransition fundsTransition = funds.prepare(changes);
        assertThat(business.value()).isEqualTo(businessTransition.beforeHash());
        assertThat(funds.value()).isEqualTo(fundsTransition.beforeHash());
        RuntimeCommitPatch patch = captured.builder().seal(
                changes, businessTransition.afterHash(), fundsTransition.afterHash());

        businessTransition.commit();
        funds.failAfterStagedOperationForTest(0);
        assertThatThrownBy(fundsTransition::commit).hasMessageContaining("injected mid-stage");
        businessTransition.rollback();
        assertThat(business.value()).isEqualTo(businessTransition.beforeHash());
        assertThat(funds.value()).isEqualTo(fundsTransition.beforeHash());

        assertThatThrownBy(businessTransition::commit).hasMessageContaining("ROLLED_BACK");
        assertThatThrownBy(fundsTransition::rollback).hasMessageContaining("PREPARED");
        RollingBusinessStateHash.HashTransition retryBusiness = business.prepare(changes);
        RollingFundsStateHash.HashTransition retryFunds = funds.prepare(changes);
        retryBusiness.commit();
        retryFunds.commit();
        assertThat(business.value()).isEqualTo(patch.businessStateHash());
        assertThat(funds.value()).isEqualTo(patch.fundsStateHash());
        runtime.close();
    }

    @Test
    void pendingReservationVisibilityTransitionMatchesCanonicalBusinessHash() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        long userId = users.getFirst();
        long orderId = 19_051;
        place(runtime, identities, userId, orderId, CoreOrderSide.BUY, 2);
        runtime.markPendingReservation(userId, orderId, 1);
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(before);
        runtime.clearChangedKeys();

        runtime.completePendingReservation(userId, orderId, 1);
        TradingCoreState materialized = RuntimeStateMaterializer.materialize(runtime, identities);
        TradingCoreState after = materialized;
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), RollingFundsStateHash.compute(before),
                RollingFundsStateHash.compute(after), true);
        RuntimeCommitPatch.PreparedChanges changes = captured.prepareChanges();

        RollingBusinessStateHash.HashTransition transition = business.prepare(changes);
        RuntimeCommitPatch patch = captured.seal(
                changes, transition.afterHash(), RollingFundsStateHash.compute(after));
        transition.commit();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(business.value()).isEqualTo(patch.businessStateHash());
        transition.rollback();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(before));
        runtime.close();
    }

    @Test
    void preparedBusinessStageReusesOneStageAcrossPreviewCommitRollbackAndRetry() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        RuntimeCommandProcessor.adjustBalance(runtime, identities, users.getFirst(),
                new BalanceAdjustmentCommand(ASSET, 37));
        TradingCoreState after = RuntimeStateMaterializer.materialize(runtime, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), RollingFundsStateHash.compute(before),
                RollingFundsStateHash.compute(after), true);
        RuntimeCommitPatch.PreparedChanges changes = captured.prepareChanges();

        RollingBusinessStateHash.HashTransition first = business.prepare(changes);
        first.commit();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        first.rollback();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(before));

        RollingBusinessStateHash.HashTransition retry = business.prepare(changes);
        retry.commit();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        runtime.close();
    }

    @Test
    void ownerAppliedHashTransitionsAvoidPreviewReplayAndRemainRollbackSafe() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        RuntimeCommandProcessor.adjustBalance(runtime, identities, users.getFirst(),
                new BalanceAdjustmentCommand(ASSET, 43));
        TradingCoreState after = RuntimeStateMaterializer.materialize(runtime, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(before, identities);
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), funds.value(), funds.value(), true);
        RuntimeCommitPatch.PreparedChanges changes = captured.prepareChanges();

        RollingBusinessStateHash.HashTransition businessAbort = business.prepareApplied(changes);
        RollingFundsStateHash.HashTransition fundsAbort = funds.prepareApplied(changes);
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(after));
        fundsAbort.rollback();
        businessAbort.rollback();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(before));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(before));

        RollingBusinessStateHash.HashTransition businessCommit = business.prepareApplied(changes);
        RollingFundsStateHash.HashTransition fundsCommit = funds.prepareApplied(changes);
        businessCommit.commit();
        fundsCommit.commit();
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(after));
        runtime.close();
    }

    @Test
    void canonicalOverlayBeforeHashDoesNotReplaceRawContributionValidation() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        RuntimeCommandProcessor.adjustBalance(runtime, identities, users.getFirst(),
                new BalanceAdjustmentCommand(ASSET, 41));
        TradingCoreState after = RuntimeStateMaterializer.materialize(runtime, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        long canonicalOverlayBefore = business.value() ^ 0x5a5a_5a5a_5a5a_5a5aL;
        long canonicalOverlayAfter = RollingBusinessStateHash.compute(after) ^ 0x5a5a_5a5a_5a5a_5a5aL;
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                canonicalOverlayBefore, canonicalOverlayAfter, RollingFundsStateHash.compute(before),
                RollingFundsStateHash.compute(after), true);
        RuntimeCommitPatch.PreparedChanges changes = captured.prepareChanges();

        RollingBusinessStateHash.HashTransition transition = business.prepare(changes);
        RuntimeCommitPatch patch = captured.seal(
                changes, canonicalOverlayAfter, RollingFundsStateHash.compute(after));
        transition.commit();
        assertThat(patch.beforeBusinessStateHash()).isEqualTo(canonicalOverlayBefore);
        assertThat(patch.businessStateHash()).isEqualTo(canonicalOverlayAfter);
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        runtime.close();
    }

    @Test
    void preparedHashTransitionsRejectStaleRepeatedAndForeignUseWithoutDrift() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        place(runtime, identities, users.getFirst(), 19_101, CoreOrderSide.BUY, 2);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(before, identities);
        TradingRuntimeState.PreparedCommit captured = runtime.prepareCommitPatch(
                1, identities, before.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), funds.value(), funds.value(), true);
        RuntimeCommitPatch.SealMetadata seal = captured.metadata();
        RuntimeCommitPatch.PreparedChanges changes = captured.builder().prepare(
                new RuntimeCommitPatch.PrepareMetadata(seal.beforeRevision(), seal.afterRevision(),
                        seal.beforeBusinessStateHash(), seal.beforeFundsStateHash(), seal.laneMask(),
                        seal.coreFactMetadata(), seal.externalAdjustment()), identities);
        RollingBusinessStateHash.HashTransition businessA = business.prepare(changes);
        RollingBusinessStateHash.HashTransition businessB = business.prepare(changes);
        RollingFundsStateHash.HashTransition fundsA = funds.prepare(changes);
        RollingFundsStateHash.HashTransition fundsB = funds.prepare(changes);
        RollingBusinessStateHash foreignBusiness = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash foreignFunds = RollingFundsStateHash.create(before, identities);
        long businessBefore = business.value();
        long fundsBefore = funds.value();

        assertThatThrownBy(businessA::rollback).hasMessageContaining("PREPARED");
        assertThatThrownBy(fundsA::rollback).hasMessageContaining("PREPARED");
        assertThatThrownBy(() -> foreignBusiness.commitForTest(businessA)).hasMessageContaining("foreign");
        assertThatThrownBy(() -> foreignFunds.commitForTest(fundsA)).hasMessageContaining("foreign");
        assertThat(foreignBusiness.value()).isEqualTo(businessBefore);
        assertThat(foreignFunds.value()).isEqualTo(fundsBefore);
        RuntimeCommitPatch.Builder foreignBuilder = RuntimeCommitPatch.builder(PRODUCT_LINE, 0, 1)
                .matcherTransition(unchangedMatcher());
        assertThatThrownBy(() -> foreignBuilder.seal(changes, businessA.afterHash(), fundsA.afterHash()))
                .hasMessageContaining("different builder");

        RuntimeCommitPatch firstPatch = captured.builder().seal(changes, businessA.afterHash(), fundsA.afterHash());
        assertThatThrownBy(() -> captured.builder().seal(changes, businessA.afterHash(), fundsA.afterHash()))
                .hasMessageContaining("already sealed");
        businessA.commit();
        fundsA.commit();
        assertThatThrownBy(businessA::commit).hasMessageContaining("COMMITTED");
        assertThatThrownBy(fundsA::commit).hasMessageContaining("COMMITTED");
        assertThatThrownBy(businessB::commit).hasMessageContaining("stale");
        assertThatThrownBy(fundsB::commit).hasMessageContaining("stale");
        assertThatThrownBy(() -> foreignBusiness.rollbackForTest(businessA)).hasMessageContaining("foreign");
        assertThatThrownBy(() -> foreignFunds.rollbackForTest(fundsA)).hasMessageContaining("foreign");
        assertThat(business.value()).isEqualTo(firstPatch.businessStateHash());
        assertThat(funds.value()).isEqualTo(firstPatch.fundsStateHash());

        runtime.clearChangedKeys();
        TradingCoreState afterFirst = RuntimeStateMaterializer.materialize(runtime, identities);
        RuntimeCommandProcessor.adjustBalance(runtime, identities, users.get(1),
                new BalanceAdjustmentCommand(ASSET, 17));
        TradingCoreState afterSecond = RuntimeStateMaterializer.materialize(runtime, identities);
        TradingRuntimeState.PreparedCommit capturedSecond = runtime.prepareCommitPatch(
                2, identities, afterFirst.revision(), unchangedMatcher(), 0,
                business.value(), business.value(), funds.value(), funds.value(), true);
        RuntimeCommitPatch.SealMetadata secondSeal = capturedSecond.metadata();
        RuntimeCommitPatch.PreparedChanges secondChanges = capturedSecond.builder().prepare(
                new RuntimeCommitPatch.PrepareMetadata(secondSeal.beforeRevision(), secondSeal.afterRevision(),
                        secondSeal.beforeBusinessStateHash(), secondSeal.beforeFundsStateHash(),
                        secondSeal.laneMask(), secondSeal.coreFactMetadata(), secondSeal.externalAdjustment()),
                identities);
        RollingBusinessStateHash.HashTransition businessC = business.prepare(secondChanges);
        RollingFundsStateHash.HashTransition fundsC = funds.prepare(secondChanges);
        RuntimeCommitPatch secondPatch = capturedSecond.builder().seal(
                secondChanges, businessC.afterHash(), fundsC.afterHash());
        businessC.commit();
        fundsC.commit();
        assertThat(business.value()).isEqualTo(secondPatch.businessStateHash());
        assertThat(funds.value()).isEqualTo(secondPatch.fundsStateHash());
        assertHashAndRestoreParity(afterSecond, business, funds, identities);

        long businessAfterSecond = business.value();
        long fundsAfterSecond = funds.value();
        assertThatThrownBy(businessA::rollback).hasMessageContaining("stale");
        assertThatThrownBy(fundsA::rollback).hasMessageContaining("stale");
        assertThat(business.value()).isEqualTo(businessAfterSecond);
        assertThat(funds.value()).isEqualTo(fundsAfterSecond);

        businessC.rollback();
        fundsC.rollback();
        assertThat(business.value()).isEqualTo(firstPatch.businessStateHash());
        assertThat(funds.value()).isEqualTo(firstPatch.fundsStateHash());
        assertThatThrownBy(businessC::rollback).hasMessageContaining("ROLLED_BACK");
        assertThatThrownBy(fundsC::rollback).hasMessageContaining("ROLLED_BACK");
        assertThatThrownBy(businessC::commit).hasMessageContaining("ROLLED_BACK");
        assertThatThrownBy(fundsC::commit).hasMessageContaining("ROLLED_BACK");
        runtime.close();
    }

    @Test
    void midStageMixedDomainFailureRollsBackBothHashesAndRemainsRetryable() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        List<Long> users = oneUserPerLane();
        TradingCoreState before = baseState(reducer, users);
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities, FOUR_LANES);
        place(runtime, identities, users.getFirst(), 20_001, CoreOrderSide.BUY, 2);
        TradingCoreState after = RuntimeStateMaterializer.materialize(runtime, identities);
        RuntimeCommitPatch patch = capture(runtime, identities, before, after, 1);
        RuntimeCommitPatch.AccountLaneOwnerGroup group = patch.accountLaneGroups().getFirst();
        assertThat(group.users()).isNotEmpty();
        assertThat(group.balances()).isNotEmpty();
        assertThat(group.reservations()).isNotEmpty();
        assertThat(group.orders()).isNotEmpty();

        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(before, identities);
        long businessBefore = business.value();
        long fundsBefore = funds.value();
        assertEveryStagedOperationRollsBack(patch, before, identities);
        business.failAfterStagedOperationForTest(0);
        funds.failAfterStagedOperationForTest(0);
        assertThatThrownBy(() -> business.update(patch)).hasMessageContaining("injected mid-stage");
        assertThatThrownBy(() -> funds.update(patch)).hasMessageContaining("injected mid-stage");
        assertThat(business.value()).isEqualTo(businessBefore);
        assertThat(funds.value()).isEqualTo(fundsBefore);

        business.update(patch);
        funds.update(patch);
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(after));
        runtime.close();
    }

    @Test
    void contributionAlgebraHandlesZeroDeleteRecreateOverflowAndXorCancellation() {
        long contribution = 0x1122_3344_5566_7788L;
        long[] empty = {0, 0, 0};
        assertAggregates(new long[]{0}, new long[]{0}, empty);
        assertAggregates(new long[]{contribution}, new long[]{contribution}, empty);
        assertAggregates(new long[]{contribution, contribution}, new long[]{contribution},
                new long[]{1, contribution, contribution});
        assertAggregates(new long[]{Long.MAX_VALUE, 1}, new long[0],
                new long[]{2, Long.MIN_VALUE, Long.MAX_VALUE ^ 1L});
        assertAggregates(new long[]{contribution, contribution}, new long[0],
                new long[]{2, contribution + contribution, 0});
    }

    @Test
    void deliveryLifecycleSettlementPatchMatchesCanonicalHashAndRestore() {
        ProductLine line = ProductLine.LINEAR_DELIVERY;
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int symbolId = identities.symbolId(SYMBOL);
        TradingCoreState before = TradingCoreState.empty(line);
        CoreTreasuryState nextTreasury = before.treasuryState().recordLifecycle(SYMBOL, 81);
        TradingCoreState after = new TradingCoreState(line, 1, before.users(), before.orders(),
                before.instruments(), before.riskState(), nextTreasury);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(line, 0, 1)
                .matcherTransition(unchangedMatcher());
        builder.recordTreasuryLifecycle(symbolId, null,
                new RuntimeCommitPatch.TreasuryLifecycleValue(81, null));
        RuntimeCommitPatch patch = seal(builder, new RuntimeCommitPatch.SealMetadata(
                0, 1, RollingBusinessStateHash.compute(before), RollingBusinessStateHash.compute(after),
                RollingFundsStateHash.compute(before), RollingFundsStateHash.compute(after),
                0, null, true), identities);
        assertEveryStagedOperationRollsBack(patch, before, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(before, identities);

        business.update(patch);
        funds.update(patch);

        assertThat(patch.globalOwnerGroup().treasuryLifecycle()).hasSize(1);
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(after));
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(after), line);
        assertThat(RollingBusinessStateHash.create(restored, identities).value()).isEqualTo(business.value());
        assertThat(RollingFundsStateHash.create(restored, identities).value()).isEqualTo(funds.value());
    }

    @Test
    void typedPositionBalanceAndTreasuryKeysDeleteThenRecreateWithCanonicalParity() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long userId = oneUserPerLane().getFirst();
        int laneId = FOUR_LANES.accountLaneId(userId);
        int assetId = identities.assetId(ASSET);
        int symbolId = identities.symbolId(SYMBOL);
        long positionKey = identities.positionKey(userId, SYMBOL);
        CorePositionState corePosition = new CorePositionState(SYMBOL, ASSET, 1, 1, 1_000, 1_000, 0, 100);
        PositionRuntime runtimePosition = new PositionRuntime(userId, symbolId, assetId,
                CoreMarginMode.CROSS, CorePositionSide.NET, 1, 1, 1_000, 1_000, 0, 100);
        CoreUserState populatedUser = new CoreUserState(PRODUCT_LINE, userId, 1,
                Map.of(ASSET, new AssetBalance(ASSET, 900, 100)), Map.of(), Map.of(SYMBOL, corePosition));
        CoreUserState deletedUser = new CoreUserState(PRODUCT_LINE, userId, 2,
                Map.of(ASSET, new AssetBalance(ASSET, 1_000, 0)), Map.of(), Map.of());
        CoreUserState recreatedUser = new CoreUserState(PRODUCT_LINE, userId, 3,
                Map.of(ASSET, new AssetBalance(ASSET, 900, 100)), Map.of(), Map.of(SYMBOL, corePosition));
        TradingCoreState populated = stateWithUserAndTreasury(1, populatedUser,
                CoreTreasuryState.empty().adjustInsurance(ASSET, 50));
        TradingCoreState deleted = stateWithUserAndTreasury(2, deletedUser, CoreTreasuryState.empty());
        TradingCoreState recreated = stateWithUserAndTreasury(3, recreatedUser,
                CoreTreasuryState.empty().adjustInsurance(ASSET, 50));
        RuntimeCommitPatch deletion = positionTreasuryPatch(1, laneId, userId, assetId, positionKey,
                populated, deleted, runtimePosition, null, 900, 100, 1_000, 0, 50, 0, identities);
        RuntimeCommitPatch recreation = positionTreasuryPatch(2, laneId, userId, assetId, positionKey,
                deleted, recreated, null, runtimePosition, 1_000, 0, 900, 100, 0, 50, identities);
        assertEveryStagedOperationRollsBack(deletion, populated, identities);
        assertEveryStagedOperationRollsBack(recreation, deleted, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(populated, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(populated, identities);

        business.update(deletion);
        funds.update(deletion);
        assertHashAndRestoreParity(deleted, business, funds, identities);
        business.update(recreation);
        funds.update(recreation);
        assertHashAndRestoreParity(recreated, business, funds, identities);
    }

    @Test
    void rejectsSkippedCanonicalSequencesWithoutHashDrift() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long userId = oneUserPerLane().getFirst();
        TradingCoreState initial = reducer.adjustBalance(TradingCoreState.empty(PRODUCT_LINE),
                userId, new BalanceAdjustmentCommand(ASSET, 10_000));
        TradingCoreState firstState = reducer.adjustBalance(initial, userId,
                new BalanceAdjustmentCommand(ASSET, 100));
        TradingCoreState secondState = reducer.adjustBalance(firstState, userId,
                new BalanceAdjustmentCommand(ASSET, 200));
        RuntimeCommitPatch first = balancePatch(1, userId, initial, firstState, identities);
        RuntimeCommitPatch gap = balancePatch(5, userId, firstState, secondState, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(initial, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(initial, identities);

        business.update(first);
        funds.update(first);
        assertRejectedWithoutDrift(business, funds, gap, "sequence");
    }

    @Test
    void rejectsDuplicateAndReorderedCoreSequencesWithoutHashDrift() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long userId = oneUserPerLane().getFirst();
        TradingCoreState initial = reducer.adjustBalance(TradingCoreState.empty(PRODUCT_LINE),
                userId, new BalanceAdjustmentCommand(ASSET, 10_000));
        TradingCoreState firstState = reducer.adjustBalance(initial, userId,
                new BalanceAdjustmentCommand(ASSET, 100));
        TradingCoreState secondState = reducer.adjustBalance(firstState, userId,
                new BalanceAdjustmentCommand(ASSET, 200));
        RuntimeCommitPatch first = balancePatch(1, userId, initial, firstState, identities);
        RuntimeCommitPatch gap = balancePatch(2, userId, firstState, secondState, identities);
        RuntimeCommitPatch reordered = balancePatch(2, userId, firstState, secondState, identities);
        RollingBusinessStateHash business = RollingBusinessStateHash.create(initial, identities);
        RollingFundsStateHash funds = RollingFundsStateHash.create(initial, identities);

        business.update(first);
        funds.update(first);
        assertRejectedWithoutDrift(business, funds, first, "sequence");
        business.update(gap);
        funds.update(gap);
        assertRejectedWithoutDrift(business, funds, first, "sequence");
        assertRejectedWithoutDrift(business, funds, reordered, "sequence");
        assertRejectedWithoutDrift(business, funds, invalidIdentityPatch(6, userId, secondState), null);
    }

    private static TradingCoreState stateWithUserAndTreasury(
            long revision, CoreUserState user, CoreTreasuryState treasury) {
        return new TradingCoreState(PRODUCT_LINE, revision, Map.of(user.userId(), user),
                Map.of(), Map.of(), CoreRiskState.empty(), treasury);
    }

    private static RuntimeCommitPatch positionTreasuryPatch(
            long sequence, int laneId, long userId, int assetId, long positionKey,
            TradingCoreState before, TradingCoreState after,
            PositionRuntime beforePosition, PositionRuntime afterPosition,
            long beforeAvailable, long beforeLocked, long afterAvailable, long afterLocked,
            long beforeInsurance, long afterInsurance, RuntimeIdentityRegistry identities) {
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                PRODUCT_LINE, sequence - 1, sequence)
                .matcherTransition(unchangedMatcher());
        builder.recordUser(laneId, runtimeUser(before.user(userId)), runtimeUser(after.user(userId)));
        builder.recordBalance(laneId, userId, assetId,
                new RuntimeCommitPatch.UserBalance(beforeAvailable, beforeLocked, 0),
                new RuntimeCommitPatch.UserBalance(afterAvailable, afterLocked, 0));
        builder.recordPosition(laneId, positionKey, beforePosition, afterPosition);
        RuntimeCommitPatch.TreasuryAssetValue beforeTreasury = beforeInsurance == 0 ? null
                : new RuntimeCommitPatch.TreasuryAssetValue(0, beforeInsurance, 0, 0, 0, 0, 0);
        RuntimeCommitPatch.TreasuryAssetValue afterTreasury = afterInsurance == 0 ? null
                : new RuntimeCommitPatch.TreasuryAssetValue(0, afterInsurance, 0, 0, 0, 0, 0);
        builder.recordTreasuryAsset(assetId, beforeTreasury, afterTreasury);
        builder.laneMask(1L << laneId);
        return seal(builder, new RuntimeCommitPatch.SealMetadata(
                before.revision(), after.revision(),
                RollingBusinessStateHash.compute(before), RollingBusinessStateHash.compute(after),
                RollingFundsStateHash.compute(before), RollingFundsStateHash.compute(after),
                1L << laneId, null, true), identities);
    }

    private static RuntimeCommitPatch seal(RuntimeCommitPatch.Builder builder,
                                           RuntimeCommitPatch.SealMetadata metadata,
                                           RuntimeIdentityRegistry identities) {
        RuntimeCommitPatch.PreparedChanges changes = builder.prepare(new RuntimeCommitPatch.PrepareMetadata(
                metadata.beforeRevision(), metadata.afterRevision(), metadata.beforeBusinessStateHash(),
                metadata.beforeFundsStateHash(), metadata.laneMask(), metadata.coreFactMetadata(),
                metadata.externalAdjustment()), identities);
        return builder.seal(changes, metadata.businessStateHash(), metadata.fundsStateHash());
    }

    private static void assertHashAndRestoreParity(
            TradingCoreState state, RollingBusinessStateHash business, RollingFundsStateHash funds,
            RuntimeIdentityRegistry identities) {
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(state));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(state));
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), PRODUCT_LINE);
        assertThat(RollingBusinessStateHash.create(restored, identities).value()).isEqualTo(business.value());
        assertThat(RollingFundsStateHash.create(restored, identities).value()).isEqualTo(funds.value());
    }

    private static TradingCoreState captureAndAssert(long sequence, TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities, TradingCoreState before,
                                                     RollingBusinessStateHash business,
                                                     RollingFundsStateHash funds) {
        TradingCoreState after = RuntimeStateMaterializer.materialize(runtime, identities);
        RuntimeCommitPatch patch = capture(runtime, identities, before, after, sequence);
        assertEveryStagedOperationRollsBack(patch, before, identities);
        business.update(patch);
        funds.update(patch);
        assertThat(business.value()).isEqualTo(RollingBusinessStateHash.compute(after));
        assertThat(funds.value()).isEqualTo(RollingFundsStateHash.compute(after));
        TradingCoreState restoredState = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(after), PRODUCT_LINE);
        assertThat(RollingBusinessStateHash.create(restoredState, identities).value()).isEqualTo(business.value());
        assertThat(RollingFundsStateHash.create(restoredState, identities).value()).isEqualTo(funds.value());
        runtime.clearChangedKeys();
        return after;
    }

    private static void assertEveryStagedOperationRollsBack(
            RuntimeCommitPatch patch, TradingCoreState before, RuntimeIdentityRegistry identities) {
        RollingBusinessStateHash businessProbe = RollingBusinessStateHash.create(before, identities);
        int businessOperations = businessProbe.stagedOperationCountForTest(patch);
        for (int index = 0; index < businessOperations; index++) {
            businessProbe = RollingBusinessStateHash.create(before, identities);
            long value = businessProbe.value();
            businessProbe.failAfterStagedOperationForTest(index);
            RollingBusinessStateHash current = businessProbe;
            assertThatThrownBy(() -> current.update(patch)).hasMessageContaining("injected mid-stage");
            assertThat(current.value()).isEqualTo(value);
            current.update(patch);
            assertThat(current.value()).isEqualTo(patch.businessStateHash());
        }
        RollingFundsStateHash fundsProbe = RollingFundsStateHash.create(before, identities);
        int fundsOperations = fundsProbe.stagedOperationCountForTest(patch);
        for (int index = 0; index < fundsOperations; index++) {
            fundsProbe = RollingFundsStateHash.create(before, identities);
            long value = fundsProbe.value();
            fundsProbe.failAfterStagedOperationForTest(index);
            RollingFundsStateHash current = fundsProbe;
            assertThatThrownBy(() -> current.update(patch)).hasMessageContaining("injected mid-stage");
            assertThat(current.value()).isEqualTo(value);
            current.update(patch);
            assertThat(current.value()).isEqualTo(patch.fundsStateHash());
        }
    }

    private static RuntimeCommitPatch capture(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                              TradingCoreState before, TradingCoreState after, long sequence) {
        TradingRuntimeState.PreparedCommit prepared = runtime.prepareCommitPatch(
                sequence, identities,
                before.revision(), unchangedMatcher(), 0,
                RollingBusinessStateHash.compute(before), RollingBusinessStateHash.compute(after),
                RollingFundsStateHash.compute(before), RollingFundsStateHash.compute(after), true);
        java.util.TreeSet<Long> userIds = new java.util.TreeSet<>(before.users().keySet());
        userIds.addAll(after.users().keySet());
        for (long userId : userIds) {
            CoreUserState beforeUser = before.user(userId);
            CoreUserState afterUser = after.user(userId);
            java.util.TreeSet<String> positionIds = new java.util.TreeSet<>();
            if (beforeUser != null) positionIds.addAll(beforeUser.positions().keySet());
            if (afterUser != null) positionIds.addAll(afterUser.positions().keySet());
            for (String positionId : positionIds) {
                CorePositionState beforePosition = beforeUser == null ? null : beforeUser.positions().get(positionId);
                CorePositionState afterPosition = afterUser == null ? null : afterUser.positions().get(positionId);
                if (!java.util.Objects.equals(beforePosition, afterPosition)) {
                    long positionKey = identities.positionKey(userId, positionId);
                    prepared.builder().recordPosition(runtime.topology().accountLaneId(userId), positionKey,
                            runtimePosition(userId, beforePosition, identities),
                            runtimePosition(userId, afterPosition, identities));
                }
            }
        }
        RuntimeCommitPatch.PreparedChanges changes = prepared.prepareChanges();
        return prepared.seal(changes, RollingBusinessStateHash.compute(after), RollingFundsStateHash.compute(after));
    }

    private static PositionRuntime runtimePosition(long userId, CorePositionState position,
                                                   RuntimeIdentityRegistry identities) {
        if (position == null) return null;
        return new PositionRuntime(userId, identities.symbolId(position.symbol()),
                identities.assetId(position.marginAsset()), position.marginMode(), position.positionSide(),
                position.instrumentVersion(), position.signedQuantitySteps(), position.entryPriceTicks(),
                position.entryValueTicks(), position.realizedPnlUnits(), position.positionMarginUnits());
    }

    private static void place(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
                              long orderId, CoreOrderSide side, long quantity) {
        PlaceOrderCommand intent = new PlaceOrderCommand(orderId, SYMBOL, 1, side, 1_000, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                false, "hash-" + orderId);
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, userId, intent, 1);
        RuntimeCommandProcessor.placeOrder(
                runtime, identities, userId, resolved, new UUID(0, orderId), quantity * 1_000);
    }

    private static TradingCoreState baseState(TradingCoreReducer reducer, List<Long> users) {
        TradingCoreState state = TradingCoreState.empty(PRODUCT_LINE);
        state = reducer.upsertInstrument(state, new UpsertInstrumentCommand(SYMBOL, 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", ASSET, ASSET,
                1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        for (long userId : users) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand(ASSET, 1_000_000));
        }
        return reducer.applyMarkPrice(state, new ApplyMarkPriceCommand(SYMBOL, 1, 1_000, 1, 1));
    }

    private static void assertAggregates(long[] additions, long[] removals, long[] expected) {
        assertThat(RollingBusinessStateHash.aggregateForTest(additions, removals)).containsExactly(expected);
        assertThat(RollingFundsStateHash.aggregateForTest(additions, removals)).containsExactly(expected);
    }

    private static void assertRejectedWithoutDrift(RollingBusinessStateHash business,
                                                   RollingFundsStateHash funds,
                                                   RuntimeCommitPatch patch, String message) {
        long businessBefore = business.value();
        long fundsBefore = funds.value();
        var businessFailure = assertThatThrownBy(() -> business.update(patch));
        var fundsFailure = assertThatThrownBy(() -> funds.update(patch));
        if (message != null) {
            businessFailure.hasMessageContaining(message);
            fundsFailure.hasMessageContaining(message);
        }
        assertThat(business.value()).isEqualTo(businessBefore);
        assertThat(funds.value()).isEqualTo(fundsBefore);
    }

    private static RuntimeCommitPatch balancePatch(long sequence, long userId,
                                                   TradingCoreState before, TradingCoreState after,
                                                   RuntimeIdentityRegistry identities) {
        int laneId = FOUR_LANES.accountLaneId(userId);
        int assetId = identities.assetId(ASSET);
        CoreUserState beforeUser = before.user(userId);
        CoreUserState afterUser = after.user(userId);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                PRODUCT_LINE, sequence - 1, sequence)
                .matcherTransition(unchangedMatcher());
        builder.recordUser(laneId, runtimeUser(beforeUser), runtimeUser(afterUser));
        builder.recordBalance(laneId, userId, assetId, patchBalance(beforeUser), patchBalance(afterUser));
        builder.laneMask(1L << laneId);
        return builder.seal(new RuntimeCommitPatch.SealMetadata(
                before.revision(), after.revision(),
                RollingBusinessStateHash.compute(before), RollingBusinessStateHash.compute(after),
                RollingFundsStateHash.compute(before), RollingFundsStateHash.compute(after),
                1L << laneId, null, true));
    }

    private static RuntimeCommitPatch invalidIdentityPatch(long sequence, long userId, TradingCoreState before) {
        int laneId = FOUR_LANES.accountLaneId(userId);
        CoreUserState beforeUser = before.user(userId);
        UserRuntime prior = runtimeUser(beforeUser);
        UserRuntime current = new UserRuntime(PRODUCT_LINE, userId,
                Math.incrementExact(prior.revision()), CorePositionMode.ONE_WAY);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                PRODUCT_LINE, sequence - 1, sequence)
                .matcherTransition(unchangedMatcher());
        builder.recordUser(laneId, prior, current);
        builder.recordBalance(laneId, userId, Integer.MAX_VALUE, null,
                new RuntimeCommitPatch.UserBalance(1, 0, 0));
        builder.laneMask(1L << laneId);
        return builder.seal(new RuntimeCommitPatch.SealMetadata(
                before.revision(), Math.incrementExact(before.revision()),
                RollingBusinessStateHash.compute(before), 1,
                RollingFundsStateHash.compute(before), 1, 1L << laneId, null, true));
    }

    private static RuntimeCommitPatch.UserBalance patchBalance(CoreUserState user) {
        AssetBalance balance = user.balances().get(ASSET);
        return new RuntimeCommitPatch.UserBalance(balance.availableUnits(), balance.lockedUnits(), 0);
    }

    private static UserRuntime runtimeUser(CoreUserState user) {
        return new UserRuntime(user.productLine(), user.userId(), user.revision(), user.positionMode());
    }

    private static List<Long> oneUserPerLane() {
        ArrayList<Long> result = new ArrayList<>(FOUR_LANES.accountLaneCount());
        for (int lane = 0; lane < FOUR_LANES.accountLaneCount(); lane++) result.add(0L);
        int found = 0;
        for (long userId = 1; found < FOUR_LANES.accountLaneCount(); userId++) {
            int lane = FOUR_LANES.accountLaneId(userId);
            if (result.get(lane) == 0) {
                result.set(lane, userId);
                found++;
            }
        }
        return List.copyOf(result);
    }

    private static CoreMatcherTransition unchangedMatcher() {
        return CoreMatcherTransition.unchanged(0, 0);
    }
}
