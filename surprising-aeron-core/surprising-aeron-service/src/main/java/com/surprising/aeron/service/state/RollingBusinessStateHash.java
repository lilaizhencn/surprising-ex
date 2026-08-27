package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.function.Predicate;

public final class RollingBusinessStateHash {

    private static final long HASH_TAG = 0x9e3779b97f4a7c15L;

    private final Aggregate users = new Aggregate();
    private final Map<Long, UserHash> userHashes = new TreeMap<>();
    private final Aggregate orders = new Aggregate();
    private final Map<Long, Long> orderContributions = new TreeMap<>();
    private final Aggregate instruments = new Aggregate();
    private final Aggregate leverages = new Aggregate();
    private final Aggregate algoOrders = new Aggregate();
    private final Aggregate timers = new Aggregate();
    private final Aggregate triggers = new Aggregate();
    private final Aggregate markPrices = new Aggregate();
    private final Aggregate riskSnapshots = new Aggregate();
    private final Aggregate liquidations = new Aggregate();
    private final Aggregate riskScans = new Aggregate();
    private final Aggregate feeBalances = new Aggregate();
    private final Aggregate insuranceBalances = new Aggregate();
    private final Aggregate insuranceDeficits = new Aggregate();
    private final Aggregate liquidationFeeBalances = new Aggregate();
    private final Aggregate fundingResidualBalances = new Aggregate();
    private final Aggregate roundingResidualBalances = new Aggregate();
    private final Aggregate clearingPnlBalances = new Aggregate();
    private final Aggregate fundingSettlements = new Aggregate();
    private final Aggregate lifecycleSettlements = new Aggregate();
    private final Aggregate fundingProgress = new Aggregate();
    private final Aggregate lifecycleProgress = new Aggregate();
    private final Map<Integer, RuntimeMutationDelta.AssetLedger> runtimeTreasury = new TreeMap<>();
    private final Map<String, Long> contributions = new HashMap<>();
    private final int productLine;
    private long revision;
    private long nextLiquidationId;
    private long riskScanControlHash;
    private RuntimeIdentityRegistry identities;

    private RollingBusinessStateHash(TradingCoreState state, RuntimeIdentityRegistry identities) {
        productLine = state.productLine().ordinal();
        revision = state.revision();
        nextLiquidationId = state.riskState().nextLiquidationId();
        riskScanControlHash = stable(state.riskState().scanControl());
        this.identities = identities == null ? new RuntimeIdentityRegistry() : identities;
        rebuild(state);
    }

    public static RollingBusinessStateHash create(TradingCoreState state) {
        return new RollingBusinessStateHash(state, null);
    }

    public static RollingBusinessStateHash create(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        return new RollingBusinessStateHash(state, identities);
    }

    public static long compute(TradingCoreState state) {
        return new RollingBusinessStateHash(state, null).value();
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before == after) return;
        revision = after.revision();
        updateUsers(before.users(), after.users());
        updateMap(orders, before.orders(), after.orders(), order -> !order.status().terminal());
        updateMap(instruments, before.instruments(), after.instruments());
        updateMap(leverages, before.leverages(), after.leverages());
        updateMap(algoOrders, before.algoOrders(), after.algoOrders(), algo -> !algo.terminal());
        updateMap(timers, before.cancelAllAfterTimers(), after.cancelAllAfterTimers());
        updateMap(triggers, before.triggerOrders(), after.triggerOrders(), trigger -> trigger.status().open());

        if (before.riskState() != after.riskState()) {
            updateMap(markPrices, before.riskState().markPrices(), after.riskState().markPrices());
            updateMap(riskSnapshots, before.riskState().snapshots(), after.riskState().snapshots());
            updateMap(liquidations, before.riskState().liquidations(), after.riskState().liquidations(),
                    liquidation -> !liquidation.terminal());
            updateMap(riskScans, before.riskState().scans(), after.riskState().scans());
            nextLiquidationId = after.riskState().nextLiquidationId();
            riskScanControlHash = stable(after.riskState().scanControl());
        }
        if (before.treasuryState() != after.treasuryState()) {
            var beforeTreasury = before.treasuryState();
            var afterTreasury = after.treasuryState();
            updateMap(feeBalances, beforeTreasury.feeBalances(), afterTreasury.feeBalances());
            updateMap(insuranceBalances, beforeTreasury.insuranceBalances(), afterTreasury.insuranceBalances());
            updateMap(insuranceDeficits, beforeTreasury.insuranceDeficits(), afterTreasury.insuranceDeficits());
            updateMap(liquidationFeeBalances, beforeTreasury.liquidationFeeBalances(),
                    afterTreasury.liquidationFeeBalances());
            updateMap(fundingResidualBalances, beforeTreasury.fundingResidualBalances(),
                    afterTreasury.fundingResidualBalances());
            updateMap(roundingResidualBalances, beforeTreasury.roundingResidualBalances(),
                    afterTreasury.roundingResidualBalances());
            updateMap(clearingPnlBalances, beforeTreasury.clearingPnlBalances(),
                    afterTreasury.clearingPnlBalances());
            updateMap(fundingSettlements, beforeTreasury.fundingSettlements(), afterTreasury.fundingSettlements());
            updateMap(lifecycleSettlements, beforeTreasury.lifecycleSettlements(), afterTreasury.lifecycleSettlements());
            updateMap(fundingProgress, beforeTreasury.fundingProgress(), afterTreasury.fundingProgress());
            updateMap(lifecycleProgress, beforeTreasury.lifecycleProgress(), afterTreasury.lifecycleProgress());
        }
    }

    public void update(RuntimeCommitEntry entry) {
        if (entry == null || entry.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid business hash commit");
        }
        revision = entry.revision();
        updateUsers(entry);
        updateOrders(entry.mutation().orders());
        RuntimeMutationDelta mutation = entry.mutation();
        updateCached("instruments", instruments, mutation.instruments(), value -> stable(value), ignored -> true);
        updateCached("leverages", leverages, mutation.leverages(), value -> stable(value), ignored -> true);
        updateCached("algo", algoOrders, mutation.algoOrders(), value -> stable(value), value -> !value.terminal());
        updateCached("timers", timers, mutation.timers(), value -> stable(value), ignored -> true);
        updateCached("triggers", triggers, mutation.triggerOrders(), value -> stable(value),
                value -> value.status().open());
        updateRuntimeMarks(mutation.markPrices());
        updateRuntimeRiskSnapshots(mutation.riskSnapshots());
        updateRuntimeLiquidations(mutation.liquidations());
        updateRuntimeRiskScans(mutation.riskScans());
        nextLiquidationId = entry.afterNextLiquidationId();
        riskScanControlHash = stable(entry.afterRiskScanControl());
        updateTreasury(entry.mutation().treasury());
    }

    public void restore(TradingCoreState state) {
        revision = state.revision();
        nextLiquidationId = state.riskState().nextLiquidationId();
        riskScanControlHash = stable(state.riskState().scanControl());
        rebuild(state);
    }

    public void restore(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        this.identities = identities;
        restore(state);
    }

    public long value() {
        long hash = CoreStateHash.start();
        hash = CoreStateHash.mix(hash, productLine);
        hash = CoreStateHash.mix(hash, revision);
        hash = mixAggregate(hash, "users", users);
        hash = mixAggregate(hash, "orders", orders);
        hash = mixAggregate(hash, "instruments", instruments);
        hash = mixAggregate(hash, "leverages", leverages);
        hash = mixAggregate(hash, "algo", algoOrders);
        hash = mixAggregate(hash, "timers", timers);
        hash = mixAggregate(hash, "triggers", triggers);
        hash = mixAggregate(hash, "markPrices", markPrices);
        hash = mixAggregate(hash, "riskSnapshots", riskSnapshots);
        hash = mixAggregate(hash, "liquidations", liquidations);
        hash = mixAggregate(hash, "riskScans", riskScans);
        hash = CoreStateHash.mix(hash, nextLiquidationId);
        hash = CoreStateHash.mix(hash, riskScanControlHash);
        hash = mixAggregate(hash, "feeBalances", feeBalances);
        hash = mixAggregate(hash, "insuranceBalances", insuranceBalances);
        hash = mixAggregate(hash, "insuranceDeficits", insuranceDeficits);
        hash = mixAggregate(hash, "liquidationFeeBalances", liquidationFeeBalances);
        hash = mixAggregate(hash, "fundingResidualBalances", fundingResidualBalances);
        hash = mixAggregate(hash, "roundingResidualBalances", roundingResidualBalances);
        hash = mixAggregate(hash, "clearingPnlBalances", clearingPnlBalances);
        hash = mixAggregate(hash, "fundingSettlements", fundingSettlements);
        hash = mixAggregate(hash, "lifecycleSettlements", lifecycleSettlements);
        hash = mixAggregate(hash, "fundingProgress", fundingProgress);
        return mixAggregate(hash, "lifecycleProgress", lifecycleProgress);
    }

    private void rebuild(TradingCoreState state) {
        contributions.clear();
        rebuildUsers(state.users());
        rebuildOrders(state.orders());
        rebuildCached("instruments", instruments, state.instruments(), ignored -> true);
        rebuildCached("leverages", leverages, state.leverages(), ignored -> true);
        rebuildCached("algo", algoOrders, state.algoOrders(), algo -> !algo.terminal());
        rebuildCached("timers", timers, state.cancelAllAfterTimers(), ignored -> true);
        rebuildCached("triggers", triggers, state.triggerOrders(), trigger -> trigger.status().open());
        rebuildCached("marks", markPrices, state.riskState().markPrices(), ignored -> true);
        rebuildCached("snapshots", riskSnapshots, state.riskState().snapshots(), ignored -> true);
        rebuildCached("liquidations", liquidations, state.riskState().liquidations(), value -> !value.terminal());
        rebuildCached("scans", riskScans, state.riskState().scans(), ignored -> true);
        rebuildMap(feeBalances, state.treasuryState().feeBalances());
        rebuildMap(insuranceBalances, state.treasuryState().insuranceBalances());
        rebuildMap(insuranceDeficits, state.treasuryState().insuranceDeficits());
        rebuildMap(liquidationFeeBalances, state.treasuryState().liquidationFeeBalances());
        rebuildMap(fundingResidualBalances, state.treasuryState().fundingResidualBalances());
        rebuildMap(roundingResidualBalances, state.treasuryState().roundingResidualBalances());
        rebuildMap(clearingPnlBalances, state.treasuryState().clearingPnlBalances());
        rebuildCached("fundingSettlements", fundingSettlements,
                state.treasuryState().fundingSettlements(), ignored -> true);
        rebuildCached("lifecycleSettlements", lifecycleSettlements,
                state.treasuryState().lifecycleSettlements(), ignored -> true);
        rebuildCached("fundingProgress", fundingProgress,
                state.treasuryState().fundingProgress(), ignored -> true);
        rebuildCached("lifecycleProgress", lifecycleProgress,
                state.treasuryState().lifecycleProgress(), ignored -> true);
        rebuildRuntimeTreasury(state.treasuryState());
    }

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values) {
        target.clear();
        values.forEach((key, value) -> target.add(entryHash(key, value)));
    }

    private <K, V> void rebuildCached(String domain, Aggregate target, Map<K, V> values,
                                      Predicate<V> included) {
        target.clear();
        values.forEach((key, value) -> {
            if (!included.test(value)) return;
            long contribution = entryHash(key, value);
            contributions.put(contributionKey(domain, key), contribution);
            target.add(contribution);
        });
    }

    private <K extends Comparable<? super K>, V> void updateCached(
            String domain, Aggregate target, RuntimeMutationDelta.ValueChanges<K, V> changes,
            java.util.function.ToLongFunction<V> stableValue, Predicate<V> included) {
        for (K key : changes.changedKeys()) {
            Long previous = contributions.remove(contributionKey(domain, key));
            if (previous != null) target.remove(previous);
            V current = changes.currentValues().get(key);
            if (current != null && included.test(current)) {
                long contribution = entryHashStable(key, stableValue.applyAsLong(current));
                contributions.put(contributionKey(domain, key), contribution);
                target.add(contribution);
            }
        }
    }

    private void updateRuntimeMarks(RuntimeMutationDelta.ValueChanges<Integer, MarkPriceRuntime> changes) {
        for (Integer symbolId : changes.changedKeys()) {
            String symbol = identities.symbol(symbolId);
            updateRuntimeContribution("marks", markPrices, symbol,
                    changes.currentValues().get(symbolId), this::stableMark, ignored -> true);
        }
    }

    private void updateRuntimeRiskSnapshots(
            RuntimeMutationDelta.ValueChanges<Long, RiskSnapshotRuntime> changes) {
        for (Long positionKey : changes.changedKeys()) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
            String key = identity.userId() + ":" + identity.positionKey();
            updateRuntimeContribution("snapshots", riskSnapshots, key,
                    changes.currentValues().get(positionKey), this::stableRiskSnapshot, ignored -> true);
        }
    }

    private void updateRuntimeLiquidations(
            RuntimeMutationDelta.ValueChanges<Long, LiquidationRuntime> changes) {
        for (Long id : changes.changedKeys()) {
            updateRuntimeContribution("liquidations", liquidations, id, changes.currentValues().get(id),
                    this::stableLiquidation, value -> !runtimeTerminal(value));
        }
    }

    private void updateRuntimeRiskScans(RuntimeMutationDelta.ValueChanges<Integer, RiskScanRuntime> changes) {
        for (Integer symbolId : changes.changedKeys()) {
            String symbol = identities.symbol(symbolId);
            updateRuntimeContribution("scans", riskScans, symbol, changes.currentValues().get(symbolId),
                    this::stableRiskScan, ignored -> true);
        }
    }

    private <K, V> void updateRuntimeContribution(String domain, Aggregate target, K key, V current,
                                                   java.util.function.ToLongFunction<V> stableValue,
                                                   Predicate<V> included) {
        Long previous = contributions.remove(contributionKey(domain, key));
        if (previous != null) target.remove(previous);
        if (current != null && included.test(current)) {
            long contribution = entryHashStable(key, stableValue.applyAsLong(current));
            contributions.put(contributionKey(domain, key), contribution);
            target.add(contribution);
        }
    }

    private static String contributionKey(String domain, Object key) {
        return domain + '\u0000' + key;
    }

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values, Predicate<V> included) {
        target.clear();
        values.forEach((key, value) -> {
            if (included.test(value)) target.add(entryHash(key, value));
        });
    }

    private void rebuildUsers(Map<Long, CoreUserState> values) {
        users.clear();
        userHashes.clear();
        values.forEach((userId, user) -> {
            UserHash hash = UserHash.create(user, identities);
            userHashes.put(userId, hash);
            users.add(entryHash(userId, hash.value()));
        });
    }

    private void updateUsers(RuntimeCommitEntry entry) {
        RuntimeMutationDelta mutation = entry.mutation();
        java.util.TreeSet<Long> changed = new java.util.TreeSet<>(mutation.users().changedKeys());
        mutation.reservations().currentValues().values().forEach(value -> changed.add(value.userId()));
        mutation.positions().currentValues().values().forEach(value -> changed.add(value.userId()));
        for (Long userId : changed) {
            UserHash hash = userHashes.get(userId);
            if (hash != null) users.remove(entryHash(userId, hash.value()));
            RuntimeMutationDelta.UserValue user = mutation.users().currentValues().get(userId);
            if (hash == null) {
                if (user == null) continue;
                hash = UserHash.create(user.user());
            } else if (user != null) {
                hash.updateUser(user.user());
            }
            hash.updateBalances(user == null ? null : user.balances(), identities);
            hash.updateReservations(userId, mutation.reservations(), identities);
            hash.updatePositions(userId, mutation.positions(), identities);
            userHashes.put(userId, hash);
            users.add(entryHash(userId, hash.value()));
        }
    }

    private void rebuildOrders(Map<Long, CoreOrderState> values) {
        orders.clear();
        orderContributions.clear();
        values.forEach((orderId, order) -> {
            if (order.status().terminal()) return;
            long contribution = entryHash(orderId, order);
            orderContributions.put(orderId, contribution);
            orders.add(contribution);
        });
    }

    private void updateOrders(RuntimeMutationDelta.ValueChanges<Long, OrderRuntime> changes) {
        for (Long orderId : changes.changedKeys()) {
            Long previous = orderContributions.remove(orderId);
            if (previous != null) orders.remove(previous);
            OrderRuntime current = changes.currentValues().get(orderId);
            if (current != null && !current.status().terminal()) {
                long contribution = entryHashStable(orderId, stableOrder(current, identities));
                orderContributions.put(orderId, contribution);
                orders.add(contribution);
            }
        }
    }

    private void rebuildRuntimeTreasury(CoreTreasuryState treasury) {
        runtimeTreasury.clear();
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(treasury.feeBalances().keySet());
        assets.addAll(treasury.insuranceBalances().keySet());
        assets.addAll(treasury.insuranceDeficits().keySet());
        assets.addAll(treasury.liquidationFeeBalances().keySet());
        assets.addAll(treasury.fundingResidualBalances().keySet());
        assets.addAll(treasury.roundingResidualBalances().keySet());
        assets.addAll(treasury.clearingPnlBalances().keySet());
        for (String asset : assets) {
            runtimeTreasury.put(identities.assetId(asset), new RuntimeMutationDelta.AssetLedger(
                    treasury.feeBalances().getOrDefault(asset, 0L),
                    treasury.insuranceBalances().getOrDefault(asset, 0L),
                    treasury.insuranceDeficits().getOrDefault(asset, 0L),
                    treasury.liquidationFeeBalances().getOrDefault(asset, 0L),
                    treasury.fundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.roundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.clearingPnlBalances().getOrDefault(asset, 0L)));
        }
    }

    private void updateTreasury(RuntimeMutationDelta.TreasuryValues treasury) {
        treasury.assets().changedKeys().forEach(assetId -> {
            String asset = identities.asset(assetId);
            RuntimeMutationDelta.AssetLedger previous = runtimeTreasury.get(assetId);
            RuntimeMutationDelta.AssetLedger current = treasury.assets().currentValues().get(assetId);
            update(feeBalances, asset, fee(previous), fee(current));
            update(insuranceBalances, asset, insurance(previous), insurance(current));
            update(insuranceDeficits, asset, deficit(previous), deficit(current));
            update(liquidationFeeBalances, asset, liquidationFee(previous), liquidationFee(current));
            update(fundingResidualBalances, asset, fundingResidual(previous), fundingResidual(current));
            update(roundingResidualBalances, asset, roundingResidual(previous), roundingResidual(current));
            update(clearingPnlBalances, asset, clearingPnl(previous), clearingPnl(current));
            if (current == null) runtimeTreasury.remove(assetId); else runtimeTreasury.put(assetId, current);
        });
        for (Integer symbolId : treasury.funding().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            RuntimeMutationDelta.FundingLedger value = treasury.funding().currentValues().get(symbolId);
            updateRuntimeContribution("fundingSettlements", fundingSettlements, symbol,
                    value == null || value.settlementId() == 0 ? null : value.settlementId(),
                    number -> stable(number), ignored -> true);
            updateRuntimeContribution("fundingProgress", fundingProgress, symbol,
                    value == null ? null : value.progress(), this::stableFundingProgress, ignored -> true);
        }
        for (Integer symbolId : treasury.lifecycle().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            RuntimeMutationDelta.LifecycleLedger value = treasury.lifecycle().currentValues().get(symbolId);
            updateRuntimeContribution("lifecycleSettlements", lifecycleSettlements, symbol,
                    value == null || value.settlementId() == 0 ? null : value.settlementId(),
                    number -> stable(number), ignored -> true);
            updateRuntimeContribution("lifecycleProgress", lifecycleProgress, symbol,
                    value == null ? null : value.progress(), this::stableLifecycleProgress, ignored -> true);
        }
    }

    private static void update(Aggregate aggregate, String asset, long previous, long current) {
        if (previous != 0) aggregate.remove(entryHash(asset, previous));
        if (current != 0) aggregate.add(entryHash(asset, current));
    }

    private static long fee(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.fee(); }
    private static long insurance(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.insurance(); }
    private static long deficit(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.deficit(); }
    private static long liquidationFee(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.liquidationFee(); }
    private static long fundingResidual(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.fundingResidual(); }
    private static long roundingResidual(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.roundingResidual(); }
    private static long clearingPnl(RuntimeMutationDelta.AssetLedger value) { return value == null ? 0 : value.clearingPnl(); }

    private void updateUsers(Map<Long, CoreUserState> before, Map<Long, CoreUserState> after) {
        if (before == after) return;
        for (Long userId : StateMapSupport.changedKeys(before, after)) {
            UserHash previousHash = userHashes.remove(userId);
            CoreUserState previous = before.get(userId);
            if (previous != null) {
                if (previousHash == null) previousHash = UserHash.create(previous, identities);
                users.remove(entryHash(userId, previousHash.value()));
            }
            CoreUserState next = after.get(userId);
            if (next != null) {
                UserHash nextHash = UserHash.create(next, identities);
                userHashes.put(userId, nextHash);
                users.add(entryHash(userId, nextHash.value()));
            }
        }
    }

    private static <K, V> void updateMap(Aggregate target, Map<K, V> before, Map<K, V> after) {
        if (before == after) return;
        if (!StateMapSupport.isDelta(after)) {
            rebuildMap(target, after);
            return;
        }
        Set<K> changed = StateMapSupport.changedKeys(after);
        for (K key : changed) {
            if (before.containsKey(key)) target.remove(entryHash(key, before.get(key)));
            if (after.containsKey(key)) target.add(entryHash(key, after.get(key)));
        }
    }

    private static <K, V> void updateMap(Aggregate target, Map<K, V> before, Map<K, V> after,
                                         Predicate<V> included) {
        if (before == after) return;
        if (!StateMapSupport.isDelta(after)) {
            rebuildMap(target, after, included);
            return;
        }
        Set<K> changed = StateMapSupport.changedKeys(after);
        for (K key : changed) {
            V previous = before.get(key);
            if (previous != null && included.test(previous)) target.remove(entryHash(key, previous));
            V next = after.get(key);
            if (next != null && included.test(next)) target.add(entryHash(key, next));
        }
    }

    private static long entryHash(Object key, Object value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, stable(key));
        return CoreStateHash.mix(hash, stable(value));
    }

    private static long entryHashStable(Object key, long stableValue) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, stable(key));
        return CoreStateHash.mix(hash, stableValue);
    }

    private static long stableOrder(OrderRuntime order, RuntimeIdentityRegistry identities) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), order.orderId());
        hash = CoreStateHash.mix(hash, order.productLine().ordinal());
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, identities.symbol(order.symbolId()));
        hash = CoreStateHash.mix(hash, order.instrumentVersion());
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
        hash = CoreStateHash.mix(hash, order.matchingPriceTicks());
        hash = CoreStateHash.mix(hash, order.quantitySteps());
        hash = CoreStateHash.mix(hash, order.executedQuantitySteps());
        hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
        hash = CoreStateHash.mix(hash, order.reduceOnly());
        hash = CoreStateHash.mix(hash, order.marginMode().wireCode());
        hash = CoreStateHash.mix(hash, order.positionSide().wireCode());
        hash = CoreStateHash.mix(hash, order.orderType().wireCode());
        hash = CoreStateHash.mix(hash, order.timeInForce().wireCode());
        hash = CoreStateHash.mix(hash, order.postOnly());
        hash = CoreStateHash.mix(hash, order.clientOrderId());
        hash = CoreStateHash.mix(hash, order.commandId().getMostSignificantBits());
        hash = CoreStateHash.mix(hash, order.commandId().getLeastSignificantBits());
        hash = CoreStateHash.mix(hash, order.makerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.takerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.cumulativeFeeUnits());
        hash = CoreStateHash.mix(hash, order.createdAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.clusterPosition());
        hash = CoreStateHash.mix(hash, order.status().ordinal());
        return CoreStateHash.mix(hash, order.revision());
    }

    private static long stable(Object value) {
        if (value == null) return 0;
        if (value instanceof CoreUserState user) {
            return TradingCoreState.hashUser(CoreStateHash.start(), user);
        }
        if (value instanceof CoreOrderState order) {
            return TradingCoreState.hashOrder(CoreStateHash.start(), order);
        }
        if (value instanceof CoreMarkPriceState mark) return stableMark(mark);
        if (value instanceof CoreRiskSnapshot snapshot) return stableRiskSnapshot(snapshot);
        if (value instanceof CoreLiquidationState liquidation) return stableLiquidation(liquidation);
        if (value instanceof CoreRiskState.RiskScan scan) return stableRiskScan(scan);
        if (value instanceof CoreTreasuryState.FundingProgress progress) return stableFundingProgress(progress);
        if (value instanceof CoreTreasuryState.LifecycleProgress progress) return stableLifecycleProgress(progress);
        long hash = CoreStateHash.mix(CoreStateHash.start(), value.getClass().getName());
        if (value instanceof Long number) return CoreStateHash.mix(hash, number.longValue());
        if (value instanceof Integer number) return CoreStateHash.mix(hash, number.longValue());
        if (value instanceof String text) return CoreStateHash.mix(hash, text);
        if (value instanceof Enum<?> enumeration) return CoreStateHash.mix(hash, enumeration.ordinal());
        if (value instanceof AssetBalance balance) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            return CoreStateHash.mix(hash, balance.lockedUnits());
        }
        if (value instanceof OrderReservation reservation) {
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.symbol());
            hash = CoreStateHash.mix(hash, reservation.instrumentVersion());
            hash = CoreStateHash.mix(hash, reservation.kind().ordinal());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            return CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        if (value instanceof CorePositionState position) {
            hash = CoreStateHash.mix(hash, position.symbol());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, position.instrumentVersion());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            return CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        return CoreStateHash.mix(hash, value.toString());
    }

    private static long stableMark(CoreMarkPriceState value) {
        return canonical(CoreMarkPriceState.class.getName(), value.symbol(), value.instrumentVersion(),
                value.markPriceTicks(), value.priceSequence(), value.generatedAtEpochMillis());
    }

    private long stableMark(MarkPriceRuntime value) {
        return canonical(CoreMarkPriceState.class.getName(), identities.symbol(value.symbolId()),
                value.instrumentVersion(), value.markPriceTicks(), value.priceSequence(),
                value.generatedAtEpochMillis());
    }

    private static long stableRiskSnapshot(CoreRiskSnapshot value) {
        return canonical(CoreRiskSnapshot.class.getName(), value.userId(), value.symbol(), value.positionSide(),
                value.priceSequence(), value.equityUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.marginRatioPpm(), value.status());
    }

    private long stableRiskSnapshot(RiskSnapshotRuntime value) {
        return canonical(CoreRiskSnapshot.class.getName(), value.userId(), identities.symbol(value.symbolId()),
                value.positionSide(), value.priceSequence(), value.equityUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.marginRatioPpm(), value.status());
    }

    private static long stableLiquidation(CoreLiquidationState value) {
        return canonical(CoreLiquidationState.class.getName(), value.liquidationId(), value.userId(), value.symbol(),
                value.marginMode(), value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                value.executionPriceTicks(), value.liquidationFeeRatePpm(), value.liquidationFeeUnits(),
                value.status(), value.nextCancelOrderId());
    }

    private long stableLiquidation(LiquidationRuntime value) {
        return canonical(CoreLiquidationState.class.getName(), value.liquidationId(), value.userId(),
                identities.symbol(value.symbolId()), value.marginMode(), value.positionSide(),
                value.instrumentVersion(), value.triggerPriceSequence(), value.signedQuantitySteps(),
                value.closeQuantitySteps(), value.deficitUnits(), value.executionPriceTicks(),
                value.liquidationFeeRatePpm(), value.liquidationFeeUnits(), value.status(), value.nextCancelOrderId());
    }

    private static boolean runtimeTerminal(LiquidationRuntime value) {
        return value.status() == CoreLiquidationState.Status.CANCELED
                || value.status() == CoreLiquidationState.Status.COMPLETED && value.deficitUnits() == 0;
    }

    private static long stableRiskScan(CoreRiskState.RiskScan value) {
        return canonical(CoreRiskState.RiskScan.class.getName(), value.symbol(), value.accountLaneId(),
                value.priceSequence(), value.scanStartPriceSequence(), value.lastUserId(), value.riskComplete(),
                value.riskUserId(), value.riskPhase(), value.riskPositionCursor(), value.riskReservationCursor(),
                value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(), value.riskIsolatedMarginUnits(),
                value.riskIsolatedReservationUnits(), value.triggerComplete(), value.triggerPhase(),
                value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private long stableRiskScan(RiskScanRuntime value) {
        return canonical(CoreRiskState.RiskScan.class.getName(), identities.symbol(value.symbolId()),
                value.accountLaneId(), value.priceSequence(), value.scanStartPriceSequence(), value.lastUserId(),
                value.riskComplete(), value.riskUserId(), value.riskPhase(), value.riskPositionCursor(),
                value.riskReservationCursor(), value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(),
                value.riskIsolatedMarginUnits(), value.riskIsolatedReservationUnits(), value.triggerComplete(),
                value.triggerPhase(), value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private static long stableFundingProgress(CoreTreasuryState.FundingProgress value) {
        return canonical(CoreTreasuryState.FundingProgress.class.getName(), value.settlementId(),
                value.instrumentVersion(), value.fundingRatePpm(), value.accountLaneId(),
                value.nextCursorUserId(), value.commandId());
    }

    private long stableFundingProgress(TreasuryRuntime.FundingProgressRuntime value) {
        return canonical(CoreTreasuryState.FundingProgress.class.getName(), value.settlementId(),
                value.instrumentVersion(), value.fundingRatePpm(), value.accountLaneId(),
                value.nextCursorUserId(), value.commandId());
    }

    private static long stableLifecycleProgress(CoreTreasuryState.LifecycleProgress value) {
        return canonical(CoreTreasuryState.LifecycleProgress.class.getName(), value.settlementId(),
                value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                value.ordersComplete(), value.accountLaneId(), value.nextCursorOrderId(),
                value.nextCursorUserId(), value.commandId());
    }

    private long stableLifecycleProgress(TreasuryRuntime.LifecycleProgressRuntime value) {
        return canonical(CoreTreasuryState.LifecycleProgress.class.getName(), value.settlementId(),
                value.instrumentVersion(), value.settlementPriceTicks(), value.optionCashUnitsPerContract(),
                value.ordersComplete(), value.accountLaneId(), value.nextCursorOrderId(),
                value.nextCursorUserId(), value.commandId());
    }

    private static long canonical(String type, Object... values) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), type);
        for (Object value : values) {
            if (value instanceof Long number) hash = CoreStateHash.mix(hash, number.longValue());
            else if (value instanceof Integer number) hash = CoreStateHash.mix(hash, number.longValue());
            else if (value instanceof Boolean flag) hash = CoreStateHash.mix(hash, flag.booleanValue());
            else if (value instanceof Enum<?> enumeration) hash = CoreStateHash.mix(hash, enumeration.ordinal());
            else hash = CoreStateHash.mix(hash, String.valueOf(value));
        }
        return hash;
    }

    private static long mixAggregate(long hash, String name, Aggregate aggregate) {
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, aggregate.count);
        hash = CoreStateHash.mix(hash, aggregate.sum);
        return CoreStateHash.mix(hash, aggregate.xor);
    }

    private static final class Aggregate {
        private long count;
        private long sum;
        private long xor;

        private void clear() {
            count = 0;
            sum = 0;
            xor = 0;
        }

        private void add(long value) {
            count++;
            sum += value;
            xor ^= value;
        }

        private void remove(long value) {
            count--;
            sum -= value;
            xor ^= value;
        }

        private Aggregate copy() {
            Aggregate result = new Aggregate();
            result.count = count;
            result.sum = sum;
            result.xor = xor;
            return result;
        }
    }

    private static final class UserHash {
        private int productLine;
        private final long userId;
        private long revision;
        private int positionMode;
        private final Aggregate balances = new Aggregate();
        private final Aggregate reservations = new Aggregate();
        private final Aggregate positions = new Aggregate();
        private final Map<Integer, Long> balanceContributions = new TreeMap<>();
        private final Map<Long, Long> reservationContributions = new TreeMap<>();
        private final Map<Long, Long> positionContributions = new TreeMap<>();

        private UserHash(UserRuntime user) {
            productLine = user.productLine().ordinal();
            userId = user.userId();
            revision = user.revision();
            positionMode = user.positionMode().wireCode();
        }

        private static UserHash create(UserRuntime user) {
            return new UserHash(user);
        }

        private static UserHash create(CoreUserState user, RuntimeIdentityRegistry identities) {
            UserHash hash = new UserHash(new UserRuntime(
                    user.productLine(), user.userId(), user.revision(), user.positionMode()));
            user.balances().forEach((asset, balance) -> {
                int assetId = identities.assetId(asset);
                long contribution = entryHash(asset, balance);
                hash.balanceContributions.put(assetId, contribution);
                hash.balances.add(contribution);
            });
            user.reservations().forEach((orderId, reservation) -> {
                if (reservation.remainingUnits() == 0) return;
                long contribution = entryHash(orderId, reservation);
                hash.reservationContributions.put(orderId, contribution);
                hash.reservations.add(contribution);
            });
            user.positions().forEach((positionIdentity, position) -> {
                long positionKey = identities.positionKey(user.userId(), positionIdentity);
                long contribution = entryHash(positionIdentity, position);
                hash.positionContributions.put(positionKey, contribution);
                hash.positions.add(contribution);
            });
            return hash;
        }

        private void updateUser(UserRuntime user) {
            productLine = user.productLine().ordinal();
            revision = user.revision();
            positionMode = user.positionMode().wireCode();
        }

        private void updateBalances(RuntimeMutationDelta.ValueChanges<Integer,
                RuntimeMutationDelta.BalanceValue> changes, RuntimeIdentityRegistry identities) {
            if (changes == null) return;
            for (Integer assetId : changes.changedKeys()) {
                Long previous = balanceContributions.remove(assetId);
                if (previous != null) balances.remove(previous);
                RuntimeMutationDelta.BalanceValue current = changes.currentValues().get(assetId);
                if (current != null) {
                    String asset = identities.asset(assetId);
                    long contribution = entryHashStable(asset, stableBalance(asset, current));
                    balanceContributions.put(assetId, contribution);
                    balances.add(contribution);
                }
            }
        }

        private void updateReservations(long ownerId,
                RuntimeMutationDelta.ValueChanges<Long, ReservationRuntime> changes,
                RuntimeIdentityRegistry identities) {
            for (Long orderId : changes.changedKeys()) {
                ReservationRuntime current = changes.currentValues().get(orderId);
                Long previous = reservationContributions.get(orderId);
                if (previous == null && (current == null || current.userId() != ownerId)) continue;
                if (previous != null) {
                    reservationContributions.remove(orderId);
                    reservations.remove(previous);
                }
                if (current != null && current.userId() == ownerId && current.reservedUnits() > 0) {
                    long contribution = entryHashStable(orderId, stableReservation(current, identities));
                    reservationContributions.put(orderId, contribution);
                    reservations.add(contribution);
                }
            }
        }

        private void updatePositions(long ownerId,
                RuntimeMutationDelta.ValueChanges<Long, PositionRuntime> changes,
                RuntimeIdentityRegistry identities) {
            for (Long positionKey : changes.changedKeys()) {
                PositionRuntime current = changes.currentValues().get(positionKey);
                Long previous = positionContributions.get(positionKey);
                if (previous == null && (current == null || current.userId() != ownerId)) continue;
                if (previous != null) {
                    positionContributions.remove(positionKey);
                    positions.remove(previous);
                }
                if (current != null && current.userId() == ownerId) {
                    String identity = identities.positionIdentity(positionKey).positionKey();
                    long contribution = entryHashStable(identity, stablePosition(current, identities));
                    positionContributions.put(positionKey, contribution);
                    positions.add(contribution);
                }
            }
        }

        private long value() {
            long hash = CoreStateHash.start();
            hash = CoreStateHash.mix(hash, productLine);
            hash = CoreStateHash.mix(hash, userId);
            hash = CoreStateHash.mix(hash, revision);
            hash = CoreStateHash.mix(hash, positionMode);
            hash = mixAggregate(hash, "balances", balances);
            hash = mixAggregate(hash, "reservations", reservations);
            return mixAggregate(hash, "positions", positions);
        }
    }

    private static long stableBalance(String asset, RuntimeMutationDelta.BalanceValue balance) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), AssetBalance.class.getName());
        hash = CoreStateHash.mix(hash, asset);
        hash = CoreStateHash.mix(hash, balance.availableUnits());
        return CoreStateHash.mix(hash, balance.lockedUnits());
    }

    private static long stableReservation(ReservationRuntime reservation, RuntimeIdentityRegistry identities) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), OrderReservation.class.getName());
        hash = CoreStateHash.mix(hash, reservation.orderId());
        hash = CoreStateHash.mix(hash, identities.symbol(reservation.symbolId()));
        hash = CoreStateHash.mix(hash, reservation.instrumentVersion());
        hash = CoreStateHash.mix(hash, reservation.kind().ordinal());
        hash = CoreStateHash.mix(hash, identities.asset(reservation.assetId()));
        hash = CoreStateHash.mix(hash, reservation.totalReservedUnits());
        hash = CoreStateHash.mix(hash, reservation.releasedUnits());
        hash = CoreStateHash.mix(hash, reservation.consumedUnits());
        return CoreStateHash.mix(hash, reservation.orderQuantitySteps());
    }

    private static long stablePosition(PositionRuntime position, RuntimeIdentityRegistry identities) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), CorePositionState.class.getName());
        hash = CoreStateHash.mix(hash, identities.symbol(position.symbolId()));
        hash = CoreStateHash.mix(hash, identities.asset(position.assetId()));
        hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
        hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
        hash = CoreStateHash.mix(hash, position.instrumentVersion());
        hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
        hash = CoreStateHash.mix(hash, position.entryPriceTicks());
        hash = CoreStateHash.mix(hash, position.entryValueTicks());
        hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
        return CoreStateHash.mix(hash, position.positionMarginUnits());
    }
}
