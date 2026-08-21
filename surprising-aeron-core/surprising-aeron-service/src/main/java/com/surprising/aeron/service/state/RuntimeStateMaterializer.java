package com.surprising.aeron.service.state;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Builds the immutable compatibility/read model from the authoritative mutable runtime. */
public final class RuntimeStateMaterializer {

    private RuntimeStateMaterializer() {
    }

    public static TradingCoreState materialize(TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return materialize(runtime, identities, null);
    }

    static TradingCoreState materialize(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        SnapshotTraversalProbe traversalProbe) {
        if (runtime == null || identities == null) throw new IllegalArgumentException("runtime and identities required");
        runtime.assertOwner();

        Map<Long, Map<Long, OrderReservation>> reservationsByUser = new HashMap<>();
        if (traversalProbe != null) traversalProbe.reservationTraversalStarted();
        runtime.reservationsForSnapshot().forEachKeyValue((orderId, reservation) -> {
            if (traversalProbe != null) traversalProbe.reservationEntryVisited();
            if (!runtime.usersForSnapshot().containsKey(reservation.userId())) {
                throw new IllegalStateException("reservation owner is not registered: " + reservation.userId());
            }
            reservationsByUser.computeIfAbsent(reservation.userId(), ignored -> new TreeMap<>())
                    .put(orderId, new OrderReservation(orderId, identities.symbol(reservation.symbolId()),
                            reservation.instrumentVersion(), reservation.kind(), identities.asset(reservation.assetId()),
                            reservation.totalReservedUnits(), reservation.releasedUnits(), reservation.consumedUnits(),
                            reservation.orderQuantitySteps()));
        });
        Map<Long, Map<String, CorePositionState>> positionsByUser = new HashMap<>();
        if (traversalProbe != null) traversalProbe.positionTraversalStarted();
        runtime.positionsForSnapshot().forEachKeyValue((positionKey, position) -> {
            if (traversalProbe != null) traversalProbe.positionEntryVisited();
            if (!runtime.usersForSnapshot().containsKey(position.userId())) {
                throw new IllegalStateException("position owner is not registered: " + position.userId());
            }
            String key = identities.positionKey(position.userId(), positionKey);
            positionsByUser.computeIfAbsent(position.userId(), ignored -> new TreeMap<>())
                    .put(key, new CorePositionState(identities.symbol(position.symbolId()),
                            identities.asset(position.assetId()), position.marginMode(), position.positionSide(),
                            position.instrumentVersion(), position.signedQuantitySteps(), position.entryPriceTicks(),
                            position.entryValueTicks(), position.realizedPnlUnits(), position.positionMarginUnits()));
        });

        Map<Long, CoreUserState> users = new TreeMap<>();
        runtime.usersForSnapshot().forEachKeyValue((userId, user) -> {
            Map<String, AssetBalance> balances = new TreeMap<>();
            var runtimeBalances = runtime.balancesForSnapshot().get(userId);
            if (runtimeBalances != null) runtimeBalances.forEachKeyValue((assetId, balance) -> {
                String asset = identities.asset(assetId);
                balances.put(asset, new AssetBalance(asset, balance.availableUnits(), balance.lockedUnits()));
            });
            Map<Long, OrderReservation> reservations = new TreeMap<>(
                    reservationsByUser.getOrDefault(userId, Map.of()));
            Map<String, CorePositionState> positions = new TreeMap<>(
                    positionsByUser.getOrDefault(userId, Map.of()));
            users.put(userId, new CoreUserState(user.productLine(), userId, user.revision(), balances,
                    reservations, positions, user.positionMode()));
        });

        Map<Long, CoreOrderState> orders = new TreeMap<>();
        runtime.ordersForSnapshot().forEachKeyValue((orderId, order) -> orders.put(orderId,
                new CoreOrderState(orderId, order.productLine(), order.userId(), identities.symbol(order.symbolId()),
                        order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                        order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.createdAtEpochMillis(), order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision())));

        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        runtime.markPricesForSnapshot().forEachKeyValue((symbolId, mark) -> {
            String symbol = identities.symbol(symbolId);
            marks.put(symbol, new CoreMarkPriceState(symbol, mark.instrumentVersion(), mark.markPriceTicks(),
                    mark.priceSequence()));
        });
        Map<String, CoreRiskSnapshot> riskSnapshots = new TreeMap<>();
        runtime.riskSnapshotsForSnapshot().forEachKeyValue((positionKey, risk) -> {
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(risk.userId(), identities.symbol(risk.symbolId()),
                    risk.positionSide(), risk.priceSequence(), risk.equityUnits(), risk.unrealizedPnlUnits(),
                    risk.maintenanceMarginUnits(), risk.marginRatioPpm(), risk.status());
            riskSnapshots.put(snapshot.key(), snapshot);
        });
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>();
        runtime.liquidationsForSnapshot().forEachKeyValue((id, value) -> liquidations.put(id,
                new CoreLiquidationState(id, value.userId(), identities.symbol(value.symbolId()), value.marginMode(),
                        value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                        value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                        value.executionPriceTicks(), value.liquidationFeeRatePpm(), value.liquidationFeeUnits(),
                        value.status(), value.nextCancelOrderId())));
        Map<String, CoreRiskState.RiskScan> scans = new TreeMap<>();
        runtime.riskScansForSnapshot().forEachKeyValue((symbolId, scan) -> {
            String symbol = identities.symbol(symbolId);
            scans.put(symbol, new CoreRiskState.RiskScan(symbol, scan.priceSequence(),
                    scan.scanStartPriceSequence(), scan.lastUserId(), scan.riskComplete(), scan.riskUserId(),
                    scan.riskPhase(), scan.riskPositionCursor(), scan.riskReservationCursor(),
                    scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                    scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                    scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                    scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                    scan.triggerOcoCursor()));
        });
        CoreRiskState risk = new CoreRiskState(marks, riskSnapshots, liquidations, scans,
                runtime.nextLiquidationId(), runtime.riskScanControl());

        Map<String, Long> fees = new TreeMap<>();
        runtime.treasury().feeBalances().forEachKeyValue((id, units) -> fees.put(identities.asset(id), units));
        Map<String, Long> insurance = new TreeMap<>();
        runtime.treasury().insuranceBalances().forEachKeyValue((id, units) -> insurance.put(identities.asset(id), units));
        Map<String, Long> deficits = new TreeMap<>();
        runtime.treasury().insuranceDeficits().forEachKeyValue((id, units) -> deficits.put(identities.asset(id), units));
        Map<String, Long> funding = new TreeMap<>();
        runtime.treasury().fundingSettlements().forEachKeyValue((id, value) -> funding.put(identities.symbol(id), value));
        Map<String, Long> lifecycle = new TreeMap<>();
        runtime.treasury().lifecycleSettlements().forEachKeyValue((id, value) -> lifecycle.put(identities.symbol(id), value));
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = new TreeMap<>();
        runtime.treasury().fundingProgresses().forEachKeyValue((id, value) -> fundingProgress.put(
                identities.symbol(id), new CoreTreasuryState.FundingProgress(value.settlementId(),
                        value.instrumentVersion(), value.fundingRatePpm(), value.nextCursorUserId(), value.commandId())));
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        runtime.treasury().lifecycleProgresses().forEachKeyValue((id, value) -> lifecycleProgress.put(
                identities.symbol(id), new CoreTreasuryState.LifecycleProgress(value.settlementId(),
                        value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                        value.ordersComplete(), value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId())));
        CoreTreasuryState treasury = new CoreTreasuryState(fees, insurance, deficits, funding, lifecycle,
                fundingProgress, lifecycleProgress);

        Map<TradingCoreState.ClientOrderKey, Long> clientIndex = new TreeMap<>();
        runtime.clientOrderIndexForSnapshot().forEachKeyValue((userId, entries) ->
                entries.forEachKeyValue((clientKey, orderId) -> clientIndex.put(
                        new TradingCoreState.ClientOrderKey(userId, identities.clientOrderId(userId, clientKey)), orderId)));
        return new TradingCoreState(runtime.productLine(), runtime.revision(), users, orders,
                new TreeMap<>(runtime.instrumentsForRuntime()), risk, treasury,
                new TreeMap<>(runtime.leveragesForRuntime()), new TreeMap<>(runtime.algoOrdersForRuntime()),
                new TreeMap<>(runtime.cancelAllAfterTimersForRuntime()), clientIndex,
                new TreeMap<>(runtime.triggerOrdersForRuntime()));
    }

    static final class SnapshotTraversalProbe {
        private int reservationTraversals;
        private int reservationEntries;
        private int positionTraversals;
        private int positionEntries;

        void reservationTraversalStarted() {
            reservationTraversals++;
        }

        void reservationEntryVisited() {
            reservationEntries++;
        }

        void positionTraversalStarted() {
            positionTraversals++;
        }

        void positionEntryVisited() {
            positionEntries++;
        }

        int reservationTraversals() {
            return reservationTraversals;
        }

        int reservationEntries() {
            return reservationEntries;
        }

        int positionTraversals() {
            return positionTraversals;
        }

        int positionEntries() {
            return positionEntries;
        }
    }
}
