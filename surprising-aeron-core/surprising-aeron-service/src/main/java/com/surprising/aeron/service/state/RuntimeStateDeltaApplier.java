package com.surprising.aeron.service.state;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Applies an immutable Core transition to the existing runtime object without replacing that object. */
public final class RuntimeStateDeltaApplier {

    private RuntimeStateDeltaApplier() {
    }

    public static void apply(TradingCoreState before, TradingCoreState after,
                             TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (before == null || after == null || runtime == null || identities == null
                || before.productLine() != after.productLine()) {
            throw new IllegalArgumentException("invalid runtime transition");
        }
        runtime.assertOwner();
        syncUsers(before, after, runtime, identities);
        syncOrders(before, after, runtime, identities);
        syncClientIndex(before, after, runtime, identities);
        syncRisk(before, after, runtime, identities);
        if (before.treasuryState() != after.treasuryState()) syncTreasury(after, runtime, identities);
        syncMap(before.instruments(), after.instruments(), runtime.instrumentsForRuntime());
        syncMap(before.leverages(), after.leverages(), runtime.leveragesForRuntime());
        syncMap(before.algoOrders(), after.algoOrders(), runtime.algoOrdersForRuntime());
        syncMap(before.cancelAllAfterTimers(), after.cancelAllAfterTimers(), runtime.cancelAllAfterTimersForRuntime());
        syncMap(before.triggerOrders(), after.triggerOrders(), runtime.triggerOrdersForRuntime());
        runtime.setRiskScanControl(after.riskState().scanControl());
        runtime.setMetadata(after.productLine(), after.revision());
    }

    private static void syncUsers(TradingCoreState before, TradingCoreState after,
                                  TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        for (long userId : changedKeys(before.users(), after.users())) {
            CoreUserState previous = before.users().get(userId);
            CoreUserState next = after.users().get(userId);
            if (next == null) {
                if (previous != null) {
                    previous.reservations().keySet().forEach(orderId -> runtime.removeReservation(orderId, userId));
                    previous.positions().keySet().forEach(key ->
                            runtime.removePosition(identities.positionKey(userId, key), userId));
                    previous.balances().keySet().forEach(asset -> {
                        if (runtime.balance(userId, identities.assetId(asset)) != null) {
                            runtime.removeBalance(userId, identities.assetId(asset));
                        }
                    });
                    runtime.removeUser(userId);
                }
                continue;
            }
            runtime.putUser(new UserRuntime(next.productLine(), userId, next.revision(), next.positionMode()));
            for (String asset : changedKeys(previous == null ? Map.of() : previous.balances(), next.balances())) {
                AssetBalance value = next.balances().get(asset);
                int assetId = identities.assetId(asset);
                if (value == null) {
                    if (runtime.balance(userId, assetId) != null) runtime.removeBalance(userId, assetId);
                } else {
                    runtime.putBalance(new BalanceRuntime(userId, assetId,
                            value.availableUnits(), value.lockedUnits()));
                }
            }
            for (long orderId : changedKeys(previous == null ? Map.of() : previous.reservations(),
                    next.reservations())) {
                OrderReservation value = next.reservations().get(orderId);
                if (value == null) {
                    if (runtime.reservation(orderId) != null) runtime.removeReservation(orderId, userId);
                } else {
                    runtime.putReservation(RuntimeStateProjector.toRuntimeReservation(userId, value, identities));
                }
            }
            for (String key : changedKeys(previous == null ? Map.of() : previous.positions(), next.positions())) {
                long positionKey = identities.positionKey(userId, key);
                CorePositionState value = next.positions().get(key);
                if (value == null) {
                    if (runtime.position(positionKey) != null) runtime.removePosition(positionKey, userId);
                } else {
                    runtime.putPosition(positionKey, new PositionRuntime(userId, identities.symbolId(value.symbol()),
                            identities.assetId(value.marginAsset()), value.marginMode(), value.positionSide(),
                            value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                            value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits()));
                }
            }
        }
    }

    private static void syncOrders(TradingCoreState before, TradingCoreState after,
                                   TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        for (long orderId : changedKeys(before.orders(), after.orders())) {
            CoreOrderState order = after.orders().get(orderId);
            if (order == null) runtime.removeOrder(orderId);
            else runtime.putOrder(RuntimeStateProjector.toRuntimeOrder(order, identities));
        }
    }

    private static void syncClientIndex(TradingCoreState before, TradingCoreState after,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        for (TradingCoreState.ClientOrderKey key : changedKeys(before.clientOrderIndex(), after.clientOrderIndex())) {
            long clientKey = identities.clientKey(key.userId(), key.clientOrderId());
            Long orderId = after.clientOrderIndex().get(key);
            if (orderId == null) runtime.removeClientOrder(key.userId(), clientKey);
            else runtime.putClientOrder(key.userId(), clientKey, orderId);
        }
    }

    private static void syncRisk(TradingCoreState before, TradingCoreState after,
                                 TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        for (String symbol : changedKeys(before.riskState().markPrices(), after.riskState().markPrices())) {
            int symbolId = identities.symbolId(symbol);
            CoreMarkPriceState mark = after.riskState().markPrices().get(symbol);
            if (mark == null) runtime.removeMarkPrice(symbolId);
            else runtime.putMarkPrice(new MarkPriceRuntime(symbolId, mark.instrumentVersion(),
                    mark.markPriceTicks(), mark.priceSequence()));
        }
        for (String key : changedKeys(before.riskState().snapshots(), after.riskState().snapshots())) {
            CoreRiskSnapshot value = after.riskState().snapshots().get(key);
            CoreRiskSnapshot identity = value != null ? value : before.riskState().snapshots().get(key);
            long positionKey = identities.positionKey(identity.userId(), positionKey(identity));
            if (value == null) runtime.removeRiskSnapshot(positionKey);
            else runtime.putRiskSnapshot(positionKey, new RiskSnapshotRuntime(value.userId(),
                    identities.symbolId(value.symbol()), value.positionSide(), value.priceSequence(),
                    value.equityUnits(), value.unrealizedPnlUnits(), value.maintenanceMarginUnits(),
                    value.marginRatioPpm(), value.status()));
        }
        for (long id : changedKeys(before.riskState().liquidations(), after.riskState().liquidations())) {
            CoreLiquidationState value = after.riskState().liquidations().get(id);
            if (value == null) runtime.removeLiquidation(id);
            else {
                LiquidationRuntime next = new LiquidationRuntime(id, value.userId(), identities.symbolId(value.symbol()),
                        value.marginMode(), value.positionSide(), value.instrumentVersion(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                        value.deficitUnits(), value.executionPriceTicks(), value.liquidationFeeRatePpm(),
                        value.liquidationFeeUnits(), value.status(), value.nextCancelOrderId());
                if (runtime.liquidation(id) == null) runtime.putLiquidation(next); else runtime.replaceLiquidation(next);
            }
        }
        for (String symbol : changedKeys(before.riskState().scans(), after.riskState().scans())) {
            int symbolId = identities.symbolId(symbol);
            CoreRiskState.RiskScan scan = after.riskState().scans().get(symbol);
            if (scan == null) runtime.removeRiskScan(symbolId);
            else runtime.putRiskScan(new RiskScanRuntime(symbolId, scan.priceSequence(),
                    scan.scanStartPriceSequence(), scan.lastUserId(), scan.riskComplete(), scan.riskUserId(),
                    scan.riskPhase(), scan.riskPositionCursor(), scan.riskReservationCursor(),
                    scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(),
                    scan.riskIsolatedReservationUnits(), scan.triggerComplete(), scan.triggerPhase(),
                    scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                    scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                    scan.triggerOcoCursor()));
        }
        runtime.setNextLiquidationId(after.riskState().nextLiquidationId());
    }

    private static void syncTreasury(TradingCoreState after, TradingRuntimeState runtime,
                                     RuntimeIdentityRegistry identities) {
        runtime.treasury().clear();
        CoreTreasuryState source = after.treasuryState();
        source.feeBalances().forEach((asset, value) -> runtime.treasury().setFee(identities.assetId(asset), value));
        Set<String> insuranceAssets = new HashSet<>(source.insuranceBalances().keySet());
        insuranceAssets.addAll(source.insuranceDeficits().keySet());
        insuranceAssets.forEach(asset -> runtime.treasury().setInsurance(identities.assetId(asset),
                source.insuranceBalances().getOrDefault(asset, 0L), source.insuranceDeficits().getOrDefault(asset, 0L)));
        source.fundingSettlements().forEach((symbol, value) ->
                runtime.treasury().setFundingSettlement(identities.symbolId(symbol), value));
        source.fundingProgress().forEach((symbol, value) -> runtime.treasury().setFundingProgress(
                identities.symbolId(symbol), new TreasuryRuntime.FundingProgressRuntime(value.settlementId(),
                        value.instrumentVersion(), value.fundingRatePpm(), value.nextCursorUserId(), value.commandId())));
        source.lifecycleSettlements().forEach((symbol, value) ->
                runtime.treasury().setLifecycleSettlement(identities.symbolId(symbol), value));
        source.lifecycleProgress().forEach((symbol, value) -> runtime.treasury().setLifecycleProgress(
                identities.symbolId(symbol), new TreasuryRuntime.LifecycleProgressRuntime(value.settlementId(),
                        value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                        value.ordersComplete(), value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId())));
    }

    private static String positionKey(CoreRiskSnapshot risk) {
        return risk.positionSide().hedgeSide() ? risk.symbol() + ':' + risk.positionSide().name() : risk.symbol();
    }

    private static <K, V> Set<K> changedKeys(Map<K, V> before, Map<K, V> after) {
        if (before == after) return Set.of();
        if (StateMapSupport.isDelta(after)) return StateMapSupport.changedKeys(after);
        Set<K> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return keys;
    }

    private static <K, V> void syncMap(Map<K, V> before, Map<K, V> after, Map<K, V> target) {
        for (K key : changedKeys(before, after)) {
            V value = after.get(key);
            if (value == null) target.remove(key); else target.put(key, value);
        }
    }
}
