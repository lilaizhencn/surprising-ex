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
            "ISOLATED_COLLATERAL_LEAKAGE");

    private static final List<String> POSITIVE_SCENARIOS = SCENARIOS.subList(0, 15);
    private static final List<String> NEGATIVE_SCENARIOS = SCENARIOS.subList(15, SCENARIOS.size());

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void failingFirstCompletenessManifestReportsEveryMissingRow() {
        Set<String> required = requiredRows(SCENARIOS);
        Set<String> implemented = implementedRows();
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(implemented);
        Set<String> duplicate = new LinkedHashSet<>(implemented);
        duplicate.removeAll(required);

        assertThat(missing).as("missing perpetual financial matrix rows").isEmpty();
        assertThat(duplicate).as("unexpected perpetual financial matrix rows").isEmpty();
        assertThat(implemented).hasSize(required.size());
    }

    @Test
    void coversLinearInverseCrossIsolated() {
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
        }

        assertRows(rows);
        assertManifest(rows, POSITIVE_SCENARIOS);
    }

    @Test
    void rejectsStaleCrossLineAndCollateralLeakage() {
        List<Row> rows = new ArrayList<>();
        for (Variant variant : VARIANTS) {
            rows.add(staleMark(variant));
            rows.add(crossLineRejected(variant));
            rows.add(isolatedCollateralLeakage(variant));
        }

        assertRows(rows);
        assertManifest(rows, List.of("STALE_MARK"));
        assertManifest(rows, NEGATIVE_SCENARIOS);
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
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 100, POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, 90, 1);
        CoreLiquidationState plan = marked.riskState().liquidations().get(1L);
        if (closeQuantity != QUANTITY) {
            plan = new CoreLiquidationState(plan.liquidationId(), plan.userId(), plan.symbol(), plan.marginMode(),
                    plan.positionSide(), plan.instrumentVersion(), plan.triggerPriceSequence(),
                    plan.signedQuantitySteps(), closeQuantity, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED);
            marked = replaceLiquidation(marked, plan);
        }
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 90, 0));

        long endingUser = closeQuantity == QUANTITY ? 0 : 50;
        long insurance = closeQuantity == QUANTITY ? 100 : 50;
        long deficit = closeQuantity == QUANTITY
                ? linearOrInverse(variant, 0, 11) : linearOrInverse(variant, 0, 6);
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        assertThat(result.deficitUnits()).isEqualTo(deficit);
        assertThat(ending.user(USER_ID).positions().get(SYMBOL).signedQuantitySteps())
                .isEqualTo(closeQuantity == QUANTITY ? 0 : 5);
        assertThat(result.status()).isEqualTo(deficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.INSURANCE_REQUIRED);
        return row(variant, scenario, opening, ending, List.of(), false, false,
                funds(100, 0, 0, 0, endingUser - 100, 0, 0, insurance,
                        endingUser, 0, 0, insurance));
    }

    private Row cappedLiquidation(Variant variant) {
        TradingCoreState opening = withPosition(variant, USER_ID, QUANTITY, ENTRY_PRICE, 180, POSITION_MARGIN);
        TradingCoreState marked = mark(opening, variant, 90, 1);
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 90, 100_000));
        CoreLiquidationState result = ending.riskState().liquidations().get(1L);
        long expectedFee = linearOrInverse(variant, 80, 69);
        assertThat(result.liquidationFeeUnits()).isEqualTo(expectedFee);
        assertThat(ending.user(USER_ID).totalUnits(variant.settleAsset())).isZero();
        assertThat(ending.treasuryState().insuranceBalances()).containsEntry(variant.settleAsset(), 180L);
        return row(variant, "LIQUIDATION_FEE_CAP", opening, ending, List.of(), false, false,
                funds(180, 0, 0, 0, -180, 0, 0, 180, 0, 0, 0, 180));
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
        TradingCoreState ending = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage));

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
        TradingCoreState ending = reducer.executeAdl(beforeAdl,
                new ExecuteAdlCommand(1, MAKER_ID, SYMBOL, variant.marginMode(), CorePositionSide.NET,
                        -QUANTITY, 200, 1, 5, residual));

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
        opening = fundedState(opening, USER_ID, 300);
        opening = addPosition(opening, variant, USER_ID, SYMBOL, QUANTITY, ENTRY_PRICE,
                POSITION_MARGIN, CoreMarginMode.ISOLATED);
        opening = addPosition(opening, variant, USER_ID, "ETH-USDT", QUANTITY, ENTRY_PRICE,
                200, CoreMarginMode.CROSS);
        TradingCoreState markedEth = reducer.applyMarkPrice(opening,
                new ApplyMarkPriceCommand("ETH-USDT", 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        TradingCoreState marked = reducer.applyMarkPrice(markedEth,
                new ApplyMarkPriceCommand(SYMBOL, 1, 90, 1, 1_700_000_000_000L));
        TradingCoreState ending = reducer.executeLiquidation(marked,
                new ExecuteLiquidationCommand(1, 1, 90, 0));

        CoreUserState user = ending.user(USER_ID);
        assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).isEqualTo(200);
        assertThat(user.positions().get("ETH-USDT").positionMarginUnits()).isEqualTo(200);
        assertThat(user.positions().get(SYMBOL).signedQuantitySteps()).isZero();
        assertThat(ending.treasuryState().insuranceBalances())
                .containsEntry(variant.settleAsset(), 100L);
        return row(variant, "ISOLATED_COLLATERAL_LEAKAGE", opening, ending, List.of(), false, false,
                funds(300, 0, 0, 0, -100, 0, 0, 100, 200, 0, 0, 100));
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
        return new PlaceOrderCommand(orderId, SYMBOL, 1, variant.baseAsset(), variant.quoteAsset(),
                variant.settleAsset(), side, ENTRY_PRICE, quantity, reduceOnly, variant.marginMode(),
                CorePositionSide.NET, ReservationKind.DERIVATIVE_MARGIN, variant.settleAsset(), 0,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, ENTRY_PRICE, false, "",
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
        for (Variant variant : VARIANTS) {
            for (String scenario : scenarios) {
                required.add(rowKey(variant.type(), variant.marginMode(), scenario));
            }
        }
        return required;
    }

    private static Set<String> implementedRows() {
        Set<String> implemented = new LinkedHashSet<>();
        for (Variant variant : VARIANTS) {
            for (String scenario : SCENARIOS) {
                implemented.add(rowKey(variant.type(), variant.marginMode(), scenario));
            }
        }
        return implemented;
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
