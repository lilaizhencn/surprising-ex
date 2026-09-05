package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.surprising.aeron.service.matching.MatcherEventFixtures.trade;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreDeliveryOptionFinancialMatrixTest {

    private static final long USER_ID = 101;
    private static final long MAKER_ID = 202;
    private static final long SECOND_MAKER_ID = 203;
    private static final long QUANTITY = 2;
    private static final long ENTRY_PRICE = 100;
    private static final long STRIKE_PRICE = 100;
    private static final long PREMIUM_PRICE = 10;
    private static final long WALLET = 2_000;
    private static final long POSITION_MARGIN = 100;
    private static final long OPTION_FEE_RATE_PPM = 100_000;
    private static final Set<String> REQUIRED_ROWS = Set.of(
            "LINEAR_DELIVERY:CROSS", "LINEAR_DELIVERY:ISOLATED",
            "INVERSE_DELIVERY:CROSS", "INVERSE_DELIVERY:ISOLATED",
            "OPTION:CALL:ITM", "OPTION:CALL:ATM", "OPTION:CALL:OTM",
            "OPTION:PUT:ITM", "OPTION:PUT:ATM", "OPTION:PUT:OTM");

    private static final List<Variant> VARIANTS = List.of(
            new Variant(ContractType.LINEAR_DELIVERY, CoreMarginMode.CROSS, null, "", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.LINEAR_DELIVERY, CoreMarginMode.ISOLATED, null, "", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.INVERSE_DELIVERY, CoreMarginMode.CROSS, null, "", 120, 100, 100,
                    "BTC", "USD", "BTC"),
            new Variant(ContractType.INVERSE_DELIVERY, CoreMarginMode.ISOLATED, null, "", 120, 100, 100,
                    "BTC", "USD", "BTC"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "ITM", 120, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "ATM", 100, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.CALL, "OTM", 80, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "ITM", 80, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "ATM", 100, 1, 1,
                    "BTC", "USDT", "USDT"),
            new Variant(ContractType.VANILLA_OPTION, CoreMarginMode.CROSS, OptionType.PUT, "OTM", 120, 1, 1,
                    "BTC", "USDT", "USDT"));

    @Test
    void crossLaneExpiryReturnsClearingContributionsBeforeSequencerTreasuryApply() {
        Variant variant = VARIANTS.getFirst();
        TradingCoreState opening = fundedState(variant, USER_ID, WALLET);
        opening = fundedState(opening, SECOND_MAKER_ID, WALLET);
        opening = addPosition(opening, USER_ID, QUANTITY, variant);
        opening = addPosition(opening, SECOND_MAKER_ID, -QUANTITY, variant);
        SettleInstrumentCommand command = new SettleInstrumentCommand(
                701, variant.symbol(), 1, variant.settlementPriceTicks(), 9_999);
        TradingCoreState expected = reducer.settleInstrument(opening, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(opening, identities);
        runtime.startAccountLanes();
        try {
            CoreSettlementProgressView actual = RuntimeSettlementProcessor.applyRuntime(command,
                    List.of(USER_ID, SECOND_MAKER_ID), null, new ActiveOrderIndex(opening), runtime, identities);

            assertThat(actual.complete()).isTrue();
            RuntimeStateParityChecker.assertMatches(expected, identities, runtime);
            assertThat(runtime.accountLane(USER_ID).queueDepth()).isZero();
            assertThat(runtime.accountLane(SECOND_MAKER_ID).queueDepth()).isZero();
        } finally {
            runtime.close();
        }
    }

    private static final Map<String, DeliveryExpectation> DELIVERY_EXPECTATIONS = Map.of(
            "LINEAR_DELIVERY:CROSS", new DeliveryExpectation(40, -40, 2_040, 1_960),
            "LINEAR_DELIVERY:ISOLATED", new DeliveryExpectation(40, -40, 2_040, 1_960),
            "INVERSE_DELIVERY:CROSS", new DeliveryExpectation(33, -33, 2_033, 1_967),
            "INVERSE_DELIVERY:ISOLATED", new DeliveryExpectation(33, -33, 2_033, 1_967));

    @Test
    void failingFirstCompletenessManifestReportsMissingDeliveryAndOptionRows() {
        List<Row> rows = allRows();
        Map<String, Integer> counts = new TreeMap<>();
        rows.forEach(row -> counts.merge(row.key(), 1, Integer::sum));
        Set<String> actual = counts.keySet();
        Set<String> missing = new LinkedHashSet<>(REQUIRED_ROWS);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(REQUIRED_ROWS);
        Set<String> duplicate = new LinkedHashSet<>();
        counts.forEach((key, count) -> {
            if (count > 1) duplicate.add(key);
        });

        assertThat(missing).as("missing delivery/option financial matrix rows").isEmpty();
        assertThat(unexpected).as("unexpected delivery/option financial matrix rows").isEmpty();
        assertThat(duplicate).as("duplicate delivery/option financial matrix rows").isEmpty();
        assertThat(rows).hasSize(REQUIRED_ROWS.size());
    }

    @Test
    void coversDeliveryAndOptionMoneyness() {
        List<Row> rows = allRows();
        assertRows(rows);
        assertThat(rows).extracting(Row::key).containsExactlyInAnyOrderElementsOf(REQUIRED_ROWS);
    }

    @Test
    void inverseDeliveryRoundingOracleUsesIndependentSignedHalfUpFormula() {
        assertThat(independentInverseDeliveryPayout(QUANTITY, ENTRY_PRICE, 120)).isEqualTo(33);
        assertThat(independentInverseDeliveryPayout(-QUANTITY, ENTRY_PRICE, 120)).isEqualTo(-33);
    }

    @Test
    void rejectsUntrustedCashDuplicateMutationAndWrongLine() {
        Variant option = VARIANTS.get(4);
        TradingCoreState matched = matchedOption(option);
        TradingCoreState settled = reducer.settleInstrument(matched,
                new SettleInstrumentCommand(401, option.symbol(), 1, option.settlementPriceTicks(), 9_999));

        assertThat(settled.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET - 20 + 40);
        assertThat(settled.user(MAKER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET + 20 - 40);
        assertThat(settled.treasuryState().feeBalances()).doesNotContainKey(option.settleAsset());
        assertThat(settled.treasuryState().lifecycleSettlements()).containsEntry(option.symbol(), 401L);
        assertThat(reducer.settleInstrument(settled,
                new SettleInstrumentCommand(401, option.symbol(), 1, 1, 1))).isSameAs(settled);

        Variant delivery = VARIANTS.get(2);
        TradingCoreState cursorState = oppositePositions(delivery, WALLET, WALLET);
        SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(402, delivery.symbol(), 1,
                delivery.settlementPriceTicks(), 77, 0, 1);
        TradingCoreReducer.SettlementApplication first = reducer.settleInstrumentWithProgress(cursorState,
                firstCommand, List.of(USER_ID, MAKER_ID), UUID.fromString("00000000-0000-0000-0000-000000000402"));
        long hash = first.state().businessStateHash();
        assertThatThrownBy(() -> reducer.settleInstrumentWithProgress(first.state(), firstCommand,
                List.of(USER_ID, MAKER_ID), UUID.fromString("00000000-0000-0000-0000-000000000402")))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_COMMAND"));
        assertThat(first.state().businessStateHash()).isEqualTo(hash);

        TradingCoreState wrongLine = TradingCoreState.empty(ProductLine.LINEAR_DELIVERY);
        long wrongLineHash = wrongLine.businessStateHash();
        assertThatThrownBy(() -> reducer.upsertInstrument(wrongLine, instrument(VARIANTS.get(4))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_MISMATCH"));
        assertThat(wrongLine.businessStateHash()).isEqualTo(wrongLineHash);
    }

    @Test
    void snapshotCursorResumeCompletesInverseAndOptionWithDerivedCash() {
        for (Variant variant : List.of(VARIANTS.get(2), VARIANTS.get(4))) {
            TradingCoreState state = variant.type().isOption()
                    ? matchedOption(variant) : oppositePositions(variant, WALLET, WALLET);
            long settlementId = variant.type().isOption() ? 403 : 404;
            SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(settlementId, variant.symbol(), 1,
                    variant.settlementPriceTicks(), 9_999, 0, 1);

            TradingCoreReducer.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                    firstCommand, List.of(USER_ID, MAKER_ID),
                    UUID.fromString("00000000-0000-0000-0000-000000000403"));

            assertThat(first.progress().complete()).isFalse();
            assertThat(first.progress().nextCursorUserId()).isEqualTo(USER_ID);
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
            assertThat(restored).isEqualTo(first.state());

            TradingCoreReducer.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                    new SettleInstrumentCommand(settlementId, variant.symbol(), 1,
                            variant.settlementPriceTicks(), 9_999, USER_ID, 1),
                    List.of(USER_ID, MAKER_ID),
                    UUID.fromString("00000000-0000-0000-0000-000000000404"));

            assertThat(second.progress().complete()).isTrue();
            assertThat(second.state().treasuryState().lifecycleProgress(variant.symbol())).isNull();
            assertFlatAndReleased(second.state(), variant);
        }
    }

    @Test
    void snapshotCursorResumeCompletesLinearAndInverseIsolatedDelivery() {
        for (Variant variant : List.of(VARIANTS.get(1), VARIANTS.get(3))) {
            TradingCoreState state = oppositePositions(variant, WALLET, WALLET);
            long settlementId = variant.type() == ContractType.LINEAR_DELIVERY ? 405 : 406;
            SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(settlementId, variant.symbol(), 1,
                    variant.settlementPriceTicks(), 9_999, 0, 1);

            TradingCoreReducer.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                    firstCommand, List.of(USER_ID, MAKER_ID),
                    UUID.fromString("00000000-0000-0000-0000-000000000405"));

            assertThat(first.progress().complete()).isFalse();
            assertThat(first.progress().nextCursorUserId()).isEqualTo(USER_ID);
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(first.state()), variant.productLine());
            assertThat(restored).isEqualTo(first.state());

            TradingCoreReducer.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                    new SettleInstrumentCommand(settlementId, variant.symbol(), 1,
                            variant.settlementPriceTicks(), 9_999, USER_ID, 1),
                    List.of(USER_ID, MAKER_ID),
                    UUID.fromString("00000000-0000-0000-0000-000000000406"));

            assertThat(second.progress().complete()).isTrue();
            assertThat(second.state().treasuryState().lifecycleProgress(variant.symbol())).isNull();
            assertFlatAndReleased(second.state(), variant);
        }
    }

    @Test
    void settlingIsolatedLossKeepsUnrelatedCrossStateAndReservationIntact() {
        Variant cross = VARIANTS.get(0);
        Variant isolated = VARIANTS.get(1);
        String crossSymbol = "BTC-DELIVERY";
        String isolatedSymbol = "ETH-DELIVERY";
        TradingCoreState state = TradingCoreState.empty(ProductLine.LINEAR_DELIVERY);
        state = reducer.upsertInstrument(state, instrument(cross, crossSymbol));
        state = reducer.upsertInstrument(state, instrument(isolated, isolatedSymbol));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(crossSymbol, 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(isolatedSymbol, 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        state = reducer.adjustBalance(state, USER_ID, new BalanceAdjustmentCommand("USDT", 200));
        state = addPosition(state, USER_ID, crossSymbol, 1, 100, 40, cross);
        state = addPosition(state, USER_ID, isolatedSymbol, 1, 140, 20, isolated);
        state = reducer.placeOrder(state, USER_ID, deliveryOrder(701, cross, crossSymbol));

        CoreUserState beforeUser = state.user(USER_ID);
        AssetBalance beforeBalance = beforeUser.balances().get("USDT");
        CorePositionState beforeCrossPosition = beforeUser.positions().get(crossSymbol);
        OrderReservation beforeReservation = beforeUser.reservations().get(701L);
        long beforeCrossCollateral = beforeBalance.totalUnits()
                - beforeUser.positions().get(isolatedSymbol).positionMarginUnits();
        assertThat(beforeBalance.availableUnits()).isEqualTo(130);
        assertThat(beforeBalance.lockedUnits()).isEqualTo(70);
        assertThat(beforeReservation.reservedUnits()).isEqualTo(10);
        assertThat(beforeReservation.remainingUnits()).isEqualTo(10);
        assertThat(beforeCrossCollateral).isEqualTo(180);
        long beforeTotal = total(state, "USDT");

        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(407, isolatedSymbol, 1, 100, 9_999));

        CoreUserState afterUser = settled.user(USER_ID);
        AssetBalance afterBalance = afterUser.balances().get("USDT");
        assertThat(afterUser.positions().get(crossSymbol)).isEqualTo(beforeCrossPosition);
        assertThat(afterUser.reservations().get(701L)).isEqualTo(beforeReservation);
        assertThat(settled.order(701).status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(afterBalance.availableUnits()).isEqualTo(beforeBalance.availableUnits());
        assertThat(afterBalance.totalUnits() - afterUser.positions().get(isolatedSymbol).positionMarginUnits())
                .isEqualTo(beforeCrossCollateral);
        assertThat(afterBalance.lockedUnits()).isEqualTo(50);
        assertThat(afterUser.positions().get(isolatedSymbol).signedQuantitySteps()).isZero();
        assertThat(afterUser.positions().get(isolatedSymbol).realizedPnlUnits()).isEqualTo(-40);
        assertThat(settled.treasuryState().insuranceBalances()).doesNotContainKey("USDT");
        assertThat(settled.treasuryState().clearingPnlBalances()).containsEntry("USDT", 20L);
        assertThat(total(settled, "USDT")).isEqualTo(beforeTotal);
    }

    @Test
    void cancelsOpenDeliveryOrderAndReleasesOwnerReservation() {
        Variant delivery = VARIANTS.get(0);
        TradingCoreState state = fundedState(delivery, USER_ID, WALLET);
        state = reducer.placeOrder(state, USER_ID, deliveryOrder(702, delivery, delivery.symbol()));
        OrderReservation beforeReservation = state.user(USER_ID).reservations().get(702L);
        long beforeTotal = total(state, delivery.settleAsset());

        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(411, delivery.symbol(), 1, delivery.settlementPriceTicks(), 9_999));

        OrderReservation afterReservation = settled.user(USER_ID).reservations().get(702L);
        assertThat(settled.order(702).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(afterReservation.reservedUnits()).isEqualTo(beforeReservation.reservedUnits());
        assertThat(afterReservation.remainingUnits()).isZero();
        assertThat(afterReservation.releasedUnits()).isEqualTo(beforeReservation.reservedUnits());
        assertThat(settled.user(USER_ID).balances().get(delivery.settleAsset()).lockedUnits()).isZero();
        assertThat(total(settled, delivery.settleAsset())).isEqualTo(beforeTotal);
    }

    @Test
    void rejectsFundingForDeliveryAndOptionWithoutStateMutation() {
        assertFundingRejected(VARIANTS.get(0), 408);
        assertFundingRejected(VARIANTS.get(2), 409);
        assertFundingRejected(VARIANTS.get(4), 410);
    }

    @Test
    void cancelsOpenOptionOrderBeforeSettlementAndReleasesReservation() {
        Variant option = VARIANTS.get(4);
        TradingCoreState state = fundedState(option, USER_ID, WALLET);
        state = fundedState(state, MAKER_ID, WALLET);
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(501, option, CoreOrderSide.SELL));
        long before = total(state, option.settleAsset());

        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(501, option.symbol(), 1, option.settlementPriceTicks(), 9_999));

        assertThat(settled.order(501).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(settled.user(MAKER_ID).balances().get(option.settleAsset()).lockedUnits()).isZero();
        assertThat(settled.user(MAKER_ID).positions()).isEmpty();
        assertThat(total(settled, option.settleAsset())).isEqualTo(before);
    }

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void deliveryAdlCandidatesIncludeProfitablePositionsAcrossMarginModesAndRestore() {
        for (Variant variant : VARIANTS.subList(0, 4)) {
            TradingCoreState state = oppositePositions(variant, WALLET, WALLET);
            state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand(
                    variant.symbol(), 1, 120, 2, 1_700_000_000_001L));
            var candidates = reducer.adlCandidates(state, variant.settleAsset(), 10);
            assertThat(candidates).as(variant.key()).hasSize(1);
            assertThat(candidates.getFirst().userId()).isEqualTo(USER_ID);
            assertThat(candidates.getFirst().unrealizedProfitUnits())
                    .isEqualTo(variant.type().isInverse() ? 33 : 40);
            TradingCoreState restored = TradingStateSnapshotCodec.decode(
                    TradingStateSnapshotCodec.encode(state), variant.productLine());
            RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
            TradingRuntimeState runtime = RuntimeStateProjector.project(restored, identities);
            try {
                var index = new AdlPositionIndex(restored, identities);
                assertThat(RuntimeRiskQueryService.adlCandidates(runtime, identities,
                        variant.settleAsset(), index.positions(variant.settleAsset()), 10))
                        .containsExactlyElementsOf(candidates);
                assertThat(reducer.adlCandidates(restored, variant.settleAsset(), 10))
                        .containsExactlyElementsOf(candidates);
                assertThat(total(restored, variant.settleAsset())).isEqualTo(2 * WALLET);
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void nonPortfolioOptionLongIsNotLiquidatedWhenAnotherShortMakesAccountUnsafe() {
        Variant option = VARIANTS.get(4);
        String shortSymbol = option.symbol() + "-SHORT";
        TradingCoreState opening = fundedState(option, USER_ID, 100);
        opening = reducer.upsertInstrument(opening, instrument(option, shortSymbol));
        opening = addPosition(opening, USER_ID, option.symbol(), 1, 10, 0, option);
        opening = addPosition(opening, USER_ID, shortSymbol, -10, 10, 0, option);
        ApplyMarkPriceCommand mark = new ApplyMarkPriceCommand(shortSymbol, 1, 100, 100, 100, 1,
                1_700_000_000_001L);
        TradingCoreState marked = reducer.applyMarkPrice(opening, mark);
        assertThat(marked.riskState().liquidations().values())
                .extracting(CoreLiquidationState::symbol).containsExactly(shortSymbol);
        assertThat(marked.user(USER_ID).positions().get(option.symbol()).signedQuantitySteps()).isEqualTo(1);
        assertThat(total(marked, option.settleAsset())).isEqualTo(100);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeDerivativeRiskProcessor.simulateMarkPrice(
                opening, mark, opening.users().keySet(), identities);
        try {
            RuntimeStateParityChecker.assertMatches(marked, identities, runtime);
            assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(marked),
                    ProductLine.OPTION)).isEqualTo(marked);
            // An already queued plan must not bypass the non-PM long-position protection.
            runtime.putLiquidation(new LiquidationRuntime(99, USER_ID,
                    identities.symbolId(option.symbol()), CoreMarginMode.CROSS, CorePositionSide.NET,
                    1, 1, 1, 1, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
            var execution = new com.surprising.aeron.protocol.ExecuteLiquidationCommand(
                    99, 1, PREMIUM_PRICE, 0);
            assertThat(RuntimeLiquidationQueryService.isExecutable(runtime, identities, execution)).isFalse();
            RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(execution, List.of(), runtime, identities);
            assertThat(runtime.liquidation(99).status()).isEqualTo(CoreLiquidationState.Status.CANCELED);
            assertThat(runtime.position(identities.positionKey(USER_ID, option.symbol()))
                    .signedQuantitySteps()).isEqualTo(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    void isolatedOptionShortEquityUsesFullMarkLiabilityInBothStateModels() {
        Variant call = VARIANTS.get(4);
        Variant isolated = new Variant(call.type(), CoreMarginMode.ISOLATED, call.optionType(), call.moneyness(),
                call.settlementPriceTicks(), call.notionalMultiplierUnits(), call.settleScaleUnits(),
                call.baseAsset(), call.quoteAsset(), call.settleAsset());
        TradingCoreState opening = fundedState(isolated, MAKER_ID, WALLET);
        opening = addPosition(opening, MAKER_ID, isolated.symbol(), -QUANTITY, PREMIUM_PRICE, 40, isolated);
        ApplyMarkPriceCommand mark = new ApplyMarkPriceCommand(isolated.symbol(), 1, 30,
                100, 100, 2, 1_700_000_000_001L);

        TradingCoreState marked = reducer.applyMarkPrice(opening, mark);
        CoreRiskSnapshot risk = marked.riskState().snapshots().get(MAKER_ID + ":" + isolated.symbol());
        assertThat(risk.equityUnits()).isEqualTo(-20);
        assertThat(risk.unrealizedPnlUnits()).isEqualTo(-40);
        assertThat(risk.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        TradingCoreState beforeMark = opening;
        assertRuntimeParity(beforeMark, marked,
                identities -> RuntimeDerivativeRiskProcessor.simulateMarkPrice(
                        beforeMark, mark, beforeMark.users().keySet(), identities));
    }

    @Test
    void optionShortLiquidationAndAdlCloseAtMarkAndConserveCashAcrossRuntimeRestore() {
        Variant option = VARIANTS.get(4);
        TradingCoreState matched = matchedOption(option);
        ApplyMarkPriceCommand shock = new ApplyMarkPriceCommand(option.symbol(), 1, 3_000,
                100, 100, 2, 1_700_000_000_001L);
        TradingCoreState marked = reducer.applyMarkPrice(matched, shock);
        CoreLiquidationState plan = marked.riskState().liquidations().values().iterator().next();
        assertThat(plan.userId()).isEqualTo(MAKER_ID);

        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(
                plan.liquidationId(), 2, 3_000, 0);
        TradingCoreState liquidated = reducer.executeLiquidation(marked, liquidation);
        assertThat(liquidated.user(MAKER_ID).positions().get(option.symbol()).signedQuantitySteps()).isZero();
        assertThat(liquidated.riskState().liquidations().get(plan.liquidationId()).deficitUnits())
                .isEqualTo(3_980);
        assertThat(total(liquidated, option.settleAsset())).isEqualTo(2 * WALLET);
        assertRuntimeParity(marked, liquidated,
                identities -> RuntimeDerivativeLiquidationProcessor.simulateExecution(marked, liquidation, identities));

        ResolveLiquidationCommand insurance = new ResolveLiquidationCommand(plan.liquidationId(),
                ResolveLiquidationCommand.Resolution.INSURANCE, 2_020);
        TradingCoreState insured = reducer.resolveLiquidation(liquidated, insurance);
        assertThat(insured.riskState().liquidations().get(plan.liquidationId()).status())
                .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
        assertRuntimeParity(liquidated, insured,
                identities -> RuntimeDerivativeLiquidationProcessor.simulateResolution(
                        liquidated, insurance, identities));

        ExecuteAdlCommand adl = new ExecuteAdlCommand(plan.liquidationId(), USER_ID, option.symbol(),
                CoreMarginMode.CROSS, CorePositionSide.NET, QUANTITY, PREMIUM_PRICE, 2, 1, 1_960);
        assertThat(reducer.adlCandidates(insured, option.settleAsset(), 10))
                .extracting(com.surprising.aeron.protocol.CoreAdlCandidateView::userId)
                .containsExactly(USER_ID);
        TradingCoreState completed = reducer.executeAdl(insured, adl);
        assertThat(completed.user(USER_ID).positions().get(option.symbol()).signedQuantitySteps()).isEqualTo(1);
        assertThat(completed.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(3_020);
        assertThat(completed.riskState().liquidations().get(plan.liquidationId()).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(total(completed, option.settleAsset())).isEqualTo(2 * WALLET);
        assertRuntimeParity(insured, completed,
                identities -> RuntimeDerivativeLiquidationProcessor.simulateAdl(insured, adl, identities));
        assertThat(TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(completed), ProductLine.OPTION))
                .isEqualTo(completed);
    }

    @Test
    void optionSellOpenAndBuyCloseReserveNetCashAndRuntimeMatchKeepsParity() {
        Variant option = VARIANTS.get(4);
        TradingCoreState state = optionFundingState(option);
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(301, option, CoreOrderSide.SELL));
        assertThat(state.user(MAKER_ID).reservations().get(301L).remainingUnits()).isEqualTo(20);
        state = reducer.placeOrder(state, USER_ID, optionOrder(302, option, CoreOrderSide.BUY));
        assertThat(state.user(USER_ID).reservations().get(302L).remainingUnits()).isEqualTo(20);

        TradingCoreState beforeMatch = state;
        List<MatcherEvent> matches =
                List.of(trade(301, MAKER_ID, PREMIUM_PRICE, QUANTITY, true, true));
        TradingCoreState matched = reducer.applyMatches(
                beforeMatch, 302, option.baseAsset(), option.quoteAsset(), matches);
        CorePositionState shortPosition = matched.user(MAKER_ID).positions().get(option.symbol());
        assertThat(shortPosition.positionMarginUnits()).isEqualTo(40);
        assertThat(matched.user(MAKER_ID).balances().get(option.settleAsset()).availableUnits()).isEqualTo(1_980);
        assertThat(matched.user(MAKER_ID).balances().get(option.settleAsset()).lockedUnits()).isEqualTo(40);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeDerivativeMatchProcessor.simulate(
                beforeMatch, 302, matches, identities)) {
            RuntimeStateParityChecker.assertMatches(matched, identities, runtime);
        }

        PlaceOrderCommand buyClose = new PlaceOrderCommand(303, option.symbol(), 1,
                CoreOrderSide.BUY, PREMIUM_PRICE, QUANTITY, true, option.marginMode(),
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
        TradingCoreState closing = reducer.placeOrder(matched, MAKER_ID, buyClose);
        assertThat(closing.user(MAKER_ID).reservations().get(303L).remainingUnits()).isOne();
        PlaceOrderCommand sellClose = new PlaceOrderCommand(304, option.symbol(), 1,
                CoreOrderSide.SELL, PREMIUM_PRICE, QUANTITY, true, option.marginMode(),
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
        closing = reducer.placeOrder(closing, USER_ID, sellClose);
        assertThat(closing.user(USER_ID).reservations().get(304L).remainingUnits()).isOne();
        List<MatcherEvent> closeMatches =
                List.of(trade(304, USER_ID, PREMIUM_PRICE, QUANTITY, true, true));
        TradingCoreState beforeClose = closing;
        TradingCoreState closed = reducer.applyMatches(
                beforeClose, 303, option.baseAsset(), option.quoteAsset(), closeMatches);
        assertThat(closed.user(MAKER_ID).positions().get(option.symbol()).signedQuantitySteps()).isZero();
        assertThat(closed.user(USER_ID).positions().get(option.symbol()).signedQuantitySteps()).isZero();
        assertThat(closed.user(MAKER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET);
        assertThat(closed.user(USER_ID).totalUnits(option.settleAsset())).isEqualTo(WALLET);
        assertThat(total(closed, option.settleAsset())).isEqualTo(2 * WALLET);

        RuntimeIdentityRegistry closeIdentities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeDerivativeMatchProcessor.simulate(
                beforeClose, 303, closeMatches, closeIdentities)) {
            RuntimeStateParityChecker.assertMatches(closed, closeIdentities, runtime);
        }
    }

    @Test
    void nonPortfolioOptionRejectsLeverageConfigurationInBothStateModels() {
        Variant option = VARIANTS.get(4);
        TradingCoreState state = fundedState(option, USER_ID, WALLET);
        UpdateLeverageCommand command = new UpdateLeverageCommand(
                option.symbol(), CoreMarginMode.CROSS, 1_000_000);
        assertThatThrownBy(() -> reducer.updateLeverage(state, USER_ID, command))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OPTION_LEVERAGE_UNSUPPORTED"));

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities)) {
            assertThatThrownBy(() -> DerivativeAccountCommandProcessor.updateLeverage(
                    runtime, identities, USER_ID, command))
                    .isInstanceOfSatisfying(CoreStateRejectedException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo("OPTION_LEVERAGE_UNSUPPORTED"));
        }
    }

    private static void assertRuntimeParity(TradingCoreState before, TradingCoreState expected,
                                            java.util.function.Function<RuntimeIdentityRegistry,
                                                    TradingRuntimeState> operation) {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = operation.apply(identities)) {
            RuntimeStateParityChecker.assertMatches(expected, identities, runtime);
        }
    }

    private List<Row> allRows() {
        return VARIANTS.stream().map(variant -> variant.type().isOption()
                ? optionRow(variant) : deliveryRow(variant)).toList();
    }

    private Row deliveryRow(Variant variant) {
        TradingCoreState opening = oppositePositions(variant, WALLET, WALLET);
        DeliveryExpectation expected = DELIVERY_EXPECTATIONS.get(variant.key());
        assertThat(expected).as(variant.key() + " independent delivery expectation").isNotNull();
        TradingCoreState ending = reducer.settleInstrument(opening,
                new SettleInstrumentCommand(100 + variant.marginMode().ordinal(), variant.symbol(), 1,
                        variant.settlementPriceTicks(), 9_999));
        assertFlatAndReleased(ending, variant);
        assertThat(userValue(ending, variant) - userValue(opening, variant))
                .as(variant.key() + " signed long payout")
                .isEqualTo(expected.longPayout());
        assertThat(userValue(ending, variant, MAKER_ID) - userValue(opening, variant, MAKER_ID))
                .as(variant.key() + " signed short payout")
                .isEqualTo(expected.shortPayout());
        assertThat(userValue(ending, variant)).as(variant.key() + " user final balance")
                .isEqualTo(expected.userEnding());
        assertThat(userValue(ending, variant, MAKER_ID)).as(variant.key() + " maker final balance")
                .isEqualTo(expected.makerEnding());
        return new Row(rowKey(variant), variant, opening, ending,
                funds(WALLET, WALLET, 0, 0, expected.longPayout(), expected.shortPayout(), 0, 0,
                        expected.userEnding(), expected.makerEnding(), 0, 0));
    }

    private Row optionRow(Variant variant) {
        TradingCoreState opening = optionFundingState(variant);
        TradingCoreState matched = matchOption(opening, variant);
        long intrinsicTicks = variant.optionType() == OptionType.CALL
                ? Math.max(variant.settlementPriceTicks() - STRIKE_PRICE, 0)
                : Math.max(STRIKE_PRICE - variant.settlementPriceTicks(), 0);
        long expectedCashPerContract = Math.multiplyExact(intrinsicTicks, variant.notionalMultiplierUnits());
        assertThat(OptionContractMath.optionSettlementCashUnits(
                matched.instruments().get(variant.symbol()), variant.settlementPriceTicks()))
                .as(variant.key() + " intrinsic cash per contract")
                .isEqualTo(expectedCashPerContract);
        long payout = Math.multiplyExact(expectedCashPerContract, QUANTITY);
        TradingCoreState ending = reducer.settleInstrument(matched,
                new SettleInstrumentCommand(200 + variant.optionType().ordinal() * 10
                        + (variant.moneyness().equals("ITM") ? 1 : variant.moneyness().equals("ATM") ? 2 : 3),
                        variant.symbol(), 1,
                        variant.settlementPriceTicks(), 9_999));
        assertFlatAndReleased(ending, variant);
        return new Row(rowKey(variant), variant, opening, ending,
                funds(WALLET, WALLET, 0, 0,
                        Math.subtractExact(payout, 20), Math.subtractExact(20, payout), 0, 0,
                        WALLET - 20 + payout, WALLET + 20 - payout, 0, 0));
    }

    private TradingCoreState matchedOption(Variant variant) {
        return matchOption(optionFundingState(variant), variant);
    }

    private TradingCoreState optionFundingState(Variant variant) {
        TradingCoreState state = fundedState(variant, USER_ID, WALLET);
        return fundedState(state, MAKER_ID, WALLET);
    }

    private TradingCoreState matchOption(TradingCoreState state, Variant variant) {
        state = reducer.placeOrder(state, MAKER_ID, optionOrder(301, variant, CoreOrderSide.SELL));
        state = reducer.placeOrder(state, USER_ID, optionOrder(302, variant, CoreOrderSide.BUY));
        return reducer.applyMatches(state, 302, variant.baseAsset(), variant.quoteAsset(),
                List.of(trade(301, MAKER_ID, PREMIUM_PRICE, QUANTITY, true, true)));
    }

    private PlaceOrderCommand optionOrder(long orderId, Variant variant, CoreOrderSide side) {
        return new PlaceOrderCommand(orderId, variant.symbol(), 1, side, PREMIUM_PRICE, QUANTITY, false, variant.marginMode(), CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
    }

    private TradingCoreState oppositePositions(Variant variant, long userWallet, long makerWallet) {
        TradingCoreState state = fundedState(variant, USER_ID, userWallet);
        state = fundedState(state, MAKER_ID, makerWallet);
        state = addPosition(state, USER_ID, QUANTITY, variant);
        return addPosition(state, MAKER_ID, -QUANTITY, variant);
    }

    private TradingCoreState addPosition(TradingCoreState state, long userId, long quantity, Variant variant) {
        return addPosition(state, userId, variant.symbol(), quantity, ENTRY_PRICE, POSITION_MARGIN, variant);
    }

    private TradingCoreState addPosition(TradingCoreState state, long userId, String symbol, long quantity,
                                         long entryPrice, long positionMargin, Variant variant) {
        CoreUserState current = state.user(userId);
        AssetBalance balance = current.balances().get(variant.settleAsset());
        if (positionMargin > 0) balance = balance.reserve(positionMargin);
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put(variant.settleAsset(), balance);
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(symbol, new CorePositionState(symbol, variant.settleAsset(), variant.marginMode(),
                CorePositionSide.NET, 1, quantity, entryPrice,
                Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, positionMargin));
        CoreUserState next = new CoreUserState(current.productLine(), userId,
                Math.incrementExact(current.revision()), balances, current.reservations(), positions,
                current.positionMode());
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, next);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState());
    }

    private TradingCoreState fundedState(Variant variant, long userId, long wallet) {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(variant.productLine()),
                instrument(variant));
        state = reducer.applyMarkPrice(state, variant.type().isOption()
                ? new ApplyMarkPriceCommand(variant.symbol(), 1, PREMIUM_PRICE, 100, 100, 1,
                1_700_000_000_000L)
                : new ApplyMarkPriceCommand(variant.symbol(), 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(variant.settleAsset(), wallet));
    }

    private TradingCoreState fundedState(TradingCoreState state, long userId, long wallet) {
        String settleAsset = state.instruments().values().iterator().next().settleAsset();
        return reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand(settleAsset, wallet));
    }

    private UpsertInstrumentCommand instrument(Variant variant) {
        return instrument(variant, variant.symbol());
    }

    private UpsertInstrumentCommand instrument(Variant variant, String symbol) {
        return new UpsertInstrumentCommand(symbol, 1, variant.type().ordinal(), variant.baseAsset(),
                variant.quoteAsset(), variant.settleAsset(), variant.notionalMultiplierUnits(), 1,
                variant.settleScaleUnits(), 100_000, 100_000, 0, 0, 2_000_000_000_000L,
                variant.optionType() == null ? -1 : variant.optionType().ordinal(),
                variant.optionType() == null ? 0 : STRIKE_PRICE);
    }

    private PlaceOrderCommand deliveryOrder(long orderId, Variant variant, String symbol) {
        return new PlaceOrderCommand(orderId, symbol, 1, CoreOrderSide.BUY, ENTRY_PRICE, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "");
    }

    private void assertFundingRejected(Variant variant, long settlementId) {
        TradingCoreState state = fundedState(variant, USER_ID, WALLET);
        TradingCoreState before = state;
        long hash = state.businessStateHash();
        assertThatThrownBy(() -> reducer.applyFunding(state,
                new ApplyFundingCommand(settlementId, variant.symbol(), 1, 100_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
        assertThat(state).isSameAs(before);
        assertThat(state.businessStateHash()).isEqualTo(hash);
        assertThat(state.treasuryState().fundingSettlements()).doesNotContainKey(variant.symbol());
    }

    private static long independentInverseDeliveryPayout(long signedQuantitySteps, long entryPriceTicks,
                                                          long settlementPriceTicks) {
        BigDecimal numerator = BigDecimal.valueOf(signedQuantitySteps)
                .multiply(BigDecimal.valueOf(100))
                .multiply(BigDecimal.valueOf(100))
                .multiply(BigDecimal.valueOf(settlementPriceTicks - entryPriceTicks));
        BigDecimal denominator = BigDecimal.valueOf(entryPriceTicks)
                .multiply(BigDecimal.valueOf(settlementPriceTicks));
        return numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private void assertFlatAndReleased(TradingCoreState state, Variant variant) {
        assertThat(state.instruments().get(variant.symbol()).expiryEpochMillis()).isPositive();
        state.users().values().forEach(user -> {
            CorePositionState position = user.positions().get(variant.symbol());
            if (position != null) {
                assertThat(position.signedQuantitySteps()).as(variant.key()).isZero();
                assertThat(position.positionMarginUnits()).as(variant.key()).isZero();
            }
            assertThat(user.balances().get(variant.settleAsset()).lockedUnits()).as(variant.key()).isZero();
        });
        assertThat(state.treasuryState().lifecycleSettlements()).containsEntry(variant.symbol(),
                state.treasuryState().lifecycleSettlement(variant.symbol()));
    }

    private void assertRows(List<Row> rows) {
        Set<String> seen = new LinkedHashSet<>();
        for (Row row : rows) {
            assertThat(seen.add(row.key())).as("duplicate matrix row").isTrue();
            Funds expected = row.funds();
            long openingUser = userValue(row.opening(), row.variant());
            long openingMaker = userValue(row.opening(), row.variant(), MAKER_ID);
            long openingFee = feeValue(row.opening(), row.variant());
            long openingInsurance = insuranceValue(row.opening(), row.variant());
            long endingUser = userValue(row.ending(), row.variant());
            long endingMaker = userValue(row.ending(), row.variant(), MAKER_ID);
            long endingFee = feeValue(row.ending(), row.variant());
            long endingInsurance = insuranceValue(row.ending(), row.variant());
            assertThat(openingUser).as(row.key() + " user opening").isEqualTo(expected.userOpening());
            assertThat(openingMaker).as(row.key() + " maker opening").isEqualTo(expected.makerOpening());
            assertThat(openingFee).as(row.key() + " fee opening").isEqualTo(expected.feeOpening());
            assertThat(openingInsurance).as(row.key() + " insurance opening").isEqualTo(expected.insuranceOpening());
            assertThat(endingUser).as(row.key() + " user ending").isEqualTo(expected.userEnding());
            assertThat(endingMaker).as(row.key() + " maker ending").isEqualTo(expected.makerEnding());
            assertThat(endingFee).as(row.key() + " fee ending").isEqualTo(expected.feeEnding());
            assertThat(endingInsurance).as(row.key() + " insurance ending").isEqualTo(expected.insuranceEnding());
            assertThat(expected.difference()).as(row.key() + " FUNDS_DIFFERENCE").isZero();
            assertThat((endingUser + endingMaker + endingFee + endingInsurance)
                    - (openingUser + openingMaker + openingFee + openingInsurance))
                    .as(row.key() + " observed FUNDS_DIFFERENCE").isZero();
        }
    }

    private long userValue(TradingCoreState state, Variant variant) {
        return userValue(state, variant, USER_ID);
    }

    private long userValue(TradingCoreState state, Variant variant, long userId) {
        return state.user(userId) == null ? 0 : state.user(userId).totalUnits(variant.settleAsset());
    }

    private long feeValue(TradingCoreState state, Variant variant) {
        return state.treasuryState().feeBalances().getOrDefault(variant.settleAsset(), 0L);
    }

    private long insuranceValue(TradingCoreState state, Variant variant) {
        return state.treasuryState().insuranceBalances().getOrDefault(variant.settleAsset(), 0L)
                - state.treasuryState().insuranceDeficits().getOrDefault(variant.settleAsset(), 0L);
    }

    private long total(TradingCoreState state, String asset) {
        long users = state.users().values().stream().mapToLong(user -> user.totalUnits(asset)).sum();
        CoreTreasuryState treasury = state.treasuryState();
        long total = users;
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
        return Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
    }

    private static String rowKey(Variant variant) {
        return variant.type().isOption()
                ? "OPTION:" + variant.optionType().name() + ':' + variant.moneyness()
                : variant.type().name() + ':' + variant.marginMode().name();
    }

    private static Funds funds(long userOpening, long makerOpening, long feeOpening, long insuranceOpening,
                               long userFlow, long makerFlow, long feeFlow, long insuranceFlow,
                               long userEnding, long makerEnding, long feeEnding, long insuranceEnding) {
        return new Funds(userOpening, makerOpening, feeOpening, insuranceOpening, userFlow, makerFlow,
                feeFlow, insuranceFlow, userEnding, makerEnding, feeEnding, insuranceEnding);
    }

    private record Variant(ContractType type, CoreMarginMode marginMode, OptionType optionType,
                           String moneyness, long settlementPriceTicks, long notionalMultiplierUnits,
                           long settleScaleUnits, String baseAsset, String quoteAsset, String settleAsset) {
        private String symbol() {
            return type.isOption() ? "BTC-OPTION" : "BTC-DELIVERY";
        }

        private ProductLine productLine() {
            return type.productLine();
        }

        private String key() {
            return rowKey(this);
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

    private record Row(String key, Variant variant, TradingCoreState opening,
                       TradingCoreState ending, Funds funds) {
    }

    private record DeliveryExpectation(long longPayout, long shortPayout, long userEnding, long makerEnding) {
    }
}
