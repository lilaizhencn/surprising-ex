package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Objects;

public final class RollingFundsStateHash {
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;

    private final int productLine;
    private final Aggregate users = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserFundsHash> userHashes =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<
            RuntimeFactFrame.TreasuryAssetValue> runtimeTreasury =
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
    private final Aggregate fees = new Aggregate();
    private final Aggregate insurance = new Aggregate();
    private final Aggregate deficits = new Aggregate();
    private final Aggregate liquidationFees = new Aggregate();
    private final Aggregate fundingResiduals = new Aggregate();
    private final Aggregate roundingResiduals = new Aggregate();
    private final Aggregate clearingPnl = new Aggregate();

    private RuntimeFactFrame.IdentityView identities;
    private long revision;
    private long lastCoreSequence = Long.MIN_VALUE;
    private long cachedValue;
    private boolean valueDirty = true;
    private long ownerGeneration;

    static long[] aggregateForTest(long[] additions, long[] removals) {
        Aggregate aggregate = new Aggregate();
        for (long contribution : additions) aggregate.add(contribution);
        for (long contribution : removals) aggregate.remove(contribution);
        return new long[]{aggregate.count, aggregate.sum, aggregate.xor};
    }

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

    public long coreSequence() { return lastCoreSequence; }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before == after) return;
        restore(after);
    }

    public void update(RuntimeFactFrame patch) {
        long nextGeneration = Math.incrementExact(ownerGeneration);
        identities = applyPatch(patch);
        revision = patch.revision();
        lastCoreSequence = patch.coreSequence();
        valueDirty = true;
        ownerGeneration = nextGeneration;
    }

    /** Applies the authoritative delta once; failures are handled by process fail-stop and replay. */
    public long applyFailStop(RuntimeFactFrame.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        identities = applyPatch(changes);
        revision = changes.afterRevision();
        lastCoreSequence = changes.coreSequence();
        valueDirty = true;
        ownerGeneration = Math.incrementExact(ownerGeneration);
        return value();
    }

    private RuntimeFactFrame.IdentityView applyPatch(RuntimeFactView patch) {
        RuntimeFactFrame.IdentityView patchIdentities = validateHeader(patch);
        for (RuntimeFactFrame.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            for (RuntimeFactFrame.UserChange change : group.users()) {
                boolean exists = userHashes.containsKey(change.userId());
                if (exists != (change.before() != null)) {
                    throw new IllegalArgumentException("funds user before-value mismatch");
                }
                if (change.before() == null) {
                    applyUserChange(change);
                }
            }
            for (RuntimeFactFrame.BalanceChange change : group.balances()) {
                String asset = patchIdentities.asset(change.key().assetId());
                UserFundsHash user = userHashes.get(change.key().userId());
                boolean actualPresent = user != null && user.hasBalanceContribution(change.key().assetId());
                boolean expectedPresent = change.before() != null;
                long actual = actualPresent ? user.balanceContribution(change.key().assetId()) : 0;
                long expected = expectedPresent ? balanceContribution(asset, change.before()) : 0;
                if (actualPresent != expectedPresent || actualPresent && actual != expected) {
                    throw new IllegalArgumentException("funds balance before-value mismatch");
                }
                applyBalanceChange(change, asset);
            }
            for (RuntimeFactFrame.UserChange change : group.users()) {
                if (change.before() == null || change.after() != null) continue;
                applyUserChange(change);
            }
        }
        for (RuntimeFactFrame.TreasuryAssetChange change : patch.globalOwnerGroup().treasuryAssets()) {
            String asset = patchIdentities.asset(change.assetId());
            if (!Objects.equals(runtimeTreasury.get(change.assetId()), normalize(change.before()))) {
                throw new IllegalArgumentException("funds treasury before-value mismatch");
            }
            applyTreasuryChange(change, asset);
        }
        return patchIdentities;
    }

    private RuntimeFactFrame.IdentityView validateHeader(RuntimeFactView patch) {
        if (patch == null || patch.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid funds hash commit");
        }
        if (lastCoreSequence != Long.MIN_VALUE && patch.previousCoreSequence() != lastCoreSequence) {
            throw new IllegalArgumentException("non-contiguous funds hash commit sequence: last "
                    + lastCoreSequence + ", previous " + patch.previousCoreSequence()
                    + ", current " + patch.coreSequence());
        }
        if (patch.beforeRevision() != revision || patch.beforeFundsStateHash() != value()) {
            throw new IllegalArgumentException("funds hash commit before-value mismatch");
        }
        RuntimeFactFrame.IdentityView patchIdentities = identities == null ? patch.identities() : identities;
        return patchIdentities;
    }

    public void restore(TradingCoreState state) {
        long nextGeneration = Math.incrementExact(ownerGeneration);
        rebuild(state);
        ownerGeneration = nextGeneration;
    }

    public void restore(TradingCoreState state, RuntimeIdentityRegistry identities) {
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        this.identities = identities;
        restore(state);
    }

    public long value() {
        if (!valueDirty) return cachedValue;
        long hash = CoreStateHash.mix(CoreStateHash.start(), productLine);
        hash = mix(hash, "users", users);
        hash = mix(hash, "fee", fees);
        hash = mix(hash, "insurance", insurance);
        hash = mix(hash, "deficit", deficits);
        hash = mix(hash, "liquidationFee", liquidationFees);
        hash = mix(hash, "fundingResidual", fundingResiduals);
        hash = mix(hash, "roundingResidual", roundingResiduals);
        cachedValue = mix(hash, "clearingPnl", clearingPnl);
        valueDirty = false;
        return cachedValue;
    }

    public void restoreAuditWatermark(long watermark) {
        if (watermark == 0) throw new IllegalArgumentException("funds audit watermark is required");
        cachedValue = watermark;
        valueDirty = false;
    }

    private void applyBalanceChange(RuntimeFactFrame.BalanceChange change, String asset) {
        long userId = change.key().userId();
        UserFundsHash user = userHashes.get(userId);
        long previousContribution = user == null ? 0 : entryHash(userId, user.value());
        if (user == null) {
            user = new UserFundsHash(userId);
            userHashes.put(userId, user);
        }
        user.replace(change, asset);
        replaceUserContribution(previousContribution, user);
    }

    private void applyUserChange(RuntimeFactFrame.UserChange change) {
        UserFundsHash user = userHashes.get(change.userId());
        UserRuntime current = change.after();
        if (current == null) {
            if (user != null) {
                long contribution = entryHash(change.userId(), user.value());
                users.remove(contribution);
                userHashes.remove(change.userId());
            }
        } else if (user == null) {
            user = new UserFundsHash(change.userId());
            userHashes.put(change.userId(), user);
            replaceUserContribution(0, user);
        }
    }

    private void applyTreasuryChange(RuntimeFactFrame.TreasuryAssetChange change, String asset) {
        RuntimeFactFrame.TreasuryAssetValue previous = change.before();
        RuntimeFactFrame.TreasuryAssetValue current = change.after();
        update(fees, asset, fee(previous), fee(current));
        update(insurance, asset, insurance(previous), insurance(current));
        update(deficits, asset, deficit(previous), deficit(current));
        update(liquidationFees, asset, liquidationFee(previous), liquidationFee(current));
        update(fundingResiduals, asset, fundingResidual(previous), fundingResidual(current));
        update(roundingResiduals, asset, roundingResidual(previous), roundingResidual(current));
        update(clearingPnl, asset, clearingPnl(previous), clearingPnl(current));
        if (current == null) runtimeTreasury.remove(change.assetId());
        else runtimeTreasury.put(change.assetId(), current);
    }

    private void rebuild(TradingCoreState state) {
        revision = state.revision();
        users.clear();
        userHashes.clear();
        runtimeTreasury.clear();
        state.users().forEach((userId, user) -> {
            UserFundsHash userHash = UserFundsHash.create(user, identities);
            userHashes.put(userId, userHash);
            long contribution = entryHash(userId.longValue(), userHash.value());
            users.add(contribution);
        });
        CoreTreasuryState treasury = state.treasuryState();
        rebuildMap(fees, treasury.feeBalances());
        rebuildMap(insurance, treasury.insuranceBalances());
        rebuildMap(deficits, treasury.insuranceDeficits());
        rebuildMap(liquidationFees, treasury.liquidationFeeBalances());
        rebuildMap(fundingResiduals, treasury.fundingResidualBalances());
        rebuildMap(roundingResiduals, treasury.roundingResidualBalances());
        rebuildMap(clearingPnl, treasury.clearingPnlBalances());
        if (identities != null) rebuildRuntimeTreasury(treasury);
        lastCoreSequence = Long.MIN_VALUE;
        valueDirty = true;
    }

    private void rebuildRuntimeTreasury(CoreTreasuryState treasury) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(treasury.feeBalances().keySet());
        assets.addAll(treasury.insuranceBalances().keySet());
        assets.addAll(treasury.insuranceDeficits().keySet());
        assets.addAll(treasury.liquidationFeeBalances().keySet());
        assets.addAll(treasury.fundingResidualBalances().keySet());
        assets.addAll(treasury.roundingResidualBalances().keySet());
        assets.addAll(treasury.clearingPnlBalances().keySet());
        for (String asset : assets) {
            RuntimeFactFrame.TreasuryAssetValue value = new RuntimeFactFrame.TreasuryAssetValue(
                    treasury.feeBalances().getOrDefault(asset, 0L),
                    treasury.insuranceBalances().getOrDefault(asset, 0L),
                    treasury.insuranceDeficits().getOrDefault(asset, 0L),
                    treasury.liquidationFeeBalances().getOrDefault(asset, 0L),
                    treasury.fundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.roundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.clearingPnlBalances().getOrDefault(asset, 0L));
            RuntimeFactFrame.TreasuryAssetValue normalized = normalize(value);
            if (normalized != null) runtimeTreasury.put(identities.assetId(asset), normalized);
        }
    }

    private void replaceUserContribution(long previousContribution, UserFundsHash user) {
        if (previousContribution != 0) {
            users.remove(previousContribution);
        }
        long currentContribution = entryHash(user.userId, user.value());
        users.add(currentContribution);
    }

    private static RuntimeFactFrame.TreasuryAssetValue normalize(RuntimeFactFrame.TreasuryAssetValue value) {
        if (value == null) return null;
        return fee(value) == 0 && insurance(value) == 0 && deficit(value) == 0
                && liquidationFee(value) == 0 && fundingResidual(value) == 0
                && roundingResidual(value) == 0 && clearingPnl(value) == 0 ? null : value;
    }

    private static long balanceContribution(String asset, RuntimeFactFrame.UserBalance balance) {
        long available = Math.addExact(balance.availableUnits(), balance.pendingReservedUnits());
        long locked = Math.subtractExact(balance.lockedUnits(), balance.pendingReservedUnits());
        long valueHash = CoreStateHash.mix(CoreStateHash.start(), asset);
        valueHash = CoreStateHash.mix(valueHash, available);
        valueHash = CoreStateHash.mix(valueHash, locked);
        return entryHash(asset, valueHash);
    }

    private void update(Aggregate aggregate, String asset, long previous, long current) {
        if (previous != 0) {
            long contribution = entryHash(asset, previous);
            aggregate.remove(contribution);
        }
        if (current != 0) {
            long contribution = entryHash(asset, current);
            aggregate.add(contribution);
        }
    }

    private static long fee(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.fee(); }
    private static long insurance(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.insurance(); }
    private static long deficit(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.deficit(); }
    private static long liquidationFee(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.liquidationFee(); }
    private static long fundingResidual(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.fundingResidual(); }
    private static long roundingResidual(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.roundingResidual(); }
    private static long clearingPnl(RuntimeFactFrame.TreasuryAssetValue value) { return value == null ? 0 : value.clearingPnl(); }

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values) {
        target.clear();
        values.forEach((key, value) -> target.add(entryHash(key, value)));
    }

    private static long entryHash(Object key, Object value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = stable(hash, key);
        return stable(hash, value);
    }

    private static long entryHash(long key, long value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, value);
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

    private static final class UserFundsHash {
        private final long userId;
        private final Aggregate balances = new Aggregate();
        private final org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap balanceContributions =
                new org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap();

        private UserFundsHash(long userId) {
            this.userId = userId;
        }

        private static UserFundsHash create(CoreUserState user, RuntimeFactFrame.IdentityView identities) {
            UserFundsHash hash = new UserFundsHash(user.userId());
            user.balances().forEach((asset, balance) -> {
                long valueHash = CoreStateHash.mix(CoreStateHash.start(), asset);
                valueHash = CoreStateHash.mix(valueHash, balance.availableUnits());
                valueHash = CoreStateHash.mix(valueHash, balance.lockedUnits());
                long contribution = entryHash(asset, valueHash);
                int assetId = identities == null ? asset.hashCode() : identities.assetId(asset);
                hash.balanceContributions.put(assetId, contribution);
                hash.balances.add(contribution);
            });
            return hash;
        }

        private void replace(RuntimeFactFrame.BalanceChange change, String asset) {
            int assetId = change.key().assetId();
            if (balanceContributions.containsKey(assetId)) {
                long previous = balanceContributions.get(assetId);
                balanceContributions.removeKey(assetId);
                balances.remove(previous);
            }
            RuntimeFactFrame.UserBalance after = change.after();
            if (after != null) {
                long current = RollingFundsStateHash.balanceContribution(asset, after);
                balanceContributions.put(assetId, current);
                balances.add(current);
            }
        }

        private boolean hasBalanceContribution(int assetId) { return balanceContributions.containsKey(assetId); }
        private long balanceContribution(int assetId) { return balanceContributions.get(assetId); }

        private long value() {
            long hash = CoreStateHash.mix(CoreStateHash.start(), userId);
            return mix(hash, "balances", balances);
        }
    }
}
