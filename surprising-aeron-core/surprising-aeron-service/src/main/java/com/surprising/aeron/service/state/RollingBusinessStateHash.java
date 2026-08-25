package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class RollingBusinessStateHash {

    private static final long HASH_TAG = 0x9e3779b97f4a7c15L;

    private final Aggregate users = new Aggregate();
    private final Map<Long, UserHash> userHashes = new TreeMap<>();
    private final Aggregate orders = new Aggregate();
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
    private final int productLine;
    private long revision;
    private long nextLiquidationId;
    private long riskScanControlHash;

    private RollingBusinessStateHash(TradingCoreState state) {
        productLine = state.productLine().ordinal();
        revision = state.revision();
        nextLiquidationId = state.riskState().nextLiquidationId();
        riskScanControlHash = stable(state.riskState().scanControl());
        rebuild(state);
    }

    public static RollingBusinessStateHash create(TradingCoreState state) {
        return new RollingBusinessStateHash(state);
    }

    public static long compute(TradingCoreState state) {
        return new RollingBusinessStateHash(state).value();
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

    public void restore(TradingCoreState state) {
        revision = state.revision();
        nextLiquidationId = state.riskState().nextLiquidationId();
        riskScanControlHash = stable(state.riskState().scanControl());
        rebuild(state);
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
        rebuildUsers(state.users());
        rebuildMap(orders, state.orders(), order -> !order.status().terminal());
        rebuildMap(instruments, state.instruments());
        rebuildMap(leverages, state.leverages());
        rebuildMap(algoOrders, state.algoOrders(), algo -> !algo.terminal());
        rebuildMap(timers, state.cancelAllAfterTimers());
        rebuildMap(triggers, state.triggerOrders(), trigger -> trigger.status().open());
        rebuildMap(markPrices, state.riskState().markPrices());
        rebuildMap(riskSnapshots, state.riskState().snapshots());
        rebuildMap(liquidations, state.riskState().liquidations(), liquidation -> !liquidation.terminal());
        rebuildMap(riskScans, state.riskState().scans());
        rebuildMap(feeBalances, state.treasuryState().feeBalances());
        rebuildMap(insuranceBalances, state.treasuryState().insuranceBalances());
        rebuildMap(insuranceDeficits, state.treasuryState().insuranceDeficits());
        rebuildMap(liquidationFeeBalances, state.treasuryState().liquidationFeeBalances());
        rebuildMap(fundingResidualBalances, state.treasuryState().fundingResidualBalances());
        rebuildMap(roundingResidualBalances, state.treasuryState().roundingResidualBalances());
        rebuildMap(clearingPnlBalances, state.treasuryState().clearingPnlBalances());
        rebuildMap(fundingSettlements, state.treasuryState().fundingSettlements());
        rebuildMap(lifecycleSettlements, state.treasuryState().lifecycleSettlements());
        rebuildMap(fundingProgress, state.treasuryState().fundingProgress());
        rebuildMap(lifecycleProgress, state.treasuryState().lifecycleProgress());
    }

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values) {
        target.clear();
        values.forEach((key, value) -> target.add(entryHash(key, value)));
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
            UserHash hash = UserHash.create(user);
            userHashes.put(userId, hash);
            users.add(entryHash(userId, hash.value()));
        });
    }

    private void updateUsers(Map<Long, CoreUserState> before, Map<Long, CoreUserState> after) {
        if (before == after) return;
        for (Long userId : StateMapSupport.changedKeys(before, after)) {
            UserHash previousHash = userHashes.remove(userId);
            CoreUserState previous = before.get(userId);
            if (previous != null) {
                if (previousHash == null) previousHash = UserHash.create(previous);
                users.remove(entryHash(userId, previousHash.value()));
            }
            CoreUserState next = after.get(userId);
            if (next != null) {
                UserHash nextHash = previousHash == null
                        ? UserHash.create(next) : previousHash.update(previous, next);
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

    private static long stable(Object value) {
        if (value == null) return 0;
        if (value instanceof CoreUserState user) {
            return TradingCoreState.hashUser(CoreStateHash.start(), user);
        }
        if (value instanceof CoreOrderState order) {
            return TradingCoreState.hashOrder(CoreStateHash.start(), order);
        }
        long hash = CoreStateHash.mix(CoreStateHash.start(), value.getClass().getName());
        return CoreStateHash.mix(hash, value.toString());
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

    private record UserHash(int productLine, long userId, long revision, int positionMode,
                            Aggregate balances, Aggregate reservations, Aggregate positions) {

        private static UserHash create(CoreUserState user) {
            Aggregate balances = new Aggregate();
            Aggregate reservations = new Aggregate();
            Aggregate positions = new Aggregate();
            rebuildMap(balances, user.balances());
            rebuildMap(reservations, user.reservations(), reservation -> reservation.remainingUnits() > 0);
            rebuildMap(positions, user.positions());
            return new UserHash(user.productLine().ordinal(), user.userId(), user.revision(),
                    user.positionMode().wireCode(), balances, reservations, positions);
        }

        private UserHash update(CoreUserState before, CoreUserState after) {
            if (before == null || before.userId() != after.userId()) return create(after);
            Aggregate nextBalances = balances.copy();
            Aggregate nextReservations = reservations.copy();
            Aggregate nextPositions = positions.copy();
            updateMap(nextBalances, before.balances(), after.balances());
            updateMap(nextReservations, before.reservations(), after.reservations(),
                    reservation -> reservation.remainingUnits() > 0);
            updateMap(nextPositions, before.positions(), after.positions());
            return new UserHash(after.productLine().ordinal(), after.userId(), after.revision(),
                    after.positionMode().wireCode(), nextBalances, nextReservations, nextPositions);
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
}
