package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class RollingFundsStateHash {

    private static final long HASH_TAG = 0xd6e8feb86659fd93L;
    private final int productLine;
    private final Aggregate users = new Aggregate();
    private final Map<Long, Long> userHashes = new TreeMap<>();
    private final Map<Long, Map<String, RuntimeMutationDelta.BalanceValue>> runtimeBalances = new TreeMap<>();
    private final Map<Integer, RuntimeMutationDelta.AssetLedger> runtimeTreasury = new TreeMap<>();
    private final Aggregate fees = new Aggregate();
    private final Aggregate insurance = new Aggregate();
    private final Aggregate deficits = new Aggregate();
    private final Aggregate liquidationFees = new Aggregate();
    private final Aggregate fundingResiduals = new Aggregate();
    private final Aggregate roundingResiduals = new Aggregate();
    private final Aggregate clearingPnl = new Aggregate();

    private RuntimeIdentityRegistry identities;

    private RollingFundsStateHash(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (state == null) throw new IllegalArgumentException("funds state is required");
        productLine = state.productLine().ordinal();
        this.identities = identities;
        rebuild(state);
    }

    public static RollingFundsStateHash create(TradingCoreState state) {
        return new RollingFundsStateHash(state, null);
    }

    public static RollingFundsStateHash create(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        return new RollingFundsStateHash(state, identities);
    }

    public static long compute(TradingCoreState state) {
        return create(state).value();
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before == after) return;
        updateUsers(before.users(), after.users());
        CoreTreasuryState previous = before.treasuryState();
        CoreTreasuryState current = after.treasuryState();
        updateMap(fees, previous.feeBalances(), current.feeBalances());
        updateMap(insurance, previous.insuranceBalances(), current.insuranceBalances());
        updateMap(deficits, previous.insuranceDeficits(), current.insuranceDeficits());
        updateMap(liquidationFees, previous.liquidationFeeBalances(), current.liquidationFeeBalances());
        updateMap(fundingResiduals, previous.fundingResidualBalances(), current.fundingResidualBalances());
        updateMap(roundingResiduals, previous.roundingResidualBalances(), current.roundingResidualBalances());
        updateMap(clearingPnl, previous.clearingPnlBalances(), current.clearingPnlBalances());
    }

    public void update(RuntimeCommitEntry entry) {
        if (entry == null || entry.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid funds hash commit");
        }
        if (identities == null) identities = entry.identities();
        RuntimeMutationDelta mutation = entry.mutation();
        for (Long userId : mutation.users().changedKeys()) {
            Long previousHash = userHashes.remove(userId);
            if (previousHash != null) users.remove(entryHash(userId, previousHash));
            RuntimeMutationDelta.UserValue current = mutation.users().currentValues().get(userId);
            if (current == null) {
                runtimeBalances.remove(userId);
            } else {
                Map<String, RuntimeMutationDelta.BalanceValue> balances = runtimeBalances.computeIfAbsent(
                        userId, ignored -> new TreeMap<>());
                current.balances().changedKeys().forEach(assetId -> {
                    String asset = identities.asset(assetId);
                    RuntimeMutationDelta.BalanceValue balance = current.balances().currentValues().get(assetId);
                    if (balance == null) balances.remove(asset); else balances.put(asset, balance);
                });
                long currentHash = hashUser(userId, balances);
                userHashes.put(userId, currentHash);
                users.add(entryHash(userId, currentHash));
            }
        }
        mutation.treasury().assets().changedKeys().forEach(assetId -> {
            String asset = identities.asset(assetId);
            RuntimeMutationDelta.AssetLedger previous = runtimeTreasury.get(assetId);
            RuntimeMutationDelta.AssetLedger current = mutation.treasury().assets().currentValues().get(assetId);
            update(fees, asset, fee(previous), fee(current));
            update(insurance, asset, insurance(previous), insurance(current));
            update(deficits, asset, deficit(previous), deficit(current));
            update(liquidationFees, asset, liquidationFee(previous), liquidationFee(current));
            update(fundingResiduals, asset, fundingResidual(previous), fundingResidual(current));
            update(roundingResiduals, asset, roundingResidual(previous), roundingResidual(current));
            update(clearingPnl, asset, clearingPnl(previous), clearingPnl(current));
            if (current == null) runtimeTreasury.remove(assetId); else runtimeTreasury.put(assetId, current);
        });
    }

    public void restore(TradingCoreState state) {
        rebuild(state);
    }

    public void restore(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        this.identities = identities;
        rebuild(state);
    }

    public long value() {
        long hash = CoreStateHash.mix(CoreStateHash.start(), productLine);
        hash = mix(hash, "users", users);
        hash = mix(hash, "fee", fees);
        hash = mix(hash, "insurance", insurance);
        hash = mix(hash, "deficit", deficits);
        hash = mix(hash, "liquidationFee", liquidationFees);
        hash = mix(hash, "fundingResidual", fundingResiduals);
        hash = mix(hash, "roundingResidual", roundingResiduals);
        return mix(hash, "clearingPnl", clearingPnl);
    }

    private void rebuild(TradingCoreState state) {
        users.clear();
        userHashes.clear();
        runtimeBalances.clear();
        runtimeTreasury.clear();
        state.users().forEach((userId, user) -> {
            long hash = hashUser(user);
            userHashes.put(userId, hash);
            users.add(entryHash(userId, hash));
            TreeMap<String, RuntimeMutationDelta.BalanceValue> balances = new TreeMap<>();
            user.balances().forEach((asset, balance) -> balances.put(asset,
                    new RuntimeMutationDelta.BalanceValue(balance.availableUnits(), balance.lockedUnits(), 0)));
            runtimeBalances.put(userId, balances);
        });
        CoreTreasuryState treasury = state.treasuryState();
        rebuildMap(fees, treasury.feeBalances());
        rebuildMap(insurance, treasury.insuranceBalances());
        rebuildMap(deficits, treasury.insuranceDeficits());
        rebuildMap(liquidationFees, treasury.liquidationFeeBalances());
        rebuildMap(fundingResiduals, treasury.fundingResidualBalances());
        rebuildMap(roundingResiduals, treasury.roundingResidualBalances());
        rebuildMap(clearingPnl, treasury.clearingPnlBalances());
        if (identities != null) {
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
    }

    private void updateUsers(Map<Long, CoreUserState> before, Map<Long, CoreUserState> after) {
        if (before == after) return;
        for (Long userId : StateMapSupport.changedKeys(before, after)) {
            Long previousHash = userHashes.remove(userId);
            if (previousHash != null) users.remove(entryHash(userId, previousHash));
            CoreUserState current = after.get(userId);
            if (current != null) {
                long currentHash = hashUser(current);
                userHashes.put(userId, currentHash);
                users.add(entryHash(userId, currentHash));
            }
        }
    }

    private static long hashUser(CoreUserState user) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), user.userId());
        for (Map.Entry<String, AssetBalance> entry : user.balances().entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue().availableUnits());
            hash = CoreStateHash.mix(hash, entry.getValue().lockedUnits());
        }
        return hash;
    }

    private static long hashUser(long userId, Map<String, RuntimeMutationDelta.BalanceValue> balances) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), userId);
        for (Map.Entry<String, RuntimeMutationDelta.BalanceValue> entry : balances.entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue().availableUnits());
            hash = CoreStateHash.mix(hash, entry.getValue().lockedUnits());
        }
        return hash;
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

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values) {
        target.clear();
        values.forEach((key, value) -> target.add(entryHash(key, value)));
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

    private static long entryHash(Object key, Object value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = stable(hash, key);
        return stable(hash, value);
    }

    private static long stable(long hash, Object value) {
        if (value instanceof Long number) return CoreStateHash.mix(hash, number.longValue());
        if (value instanceof Integer number) return CoreStateHash.mix(hash, number.longValue());
        if (value instanceof String text) return CoreStateHash.mix(hash, text);
        return CoreStateHash.mix(hash, String.valueOf(value));
    }

    private static long mix(long hash, String name, Aggregate aggregate) {
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, aggregate.count);
        hash = CoreStateHash.mix(hash, aggregate.sum);
        return CoreStateHash.mix(hash, aggregate.xor);
    }

    private static final class Aggregate {
        private long count;
        private long sum;
        private long xor;

        private void clear() { count = 0; sum = 0; xor = 0; }
        private void add(long value) { count++; sum += value; xor ^= value; }
        private void remove(long value) { count--; sum -= value; xor ^= value; }
    }
}
