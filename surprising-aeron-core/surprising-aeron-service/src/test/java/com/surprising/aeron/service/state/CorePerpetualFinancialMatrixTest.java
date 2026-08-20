package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatch;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorePerpetualFinancialMatrixTest {

    private static final long USER_ID = 101;
    private static final long MAKER_ID = 202;
    private static final long SECOND_MAKER_ID = 203;
    private static final String SYMBOL = "BTC-USDT";
    private static final long ENTRY_PRICE = 100;
    private static final long QUANTITY = 10;
    private static final long POSITION_MARGIN = 100;
    private static final long DEFAULT_WALLET = 1_000;

    private static final List<Variant> VARIANTS = List.of(
            new Variant(ContractType.LINEAR_PERPETUAL, CoreMarginMode.CROSS, "BTC", "USDT", "USDT", 1, 1),
            new Variant(ContractType.LINEAR_PERPETUAL, CoreMarginMode.ISOLATED, "BTC", "USDT", "USDT", 1, 1),
            new Variant(ContractType.INVERSE_PERPETUAL, CoreMarginMode.CROSS, "BTC", "USD", "BTC", 100, 100),
            new Variant(ContractType.INVERSE_PERPETUAL, CoreMarginMode.ISOLATED, "BTC", "USD", "BTC", 100, 100));

    private static final List<String> SCENARIOS = List.of(
            "OPEN_CLOSE_REVERSAL",
            "TIER_CHANGE",
            "MAKER_TAKER_FEES",
            "FUNDING_POSITIVE",
            "FUNDING_NEGATIVE",
            "STALE_MARK",
            "RISK_SCAN",
            "LIQUIDATION_PARTIAL",
            "LIQUIDATION_FULL",
            "LIQUIDATION_FEE_CAP",
            "INSURANCE_FULL",
            "INSURANCE_PARTIAL",
            "ADL_ORDER",
            "ADL_COVERAGE",
            "SNAPSHOT_CONTINUATION",
            "CROSS_LINE_REJECTED",
            "ISOLATED_COLLATERAL_LEAKAGE",
            "ISOLATED_FREE_COLLATERAL_LEAKAGE");

    private static final List<String> POSITIVE_SCENARIOS = SCENARIOS.subList(0, 15);
    private static final List<String> NEGATIVE_SCENARIOS = SCENARIOS.subList(15, SCENARIOS.size());

    private static final Set<String> REQUIRED_ROWS = Set.of(
            "LINEAR_PERPETUAL:CROSS:OPEN_CLOSE_REVERSAL",
            "LINEAR_PERPETUAL:CROSS:TIER_CHANGE",
            "LINEAR_PERPETUAL:CROSS:MAKER_TAKER_FEES",
            "LINEAR_PERPETUAL:CROSS:FUNDING_POSITIVE",
            "LINEAR_PERPETUAL:CROSS:FUNDING_NEGATIVE",
            "LINEAR_PERPETUAL:CROSS:STALE_MARK",
            "LINEAR_PERPETUAL:CROSS:RISK_SCAN",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_PARTIAL",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_FULL",
            "LINEAR_PERPETUAL:CROSS:LIQUIDATION_FEE_CAP",
            "LINEAR_PERPETUAL:CROSS:INSURANCE_FULL",
            "LINEAR_PERPETUAL:CROSS:INSURANCE_PARTIAL",
            "LINEAR_PERPETUAL:CROSS:ADL_ORDER",
            "LINEAR_PERPETUAL:CROSS:ADL_COVERAGE",
            "LINEAR_PERPETUAL:CROSS:SNAPSHOT_CONTINUATION",
            "LINEAR_PERPETUAL:CROSS:CROSS_LINE_REJECTED",
            "LINEAR_PERPETUAL:CROSS:ISOLATED_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:CROSS:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:ISOLATED:OPEN_CLOSE_REVERSAL",
            "LINEAR_PERPETUAL:ISOLATED:TIER_CHANGE",
            "LINEAR_PERPETUAL:ISOLATED:MAKER_TAKER_FEES",
            "LINEAR_PERPETUAL:ISOLATED:FUNDING_POSITIVE",
            "LINEAR_PERPETUAL:ISOLATED:FUNDING_NEGATIVE",
            "LINEAR_PERPETUAL:ISOLATED:STALE_MARK",
            "LINEAR_PERPETUAL:ISOLATED:RISK_SCAN",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_PARTIAL",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_FULL",
            "LINEAR_PERPETUAL:ISOLATED:LIQUIDATION_FEE_CAP",
            "LINEAR_PERPETUAL:ISOLATED:INSURANCE_FULL",
            "LINEAR_PERPETUAL:ISOLATED:INSURANCE_PARTIAL",
            "LINEAR_PERPETUAL:ISOLATED:ADL_ORDER",
            "LINEAR_PERPETUAL:ISOLATED:ADL_COVERAGE",
            "LINEAR_PERPETUAL:ISOLATED:SNAPSHOT_CONTINUATION",
            "LINEAR_PERPETUAL:ISOLATED:CROSS_LINE_REJECTED",
            "LINEAR_PERPETUAL:ISOLATED:ISOLATED_COLLATERAL_LEAKAGE",
            "LINEAR_PERPETUAL:ISOLATED:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:CROSS:OPEN_CLOSE_REVERSAL",
            "INVERSE_PERPETUAL:CROSS:TIER_CHANGE",
            "INVERSE_PERPETUAL:CROSS:MAKER_TAKER_FEES",
            "INVERSE_PERPETUAL:CROSS:FUNDING_POSITIVE",
            "INVERSE_PERPETUAL:CROSS:FUNDING_NEGATIVE",
            "INVERSE_PERPETUAL:CROSS:STALE_MARK",
            "INVERSE_PERPETUAL:CROSS:RISK_SCAN",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_PARTIAL",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_FULL",
            "INVERSE_PERPETUAL:CROSS:LIQUIDATION_FEE_CAP",
            "INVERSE_PERPETUAL:CROSS:INSURANCE_FULL",
            "INVERSE_PERPETUAL:CROSS:INSURANCE_PARTIAL",
            "INVERSE_PERPETUAL:CROSS:ADL_ORDER",
            "INVERSE_PERPETUAL:CROSS:ADL_COVERAGE",
            "INVERSE_PERPETUAL:CROSS:SNAPSHOT_CONTINUATION",
            "INVERSE_PERPETUAL:CROSS:CROSS_LINE_REJECTED",
            "INVERSE_PERPETUAL:CROSS:ISOLATED_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:CROSS:ISOLATED_FREE_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:ISOLATED:OPEN_CLOSE_REVERSAL",
            "INVERSE_PERPETUAL:ISOLATED:TIER_CHANGE",
            "INVERSE_PERPETUAL:ISOLATED:MAKER_TAKER_FEES",
            "INVERSE_PERPETUAL:ISOLATED:FUNDING_POSITIVE",
            "INVERSE_PERPETUAL:ISOLATED:FUNDING_NEGATIVE",
            "INVERSE_PERPETUAL:ISOLATED:STALE_MARK",
            "INVERSE_PERPETUAL:ISOLATED:RISK_SCAN",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_PARTIAL",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_FULL",
            "INVERSE_PERPETUAL:ISOLATED:LIQUIDATION_FEE_CAP",
            "INVERSE_PERPETUAL:ISOLATED:INSURANCE_FULL",
            "INVERSE_PERPETUAL:ISOLATED:INSURANCE_PARTIAL",
            "INVERSE_PERPETUAL:ISOLATED:ADL_ORDER",
            "INVERSE_PERPETUAL:ISOLATED:ADL_COVERAGE",
            "INVERSE_PERPETUAL:ISOLATED:SNAPSHOT_CONTINUATION",
            "INVERSE_PERPETUAL:ISOLATED:CROSS_LINE_REJECTED",
            "INVERSE_PERPETUAL:ISOLATED:ISOLATED_COLLATERAL_LEAKAGE",
            "INVERSE_PERPETUAL:ISOLATED:ISOLATED_FREE_COLLATERAL_LEAKAGE");

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void failingFirstCompletenessManifestReportsEveryMissingRow() {
        List<Row> rows = allRows();
        Map<String, Integer> counts = new TreeMap<>();
        for (Row row : rows) {
            counts.merge(row.key(), 1, Integer::sum);
        }
        Set<String> actual = counts.keySet();
        Set<String> required = REQUIRED_ROWS;
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(required);
        Set<String> duplicate = new LinkedHashSet<>();
        counts.forEach((key, count) -> {
            if (count > 1) duplicate.add(key);
        });

        assertThat(missing).as("missing perpetual financial matrix rows").isEmpty();
        assertThat(unexpected).as("unexpected perpetual financial matrix rows").isEmpty();
        assertThat(duplicate).as("duplicate perpetual financial matrix rows").isEmpty();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(required);
        assertThat(rows).hasSize(required.size());
    }

    @Test
    void coversLinearInverseCrossIsolated() {
        List<Row> rows = allRows();

        assertRows(rows);
        assertManifest(rows, POSITIVE_SCENARIOS);
    }

    @Test
    void rejectsStaleCrossLineAndCollateralLeakage() {
        List<Row> rows = allRows();

        assertRows(rows);
        assertManifest(rows, List.of("STALE_MARK"));
        assertManifest(rows, NEGATIVE_SCENARIOS);
    }

    @Test
    void persistentLiquidationCancellationAdvanceMatchesReducer() {
        Variant variant = VARIANTS.getFirst();
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE,
                101, POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, 90, 1);
        CoreLiquidationState planned = marked.riskState().liquidations().get(1L);
        TradingCoreState placed = reducer.placeOrder(marked, USER_ID,
                order(701, variant, CoreOrderSide.SELL, 1, true, 0, 0));
        TradingCoreState ordered = replaceLiquidation(placed, planned.ordered(701));
        ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(1, 1, 90, 0, 701, 10);
        List<CoreOrderState> canceled = List.of(ordered.order(701));
        TradingCoreState expected = reducer.advanceLiquidationCancellation(ordered, command, canceled, 702);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(ordered, identities);

        assertThat(RuntimePerpetualLiquidationProcessor.applyCancellationAdvance(
                ordered, command, canceled, 702, runtime, identities)).isSameAs(runtime);
        RuntimeStateParityChecker.assertMatches(expected, identities, runtime);

        TradingCoreState canceledState = reducer.cancelLifecycleOrders(ordered, canceled);
        TradingCoreState executionExpected = reducer.executeLiquidationAfterCancellation(canceledState, command);
        TradingRuntimeState executionRuntime = RuntimeStateProjector.project(ordered, identities);
        assertThat(RuntimePerpetualLiquidationProcessor.applyExecution(
                ordered, command, canceled, executionRuntime, identities)).isSameAs(executionRuntime);
        RuntimeStateParityChecker.assertMatches(executionExpected, identities, executionRuntime);
    }

    @Test
    void mixedPricePendingOrdersReserveForTheirActualFillPrices() {
        for (Variant variant : List.of(VARIANTS.getFirst(), VARIANTS.get(2))) {
            TradingCoreState opening = pairFunded(variant, false, DEFAULT_WALLET, DEFAULT_WALLET);
            TradingCoreState placed = reducer.placeOrder(opening, USER_ID,
                    pricedOrder(801, variant, CoreOrderSide.SELL, 1, 100, false, 100_000, 100_000));
            placed = reducer.placeOrder(placed, USER_ID,
                    pricedOrder(802, variant, CoreOrderSide.SELL, 1, 101, false, 100_000, 100_000));
            long expectedMargin = variant.type().isInverse() ? 20 : 21;
            long expectedFee = variant.type().isInverse() ? 20 : 21;
            assertThat(placed.user(USER_ID).balances().get(variant.settleAsset()).lockedUnits())
                    .isEqualTo(expectedMargin + expectedFee);

            placed = reducer.placeOrder(placed, MAKER_ID,
                    pricedOrder(803, variant, CoreOrderSide.BUY, 1, 100));
            TradingCoreState afterFirstFill = reducer.applyMatches(placed, 803, variant.baseAsset(),
                    variant.quoteAsset(), List.of(new CoreMatch(801, USER_ID, 100, 1, true, true)));
            afterFirstFill = reducer.placeOrder(afterFirstFill, MAKER_ID,
                    pricedOrder(804, variant, CoreOrderSide.BUY, 1, 101));
            TradingCoreState ending = reducer.applyMatches(afterFirstFill, 804, variant.baseAsset(),
                    variant.quoteAsset(), List.of(new CoreMatch(802, USER_ID, 101, 1, true, true)));

            assertThat(ending.user(USER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-2);
            assertThat(ending.user(USER_ID).positions().get(SYMBOL).positionMarginUnits())
                    .isEqualTo(expectedMargin);
            assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset()))
                    .isEqualTo(DEFAULT_WALLET - expectedFee);
            assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset())).isEqualTo(DEFAULT_WALLET);
            assertThat(ending.treasuryState().feeBalances()).containsEntry(variant.settleAsset(), expectedFee);
        }
    }

    private List<Row> allRows() {
        List<Row> rows = new ArrayList<>();
        for (Variant variant : VARIANTS) {
            rows.add(openCloseReversal(variant));
            rows.add(tierChange(variant));
            rows.add(makerTakerFees(variant));
            rows.add(funding(variant, 100_000, "FUNDING_POSITIVE"));
            rows.add(funding(variant, -100_000, "FUNDING_NEGATIVE"));
            rows.add(staleMark(variant));
            rows.add(riskScan(variant));
            rows.add(liquidation(variant, 5, "LIQUIDATION_PARTIAL"));
            rows.add(liquidation(variant, QUANTITY, "LIQUIDATION_FULL"));
            rows.add(cappedLiquidation(variant));
            rows.add(insurance(variant, true));
            rows.add(insurance(variant, false));
            rows.add(adlOrder(variant));
            rows.add(adlCoverage(variant));
            rows.add(snapshotContinuation(variant));
            rows.add(crossLineRejected(variant));
            rows.add(isolatedCollateralLeakage(variant));
            rows.add(isolatedFreeCollateralLeakage(variant));
        }
        return rows;
    }

    private Row openCloseReversal(Variant variant) {
        TradingCoreState opening = pairFunded(variant, false, DEFAULT_WALLET, DEFAULT_WALLET);
        TradingCoreState opened = match(variant, opening, 1, 2, CoreOrderSide.BUY,
                CoreOrderSide.SELL, QUANTITY);
        TradingCoreState reversed = match(variant, opened, 3, 4, CoreOrderSide.SELL,
                CoreOrderSide.BUY, 15);

        assertThat(reversed.user(USER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-5);
        assertThat(reversed.user(MAKER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(5);
        assertThat(reversed.user(USER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(50);
        assertThat(reversed.user(MAKER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(50);
        return row(variant, "OPEN_CLOSE_REVERSAL", opening, reversed, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, 0, 0, 0, 0, 1_000, 1_000, 0, 0));
    }

    private Row tierChange(Variant variant) {
        TradingCoreState opening = pairFunded(variant, true, DEFAULT_WALLET, DEFAULT_WALLET);
        TradingCoreState first = match(variant, opening, 11, 12, CoreOrderSide.BUY,
                CoreOrderSide.SELL, 9);
        TradingCoreState second = match(variant, first, 13, 14, CoreOrderSide.BUY,
                CoreOrderSide.SELL, QUANTITY);

        assertThat(CoreContractMath.riskBracket(second.instruments().get(SYMBOL), 1_900).bracketNo())
                .isEqualTo(2);
        assertThat(second.user(USER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(380);
        assertThat(second.user(MAKER_ID).positions().get(SYMBOL).positionMarginUnits()).isEqualTo(380);
        return row(variant, "TIER_CHANGE", opening, second, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, 0, 0, 0, 0, 1_000, 1_000, 0, 0));
    }

    private Row makerTakerFees(Variant variant) {
        TradingCoreState opening = pairFunded(variant, false, DEFAULT_WALLET, DEFAULT_WALLET);
        TradingCoreState state = reducer.placeOrder(opening, MAKER_ID,
                order(21, variant, CoreOrderSide.SELL, QUANTITY, false, -50_000, 200_000));
        state = reducer.placeOrder(state, USER_ID,
                order(22, variant, CoreOrderSide.BUY, QUANTITY, false, 0, 100_000));
        TradingCoreState ending = reducer.applyMatches(state, 22, variant.baseAsset(), variant.quoteAsset(),
                List.of(new CoreMatch(21, MAKER_ID, ENTRY_PRICE, QUANTITY, true, true)));

        assertThat(ending.treasuryState().feeBalances()).containsEntry(variant.settleAsset(), 50L);
        return row(variant, "MAKER_TAKER_FEES", opening, ending, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, -100, 50, 50, 0, 900, 1_050, 50, 0));
    }

    private Row funding(Variant variant, long fundingRatePpm, String scenario) {
        TradingCoreState opening = oppositePositions(variant, DEFAULT_WALLET, DEFAULT_WALLET);
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        TradingCoreReducer.FundingApplication application = reducer.applyFundingWithFacts(marked,
                new ApplyFundingCommand(300 + (fundingRatePpm > 0 ? 1 : 2), SYMBOL, 1,
                        fundingRatePpm));
        TradingCoreState ending = application.state();

        long userEnding = fundingRatePpm > 0 ? 900 : 1_100;
        long makerEnding = fundingRatePpm > 0 ? 1_100 : 900;
        long userFlow = fundingRatePpm > 0 ? -100 : 100;
        long makerFlow = Math.negateExact(userFlow);
        assertThat(application.payments()).extracting(payment -> payment.amountUnits())
                .containsExactly(userFlow, makerFlow);
        return row(variant, scenario, opening, ending, List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, userFlow, makerFlow, 0, 0,
                        userEnding, makerEnding, 0, 0));
    }

    private Row staleMark(Variant variant) {
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, DEFAULT_WALLET,
                POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        long hash = marked.businessStateHash();
        assertThatThrownBy(() -> reducer.applyMarkPrice(marked,
                new ApplyMarkPriceCommand(SYMBOL, 1, ENTRY_PRICE, 1, 1_700_000_000_000L)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));
        assertThat(marked.businessStateHash()).isEqualTo(hash);
        return row(variant, "STALE_MARK", opening, marked, List.of(), false, false,
                funds(1_000, 0, 0, 0, 0, 0, 0, 0, 1_000, 0, 0, 0));
    }

    private Row riskScan(Variant variant) {
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        TradingCoreState ending = mark(opening, variant, 90, 1);
        CoreRiskSnapshot snapshot = ending.riskState().snapshots().get(USER_ID + ":" + SYMBOL);
        CoreLiquidationState liquidation = ending.riskState().liquidations().get(1L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(snapshot.unrealizedPnlUnits()).isEqualTo(linearOrInverse(variant, -100, -111));
        assertThat(snapshot.maintenanceMarginUnits()).isEqualTo(linearOrInverse(variant, 90, 112));
        assertThat(snapshot.equityUnits()).isEqualTo(linearOrInverse(variant, 0, -11));
        assertThat(liquidation.status()).isEqualTo(CoreLiquidationState.Status.PLANNED);
        return row(variant, "RISK_SCAN", opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0));
    }

    private Row liquidation(Variant variant, long closeQuantity, String scenario) {
        boolean partial = closeQuantity != QUANTITY;
        TradingCoreState opening;
        TradingCoreState marked;
        TradingCoreReducer.FundingApplication funding = null;
        long executionPrice;
        long feeRate;
        if (partial) {
            opening = oppositePositions(variant, 450, 300);
            TradingCoreState markedAtEntry = mark(opening, variant, ENTRY_PRICE, 1);
            funding = reducer.applyFundingWithFacts(markedAtEntry,
                    new ApplyFundingCommand(500, SYMBOL, 1, 100_000));
            marked = mark(funding.state(), variant, 70, 2);
            executionPrice = 70;
            feeRate = 100_000;
        } else {
            opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
            marked = mark(opening, variant, 90, 1);
            executionPrice = 90;
            feeRate = 0;
        }
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        if (partial) {
            plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.symbol(), plan.marginMode(),
                    plan.positionSide(), plan.instrumentVersion(), plan.triggerPriceSequence(),
                    plan.signedQuantitySteps(), closeQuantity, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
            marked = replaceLiquidation(marked, plan);
        }
        ExecuteLiquidationCommand liquidationCommand = new ExecuteLiquidationCommand(
                1, partial ? 2 : 1, executionPrice, feeRate);
        RuntimeIdentityRegistry liquidationIdentities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtimeLiquidated = RuntimeStateProjector.project(marked, liquidationIdentities);
        assertThat(RuntimePerpetualLiquidationProcessor.applyExecution(
                marked, liquidationCommand, List.of(), runtimeLiquidated, liquidationIdentities))
                .isSameAs(runtimeLiquidated);
        TradingCoreState ending = reducer.executeLiquidation(marked, liquidationCommand);
        RuntimeStateParityChecker.assertMatches(ending, liquidationIdentities, runtimeLiquidated);

        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        if (partial) {
            long realizedPnl = linearOrInverse(variant, -150, -214);
            boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
            long liquidationFee = isolated ? 0 : linearOrInverse(variant, 35, 72);
            long insurance = isolated ? 50 : linearOrInverse(variant, 185, 286);
            long userEnding = isolated ? 300 : linearOrInverse(variant, 165, 64);
            assertThat(funding.payments()).extracting(payment -> payment.amountUnits())
                    .containsExactly(-100L, 100L);
            assertThat(ending.treasuryState().fundingSettlements()).containsEntry(SYMBOL, 500L);
            assertThat(position.signedQuantitySteps()).isEqualTo(5);
            assertThat(position.positionMarginUnits()).isEqualTo(50);
            assertThat(position.entryPriceTicks()).isEqualTo(100);
            assertThat(position.entryValueTicks()).isEqualTo(500);
            assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
            assertThat(result.deficitUnits()).isEqualTo(isolated ? linearOrInverse(variant, 100, 164) : 0);
            assertThat(result.status()).isEqualTo(isolated
                    ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED);
            assertThat(result.liquidationFeeUnits()).isEqualTo(liquidationFee);
            assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(userEnding);
            assertThat(ending.treasuryState().feeBalances()).doesNotContainKey(variant.settleAsset());
            assertThat(ending.treasuryState().insuranceBalances())
                    .containsEntry(variant.settleAsset(), insurance);
            assertShortPartialLiquidation(variant);
            assertNonDivisiblePartial(variant);
            assertLiquidationBoundaries(variant);
            return row(variant, scenario, opening, ending, List.of(MAKER_ID), false, false,
                    funds(450, 300, 0, 0, userEnding - 450, 100, 0, insurance,
                            userEnding, 400, 0, insurance));
        }

        long realizedPnl = linearOrInverse(variant, -100, -111);
        assertThat(position.signedQuantitySteps()).isZero();
        assertThat(position.positionMarginUnits()).isZero();
        assertThat(position.entryPriceTicks()).isZero();
        assertThat(position.entryValueTicks()).isZero();
        assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
        long deficit = linearOrInverse(variant, 0, 11);
        assertThat(result.deficitUnits()).isEqualTo(deficit);
        assertThat(result.status()).isEqualTo(deficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(result.liquidationFeeUnits()).isZero();
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 100L);
        return row(variant, scenario, opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, -100, 0, 0, 100, 0, 0, 0, 100));
    }

    private void assertShortPartialLiquidation(Variant variant) {
        TradingCoreState opening = withPosition(variant, USER_ID, -QUANTITY, ENTRY_PRICE, 400, POSITION_MARGIN);
        opening = withPosition(opening, variant, MAKER_ID, QUANTITY, ENTRY_PRICE, 300, POSITION_MARGIN);
        TradingCoreState markedAtEntry = mark(opening, variant, ENTRY_PRICE, 1);
        TradingCoreReducer.FundingApplication funding = reducer.applyFundingWithFacts(markedAtEntry,
                new ApplyFundingCommand(501, SYMBOL, 1, -100_000));
        TradingCoreState marked = mark(funding.state(), variant, 150, 2);
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.symbol(), plan.marginMode(),
                plan.positionSide(), plan.instrumentVersion(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 5, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        marked = replaceLiquidation(marked, plan);
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 2, 150, 100_000));

        long realizedPnl = linearOrInverse(variant, -250, -167);
        boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
        long liquidationFee = isolated ? 0 : linearOrInverse(variant, 0, 34);
        long insurance = isolated ? 50 : linearOrInverse(variant, 250, 201);
        long userEnding = isolated ? 250 : linearOrInverse(variant, 50, 99);
        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(funding.payments()).extracting(payment -> payment.amountUnits())
                .containsExactly(-100L, 100L);
        assertThat(ending.treasuryState().fundingSettlements()).containsEntry(SYMBOL, 501L);
        assertThat(position.signedQuantitySteps()).isEqualTo(-5);
        assertThat(position.positionMarginUnits()).isEqualTo(50);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.entryValueTicks()).isEqualTo(500);
        assertThat(position.realizedPnlUnits()).isEqualTo(realizedPnl);
        assertThat(result.deficitUnits()).isEqualTo(isolated ? linearOrInverse(variant, 200, 117) : 0);
        assertThat(result.status()).isEqualTo(isolated
                ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED);
        assertThat(result.liquidationFeeUnits()).isEqualTo(liquidationFee);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(userEnding);
        assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset())).isEqualTo(400);
        assertThat(ending.treasuryState().feeBalances()).doesNotContainKey(variant.settleAsset());
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), insurance);
        assertThat(userEnding + 400 + insurance - 700).isZero();
    }

    private void assertNonDivisiblePartial(Variant variant) {
        assertRoundedPartial(variant, 3, 60, 2, linearOrInverse(variant, -40, -67),
                linearOrInverse(variant, 7, 34), linearOrInverse(variant, 68, 68),
                linearOrInverse(variant, 33, 33));
        assertRoundedPartial(variant, -3, 140, -2, linearOrInverse(variant, -40, -29),
                linearOrInverse(variant, 7, 0), linearOrInverse(variant, 68, 72),
                linearOrInverse(variant, 33, 29));
    }

    private void assertRoundedPartial(Variant variant, long signedQuantity, long markPrice,
                                      long expectedQuantity, long expectedPnl, long expectedDeficit,
                                      long expectedUser, long expectedInsurance) {
        TradingCoreState opening = withPosition(variant, USER_ID, signedQuantity, ENTRY_PRICE, 101, 101);
        TradingCoreState marked = mark(opening, variant, markPrice, 1);
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.symbol(), plan.marginMode(),
                plan.positionSide(), plan.instrumentVersion(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 1, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        marked = replaceLiquidation(marked, plan);
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, markPrice, 0));

        CorePositionState position = ending.user(USER_ID).positions().get(SYMBOL);
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(position.signedQuantitySteps()).isEqualTo(expectedQuantity);
        assertThat(position.positionMarginUnits()).isEqualTo(68);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.entryValueTicks()).isEqualTo(200);
        assertThat(position.realizedPnlUnits()).isEqualTo(expectedPnl);
        assertThat(result.deficitUnits()).isEqualTo(expectedDeficit);
        assertThat(result.status()).isEqualTo(expectedDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(expectedUser);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), expectedInsurance);
        assertThat(expectedUser + expectedInsurance - 101).isZero();
    }

    private void assertLiquidationBoundaries(Variant variant) {
        assertThatThrownBy(() -> new CoreLiquidationState(1, USER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, 1, 1, Long.MIN_VALUE, 1, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new CoreLiquidationState(1, USER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, 1, 1, QUANTITY, QUANTITY + 1, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED)).isInstanceOf(IllegalArgumentException.class);

        TradingCoreState opening = withPosition(variant, USER_ID, 3, ENTRY_PRICE, 100, 100);
        TradingCoreState marked = mark(opening, variant, 50, 1);
        CorePositionState position = marked.user(USER_ID).positions().get(SYMBOL);
        TradingCoreState overflow = replacePosition(marked, USER_ID,
                new CorePositionState(SYMBOL, variant.settleAsset(), variant.marginMode(), CorePositionSide.NET,
                        1, 3, ENTRY_PRICE, Long.MAX_VALUE, position.realizedPnlUnits(),
                        position.positionMarginUnits()));
        CoreLiquidationState plan = overflow.riskState().liquidations().get(1L);
        plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.symbol(), plan.marginMode(),
                plan.positionSide(), plan.instrumentVersion(), plan.triggerPriceSequence(),
                plan.signedQuantitySteps(), 1, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
        overflow = replaceLiquidation(overflow, plan);
        TradingCoreState overflowState = overflow;
        long hash = overflowState.businessStateHash();
        assertThatThrownBy(() -> reducer.executeLiquidation(overflowState,
                new ExecuteLiquidationCommand(1, 1, 50, 0))).isInstanceOf(ArithmeticException.class);
        assertThat(overflowState.businessStateHash()).isEqualTo(hash);
    }

    private Row cappedLiquidation(Variant variant) {
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 180, POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, 90, 1);
        ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(1, 1, 90, 100_000);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtimeEnding = RuntimeStateProjector.project(marked, identities);
        assertThat(RuntimePerpetualLiquidationProcessor.applyExecution(
                marked, command, List.of(), runtimeEnding, identities)).isSameAs(runtimeEnding);
        TradingCoreState ending = reducer.executeLiquidation(marked, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        boolean isolated = variant.marginMode() == CoreMarginMode.ISOLATED;
        long expectedFee = isolated ? 0 : linearOrInverse(variant, 80, 69);
        long expectedUser = isolated ? 80 : 0;
        long expectedInsurance = isolated ? 100 : 180;
        long expectedDeficit = isolated ? linearOrInverse(variant, 0, 11) : 0;
        assertThat(result.liquidationFeeUnits()).isEqualTo(expectedFee);
        assertThat(result.deficitUnits()).isEqualTo(expectedDeficit);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isEqualTo(expectedUser);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), expectedInsurance);
        return row(variant, "LIQUIDATION_FEE_CAP", opening, ending, List.of(), false, false,
                funds(180, 0, 0, 0, expectedUser - 180, 0, 0, expectedInsurance,
                        expectedUser, 0, 0, expectedInsurance));
    }

    private Row insurance(Variant variant, boolean full) {
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, 1, 1);
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        long deficit = linearOrInverse(variant, 890, 98_900);
        long coverage = full ? deficit : 25;
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), coverage));
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(
                1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtimeEnding = RuntimeStateProjector.project(funded, identities);
        assertThat(RuntimePerpetualLiquidationProcessor.applyResolution(
                funded, command, runtimeEnding, identities)).isSameAs(runtimeEnding);
        TradingCoreState ending = reducer.resolveLiquidation(funded, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);

        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(result.deficitUnits()).isEqualTo(full ? 0 : deficit - coverage);
        assertThat(result.status()).isEqualTo(full
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED);
        assertThat(ending.treasuryState().insuranceBalances()).containsEntry(variant.settleAsset(), 100L);
        return row(variant, full ? "INSURANCE_FULL" : "INSURANCE_PARTIAL", opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, -100, 0, 0, 100, 0, 0, 0, 100));
    }

    private Row adlOrder(Variant variant) {
        TradingCoreState opening = adlSetup(variant);
        TradingCoreState marked = mark(opening, variant, 1, 1);
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), 25));
        TradingCoreState ending = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, 25));
        List<com.surprising.aeron.protocol.CoreAdlCandidateView> candidates =
                reducer.adlCandidates(ending, variant.settleAsset(), 10);

        assertThat(candidates).extracting(candidate -> candidate.userId()).containsExactly(MAKER_ID, SECOND_MAKER_ID);
        long residual = linearOrInverse(variant, 865, 98_875);
        long makerOpening = linearOrInverse(variant, 5_480, 200_833);
        return row(variant, "ADL_ORDER", ending, ending, List.of(MAKER_ID, SECOND_MAKER_ID), true, true,
                funds(0, makerOpening, 0, 100 - residual, 0, 0, 0, 0,
                        0, makerOpening, 0, 100 - residual));
    }

    private Row adlCoverage(Variant variant) {
        TradingCoreState opening = adlSetup(variant);
        TradingCoreState marked = mark(opening, variant, 1, 1);
        TradingCoreState liquidated = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        long insuranceCoverage = variant.type() == ContractType.LINEAR_PERPETUAL ? 25 : 50_000;
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(variant.settleAsset(), insuranceCoverage));
        TradingCoreState beforeAdl = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE,
                        insuranceCoverage));
        long residual = linearOrInverse(variant, 865, 48_900);
        ExecuteAdlCommand command = new ExecuteAdlCommand(1, MAKER_ID, SYMBOL, variant.marginMode(),
                CorePositionSide.NET, -QUANTITY, 200, 1, 5, residual);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtimeEnding = RuntimeStateProjector.project(beforeAdl, identities);
        assertThat(RuntimePerpetualLiquidationProcessor.applyAdl(
                beforeAdl, command, runtimeEnding, identities)).isSameAs(runtimeEnding);
        TradingCoreState ending = reducer.executeAdl(beforeAdl, command);
        RuntimeStateParityChecker.assertMatches(ending, identities, runtimeEnding);

        long targetCashFlow = linearOrInverse(variant, 130, 850);
        long targetEndingEconomic = linearOrInverse(variant, 2_125, 51_600);
        assertThat(ending.user(MAKER_ID).positions().get(SYMBOL).signedQuantitySteps()).isEqualTo(-5);
        assertThat(ending.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(ending.riskState().liquidations().get(1L).deficitUnits()).isZero();
        assertThat(ending.user(MAKER_ID).totalUnits(variant.settleAsset()))
                .isEqualTo(linearOrInverse(variant, 1_130, 1_850));
        return row(variant, "ADL_COVERAGE", beforeAdl, ending, List.of(MAKER_ID), true, true,
                funds(0, linearOrInverse(variant, 2_990, 100_500), 0, 100 - residual,
                        0, Math.negateExact(residual), 0, residual,
                        0, targetEndingEconomic, 0, 100));
    }

    private Row snapshotContinuation(Variant variant) {
        TradingCoreState opening = oppositePositions(variant, DEFAULT_WALLET, DEFAULT_WALLET);
        TradingCoreState marked = mark(opening, variant, ENTRY_PRICE, 1);
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        TradingCoreReducer.FundingApplication first = reducer.applyFundingWithFacts(marked,
                new ApplyFundingCommand(301, SYMBOL, 1, 100_000, 0, 1),
                List.of(USER_ID, MAKER_ID), firstId);
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
        assertThat(restored).isEqualTo(first.state());
        TradingCoreReducer.FundingApplication second = reducer.applyFundingWithFacts(restored,
                new ApplyFundingCommand(301, SYMBOL, 1, 100_000, USER_ID, 1),
                List.of(USER_ID, MAKER_ID), UUID.fromString("00000000-0000-0000-0000-000000000302"));
        assertThat(first.progress().complete()).isFalse();
        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().fundingSettlements()).containsEntry(SYMBOL, 301L);
        return row(variant, "SNAPSHOT_CONTINUATION", opening, second.state(), List.of(MAKER_ID), false, false,
                funds(1_000, 1_000, 0, 0, -100, 100, 0, 0, 900, 1_100, 0, 0));
    }

    private Row crossLineRejected(Variant variant) {
        TradingCoreState opening = fundedState(variant, USER_ID, DEFAULT_WALLET);
        ContractType otherType = variant.type() == ContractType.LINEAR_PERPETUAL
                ? ContractType.INVERSE_PERPETUAL : ContractType.LINEAR_PERPETUAL;
        UpsertInstrumentCommand wrongLine = new UpsertInstrumentCommand(
                SYMBOL, 2, otherType.ordinal(), "BTC", variant.quoteAsset(), variant.settleAsset(),
                variant.notionalMultiplierUnits(), 1, variant.settleScaleUnits(),
                100_000, 100_000, 0, 0, 0, -1, 0, 10_000_000,
                1_000_000, 0, 1_000_000,
                List.of(new CoreRiskLimitBracket(1, 0, 1_000_000, 10_000_000, 100_000, 100_000)));
        long hash = opening.businessStateHash();
        assertThatThrownBy(() -> reducer.upsertInstrument(opening, wrongLine))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_MISMATCH"));
        assertThat(opening.businessStateHash()).isEqualTo(hash);
        return row(variant, "CROSS_LINE_REJECTED", opening, opening, List.of(), false, false,
                funds(1_000, 0, 0, 0, 0, 0, 0, 0, 1_000, 0, 0, 0));
    }

    private Row isolatedCollateralLeakage(Variant variant) {
        TradingCoreState opening = stateWithInstrument(variant, false);
        opening = reducer.upsertInstrument(opening, instrument(variant, "ETH-USDT", "ETH", false));
        opening = reducer.upsertInstrument(opening, instrument(variant, "SOL-USDT", "SOL", false));
        opening = fundedState(opening, USER_ID, 175);
        opening = addPosition(opening, variant, USER_ID, SYMBOL, QUANTITY, ENTRY_PRICE,
                50, variant.marginMode());
        opening = addPosition(opening, variant, USER_ID, "ETH-USDT", 1, ENTRY_PRICE,
                75, CoreMarginMode.CROSS);
        opening = addPosition(opening, variant, USER_ID, "SOL-USDT", 1, ENTRY_PRICE,
                50, CoreMarginMode.ISOLATED);
        TradingCoreState markedEth = reducer.applyMarkPrice(opening,
                new ApplyMarkPriceCommand("ETH-USDT", 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        TradingCoreState markedSol = reducer.applyMarkPrice(markedEth,
                new ApplyMarkPriceCommand("SOL-USDT", 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        TradingCoreState marked = reducer.applyMarkPrice(markedSol,
                new ApplyMarkPriceCommand(SYMBOL, 1, 90, 1, 1_700_000_000_000L));
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 90, 0));

        CoreUserState user = ending.user(USER_ID);
        assertThat(user.positions().get(SYMBOL).marginMode()).isEqualTo(variant.marginMode());
        assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(125);
        assertThat(user.positions().get("ETH-USDT").marginMode()).isEqualTo(CoreMarginMode.CROSS);
        assertThat(user.positions().get("ETH-USDT").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("ETH-USDT").positionMarginUnits()).isEqualTo(75);
        assertThat(user.positions().get("SOL-USDT").marginMode()).isEqualTo(CoreMarginMode.ISOLATED);
        assertThat(user.positions().get("SOL-USDT").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("SOL-USDT").positionMarginUnits()).isEqualTo(50);
        assertThat(user.positions().get(SYMBOL).signedQuantitySteps()).isZero();
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 50L);
        return row(variant, "ISOLATED_COLLATERAL_LEAKAGE", opening, ending, List.of(), false, false,
                funds(175, 0, 0, 0, -50, 0, 0, 50, 125, 0, 0, 50));
    }

    private Row isolatedFreeCollateralLeakage(Variant variant) {
        TradingCoreState opening = stateWithInstrument(variant, false);
        opening = reducer.upsertInstrument(opening, instrument(variant, "ETH-USDT", "ETH", false));
        opening = fundedState(opening, USER_ID, 175);
        opening = addPosition(opening, variant, USER_ID, SYMBOL, QUANTITY, ENTRY_PRICE,
                50, CoreMarginMode.ISOLATED);
        opening = addPosition(opening, variant, USER_ID, "ETH-USDT", 1, ENTRY_PRICE,
                75, CoreMarginMode.CROSS);
        assertThat(opening.user(USER_ID).balances().get(variant.settleAsset()).availableUnits()).isEqualTo(50);
        assertThat(opening.user(USER_ID).balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(125);
        TradingCoreState markedEth = reducer.applyMarkPrice(opening,
                new ApplyMarkPriceCommand("ETH-USDT", 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        TradingCoreState marked = reducer.applyMarkPrice(markedEth,
                new ApplyMarkPriceCommand(SYMBOL, 1, 90, 1, 1_700_000_000_000L));
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 90, 0));

        CoreUserState user = ending.user(USER_ID);
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(user.balances().get(variant.settleAsset()).availableUnits()).isEqualTo(50);
        assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(75);
        assertThat(user.positions().get(SYMBOL).signedQuantitySteps()).isZero();
        assertThat(user.positions().get(SYMBOL).marginMode()).isEqualTo(CoreMarginMode.ISOLATED);
        assertThat(user.positions().get("ETH-USDT").signedQuantitySteps()).isEqualTo(1);
        assertThat(user.positions().get("ETH-USDT").marginMode()).isEqualTo(CoreMarginMode.CROSS);
        assertThat(user.positions().get("ETH-USDT").positionMarginUnits()).isEqualTo(75);
        assertThat(result.deficitUnits()).isEqualTo(linearOrInverse(variant, 50, 61));
        assertThat(result.status()).isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 50L);
        return row(variant, "ISOLATED_FREE_COLLATERAL_LEAKAGE", opening, ending, List.of(), false, false,
                funds(175, 0, 0, 0, -50, 0, 0, 50, 125, 0, 0, 50));
    }

    private TradingCoreState adlSetup(Variant variant) {
        TradingCoreState state = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        state = withPosition(state, variant, MAKER_ID, -QUANTITY, 200, DEFAULT_WALLET, POSITION_MARGIN);
        return withPosition(state, variant, SECOND_MAKER_ID, -QUANTITY, 150, DEFAULT_WALLET, POSITION_MARGIN);
    }

    private TradingCoreState oppositePositions(Variant variant, long userWallet, long makerWallet) {
        TradingCoreState state = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, userWallet,
                POSITION_MARGIN);
        return withPosition(state, variant, MAKER_ID, -QUANTITY, ENTRY_PRICE, makerWallet, POSITION_MARGIN);
    }

    private TradingCoreState pairFunded(Variant variant, boolean tiered, long userWallet, long makerWallet) {
        TradingCoreState state = stateWithInstrument(variant, tiered);
        state = reducer.adjustBalance(state, USER_ID,
                new BalanceAdjustmentCommand(variant.settleAsset(), userWallet));
        return reducer.adjustBalance(state, MAKER_ID,
                new BalanceAdjustmentCommand(variant.settleAsset(), makerWallet));
    }

    private TradingCoreState match(Variant variant, TradingCoreState state, long makerOrderId,
                                   long takerOrderId, CoreOrderSide takerSide, CoreOrderSide makerSide,
                                   long quantity) {
        TradingCoreState placed = reducer.placeOrder(state, MAKER_ID,
                order(makerOrderId, variant, makerSide, quantity, false, 0, 0));
        placed = reducer.placeOrder(placed, USER_ID,
                order(takerOrderId, variant, takerSide, quantity, false, 0, 0));
        return reducer.applyMatches(placed, takerOrderId, variant.baseAsset(), variant.quoteAsset(),
                List.of(new CoreMatch(makerOrderId, MAKER_ID, ENTRY_PRICE, quantity, true, true)));
    }

    private PlaceOrderCommand order(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                     boolean reduceOnly, long makerFeeRatePpm, long takerFeeRatePpm) {
        return pricedOrder(orderId, variant, side, quantity, ENTRY_PRICE, reduceOnly,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    private PlaceOrderCommand pricedOrder(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                           long priceTicks) {
        return pricedOrder(orderId, variant, side, quantity, priceTicks, false, 0, 0);
    }

    private PlaceOrderCommand pricedOrder(long orderId, Variant variant, CoreOrderSide side, long quantity,
                                          long priceTicks, boolean reduceOnly,
                                          long makerFeeRatePpm, long takerFeeRatePpm) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, variant.baseAsset(), variant.quoteAsset(),
                variant.settleAsset(), side, priceTicks, quantity, reduceOnly, variant.marginMode(),
                CorePositionSide.NET, ReservationKind.DERIVATIVE_MARGIN, variant.settleAsset(), 0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, priceTicks, false, "",
                makerFeeRatePpm, takerFeeRatePpm);
    }

    private TradingCoreState mark(TradingCoreState state, Variant variant, long price, long sequence) {
        return reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(SYMBOL, 1, price, sequence, 1_700_000_000_000L));
    }

    private TradingCoreState withPosition(Variant variant, long userId, long quantity, long entryPrice,
                                          long wallet, long margin) {
        TradingCoreState state = fundedState(variant, userId, wallet);
        return addPosition(state, variant, userId, SYMBOL, quantity, entryPrice, margin, variant.marginMode());
    }

    private TradingCoreState withPosition(TradingCoreState state, Variant variant, long userId, long quantity,
                                          long entryPrice, long wallet, long margin) {
        state = fundedState(state, userId, wallet);
        return addPosition(state, variant, userId, SYMBOL, quantity, entryPrice, margin, variant.marginMode());
    }

    private TradingCoreState addPosition(TradingCoreState state, Variant variant, long userId, String symbol,
                                         long quantity, long entryPrice, long margin, CoreMarginMode marginMode) {
        CoreUserState current = state.user(userId);
        AssetBalance currentBalance = current.balances().getOrDefault(variant.settleAsset(),
                new AssetBalance(variant.settleAsset(), 0, 0));
        AssetBalance nextBalance = currentBalance.reserve(margin);
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put(variant.settleAsset(), nextBalance);
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(symbol, new CorePositionState(symbol, variant.settleAsset(), marginMode,
                CorePositionSide.NET, 1, quantity, entryPrice,
                Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, margin));
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(current.revision()), balances, current.reservations(), positions,
                current.positionMode());
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    private TradingCoreState fundedState(Variant variant, long userId, long wallet) {
        return reducer.adjustBalance(stateWithInstrument(variant, false), userId,
                new BalanceAdjustmentCommand(variant.settleAsset(), wallet));
    }

    private TradingCoreState fundedState(TradingCoreState state, long userId, long wallet) {
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(state.instruments().get(SYMBOL).settleAsset(), wallet));
    }

    private TradingCoreState stateWithInstrument(Variant variant, boolean tiered) {
        return reducer.upsertInstrument(TradingCoreState.empty(variant.productLine()),
                instrument(variant, SYMBOL, "BTC", tiered));
    }

    private UpsertInstrumentCommand instrument(Variant variant, String symbol, String baseAsset,
                                               boolean tiered) {
        List<CoreRiskLimitBracket> brackets = tiered
                ? List.of(new CoreRiskLimitBracket(1, 0, 1_000, 5_000_000, 100_000, 100_000),
                new CoreRiskLimitBracket(2, 1_000, 2_500, 5_000_000, 200_000, 200_000))
                : List.of(new CoreRiskLimitBracket(1, 0, 1_000_000, 10_000_000, 100_000, 100_000));
        long maxLeveragePpm = tiered ? 5_000_000 : 10_000_000;
        return new UpsertInstrumentCommand(symbol, 1, variant.type().ordinal(), baseAsset,
                variant.quoteAsset(), variant.settleAsset(), variant.notionalMultiplierUnits(), 1,
                variant.settleScaleUnits(), 100_000, 100_000, 0, 0, 0, -1, 0, maxLeveragePpm,
                tiered ? 2_500 : 1_000_000, 0, 1_000_000, brackets);
    }

    private TradingCoreState replaceLiquidation(TradingCoreState state, CoreLiquidationState liquidation) {
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation);
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                state.orders(), state.instruments(), risk, state.treasuryState());
    }

    private TradingCoreState replacePosition(TradingCoreState state, long userId, CorePositionState position) {
        CoreUserState current = state.user(userId);
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(position.key(), position);
        CoreUserState nextUser = new CoreUserState(current.productLine(), current.userId(),
                Math.incrementExact(current.revision()), current.balances(), current.reservations(), positions,
                current.positionMode());
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    private Row row(Variant variant, String scenario, TradingCoreState opening, TradingCoreState ending,
                    List<Long> makerIds, boolean economic, boolean includeLiquidationDeficit, Funds funds) {
        return new Row(rowKey(variant.type(), variant.marginMode(), scenario), variant, opening, ending,
                makerIds, economic, includeLiquidationDeficit, funds);
    }

    private void assertRows(List<Row> rows) {
        Set<String> seen = new LinkedHashSet<>();
        for (Row row : rows) {
            assertThat(seen.add(row.key())).as("duplicate matrix row").isTrue();
            Funds expected = row.funds();
            long openingUser = userValue(row, row.opening(), USER_ID);
            long openingMaker = makerValue(row, row.opening());
            long openingFee = feeValue(row.opening(), row.variant().settleAsset());
            long openingInsurance = insuranceValue(row, row.opening());
            long endingUser = userValue(row, row.ending(), USER_ID);
            long endingMaker = makerValue(row, row.ending());
            long endingFee = feeValue(row.ending(), row.variant().settleAsset());
            long endingInsurance = insuranceValue(row, row.ending());
            assertThat(openingUser).as(row.key() + " user opening").isEqualTo(expected.userOpening());
            assertThat(openingMaker).as(row.key() + " maker opening").isEqualTo(expected.makerOpening());
            assertThat(openingFee).as(row.key() + " fee opening").isEqualTo(expected.feeOpening());
            assertThat(openingInsurance).as(row.key() + " insurance opening")
                    .isEqualTo(expected.insuranceOpening());
            assertThat(endingUser).as(row.key() + " user ending").isEqualTo(expected.userEnding());
            assertThat(endingMaker).as(row.key() + " maker ending").isEqualTo(expected.makerEnding());
            assertThat(endingFee).as(row.key() + " fee ending").isEqualTo(expected.feeEnding());
            assertThat(endingInsurance).as(row.key() + " insurance ending")
                    .isEqualTo(expected.insuranceEnding());
            assertThat(endingUser - openingUser).as(row.key() + " user flow")
                    .isEqualTo(expected.userFlow());
            assertThat(endingMaker - openingMaker).as(row.key() + " maker flow")
                    .isEqualTo(expected.makerFlow());
            assertThat(endingFee - openingFee).as(row.key() + " fee flow")
                    .isEqualTo(expected.feeFlow());
            assertThat(endingInsurance - openingInsurance).as(row.key() + " insurance flow")
                    .isEqualTo(expected.insuranceFlow());
            assertThat(expected.difference()).as(row.key() + " FUNDS_DIFFERENCE").isZero();
            assertThat((endingUser + endingMaker + endingFee + endingInsurance)
                    - (openingUser + openingMaker + openingFee + openingInsurance))
                    .as(row.key() + " observed FUNDS_DIFFERENCE").isZero();
        }
    }

    private void assertManifest(List<Row> rows, List<String> scenarios) {
        Set<String> expected = requiredRows(scenarios);
        Set<String> actual = new LinkedHashSet<>();
        rows.stream().filter(row -> scenarios.contains(row.key().split(":")[2])).forEach(row -> actual.add(row.key()));
        assertThat(actual).as("matrix completeness manifest").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rows.stream().filter(row -> scenarios.contains(row.key().split(":")[2])).count())
                .isEqualTo(expected.size());
    }

    private static Set<String> requiredRows(List<String> scenarios) {
        Set<String> required = new LinkedHashSet<>();
        for (String key : REQUIRED_ROWS) {
            String scenario = key.substring(key.lastIndexOf(':') + 1);
            if (scenarios.contains(scenario)) {
                required.add(key);
            }
        }
        return required;
    }

    private long userValue(Row row, TradingCoreState state, long userId) {
        CoreUserState user = state.user(userId);
        if (user == null) return 0;
        long value = user.totalUnits(row.variant().settleAsset());
        if (!row.economic()) return value;
        for (CorePositionState position : user.positions().values()) {
            if (position.signedQuantitySteps() == 0 || !position.marginAsset().equals(row.variant().settleAsset())) {
                continue;
            }
            CoreMarkPriceState mark = state.riskState().markPrices().get(position.symbol());
            CoreInstrumentState instrument = state.instruments().get(position.symbol());
            if (mark != null && instrument != null) {
                value = Math.addExact(value, CoreContractMath.pnlUnits(instrument,
                        position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks()));
            }
        }
        return value;
    }

    private long makerValue(Row row, TradingCoreState state) {
        long value = 0;
        for (Long makerId : row.makerIds()) {
            value = Math.addExact(value, userValue(row, state, makerId));
        }
        return value;
    }

    private static long feeValue(TradingCoreState state, String asset) {
        return state.treasuryState().feeBalances().getOrDefault(asset, 0L);
    }

    private long insuranceValue(Row row, TradingCoreState state) {
        long value = state.treasuryState().insuranceBalances()
                .getOrDefault(row.variant().settleAsset(), 0L);
        if (!row.includeLiquidationDeficit()) return value;
        long deficit = state.riskState().liquidations().values().stream()
                .filter(liquidation -> liquidation.symbol().equals(SYMBOL))
                .mapToLong(CoreLiquidationState::deficitUnits).sum();
        return Math.subtractExact(value, deficit);
    }

    private static Funds funds(long userOpening, long makerOpening, long feeOpening, long insuranceOpening,
                               long userFlow, long makerFlow, long feeFlow, long insuranceFlow,
                               long userEnding, long makerEnding, long feeEnding, long insuranceEnding) {
        return new Funds(userOpening, makerOpening, feeOpening, insuranceOpening, userFlow, makerFlow,
                feeFlow, insuranceFlow, userEnding, makerEnding, feeEnding, insuranceEnding);
    }

    private static long linearOrInverse(Variant variant, long linear, long inverse) {
        return variant.type() == ContractType.LINEAR_PERPETUAL ? linear : inverse;
    }

    private static String rowKey(ContractType contractType, CoreMarginMode marginMode, String scenario) {
        return contractType.name() + ":" + marginMode.name() + ":" + scenario;
    }

    private record Variant(ContractType type, CoreMarginMode marginMode, String baseAsset, String quoteAsset,
                           String settleAsset, long notionalMultiplierUnits, long settleScaleUnits) {
        private ProductLine productLine() {
            return type.productLine();
        }
    }

    private record Funds(long userOpening, long makerOpening, long feeOpening, long insuranceOpening,
                         long userFlow, long makerFlow, long feeFlow, long insuranceFlow,
                         long userEnding, long makerEnding, long feeEnding, long insuranceEnding) {
        private long difference() {
            return userEnding + makerEnding + feeEnding + insuranceEnding
                    - userOpening - makerOpening - feeOpening - insuranceOpening
                    - userFlow - makerFlow - feeFlow - insuranceFlow;
        }
    }

    private record Row(String key, Variant variant, TradingCoreState opening, TradingCoreState ending,
                       List<Long> makerIds, boolean economic, boolean includeLiquidationDeficit, Funds funds) {
    }
}
