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
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
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
        assertThat(CoreContractMath.optionSettlementCashUnits(
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
        AssetBalance balance = current.balances().get(variant.settleAsset()).reserve(positionMargin);
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
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand(variant.symbol(), 1, ENTRY_PRICE, 1, 1_700_000_000_000L));
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
