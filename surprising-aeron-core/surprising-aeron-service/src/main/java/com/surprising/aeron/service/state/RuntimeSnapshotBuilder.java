package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

final class RuntimeSnapshotBuilder {

    private RuntimeSnapshotBuilder() {
    }

    static TradingRuntimeSnapshot capture(TradingRuntimeState state, long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("snapshot revision must not be negative");
        }
        Map<Long, TradingRuntimeSnapshot.UserSnapshot> users = new TreeMap<>();
        state.usersForSnapshot().forEachKeyValue((userId, user) ->
                users.put(userId, new TradingRuntimeSnapshot.UserSnapshot(user.productLine(), user.userId(),
                        user.revision(), user.positionMode())));

        Map<TradingRuntimeSnapshot.BalanceKey, TradingRuntimeSnapshot.BalanceSnapshot> balances = new TreeMap<>();
        state.balancesForSnapshot().forEachKeyValue((userId, userBalances) ->
                userBalances.forEachKeyValue((assetId, balance) -> balances.put(
                        new TradingRuntimeSnapshot.BalanceKey(userId, assetId),
                        new TradingRuntimeSnapshot.BalanceSnapshot(balance.availableUnits(), balance.lockedUnits()))));

        Map<Long, TradingRuntimeSnapshot.OrderSnapshot> orders = new TreeMap<>();
        state.ordersForSnapshot().forEachKeyValue((orderId, order) -> orders.put(orderId,
                new TradingRuntimeSnapshot.OrderSnapshot(order.productLine(), order.userId(), order.symbolId(),
                        order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                        order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision())));

        Map<Long, TradingRuntimeSnapshot.ReservationSnapshot> reservations = new TreeMap<>();
        state.reservationsForSnapshot().forEachKeyValue((orderId, reservation) -> reservations.put(orderId,
                new TradingRuntimeSnapshot.ReservationSnapshot(reservation.userId(), reservation.symbolId(),
                        reservation.instrumentVersion(), reservation.kind(), reservation.assetId(),
                        reservation.totalReservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                        reservation.orderQuantitySteps())));

        Map<TradingRuntimeSnapshot.ClientOrderKey, Long> clientOrderIndex = new TreeMap<>();
        state.clientOrderIndexForSnapshot().forEachKeyValue((userId, userClientOrders) ->
                userClientOrders.forEachKeyValue((clientKey, orderId) ->
                        clientOrderIndex.put(new TradingRuntimeSnapshot.ClientOrderKey(userId, clientKey), orderId)));

        Map<TradingRuntimeSnapshot.PositionKey, TradingRuntimeSnapshot.PositionSnapshot> positions = new TreeMap<>();
        state.positionsForSnapshot().forEachKeyValue((positionKey, position) -> positions.put(
                new TradingRuntimeSnapshot.PositionKey(position.userId(), positionKey),
                new TradingRuntimeSnapshot.PositionSnapshot(position.userId(), position.symbolId(), position.assetId(),
                        position.marginMode(), position.positionSide(), position.instrumentVersion(),
                        position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                        position.realizedPnlUnits(), position.positionMarginUnits())));

        Map<Long, TradingRuntimeSnapshot.LiquidationSnapshot> liquidations = new TreeMap<>();
        state.liquidationsForSnapshot().forEachKeyValue((liquidationId, liquidation) -> liquidations.put(
                liquidationId, new TradingRuntimeSnapshot.LiquidationSnapshot(liquidation.userId(),
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(),
                        liquidation.instrumentVersion(), liquidation.triggerPriceSequence(),
                        liquidation.signedQuantitySteps(), liquidation.closeQuantitySteps(),
                        liquidation.deficitUnits(), liquidation.executionPriceTicks(),
                        liquidation.liquidationFeeRatePpm(), liquidation.liquidationFeeUnits(),
                        liquidation.status(), liquidation.nextCancelOrderId())));

        Map<Integer, TradingRuntimeSnapshot.MarkPriceSnapshot> markPrices = new TreeMap<>();
        state.markPricesForSnapshot().forEachKeyValue((symbolId, mark) -> markPrices.put(symbolId,
                new TradingRuntimeSnapshot.MarkPriceSnapshot(mark.instrumentVersion(), mark.markPriceTicks(),
                        mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                        mark.generatedAtEpochMillis())));
        Map<TradingRuntimeSnapshot.PositionKey, TradingRuntimeSnapshot.RiskSnapshot> riskSnapshots = new TreeMap<>();
        state.riskSnapshotsForSnapshot().forEachKeyValue((positionKey, risk) -> riskSnapshots.put(
                new TradingRuntimeSnapshot.PositionKey(risk.userId(), positionKey),
                new TradingRuntimeSnapshot.RiskSnapshot(risk.userId(), risk.symbolId(), risk.positionSide(),
                        risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                        risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status())));
        Map<Integer, TradingRuntimeSnapshot.RiskScanSnapshot> riskScans = new TreeMap<>();
        state.riskScansForSnapshot().forEachKeyValue((symbolId, scan) -> riskScans.put(symbolId,
                new TradingRuntimeSnapshot.RiskScanSnapshot(scan.accountLaneId(), scan.priceSequence(),
                        scan.scanStartPriceSequence(),
                        scan.lastUserId(), scan.riskComplete(), scan.riskUserId(), scan.riskPhase(),
                        scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                        scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(),
                        scan.riskIsolatedReservationUnits(), scan.triggerComplete(), scan.triggerPhase(),
                        scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                        scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                        scan.triggerOcoCursor())));

        Map<Integer, TradingRuntimeSnapshot.TreasurySnapshot> treasury = new TreeMap<>();
        TreeSet<Integer> treasuryAssets = new TreeSet<>();
        state.treasury().feeBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().insuranceBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().insuranceDeficits().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().liquidationFeeBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().fundingResidualBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().roundingResidualBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        state.treasury().clearingPnlBalances().forEachKeyValue((assetId, units) -> treasuryAssets.add(assetId));
        treasuryAssets.forEach(assetId -> treasury.put(assetId, new TradingRuntimeSnapshot.TreasurySnapshot(
                state.treasury().fee(assetId), state.treasury().insurance(assetId),
                state.treasury().insuranceDeficit(assetId), state.treasury().liquidationFee(assetId),
                state.treasury().fundingResidual(assetId), state.treasury().roundingResidual(assetId),
                state.treasury().clearingPnl(assetId))));
        Map<Integer, Long> fundingSettlements = new TreeMap<>();
        state.treasury().fundingSettlements().forEachKeyValue(fundingSettlements::put);
        Map<Integer, TradingRuntimeSnapshot.FundingProgressSnapshot> fundingProgress = new TreeMap<>();
        state.treasury().fundingProgresses().forEachKeyValue((symbolId, progress) -> fundingProgress.put(symbolId,
                new TradingRuntimeSnapshot.FundingProgressSnapshot(progress.settlementId(),
                        progress.instrumentVersion(), progress.fundingRatePpm(), progress.accountLaneId(),
                        progress.nextCursorUserId(),
                        progress.commandId(), progress.markPriceTicks(), progress.priceSequence())));
        Map<Integer, Long> lifecycleSettlements = new TreeMap<>();
        state.treasury().lifecycleSettlements().forEachKeyValue(lifecycleSettlements::put);
        Map<Integer, TradingRuntimeSnapshot.LifecycleProgressSnapshot> lifecycleProgress = new TreeMap<>();
        state.treasury().lifecycleProgresses().forEachKeyValue((symbolId, progress) -> lifecycleProgress.put(symbolId,
                new TradingRuntimeSnapshot.LifecycleProgressSnapshot(progress.settlementId(),
                        progress.instrumentVersion(), progress.settlementPriceTicks(),
                        progress.optionCashUnitsPerContract(), progress.ordersComplete(),
                        progress.accountLaneId(), progress.nextCursorOrderId(),
                        progress.nextCursorUserId(), progress.commandId())));
        return new TradingRuntimeSnapshot(revision, users, balances, orders, reservations, clientOrderIndex,
                positions, liquidations, markPrices, riskSnapshots, riskScans, state.nextLiquidationId(),
                new TreeMap<>(state.instrumentsForRuntime()), new TreeMap<>(state.leveragesForRuntime()),
                new TreeMap<>(state.algoOrdersForRuntime()), new TreeMap<>(state.cancelAllAfterTimersForRuntime()),
                new TreeMap<>(state.triggerOrdersForRuntime()), treasury, fundingSettlements, fundingProgress,
                lifecycleSettlements, lifecycleProgress);
    }
}
