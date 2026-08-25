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
                        order.instrumentVersion(), order.side(), order.priceTicks(), order.matchingPriceTicks(),
                        order.quantitySteps(),
                        order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
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
        Map<String, Long> liquidationFees = new TreeMap<>();
        runtime.treasury().liquidationFeeBalances().forEachKeyValue((id, units) ->
                liquidationFees.put(identities.asset(id), units));
        Map<String, Long> fundingResiduals = new TreeMap<>();
        runtime.treasury().fundingResidualBalances().forEachKeyValue((id, units) ->
                fundingResiduals.put(identities.asset(id), units));
        Map<String, Long> roundingResiduals = new TreeMap<>();
        runtime.treasury().roundingResidualBalances().forEachKeyValue((id, units) ->
                roundingResiduals.put(identities.asset(id), units));
        Map<String, Long> clearingPnl = new TreeMap<>();
        runtime.treasury().clearingPnlBalances().forEachKeyValue((id, units) ->
                clearingPnl.put(identities.asset(id), units));
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
        CoreTreasuryState treasury = new CoreTreasuryState(fees, insurance, deficits, liquidationFees,
                fundingResiduals, roundingResiduals, clearingPnl, funding, lifecycle,
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

    public static TradingCoreState materializeTransition(TradingRuntimeState runtime,
                                                         RuntimeIdentityRegistry identities,
                                                         TradingCoreState previous) {
        return materializeTransition(runtime, identities, previous, null);
    }

    static TradingCoreState materializeTransition(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                   TradingCoreState previous,
                                                   SnapshotTraversalProbe traversalProbe) {
        if (runtime == null || identities == null || previous == null) {
            throw new IllegalArgumentException("runtime, identities and previous state required");
        }
        runtime.assertOwner();
        if (runtime.productLine() != previous.productLine() || runtime.revision() < previous.revision()) {
            throw new IllegalStateException("runtime transition metadata is out of order");
        }

        Map<Long, CoreUserState> users = StateMapSupport.delta(previous.users());
        long[] changedReservations = runtime.changedReservations().toArray();
        long[] changedPositions = runtime.changedPositions().toArray();
        for (long userId : runtime.changedUsers().toArray()) {
            UserRuntime runtimeUser = runtime.user(userId);
            CoreUserState beforeUser = previous.user(userId);
            if (runtimeUser == null) {
                users.remove(userId);
                continue;
            }
            Map<String, AssetBalance> balances = StateMapSupport.delta(
                    beforeUser == null ? Map.of() : beforeUser.balances());
            for (int assetId : runtime.changedBalances(userId).toArray()) {
                String asset = identities.asset(assetId);
                BalanceRuntime balance = runtime.balance(userId, assetId);
                if (balance == null) balances.remove(asset);
                else balances.put(asset, new AssetBalance(asset, balance.availableUnits(), balance.lockedUnits()));
            }
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(
                    beforeUser == null ? Map.of() : beforeUser.reservations());
            for (long orderId : changedReservations) {
                ReservationRuntime reservation = runtime.reservation(orderId);
                OrderReservation prior = beforeUser == null ? null : beforeUser.reservations().get(orderId);
                if (reservation != null && reservation.userId() == userId) {
                    reservations.put(orderId, reservation(reservation, identities));
                } else if (prior != null) {
                    reservations.remove(orderId);
                }
            }
            Map<String, CorePositionState> positions = StateMapSupport.delta(
                    beforeUser == null ? Map.of() : beforeUser.positions());
            for (long positionKey : changedPositions) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
                if (identity.userId() != userId) continue;
                PositionRuntime position = runtime.position(positionKey);
                if (position == null) positions.remove(identity.positionKey());
                else positions.put(identity.positionKey(), position(positionKey, position, identities));
            }
            CoreUserState user = beforeUser == null
                    ? new CoreUserState(runtimeUser.productLine(), userId, runtimeUser.revision(), balances,
                    reservations, positions, runtimeUser.positionMode())
                    : beforeUser.transition(runtimeUser.revision(), balances, reservations, positions,
                    runtimeUser.positionMode());
            users.put(userId, user);
        }

        Map<Long, CoreOrderState> orders = StateMapSupport.delta(previous.orders());
        for (long orderId : runtime.changedOrders().toArray()) {
            OrderRuntime order = runtime.order(orderId);
            if (order == null) orders.remove(orderId);
            else orders.put(orderId, order(order, identities));
        }

        Map<String, CoreMarkPriceState> marks = StateMapSupport.delta(previous.riskState().markPrices());
        for (int symbolId : runtime.changedMarkPrices().toArray()) {
            String symbol = identities.symbol(symbolId);
            MarkPriceRuntime mark = runtime.markPrice(symbolId);
            if (mark == null) marks.remove(symbol);
            else marks.put(symbol, new CoreMarkPriceState(symbol, mark.instrumentVersion(),
                    mark.markPriceTicks(), mark.priceSequence()));
        }
        Map<String, CoreRiskSnapshot> snapshots = StateMapSupport.delta(previous.riskState().snapshots());
        for (long positionKey : runtime.changedRiskSnapshots().toArray()) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
            String snapshotKey = identity.userId() + ":" + identity.positionKey();
            RiskSnapshotRuntime risk = runtime.riskSnapshot(positionKey);
            if (risk == null) {
                snapshots.remove(snapshotKey);
            } else {
                CoreRiskSnapshot snapshot = riskSnapshot(risk, identities);
                if (!snapshot.key().equals(snapshotKey)) {
                    throw new IllegalStateException("runtime risk snapshot identity mismatch: " + positionKey);
                }
                snapshots.put(snapshot.key(), snapshot);
            }
        }
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(
                previous.riskState().liquidations());
        for (long liquidationId : runtime.changedLiquidations().toArray()) {
            LiquidationRuntime liquidation = runtime.liquidation(liquidationId);
            if (liquidation == null) liquidations.remove(liquidationId);
            else liquidations.put(liquidationId, liquidation(liquidation, identities));
        }
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(previous.riskState().scans());
        for (int symbolId : runtime.changedRiskScans().toArray()) {
            String symbol = identities.symbol(symbolId);
            RiskScanRuntime scan = runtime.riskScan(symbolId);
            if (scan == null) scans.remove(symbol);
            else scans.put(symbol, riskScan(scan, identities));
        }
        CoreRiskState riskState = new CoreRiskState(marks, snapshots, liquidations, scans,
                runtime.nextLiquidationId(), runtime.riskScanControl());

        CoreTreasuryState treasuryState = treasuryTransition(runtime.treasury(), identities,
                previous.treasuryState());
        Map<String, CoreInstrumentState> instruments = StateMapSupport.delta(previous.instruments());
        for (String symbol : runtime.changedInstruments()) {
            CoreInstrumentState instrument = runtime.instrumentsForRuntime().get(symbol);
            if (instrument == null) instruments.remove(symbol); else instruments.put(symbol, instrument);
        }
        Map<CoreLeverageKey, Long> leverages = StateMapSupport.delta(previous.leverages());
        for (CoreLeverageKey key : runtime.changedLeverages()) {
            Long leverage = runtime.leveragesForRuntime().get(key);
            if (leverage == null) leverages.remove(key); else leverages.put(key, leverage);
        }
        Map<Long, CoreAlgoOrderState> algoOrders = StateMapSupport.delta(previous.algoOrders());
        for (long id : runtime.changedAlgoOrders().toArray()) {
            CoreAlgoOrderState value = runtime.algoOrdersForRuntime().get(id);
            if (value == null) algoOrders.remove(id); else algoOrders.put(id, value);
        }
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = StateMapSupport.delta(
                previous.cancelAllAfterTimers());
        for (CoreCancelAllAfterKey key : runtime.changedCancelAllAfterTimers()) {
            CoreCancelAllAfterState value = runtime.cancelAllAfterTimersForRuntime().get(key);
            if (value == null) timers.remove(key); else timers.put(key, value);
        }
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(previous.triggerOrders());
        for (long id : runtime.changedTriggerOrders().toArray()) {
            CoreTriggerOrderState value = runtime.triggerOrdersForRuntime().get(id);
            if (value == null) triggers.remove(id); else triggers.put(id, value);
        }
        Map<TradingCoreState.ClientOrderKey, Long> clients = StateMapSupport.delta(previous.clientOrderIndex());
        runtime.changedClientOrdersByUser().forEachKeyValue((userId, keys) -> {
            for (long clientKey : keys.toArray()) {
                TradingCoreState.ClientOrderKey key = new TradingCoreState.ClientOrderKey(
                        userId, identities.clientOrderId(userId, clientKey));
                Long orderId = runtime.orderIdByClient(userId, clientKey);
                if (orderId == null) clients.remove(key); else clients.put(key, orderId);
            }
        });
        return new TradingCoreState(runtime.productLine(), runtime.revision(), users, orders, instruments,
                riskState, treasuryState, leverages, algoOrders, timers, clients, triggers);
    }

    private static OrderReservation reservation(ReservationRuntime value, RuntimeIdentityRegistry identities) {
        return new OrderReservation(value.orderId(), identities.symbol(value.symbolId()), value.instrumentVersion(),
                value.kind(), identities.asset(value.assetId()), value.totalReservedUnits(), value.releasedUnits(),
                value.consumedUnits(), value.orderQuantitySteps());
    }

    private static CorePositionState position(long positionKey, PositionRuntime value,
                                              RuntimeIdentityRegistry identities) {
        String identity = identities.positionKey(value.userId(), positionKey);
        CorePositionState result = new CorePositionState(identities.symbol(value.symbolId()),
                identities.asset(value.assetId()), value.marginMode(), value.positionSide(),
                value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits());
        if (!result.key().equals(identity)) {
            throw new IllegalStateException("runtime position identity mismatch: " + positionKey);
        }
        return result;
    }

    private static CoreOrderState order(OrderRuntime value, RuntimeIdentityRegistry identities) {
        return new CoreOrderState(value.orderId(), value.productLine(), value.userId(),
                identities.symbol(value.symbolId()), value.instrumentVersion(), value.side(), value.priceTicks(),
                value.matchingPriceTicks(), value.quantitySteps(), value.executedQuantitySteps(),
                value.remainingQuantitySteps(), value.reduceOnly(), value.marginMode(), value.positionSide(),
                value.orderType(), value.timeInForce(), value.postOnly(), value.clientOrderId(), value.commandId(),
                value.makerFeeRatePpm(), value.takerFeeRatePpm(), value.cumulativeFeeUnits(), value.createdAtEpochMillis(),
                value.updatedAtEpochMillis(), value.clusterPosition(), value.status(), value.revision());
    }

    private static CoreRiskSnapshot riskSnapshot(RiskSnapshotRuntime value,
                                                 RuntimeIdentityRegistry identities) {
        return new CoreRiskSnapshot(value.userId(), identities.symbol(value.symbolId()), value.positionSide(),
                value.priceSequence(), value.equityUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.marginRatioPpm(), value.status());
    }

    private static CoreLiquidationState liquidation(LiquidationRuntime value,
                                                    RuntimeIdentityRegistry identities) {
        return new CoreLiquidationState(value.liquidationId(), value.userId(),
                identities.symbol(value.symbolId()), value.marginMode(), value.positionSide(),
                value.instrumentVersion(), value.triggerPriceSequence(), value.signedQuantitySteps(),
                value.closeQuantitySteps(), value.deficitUnits(), value.executionPriceTicks(),
                value.liquidationFeeRatePpm(), value.liquidationFeeUnits(), value.status(),
                value.nextCancelOrderId());
    }

    private static CoreRiskState.RiskScan riskScan(RiskScanRuntime value,
                                                   RuntimeIdentityRegistry identities) {
        return new CoreRiskState.RiskScan(identities.symbol(value.symbolId()), value.priceSequence(),
                value.scanStartPriceSequence(), value.lastUserId(), value.riskComplete(), value.riskUserId(),
                value.riskPhase(), value.riskPositionCursor(), value.riskReservationCursor(),
                value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(),
                value.riskIsolatedMarginUnits(), value.riskIsolatedReservationUnits(), value.triggerComplete(),
                value.triggerPhase(), value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private static CoreTreasuryState treasuryTransition(TreasuryRuntime runtime,
                                                        RuntimeIdentityRegistry identities,
                                                        CoreTreasuryState previous) {
        Map<String, Long> fees = StateMapSupport.delta(previous.feeBalances());
        Map<String, Long> insurance = StateMapSupport.delta(previous.insuranceBalances());
        Map<String, Long> deficits = StateMapSupport.delta(previous.insuranceDeficits());
        Map<String, Long> liquidationFees = StateMapSupport.delta(previous.liquidationFeeBalances());
        Map<String, Long> fundingResiduals = StateMapSupport.delta(previous.fundingResidualBalances());
        Map<String, Long> roundingResiduals = StateMapSupport.delta(previous.roundingResidualBalances());
        Map<String, Long> clearingPnl = StateMapSupport.delta(previous.clearingPnlBalances());
        for (int assetId : runtime.changedAssets().toArray()) {
            String asset = identities.asset(assetId);
            putOrRemove(fees, asset, runtime.fee(assetId));
            putOrRemove(insurance, asset, runtime.insurance(assetId));
            putOrRemove(deficits, asset, runtime.insuranceDeficit(assetId));
            putOrRemove(liquidationFees, asset, runtime.liquidationFee(assetId));
            putOrRemove(fundingResiduals, asset, runtime.fundingResidual(assetId));
            putOrRemove(roundingResiduals, asset, runtime.roundingResidual(assetId));
            putOrRemove(clearingPnl, asset, runtime.clearingPnl(assetId));
        }
        Map<String, Long> fundingSettlements = StateMapSupport.delta(previous.fundingSettlements());
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = StateMapSupport.delta(
                previous.fundingProgress());
        for (int symbolId : runtime.changedFundingSymbols().toArray()) {
            String symbol = identities.symbol(symbolId);
            putOrRemove(fundingSettlements, symbol, runtime.fundingSettlement(symbolId));
            TreasuryRuntime.FundingProgressRuntime value = runtime.fundingProgress(symbolId);
            if (value == null) fundingProgress.remove(symbol);
            else fundingProgress.put(symbol, new CoreTreasuryState.FundingProgress(value.settlementId(),
                    value.instrumentVersion(), value.fundingRatePpm(), value.nextCursorUserId(), value.commandId()));
        }
        Map<String, Long> lifecycleSettlements = StateMapSupport.delta(previous.lifecycleSettlements());
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = StateMapSupport.delta(
                previous.lifecycleProgress());
        for (int symbolId : runtime.changedLifecycleSymbols().toArray()) {
            String symbol = identities.symbol(symbolId);
            putOrRemove(lifecycleSettlements, symbol, runtime.lifecycleSettlement(symbolId));
            TreasuryRuntime.LifecycleProgressRuntime value = runtime.lifecycleProgress(symbolId);
            if (value == null) lifecycleProgress.remove(symbol);
            else lifecycleProgress.put(symbol, new CoreTreasuryState.LifecycleProgress(value.settlementId(),
                    value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                    value.ordersComplete(), value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId()));
        }
        return new CoreTreasuryState(fees, insurance, deficits, liquidationFees, fundingResiduals,
                roundingResiduals, clearingPnl, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    private static void putOrRemove(Map<String, Long> values, String key, long value) {
        if (value == 0) values.remove(key); else values.put(key, value);
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
