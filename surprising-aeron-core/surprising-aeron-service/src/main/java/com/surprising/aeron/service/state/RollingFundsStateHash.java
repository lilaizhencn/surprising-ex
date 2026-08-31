package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Objects;

public final class RollingFundsStateHash {
    private static final long HASH_TAG = 0xd6e8feb86659fd93L;
    private static final int RESTORED_OWNER = -1;
    private static final int GLOBAL_OWNER = 63;
    private static final int RESTORED_OWNER_INDEX = 64;

    private final int productLine;
    private final Aggregate users = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserFundsHash> userHashes =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final OwnerDomains[] ownerDomains = ownerDomains();
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<
            RuntimeCommitPatch.TreasuryAssetValue> runtimeTreasury =
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
    private final Aggregate fees = new Aggregate();
    private final Aggregate insurance = new Aggregate();
    private final Aggregate deficits = new Aggregate();
    private final Aggregate liquidationFees = new Aggregate();
    private final Aggregate fundingResiduals = new Aggregate();
    private final Aggregate roundingResiduals = new Aggregate();
    private final Aggregate clearingPnl = new Aggregate();

    private RuntimeCommitPatch.IdentityView identities;
    private long revision;
    private long lastCoreSequence = Long.MIN_VALUE;
    private long cachedValue;
    private boolean valueDirty = true;
    private long ownerGeneration;
    private int failAfterStagedOperation = -1;

    private enum Domain { USERS, FEES, INSURANCE, DEFICITS, LIQUIDATION_FEES,
        FUNDING_RESIDUALS, ROUNDING_RESIDUALS, CLEARING_PNL }

    void failAfterStagedOperationForTest(int operationIndex) {
        if (operationIndex < 0) throw new IllegalArgumentException("operation index must not be negative");
        failAfterStagedOperation = operationIndex;
    }

    int stagedOperationCountForTest(RuntimeCommitPatch patch) { return stagePatch(patch).size(); }

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

    public void update(RuntimeCommitPatch patch) {
        FundsPatchStage staged = stagePatch(patch);
        long nextGeneration = Math.incrementExact(ownerGeneration);
        staged.apply();
        identities = staged.identities;
        revision = patch.revision();
        lastCoreSequence = patch.coreSequence();
        valueDirty = true;
        ownerGeneration = nextGeneration;
    }

    public HashTransition prepare(RuntimeCommitPatch.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        FundsPatchStage staged = stagePatch(changes);
        long beforeHash = value();
        long beforeRevision = revision;
        long beforeSequence = lastCoreSequence;
        RuntimeCommitPatch.IdentityView beforeIdentities = identities;
        long beforeCachedValue = cachedValue;
        boolean beforeDirty = valueDirty;
        long afterHash;
        try {
            afterHash = staged.preview(() -> {
                identities = staged.identities;
                revision = changes.afterRevision();
                lastCoreSequence = changes.coreSequence();
                valueDirty = true;
                return value();
            });
        } finally {
            identities = beforeIdentities;
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            cachedValue = beforeCachedValue;
            valueDirty = beforeDirty;
        }
        return new HashTransition(this, staged, beforeHash, afterHash, beforeRevision, beforeSequence,
                beforeIdentities, changes.afterRevision(), changes.coreSequence(), ownerGeneration, false);
    }

    public HashTransition prepareApplied(RuntimeCommitPatch.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        FundsPatchStage staged = stagePatch(changes);
        long beforeHash = value();
        long beforeRevision = revision;
        long beforeSequence = lastCoreSequence;
        RuntimeCommitPatch.IdentityView beforeIdentities = identities;
        boolean applied = false;
        try {
            staged.apply();
            applied = true;
            identities = staged.identities;
            revision = changes.afterRevision();
            lastCoreSequence = changes.coreSequence();
            valueDirty = true;
            long afterHash = value();
            return new HashTransition(this, staged, beforeHash, afterHash, beforeRevision, beforeSequence,
                    beforeIdentities, changes.afterRevision(), changes.coreSequence(), ownerGeneration, true);
        } catch (RuntimeException failure) {
            if (applied) staged.rollbackApplied();
            identities = beforeIdentities;
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            valueDirty = true;
            throw failure;
        }
    }

    public final class HashTransition {
        private final RollingFundsStateHash owner;
        private final FundsPatchStage staged;
        private final long beforeHash;
        private final long afterHash;
        private final long beforeRevision;
        private final long beforeSequence;
        private final RuntimeCommitPatch.IdentityView beforeIdentities;
        private final long afterRevision;
        private final long afterSequence;
        private final long preparedGeneration;
        private long committedGeneration = -1;
        private TransitionState state = TransitionState.PREPARED;

        private HashTransition(RollingFundsStateHash owner, FundsPatchStage staged,
                               long beforeHash, long afterHash,
                               long beforeRevision, long beforeSequence,
                               RuntimeCommitPatch.IdentityView beforeIdentities,
                               long afterRevision, long afterSequence, long preparedGeneration) {
            this(owner, staged, beforeHash, afterHash, beforeRevision, beforeSequence, beforeIdentities,
                    afterRevision, afterSequence, preparedGeneration, false);
        }

        private HashTransition(RollingFundsStateHash owner, FundsPatchStage staged,
                               long beforeHash, long afterHash,
                               long beforeRevision, long beforeSequence,
                               RuntimeCommitPatch.IdentityView beforeIdentities,
                               long afterRevision, long afterSequence, long preparedGeneration,
                               boolean applied) {
            this.owner = owner;
            this.staged = staged;
            this.beforeHash = beforeHash;
            this.afterHash = afterHash;
            this.beforeRevision = beforeRevision;
            this.beforeSequence = beforeSequence;
            this.beforeIdentities = beforeIdentities;
            this.afterRevision = afterRevision;
            this.afterSequence = afterSequence;
            this.preparedGeneration = preparedGeneration;
            if (applied) state = TransitionState.APPLIED;
        }

        public long beforeHash() { return beforeHash; }
        public long afterHash() { return afterHash; }
        public void commit() {
            commitOn(owner);
        }
        private void commitOn(RollingFundsStateHash target) {
            if (state == TransitionState.APPLIED) {
                if (owner != target || ownerGeneration != preparedGeneration
                        || revision != afterRevision || lastCoreSequence != afterSequence
                        || value() != afterHash) {
                    throw new IllegalStateException("stale or foreign applied funds hash transition");
                }
                ownerGeneration = Math.incrementExact(ownerGeneration);
                committedGeneration = ownerGeneration;
                state = TransitionState.COMMITTED;
                return;
            }
            requireState(TransitionState.PREPARED, "commit");
            if (owner != target || ownerGeneration != preparedGeneration || revision != beforeRevision
                    || lastCoreSequence != beforeSequence || value() != beforeHash) {
                throw new IllegalStateException("stale or foreign funds hash transition");
            }
            long nextGeneration = Math.incrementExact(ownerGeneration);
            staged.apply();
            identities = staged.identities;
            revision = afterRevision;
            lastCoreSequence = afterSequence;
            valueDirty = true;
            if (value() != afterHash) {
                staged.rollbackApplied();
                identities = beforeIdentities;
                revision = beforeRevision;
                lastCoreSequence = beforeSequence;
                valueDirty = true;
                throw new IllegalStateException("funds hash transition after-value mismatch");
            }
            ownerGeneration = nextGeneration;
            committedGeneration = ownerGeneration;
            state = TransitionState.COMMITTED;
        }
        public void rollback() {
            rollbackOn(owner);
        }
        private void rollbackOn(RollingFundsStateHash target) {
            boolean applied = state == TransitionState.APPLIED;
            if (!applied) requireState(TransitionState.COMMITTED, "rollback");
            long expectedGeneration = applied ? preparedGeneration : committedGeneration;
            if (owner != target || ownerGeneration != expectedGeneration || revision != afterRevision
                    || lastCoreSequence != afterSequence || value() != afterHash) {
                throw new IllegalStateException("stale or foreign committed funds hash transition");
            }
            long nextGeneration = Math.incrementExact(ownerGeneration);
            staged.rollbackApplied();
            identities = beforeIdentities;
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            valueDirty = true;
            if (value() != beforeHash) throw new IllegalStateException("funds hash rollback mismatch");
            ownerGeneration = nextGeneration;
            state = TransitionState.ROLLED_BACK;
        }
        private void requireState(TransitionState expected, String operation) {
            if (state != expected) {
                throw new IllegalStateException("funds hash transition cannot " + operation + " from " + state);
            }
        }
    }

    void commitForTest(HashTransition transition) {
        if (transition == null) throw new IllegalArgumentException("funds hash transition is required");
        transition.commitOn(this);
    }

    void rollbackForTest(HashTransition transition) {
        if (transition == null) throw new IllegalArgumentException("funds hash transition is required");
        transition.rollbackOn(this);
    }

    private enum TransitionState { PREPARED, APPLIED, COMMITTED, ROLLED_BACK }

    private FundsPatchStage stagePatch(RuntimeCommitView patch) {
        RuntimeCommitPatch.IdentityView patchIdentities = validateHeader(patch);
        FundsPatchStage staged = new FundsPatchStage(patchIdentities);
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                boolean exists = userHashes.containsKey(change.userId());
                if (exists != (change.before() != null)) {
                    throw new IllegalArgumentException("funds user before-value mismatch");
                }
                if (change.before() == null) {
                    RuntimeCommitPatch.UserChange reverse = new RuntimeCommitPatch.UserChange(
                            change.userId(), change.after(), null);
                    staged.add(() -> applyUserChange(group.laneId(), change),
                            () -> applyUserChange(group.laneId(), reverse));
                }
            }
            for (RuntimeCommitPatch.BalanceChange change : group.balances()) {
                String asset = patchIdentities.asset(change.key().assetId());
                UserFundsHash user = userHashes.get(change.key().userId());
                Long actual = user == null ? null : user.balanceContribution(change.key().assetId());
                Long expected = balanceContribution(asset, change.before());
                if (!Objects.equals(actual, expected)) {
                    throw new IllegalArgumentException("funds balance before-value mismatch");
                }
                balanceContribution(asset, change.after());
                int previousOwner = user == null ? group.laneId() : user.owner;
                RuntimeCommitPatch.BalanceChange reverse = new RuntimeCommitPatch.BalanceChange(
                        change.key(), change.after(), change.before());
                staged.add(() -> applyBalanceChange(group.laneId(), change, asset),
                        () -> applyBalanceChange(previousOwner, reverse, asset));
            }
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                if (change.before() == null || change.after() != null) continue;
                int previousOwner = userHashes.get(change.userId()).owner;
                RuntimeCommitPatch.UserChange reverse = new RuntimeCommitPatch.UserChange(
                        change.userId(), null, change.before());
                staged.add(() -> applyUserChange(group.laneId(), change),
                        () -> applyUserChange(previousOwner, reverse));
            }
        }
        for (RuntimeCommitPatch.TreasuryAssetChange change : patch.globalOwnerGroup().treasuryAssets()) {
            String asset = patchIdentities.asset(change.assetId());
            if (!Objects.equals(runtimeTreasury.get(change.assetId()), normalize(change.before()))) {
                throw new IllegalArgumentException("funds treasury before-value mismatch");
            }
            RuntimeCommitPatch.TreasuryAssetChange reverse = new RuntimeCommitPatch.TreasuryAssetChange(
                    change.assetId(), change.after(), change.before());
            staged.add(() -> applyTreasuryChange(change, asset),
                    () -> applyTreasuryChange(reverse, asset));
        }
        return staged;
    }

    private RuntimeCommitPatch.IdentityView validateHeader(RuntimeCommitView patch) {
        if (patch == null || patch.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid funds hash commit");
        }
        if (lastCoreSequence != Long.MIN_VALUE
                && (patch.previousCoreSequence() < lastCoreSequence
                || patch.coreSequence() <= lastCoreSequence)) {
            throw new IllegalArgumentException("non-monotonic funds hash commit sequence: last "
                    + lastCoreSequence + ", previous " + patch.previousCoreSequence()
                    + ", current " + patch.coreSequence());
        }
        if (patch.beforeRevision() != revision || patch.beforeFundsStateHash() != value()) {
            throw new IllegalArgumentException("funds hash commit before-value mismatch");
        }
        RuntimeCommitPatch.IdentityView patchIdentities = identities == null ? patch.identities() : identities;
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
        hash = mixOwnerDomain(hash, "users", Domain.USERS);
        hash = mixOwnerDomain(hash, "fee", Domain.FEES);
        hash = mixOwnerDomain(hash, "insurance", Domain.INSURANCE);
        hash = mixOwnerDomain(hash, "deficit", Domain.DEFICITS);
        hash = mixOwnerDomain(hash, "liquidationFee", Domain.LIQUIDATION_FEES);
        hash = mixOwnerDomain(hash, "fundingResidual", Domain.FUNDING_RESIDUALS);
        hash = mixOwnerDomain(hash, "roundingResidual", Domain.ROUNDING_RESIDUALS);
        cachedValue = mixOwnerDomain(hash, "clearingPnl", Domain.CLEARING_PNL);
        valueDirty = false;
        return cachedValue;
    }

    private void applyBalanceChange(int owner, RuntimeCommitPatch.BalanceChange change, String asset) {
        long userId = change.key().userId();
        UserFundsHash user = userHashes.get(userId);
        long previousContribution = user == null ? 0 : entryHash(userId, user.value());
        int previousOwner = user == null ? owner : user.owner;
        if (user == null) {
            user = new UserFundsHash(userId, owner);
            userHashes.put(userId, user);
        }
        user.replace(change, asset);
        replaceUserContribution(previousOwner, previousContribution, owner, user);
    }

    private void applyUserChange(int owner, RuntimeCommitPatch.UserChange change) {
        UserFundsHash user = userHashes.get(change.userId());
        if (change.after() == null) {
            if (user != null) {
                long contribution = entryHash(change.userId(), user.value());
                users.remove(contribution);
                removeOwner(user.owner, Domain.USERS, contribution);
                userHashes.remove(change.userId());
            }
        } else if (user == null) {
            user = new UserFundsHash(change.userId(), owner);
            userHashes.put(change.userId(), user);
            replaceUserContribution(owner, 0, owner, user);
        }
    }

    private void applyTreasuryChange(RuntimeCommitPatch.TreasuryAssetChange change, String asset) {
        RuntimeCommitPatch.TreasuryAssetValue previous = change.before();
        RuntimeCommitPatch.TreasuryAssetValue current = change.after();
        update(fees, Domain.FEES, asset, fee(previous), fee(current));
        update(insurance, Domain.INSURANCE, asset, insurance(previous), insurance(current));
        update(deficits, Domain.DEFICITS, asset, deficit(previous), deficit(current));
        update(liquidationFees, Domain.LIQUIDATION_FEES, asset,
                liquidationFee(previous), liquidationFee(current));
        update(fundingResiduals, Domain.FUNDING_RESIDUALS, asset,
                fundingResidual(previous), fundingResidual(current));
        update(roundingResiduals, Domain.ROUNDING_RESIDUALS, asset,
                roundingResidual(previous), roundingResidual(current));
        update(clearingPnl, Domain.CLEARING_PNL, asset, clearingPnl(previous), clearingPnl(current));
        if (current == null) runtimeTreasury.remove(change.assetId());
        else runtimeTreasury.put(change.assetId(), current);
    }

    private void rebuild(TradingCoreState state) {
        revision = state.revision();
        users.clear();
        userHashes.clear();
        clearOwnerDomains();
        runtimeTreasury.clear();
        state.users().forEach((userId, user) -> {
            UserFundsHash userHash = UserFundsHash.create(user, identities, RESTORED_OWNER);
            userHashes.put(userId, userHash);
            long contribution = entryHash(userId, userHash.value());
            users.add(contribution);
            addOwner(RESTORED_OWNER, Domain.USERS, contribution);
        });
        CoreTreasuryState treasury = state.treasuryState();
        rebuildMap(fees, treasury.feeBalances());
        rebuildMap(insurance, treasury.insuranceBalances());
        rebuildMap(deficits, treasury.insuranceDeficits());
        rebuildMap(liquidationFees, treasury.liquidationFeeBalances());
        rebuildMap(fundingResiduals, treasury.fundingResidualBalances());
        rebuildMap(roundingResiduals, treasury.roundingResidualBalances());
        rebuildMap(clearingPnl, treasury.clearingPnlBalances());
        setOwnerAggregate(Integer.MAX_VALUE, Domain.FEES, fees);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.INSURANCE, insurance);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.DEFICITS, deficits);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.LIQUIDATION_FEES, liquidationFees);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.FUNDING_RESIDUALS, fundingResiduals);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.ROUNDING_RESIDUALS, roundingResiduals);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.CLEARING_PNL, clearingPnl);
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
            RuntimeCommitPatch.TreasuryAssetValue value = new RuntimeCommitPatch.TreasuryAssetValue(
                    treasury.feeBalances().getOrDefault(asset, 0L),
                    treasury.insuranceBalances().getOrDefault(asset, 0L),
                    treasury.insuranceDeficits().getOrDefault(asset, 0L),
                    treasury.liquidationFeeBalances().getOrDefault(asset, 0L),
                    treasury.fundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.roundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.clearingPnlBalances().getOrDefault(asset, 0L));
            RuntimeCommitPatch.TreasuryAssetValue normalized = normalize(value);
            if (normalized != null) runtimeTreasury.put(identities.assetId(asset), normalized);
        }
    }

    private void replaceUserContribution(int previousOwner, long previousContribution,
                                         int currentOwner, UserFundsHash user) {
        if (previousContribution != 0) {
            users.remove(previousContribution);
            removeOwner(previousOwner, Domain.USERS, previousContribution);
        }
        user.owner = currentOwner;
        long currentContribution = entryHash(user.userId, user.value());
        users.add(currentContribution);
        addOwner(currentOwner, Domain.USERS, currentContribution);
    }

    private static RuntimeCommitPatch.TreasuryAssetValue normalize(RuntimeCommitPatch.TreasuryAssetValue value) {
        if (value == null) return null;
        return fee(value) == 0 && insurance(value) == 0 && deficit(value) == 0
                && liquidationFee(value) == 0 && fundingResidual(value) == 0
                && roundingResidual(value) == 0 && clearingPnl(value) == 0 ? null : value;
    }

    private static Long balanceContribution(String asset, RuntimeCommitPatch.UserBalance balance) {
        if (balance == null) return null;
        long available = Math.addExact(balance.availableUnits(), balance.pendingReservedUnits());
        long locked = Math.subtractExact(balance.lockedUnits(), balance.pendingReservedUnits());
        long valueHash = CoreStateHash.mix(CoreStateHash.start(), asset);
        valueHash = CoreStateHash.mix(valueHash, available);
        valueHash = CoreStateHash.mix(valueHash, locked);
        return entryHash(asset, valueHash);
    }

    private void update(Aggregate aggregate, Domain domain, String asset, long previous, long current) {
        if (previous != 0) {
            long contribution = entryHash(asset, previous);
            aggregate.remove(contribution);
            removeOwner(Integer.MAX_VALUE, domain, contribution);
        }
        if (current != 0) {
            long contribution = entryHash(asset, current);
            aggregate.add(contribution);
            addOwner(Integer.MAX_VALUE, domain, contribution);
        }
    }

    private static long fee(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.fee(); }
    private static long insurance(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.insurance(); }
    private static long deficit(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.deficit(); }
    private static long liquidationFee(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.liquidationFee(); }
    private static long fundingResidual(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.fundingResidual(); }
    private static long roundingResidual(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.roundingResidual(); }
    private static long clearingPnl(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.clearingPnl(); }

    private static <K, V> void rebuildMap(Aggregate target, Map<K, V> values) {
        target.clear();
        values.forEach((key, value) -> target.add(entryHash(key, value)));
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

    private long mixOwnerDomain(long hash, String name, Domain domain) {
        long count = 0;
        long sum = 0;
        long xor = 0;
        for (OwnerDomains owner : ownerDomains) {
            Aggregate aggregate = owner.aggregate(domain);
            count += aggregate.count;
            sum += aggregate.sum;
            xor ^= aggregate.xor;
        }
        hash = CoreStateHash.mix(hash, name);
        hash = CoreStateHash.mix(hash, count);
        hash = CoreStateHash.mix(hash, sum);
        return CoreStateHash.mix(hash, xor);
    }

    private void addOwner(int owner, Domain domain, long contribution) {
        ownerDomains[ownerIndex(owner)].aggregate(domain).add(contribution);
    }

    private void removeOwner(int owner, Domain domain, long contribution) {
        ownerDomains[ownerIndex(owner)].aggregate(domain).remove(contribution);
    }

    private void setOwnerAggregate(int owner, Domain domain, Aggregate source) {
        Aggregate target = ownerDomains[ownerIndex(owner)].aggregate(domain);
        target.count = source.count;
        target.sum = source.sum;
        target.xor = source.xor;
    }

    private static int ownerIndex(int owner) {
        if (owner == RESTORED_OWNER) return RESTORED_OWNER_INDEX;
        if (owner == Integer.MAX_VALUE) return GLOBAL_OWNER;
        if (owner < 0 || owner >= GLOBAL_OWNER) throw new IllegalArgumentException("invalid funds hash owner");
        return owner;
    }

    private static OwnerDomains[] ownerDomains() {
        OwnerDomains[] result = new OwnerDomains[RESTORED_OWNER_INDEX + 1];
        for (int index = 0; index < result.length; index++) result[index] = new OwnerDomains();
        return result;
    }

    private void clearOwnerDomains() { for (OwnerDomains owner : ownerDomains) owner.clear(); }

    private static final class OwnerDomains {
        private final Aggregate[] domains = new Aggregate[Domain.values().length];
        private OwnerDomains() {
            for (int index = 0; index < domains.length; index++) domains[index] = new Aggregate();
        }
        private Aggregate aggregate(Domain domain) { return domains[domain.ordinal()]; }
        private void clear() { for (Aggregate aggregate : domains) aggregate.clear(); }
    }

    private final class FundsPatchStage {
        private final RuntimeCommitPatch.IdentityView identities;
        private final java.util.ArrayList<StagedOperation> operations = new java.util.ArrayList<>();
        private java.util.ArrayDeque<Runnable> appliedRollbacks;
        private FundsPatchStage(RuntimeCommitPatch.IdentityView identities) { this.identities = identities; }
        private void add(Runnable operation, Runnable rollback) {
            operations.add(new StagedOperation(operation, rollback));
        }
        private int size() { return operations.size(); }
        private long preview(java.util.function.LongSupplier value) {
            java.util.ArrayDeque<Runnable> rollbacks = new java.util.ArrayDeque<>();
            try {
                for (StagedOperation operation : operations) {
                    operation.apply().run();
                    rollbacks.push(operation.rollback());
                }
                return value.getAsLong();
            } finally {
                while (!rollbacks.isEmpty()) rollbacks.pop().run();
                valueDirty = true;
            }
        }
        private void apply() {
            java.util.ArrayDeque<Runnable> rollbacks = new java.util.ArrayDeque<>();
            try {
                for (int index = 0; index < operations.size(); index++) {
                    StagedOperation operation = operations.get(index);
                    operation.apply().run();
                    rollbacks.push(operation.rollback());
                    if (index == failAfterStagedOperation) {
                        failAfterStagedOperation = -1;
                        throw new IllegalStateException("injected mid-stage funds hash apply failure");
                    }
                }
                appliedRollbacks = rollbacks;
            } catch (RuntimeException failure) {
                while (!rollbacks.isEmpty()) rollbacks.pop().run();
                valueDirty = true;
                throw failure;
            }
        }
        private void rollbackApplied() {
            if (appliedRollbacks == null) return;
            while (!appliedRollbacks.isEmpty()) appliedRollbacks.pop().run();
            appliedRollbacks = null;
            valueDirty = true;
        }
    }

    private record StagedOperation(Runnable apply, Runnable rollback) {}

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
        private int owner;
        private final Aggregate balances = new Aggregate();
        private final org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap balanceContributions =
                new org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap();

        private UserFundsHash(long userId, int owner) {
            this.userId = userId;
            this.owner = owner;
        }

        private static UserFundsHash create(CoreUserState user, RuntimeCommitPatch.IdentityView identities, int owner) {
            UserFundsHash hash = new UserFundsHash(user.userId(), owner);
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

        private void replace(RuntimeCommitPatch.BalanceChange change, String asset) {
            int assetId = change.key().assetId();
            if (balanceContributions.containsKey(assetId)) {
                long previous = balanceContributions.get(assetId);
                balanceContributions.removeKey(assetId);
                balances.remove(previous);
            }
            Long current = RollingFundsStateHash.balanceContribution(asset, change.after());
            if (current != null) {
                balanceContributions.put(assetId, current);
                balances.add(current);
            }
        }

        private Long balanceContribution(int assetId) {
            return balanceContributions.containsKey(assetId) ? balanceContributions.get(assetId) : null;
        }

        private long value() {
            long hash = CoreStateHash.mix(CoreStateHash.start(), userId);
            return mix(hash, "balances", balances);
        }
    }
}
