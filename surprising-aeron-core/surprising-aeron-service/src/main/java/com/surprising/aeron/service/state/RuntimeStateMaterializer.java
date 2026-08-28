package com.surprising.aeron.service.state;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

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
        var runtimeUsers = runtime.usersForSnapshot();
        var runtimeBalances = runtime.balancesForSnapshot();

        Map<Long, Map<Long, OrderReservation>> reservationsByUser = new HashMap<>();
        if (traversalProbe != null) traversalProbe.reservationTraversalStarted();
        runtime.reservationsForSnapshot().forEachKeyValue((orderId, reservation) -> {
            if (traversalProbe != null) traversalProbe.reservationEntryVisited();
            if (runtime.pendingReservation(orderId, reservation.userId())) return;
            if (!runtimeUsers.containsKey(reservation.userId())) {
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
            if (!runtimeUsers.containsKey(position.userId())) {
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
        runtimeUsers.forEachKeyValue((userId, user) -> {
            Map<String, AssetBalance> balances = new TreeMap<>();
            var userBalances = runtimeBalances.get(userId);
            if (userBalances != null) userBalances.forEachKeyValue((assetId, balance) -> {
                String asset = identities.asset(assetId);
                long pending = runtime.pendingReservedUnits(userId, assetId);
                balances.put(asset, new AssetBalance(asset, Math.addExact(balance.availableUnits(), pending),
                        Math.subtractExact(balance.lockedUnits(), pending)));
            });
            Map<Long, OrderReservation> reservations = new TreeMap<>(
                    reservationsByUser.getOrDefault(userId, Map.of()));
            Map<String, CorePositionState> positions = new TreeMap<>(
                    positionsByUser.getOrDefault(userId, Map.of()));
            users.put(userId, new CoreUserState(user.productLine(), userId,
                    Math.subtractExact(user.revision(), runtime.pendingReservationCount(userId)), balances,
                    reservations, positions, user.positionMode()));
        });

        Map<Long, CoreOrderState> orders = new TreeMap<>();
        runtime.ordersForSnapshot().forEachKeyValue((orderId, order) -> {
            if (runtime.pendingReservation(orderId, order.userId())) return;
            orders.put(orderId,
                new CoreOrderState(orderId, order.productLine(), order.userId(), identities.symbol(order.symbolId()),
                        order.instrumentVersion(), order.side(), order.priceTicks(), order.matchingPriceTicks(),
                        order.quantitySteps(),
                        order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                        order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                        order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                        order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                        order.updatedAtEpochMillis(),
                        order.clusterPosition(), order.status(), order.revision()));
        });

        Map<String, CoreMarkPriceState> marks = new TreeMap<>();
        runtime.markPricesForSnapshot().forEachKeyValue((symbolId, mark) -> {
            String symbol = identities.symbol(symbolId);
            marks.put(symbol, new CoreMarkPriceState(symbol, mark.instrumentVersion(), mark.markPriceTicks(),
                    mark.priceSequence(), mark.generatedAtEpochMillis()));
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
            scans.put(symbol, new CoreRiskState.RiskScan(symbol, scan.accountLaneId(), scan.priceSequence(),
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
                        value.instrumentVersion(), value.fundingRatePpm(), value.accountLaneId(),
                        value.nextCursorUserId(), value.commandId())));
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = new TreeMap<>();
        runtime.treasury().lifecycleProgresses().forEachKeyValue((id, value) -> lifecycleProgress.put(
                identities.symbol(id), new CoreTreasuryState.LifecycleProgress(value.settlementId(),
                        value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                        value.ordersComplete(), value.accountLaneId(), value.nextCursorOrderId(),
                        value.nextCursorUserId(), value.commandId())));
        CoreTreasuryState treasury = new CoreTreasuryState(fees, insurance, deficits, liquidationFees,
                fundingResiduals, roundingResiduals, clearingPnl, funding, lifecycle,
                fundingProgress, lifecycleProgress);

        Map<TradingCoreState.ClientOrderKey, Long> clientIndex = new TreeMap<>();
        runtime.clientOrderIndexForSnapshot().forEachKeyValue((userId, entries) ->
                entries.forEachKeyValue((clientKey, orderId) -> {
                    if (!runtime.pendingReservation(orderId, userId)) {
                        clientIndex.put(new TradingCoreState.ClientOrderKey(
                                userId, identities.clientOrderId(userId, clientKey)), orderId);
                    }
                }));
        return new TradingCoreState(runtime.productLine(),
                Math.subtractExact(runtime.revision(), runtime.pendingReservationCount()), users, orders,
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
        return materializeTransition(runtime.captureMutationDelta(), identities, previous, traversalProbe);
    }

    public static TradingCoreState materializeTransition(RuntimeMutationDelta delta,
                                                         RuntimeIdentityRegistry identities,
                                                         TradingCoreState previous) {
        return materializeTransition(delta, identities, previous, null);
    }

    private static TradingCoreState materializeTransition(RuntimeMutationDelta delta,
                                                          RuntimeIdentityRegistry identities,
                                                          TradingCoreState previous,
                                                          SnapshotTraversalProbe traversalProbe) {
        if (delta == null || identities == null || previous == null
                || delta.productLine() != previous.productLine() || delta.revision() < previous.revision()) {
            throw new IllegalArgumentException("runtime mutation delta is out of order");
        }

        Map<Long, CoreUserState> users = StateMapSupport.delta(previous.users());
        for (Long userId : delta.users().changedKeys()) {
            RuntimeMutationDelta.UserValue runtimeUser = delta.users().currentValues().get(userId);
            CoreUserState beforeUser = previous.user(userId);
            if (runtimeUser == null) {
                users.remove(userId);
                continue;
            }
            users.put(userId, materializeUser(userId, delta, identities, beforeUser));
        }

        Map<Long, CoreOrderState> orders = StateMapSupport.delta(previous.orders());
        for (Long orderId : delta.orders().changedKeys()) {
            OrderRuntime order = delta.orders().currentValues().get(orderId);
            if (order == null || delta.pendingReservations().contains(orderId)) orders.remove(orderId);
            else orders.put(orderId, orderSnapshot(order, identities));
        }
        Map<String, CoreMarkPriceState> marks = StateMapSupport.delta(previous.riskState().markPrices());
        for (Integer symbolId : delta.markPrices().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            MarkPriceRuntime mark = delta.markPrices().currentValues().get(symbolId);
            if (mark == null) marks.remove(symbol);
            else marks.put(symbol, new CoreMarkPriceState(symbol, mark.instrumentVersion(),
                    mark.markPriceTicks(), mark.priceSequence(), mark.generatedAtEpochMillis()));
        }
        Map<String, CoreRiskSnapshot> snapshots = StateMapSupport.delta(previous.riskState().snapshots());
        for (Long positionKey : delta.riskSnapshots().changedKeys()) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
            String snapshotKey = identity.userId() + ":" + identity.positionKey();
            RiskSnapshotRuntime risk = delta.riskSnapshots().currentValues().get(positionKey);
            if (risk == null) snapshots.remove(snapshotKey);
            else {
                CoreRiskSnapshot snapshot = riskSnapshot(risk, identities);
                if (!snapshot.key().equals(snapshotKey)) {
                    throw new IllegalStateException("runtime risk snapshot identity mismatch: " + positionKey);
                }
                snapshots.put(snapshot.key(), snapshot);
            }
        }
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(previous.riskState().liquidations());
        applyValues(delta.liquidations(), liquidations, value -> liquidation(value, identities));
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(previous.riskState().scans());
        for (Integer symbolId : delta.riskScans().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            RiskScanRuntime scan = delta.riskScans().currentValues().get(symbolId);
            if (scan == null) scans.remove(symbol); else scans.put(symbol, riskScan(scan, identities));
        }
        CoreRiskState riskState = new CoreRiskState(marks, snapshots, liquidations, scans,
                delta.nextLiquidationId(), delta.riskScanControl());

        CoreTreasuryState treasuryState = treasuryTransition(delta.treasury(), identities, previous.treasuryState());
        Map<String, CoreInstrumentState> instruments = StateMapSupport.delta(previous.instruments());
        applyValues(delta.instruments(), instruments, java.util.function.Function.identity());
        Map<CoreLeverageKey, Long> leverages = StateMapSupport.delta(previous.leverages());
        applyValues(delta.leverages(), leverages, java.util.function.Function.identity());
        Map<Long, CoreAlgoOrderState> algoOrders = StateMapSupport.delta(previous.algoOrders());
        applyValues(delta.algoOrders(), algoOrders, java.util.function.Function.identity());
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = StateMapSupport.delta(
                previous.cancelAllAfterTimers());
        applyValues(delta.timers(), timers, java.util.function.Function.identity());
        Map<Long, CoreTriggerOrderState> triggers = StateMapSupport.delta(previous.triggerOrders());
        applyValues(delta.triggerOrders(), triggers, java.util.function.Function.identity());
        Map<TradingCoreState.ClientOrderKey, Long> clients = StateMapSupport.delta(previous.clientOrderIndex());
        for (RuntimeMutationDelta.RuntimeClientKey runtimeKey : delta.clientOrders().changedKeys()) {
            TradingCoreState.ClientOrderKey key = new TradingCoreState.ClientOrderKey(runtimeKey.userId(),
                    identities.clientOrderId(runtimeKey.userId(), runtimeKey.clientKey()));
            Long orderId = delta.clientOrders().currentValues().get(runtimeKey);
            if (orderId == null) clients.remove(key); else clients.put(key, orderId);
        }
        return new TradingCoreState(delta.productLine(),
                Math.subtractExact(delta.revision(), delta.pendingReservationCount()), users, orders, instruments,
                riskState, treasuryState, leverages, algoOrders, timers, clients, triggers);
    }

    private static CoreUserState materializeUser(long userId, RuntimeMutationDelta delta,
                                                 RuntimeIdentityRegistry identities, CoreUserState beforeUser) {
        RuntimeMutationDelta.UserValue runtimeUser = delta.users().currentValues().get(userId);
        if (runtimeUser == null) return null;
        Map<String, AssetBalance> balances = StateMapSupport.delta(
                beforeUser == null ? Map.of() : beforeUser.balances());
        for (Integer assetId : runtimeUser.balances().changedKeys()) {
            String asset = identities.asset(assetId);
            RuntimeMutationDelta.BalanceValue balance = runtimeUser.balances().currentValues().get(assetId);
            if (balance == null) balances.remove(asset);
            else balances.put(asset, new AssetBalance(asset,
                    Math.addExact(balance.availableUnits(), balance.pendingReservedUnits()),
                    Math.subtractExact(balance.lockedUnits(), balance.pendingReservedUnits())));
        }
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(
                beforeUser == null ? Map.of() : beforeUser.reservations());
        for (Long orderId : delta.reservations().changedKeys()) {
            ReservationRuntime value = delta.reservations().currentValues().get(orderId);
            OrderReservation prior = beforeUser == null ? null : beforeUser.reservations().get(orderId);
            if (value != null && value.userId() == userId && !delta.pendingReservations().contains(orderId)) {
                reservations.put(orderId, reservation(value, identities));
            } else if (prior != null) {
                reservations.remove(orderId);
            }
        }
        Map<String, CorePositionState> positions = StateMapSupport.delta(
                beforeUser == null ? Map.of() : beforeUser.positions());
        for (Long positionKey : delta.positions().changedKeys()) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
            if (identity.userId() != userId) continue;
            PositionRuntime value = delta.positions().currentValues().get(positionKey);
            if (value == null) positions.remove(identity.positionKey());
            else positions.put(identity.positionKey(), position(positionKey, value, identities));
        }
        UserRuntime current = runtimeUser.user();
        long revision = Math.subtractExact(current.revision(), runtimeUser.pendingReservationCount());
        return beforeUser == null
                ? new CoreUserState(current.productLine(), userId, revision, balances,
                reservations, positions, current.positionMode())
                : beforeUser.transition(revision, balances, reservations, positions, current.positionMode());
    }

    private static CoreTreasuryState.FundingProgress fundingProgress(
            TreasuryRuntime.FundingProgressRuntime value) {
        return new CoreTreasuryState.FundingProgress(value.settlementId(), value.instrumentVersion(),
                value.fundingRatePpm(), value.accountLaneId(), value.nextCursorUserId(), value.commandId());
    }

    private static CoreTreasuryState.LifecycleProgress lifecycleProgress(
            TreasuryRuntime.LifecycleProgressRuntime value) {
        return new CoreTreasuryState.LifecycleProgress(value.settlementId(), value.instrumentVersion(),
                value.settlementPriceTicks(), value.optionCashUnitsPerContract(), value.ordersComplete(),
                value.accountLaneId(), value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId());
    }

    static OrderReservation reservation(ReservationRuntime value, RuntimeIdentityRegistry identities) {
        return new OrderReservation(value.orderId(), identities.symbol(value.symbolId()), value.instrumentVersion(),
                value.kind(), identities.asset(value.assetId()), value.totalReservedUnits(), value.releasedUnits(),
                value.consumedUnits(), value.orderQuantitySteps());
    }

    static CorePositionState position(long positionKey, PositionRuntime value,
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

    public static CoreOrderState orderSnapshot(OrderRuntime value, RuntimeIdentityRegistry identities) {
        if (value == null || identities == null) throw new IllegalArgumentException("runtime order is required");
        return new CoreOrderState(value.orderId(), value.productLine(), value.userId(),
                identities.symbol(value.symbolId()), value.instrumentVersion(), value.side(), value.priceTicks(),
                value.matchingPriceTicks(), value.quantitySteps(), value.executedQuantitySteps(),
                value.remainingQuantitySteps(), value.reduceOnly(), value.marginMode(), value.positionSide(),
                value.orderType(), value.timeInForce(), value.postOnly(), value.clientOrderId(), value.commandId(),
                value.makerFeeRatePpm(), value.takerFeeRatePpm(), value.cumulativeFeeUnits(), value.createdAtEpochMillis(),
                value.updatedAtEpochMillis(), value.clusterPosition(), value.status(), value.revision());
    }

    static CoreRiskSnapshot riskSnapshot(RiskSnapshotRuntime value,
                                         RuntimeIdentityRegistry identities) {
        return new CoreRiskSnapshot(value.userId(), identities.symbol(value.symbolId()), value.positionSide(),
                value.priceSequence(), value.equityUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.marginRatioPpm(), value.status());
    }

    static CoreLiquidationState liquidation(LiquidationRuntime value,
                                            RuntimeIdentityRegistry identities) {
        return new CoreLiquidationState(value.liquidationId(), value.userId(),
                identities.symbol(value.symbolId()), value.marginMode(), value.positionSide(),
                value.instrumentVersion(), value.triggerPriceSequence(), value.signedQuantitySteps(),
                value.closeQuantitySteps(), value.deficitUnits(), value.executionPriceTicks(),
                value.liquidationFeeRatePpm(), value.liquidationFeeUnits(), value.status(),
                value.nextCancelOrderId());
    }

    static CoreRiskState.RiskScan riskScan(RiskScanRuntime value,
                                           RuntimeIdentityRegistry identities) {
        return new CoreRiskState.RiskScan(identities.symbol(value.symbolId()), value.accountLaneId(),
                value.priceSequence(),
                value.scanStartPriceSequence(), value.lastUserId(), value.riskComplete(), value.riskUserId(),
                value.riskPhase(), value.riskPositionCursor(), value.riskReservationCursor(),
                value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(),
                value.riskIsolatedMarginUnits(), value.riskIsolatedReservationUnits(), value.triggerComplete(),
                value.triggerPhase(), value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private static <K, A, B> void applyValues(RuntimeMutationDelta.ValueChanges<K, A> changes,
                                               Map<K, B> target,
                                               java.util.function.Function<A, B> mapper) {
        for (K key : changes.changedKeys()) {
            A value = changes.currentValues().get(key);
            if (value == null) target.remove(key); else target.put(key, mapper.apply(value));
        }
    }

    static CoreTreasuryState treasuryTransition(RuntimeMutationDelta.TreasuryValues changes,
                                                RuntimeIdentityRegistry identities,
                                                CoreTreasuryState previous) {
        Map<String, Long> fees = StateMapSupport.delta(previous.feeBalances());
        Map<String, Long> insurance = StateMapSupport.delta(previous.insuranceBalances());
        Map<String, Long> deficits = StateMapSupport.delta(previous.insuranceDeficits());
        Map<String, Long> liquidationFees = StateMapSupport.delta(previous.liquidationFeeBalances());
        Map<String, Long> fundingResiduals = StateMapSupport.delta(previous.fundingResidualBalances());
        Map<String, Long> roundingResiduals = StateMapSupport.delta(previous.roundingResidualBalances());
        Map<String, Long> clearingPnl = StateMapSupport.delta(previous.clearingPnlBalances());
        for (Integer assetId : changes.assets().changedKeys()) {
            String asset = identities.asset(assetId);
            RuntimeMutationDelta.AssetLedger value = changes.assets().currentValues().get(assetId);
            putOrRemove(fees, asset, value.fee());
            putOrRemove(insurance, asset, value.insurance());
            putOrRemove(deficits, asset, value.deficit());
            putOrRemove(liquidationFees, asset, value.liquidationFee());
            putOrRemove(fundingResiduals, asset, value.fundingResidual());
            putOrRemove(roundingResiduals, asset, value.roundingResidual());
            putOrRemove(clearingPnl, asset, value.clearingPnl());
        }
        Map<String, Long> fundingSettlements = StateMapSupport.delta(previous.fundingSettlements());
        Map<String, CoreTreasuryState.FundingProgress> fundingProgress = StateMapSupport.delta(
                previous.fundingProgress());
        for (Integer symbolId : changes.funding().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            RuntimeMutationDelta.FundingLedger value = changes.funding().currentValues().get(symbolId);
            putOrRemove(fundingSettlements, symbol, value.settlementId());
            TreasuryRuntime.FundingProgressRuntime progress = value.progress();
            if (progress == null) fundingProgress.remove(symbol);
            else fundingProgress.put(symbol, new CoreTreasuryState.FundingProgress(progress.settlementId(),
                    progress.instrumentVersion(), progress.fundingRatePpm(), progress.accountLaneId(),
                    progress.nextCursorUserId(), progress.commandId()));
        }
        Map<String, Long> lifecycleSettlements = StateMapSupport.delta(previous.lifecycleSettlements());
        Map<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress = StateMapSupport.delta(
                previous.lifecycleProgress());
        for (Integer symbolId : changes.lifecycle().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            RuntimeMutationDelta.LifecycleLedger value = changes.lifecycle().currentValues().get(symbolId);
            putOrRemove(lifecycleSettlements, symbol, value.settlementId());
            TreasuryRuntime.LifecycleProgressRuntime progress = value.progress();
            if (progress == null) lifecycleProgress.remove(symbol);
            else lifecycleProgress.put(symbol, new CoreTreasuryState.LifecycleProgress(progress.settlementId(),
                    progress.instrumentVersion(), progress.settlementPriceTicks(),
                    progress.optionCashUnitsPerContract(), progress.ordersComplete(), progress.accountLaneId(),
                    progress.nextCursorOrderId(), progress.nextCursorUserId(), progress.commandId()));
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
