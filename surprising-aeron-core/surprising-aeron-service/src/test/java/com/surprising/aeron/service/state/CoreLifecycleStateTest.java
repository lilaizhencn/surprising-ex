package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CoreLifecycleStateTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void linearFundingIsZeroSumAndSettlementIdSurvivesSnapshot() {
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1));

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
        assertThat(exercised.user(1).totalUnits("USDT")).isEqualTo(1_050);
        assertThat(exercised.user(2).totalUnits("USDT")).isEqualTo(950);
        assertThat(exercised.users().values()).allSatisfy(user ->
                assertThat(user.positions().get("BTC-USDT").signedQuantitySteps()).isZero());
    }

    @Test
    void liquidationCreatesExplicitDeficitAndInsuranceReceiptClosesIt() {
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1, 1));
        CoreLiquidationState plan = state.riskState().liquidations().get(1L);
        assertThat(plan.status()).isEqualTo(CoreLiquidationState.Status.PLANNED);

        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1));

        assertThat(liquidated.user(1).positions().get("BTC-USDT").signedQuantitySteps()).isZero();
        assertThat(liquidated.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        assertThat(deficit).isPositive();

        TradingCoreState resolved = reducer.resolveLiquidation(liquidated,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, deficit));
        assertThat(resolved.treasuryState().insuranceBalances()).containsEntry("USDT", deficit + 100);
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
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
                funded.bookState(), funded.instruments(), funded.riskState(), funded.treasuryState());
    }

    private static long total(TradingCoreState state, String asset) {
        long users = state.users().values().stream().mapToLong(user -> user.totalUnits(asset)).sum();
        long fee = state.treasuryState().feeBalances().getOrDefault(asset, 0L);
        long insurance = state.treasuryState().insuranceBalances().getOrDefault(asset, 0L);
        long deficit = state.treasuryState().insuranceDeficits().getOrDefault(asset, 0L);
        return Math.subtractExact(Math.addExact(Math.addExact(users, fee), insurance), deficit);
    }
}
