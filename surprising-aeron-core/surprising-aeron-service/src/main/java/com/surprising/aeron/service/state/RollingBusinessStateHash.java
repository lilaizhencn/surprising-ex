package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.function.Predicate;

public final class RollingBusinessStateHash {

    private static final long HASH_TAG = 0x9e3779b97f4a7c15L;
    private static final int GLOBAL_OWNER = 63;
    private static final int RESTORED_OWNER = 64;

    private final Aggregate users = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserHash> userHashes =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final OwnerDomains[] ownerDomains = ownerDomains();
    private final Aggregate orders = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<OwnedContribution>
            orderContributions = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
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
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<
            RuntimeCommitPatch.TreasuryAssetValue> runtimeTreasury =
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
    private final Map<ContributionKey, OwnedContribution> contributions = new HashMap<>();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap
            pendingBeforeCountsScratch = new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<
            RuntimeCommitPatch.ReservationChange> reservationChangesScratch =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final int productLine;
    private long revision;
    private long nextLiquidationId;
    private long riskScanControlHash;
    private long lastCoreSequence = Long.MIN_VALUE;
    private long cachedValue;
    private boolean valueDirty = true;
    private long ownerGeneration;
    private RuntimeIdentityRegistry identities;
    private int failAfterStagedOperation = -1;

    private enum Domain {
        USERS, ORDERS, INSTRUMENTS, LEVERAGES, ALGO, TIMERS, TRIGGERS, MARKS, SNAPSHOTS,
        LIQUIDATIONS, SCANS, FEES, INSURANCE, DEFICITS, LIQUIDATION_FEES, FUNDING_RESIDUALS,
        ROUNDING_RESIDUALS, CLEARING_PNL, FUNDING_SETTLEMENTS, LIFECYCLE_SETTLEMENTS,
        FUNDING_PROGRESS, LIFECYCLE_PROGRESS
    }

    private record OwnedContribution(int owner, long value) {}

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

    public long coreSequence() { return lastCoreSequence; }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before == after) return;
        restore(after);
    }

    public void update(RuntimeCommitPatch entry) {
        BusinessPatchStage staged = stagePatch(entry);
        long nextGeneration = Math.incrementExact(ownerGeneration);
        staged.apply();
        revision = entry.revision();
        lastCoreSequence = entry.coreSequence();
        valueDirty = true;
        ownerGeneration = nextGeneration;
    }

    public HashTransition prepare(RuntimeCommitPatch.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        BusinessPatchStage staged = stagePatch(changes);
        long beforeHash = value();
        long beforeRevision = revision;
        long beforeSequence = lastCoreSequence;
        long beforeCachedValue = cachedValue;
        boolean beforeDirty = valueDirty;
        long afterHash;
        try {
            afterHash = staged.preview(() -> {
                revision = changes.afterRevision();
                lastCoreSequence = changes.coreSequence();
                valueDirty = true;
                return value();
            });
        } finally {
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            cachedValue = beforeCachedValue;
            valueDirty = beforeDirty;
        }
        return new HashTransition(this, staged, beforeHash, afterHash, beforeRevision, beforeSequence,
                changes.afterRevision(), changes.coreSequence(), ownerGeneration, false);
    }

    public HashTransition prepareApplied(RuntimeCommitPatch.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        BusinessPatchStage staged = stagePatch(changes);
        long beforeHash = value();
        long beforeRevision = revision;
        long beforeSequence = lastCoreSequence;
        boolean applied = false;
        try {
            staged.apply();
            applied = true;
            revision = changes.afterRevision();
            lastCoreSequence = changes.coreSequence();
            valueDirty = true;
            long afterHash = value();
            return new HashTransition(this, staged, beforeHash, afterHash, beforeRevision, beforeSequence,
                    changes.afterRevision(), changes.coreSequence(), ownerGeneration, true);
        } catch (RuntimeException failure) {
            if (applied) staged.rollbackApplied();
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            valueDirty = true;
            throw failure;
        }
    }

    public final class HashTransition {
        private final RollingBusinessStateHash owner;
        private final BusinessPatchStage staged;
        private final long beforeHash;
        private final long afterHash;
        private final long beforeRevision;
        private final long beforeSequence;
        private final long afterRevision;
        private final long afterSequence;
        private final long preparedGeneration;
        private long committedGeneration = -1;
        private TransitionState state = TransitionState.PREPARED;

        private HashTransition(RollingBusinessStateHash owner, BusinessPatchStage staged,
                               long beforeHash, long afterHash,
                               long beforeRevision, long beforeSequence,
                               long afterRevision, long afterSequence, long preparedGeneration,
                               boolean applied) {
            this.owner = owner;
            this.staged = staged;
            this.beforeHash = beforeHash;
            this.afterHash = afterHash;
            this.beforeRevision = beforeRevision;
            this.beforeSequence = beforeSequence;
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
        private void commitOn(RollingBusinessStateHash target) {
            if (state == TransitionState.APPLIED) {
                if (owner != target || ownerGeneration != preparedGeneration
                        || revision != afterRevision || lastCoreSequence != afterSequence
                        || value() != afterHash) {
                    throw new IllegalStateException("stale or foreign applied business hash transition");
                }
                ownerGeneration = Math.incrementExact(ownerGeneration);
                committedGeneration = ownerGeneration;
                state = TransitionState.COMMITTED;
                return;
            }
            requireState(TransitionState.PREPARED, "commit");
            if (owner != target || ownerGeneration != preparedGeneration
                    || revision != beforeRevision || lastCoreSequence != beforeSequence || value() != beforeHash) {
                throw new IllegalStateException("stale or foreign business hash transition");
            }
            long nextGeneration = Math.incrementExact(ownerGeneration);
            staged.apply();
            revision = afterRevision;
            lastCoreSequence = afterSequence;
            valueDirty = true;
            if (value() != afterHash) {
                staged.rollbackApplied();
                revision = beforeRevision;
                lastCoreSequence = beforeSequence;
                valueDirty = true;
                throw new IllegalStateException("business hash transition after-value mismatch");
            }
            ownerGeneration = nextGeneration;
            committedGeneration = ownerGeneration;
            state = TransitionState.COMMITTED;
        }
        public void rollback() {
            rollbackOn(owner);
        }
        private void rollbackOn(RollingBusinessStateHash target) {
            boolean applied = state == TransitionState.APPLIED;
            if (!applied) requireState(TransitionState.COMMITTED, "rollback");
            long expectedGeneration = applied ? preparedGeneration : committedGeneration;
            if (owner != target || ownerGeneration != expectedGeneration
                    || revision != afterRevision || lastCoreSequence != afterSequence || value() != afterHash) {
                throw new IllegalStateException("stale or foreign committed business hash transition");
            }
            long nextGeneration = Math.incrementExact(ownerGeneration);
            staged.rollbackApplied();
            revision = beforeRevision;
            lastCoreSequence = beforeSequence;
            valueDirty = true;
            if (value() != beforeHash) throw new IllegalStateException("business hash rollback mismatch");
            ownerGeneration = nextGeneration;
            state = TransitionState.ROLLED_BACK;
        }
        private void requireState(TransitionState expected, String operation) {
            if (state != expected) {
                throw new IllegalStateException("business hash transition cannot " + operation + " from " + state);
            }
        }
    }

    void commitForTest(HashTransition transition) {
        if (transition == null) throw new IllegalArgumentException("business hash transition is required");
        transition.commitOn(this);
    }

    void rollbackForTest(HashTransition transition) {
        if (transition == null) throw new IllegalArgumentException("business hash transition is required");
        transition.rollbackOn(this);
    }

    private enum TransitionState { PREPARED, APPLIED, COMMITTED, ROLLED_BACK }

    private BusinessPatchStage stagePatch(RuntimeCommitView patch) {
        if (patch == null || patch.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid business hash commit");
        }
        if (lastCoreSequence != Long.MIN_VALUE
                && (patch.previousCoreSequence() < lastCoreSequence
                || patch.coreSequence() <= lastCoreSequence)) {
            throw new IllegalArgumentException("non-monotonic business hash commit sequence: last "
                    + lastCoreSequence + ", previous " + patch.previousCoreSequence()
                    + ", current " + patch.coreSequence());
        }
        if (patch.beforeRevision() != revision) {
            throw new IllegalArgumentException("business hash commit before-value mismatch");
        }
        BusinessPatchStage staged = new BusinessPatchStage();
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            UserGroupUpdate userStage = new UserGroupUpdate(group.laneId());
            pendingBeforeCountsScratch.clear();
            reservationChangesScratch.clear();
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                pendingBeforeCountsScratch.put(change.userId(), change.pendingReservationCountAfter());
            }
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                reservationChangesScratch.put(change.orderId(), change);
                if (change.pendingAfter() && change.after() != null) {
                    long userId = change.after().userId();
                    pendingBeforeCountsScratch.put(
                            userId, Math.addExact(pendingBeforeCountsScratch.get(userId), -1));
                }
                if (change.pendingBefore() && change.before() != null) {
                    long userId = change.before().userId();
                    pendingBeforeCountsScratch.put(
                            userId, Math.addExact(pendingBeforeCountsScratch.get(userId), 1));
                }
            }
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                UserHash current = userHashes.get(change.userId());
                if ((current != null) != (change.before() != null)) {
                    throw new IllegalArgumentException("business user before-value mismatch");
                }
                userStage.rollbackOwner(change.userId(), current == null ? group.laneId() : current.owner,
                        pendingBeforeCountsScratch.get(change.userId()));
                userStage.append(change);
            }
            for (RuntimeCommitPatch.BalanceChange change : group.balances()) {
                String asset = identities.asset(change.key().assetId());
                UserHash user = userHashes.get(change.key().userId());
                Long actual = user == null ? null : user.balanceContribution(change.key().assetId());
                Long expected = change.before() == null ? null
                        : entryHashStable(asset, stableBalance(asset, change.before()));
                requireContribution(actual, expected, "balance");
                if (change.after() != null) stableBalance(asset, change.after());
                userStage.append(change);
            }
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                ReservationRuntime before = change.before();
                UserHash user = before == null ? null : userHashes.get(before.userId());
                Long actual = user == null ? null : user.reservationContribution(change.orderId());
                Long expected = before == null || before.reservedUnits() == 0 || change.pendingBefore() ? null
                        : entryHashStable(change.orderId(), stableReservation(before, identities));
                requireContribution(actual, expected, "reservation");
                if (change.after() != null) stableReservation(change.after(), identities);
                userStage.append(change);
            }
            for (RuntimeCommitPatch.PositionChange change : group.positions()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.positionKey());
                PositionRuntime before = change.before();
                UserHash user = before == null ? null : userHashes.get(before.userId());
                Long actual = user == null ? null : user.positionContribution(change.positionKey());
                Long expected = before == null ? null : entryHashStable(
                        identity.positionKey(), stablePosition(before, identities));
                requireContribution(actual, expected, "position");
                if (change.after() != null) stablePosition(change.after(), identities);
                userStage.append(change);
            }
            for (RuntimeCommitPatch.OrderChange change : group.orders()) {
                RuntimeCommitPatch.ReservationChange reservationChange =
                        reservationChangesScratch.get(change.orderId());
                boolean pendingBefore = reservationChange != null && reservationChange.pendingBefore();
                boolean pendingAfter = reservationChange != null && reservationChange.pendingAfter();
                OwnedContribution owned = orderContributions.get(change.orderId());
                Long actual = owned == null ? null : owned.value();
                Long expected = change.before() == null || change.before().status().terminal() || pendingBefore ? null
                        : entryHashStable(change.orderId(), stableOrder(change.before(), identities));
                requireContribution(actual, expected, "order");
                if (change.after() != null) stableOrder(change.after(), identities);
                int previousOwner = owned == null ? group.laneId() : owned.owner();
                staged.addOrder(group.laneId(), change, !pendingAfter,
                        previousOwner, !pendingBefore);
            }
            for (RuntimeCommitPatch.LeverageChange change : group.leverages()) {
                validateCachedBefore("leverages", change.key(), change.before(), value -> stable(value),
                        ignored -> true);
                int previousOwner = contributionOwner("leverages", change.key(), group.laneId());
                staged.addTyped(BusinessPatchStage.LEVERAGE, group.laneId(), previousOwner, null, change);
            }
            for (RuntimeCommitPatch.AlgoOrderChange change : group.algoOrders()) {
                validateCachedBefore("algo", change.algoOrderId(), change.before(), value -> stable(value),
                        value -> !value.terminal());
                int previousOwner = contributionOwner("algo", change.algoOrderId(), group.laneId());
                staged.addTyped(BusinessPatchStage.ALGO, group.laneId(), previousOwner, null, change);
            }
            for (RuntimeCommitPatch.TimerChange change : group.timers()) {
                validateCachedBefore("timers", change.key(), change.before(), value -> stable(value),
                        ignored -> true);
                int previousOwner = contributionOwner("timers", change.key(), group.laneId());
                staged.addTyped(BusinessPatchStage.TIMER, group.laneId(), previousOwner, null, change);
            }
            for (RuntimeCommitPatch.TriggerOrderChange change : group.triggerOrders()) {
                validateCachedBefore("triggers", change.triggerOrderId(), change.before(), value -> stable(value),
                        value -> value.status().open());
                int previousOwner = contributionOwner("triggers", change.triggerOrderId(), group.laneId());
                staged.addTyped(BusinessPatchStage.TRIGGER, group.laneId(), previousOwner, null, change);
            }
            for (RuntimeCommitPatch.RiskSnapshotChange change : group.riskSnapshots()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.riskKey());
                validateCachedBefore("snapshots",
                        new PositionContributionKey(identity.userId(), identity.positionKey()),
                        change.before(), this::stableRiskSnapshot, ignored -> true);
                PositionContributionKey key = new PositionContributionKey(identity.userId(), identity.positionKey());
                int previousOwner = contributionOwner("snapshots", key, group.laneId());
                staged.addTyped(BusinessPatchStage.RISK_SNAPSHOT,
                        group.laneId(), previousOwner, key, change);
            }
            for (RuntimeCommitPatch.LiquidationChange change : group.liquidations()) {
                validateCachedBefore("liquidations", change.liquidationId(), change.before(),
                        this::stableLiquidation, value -> !runtimeTerminal(value));
                int previousOwner = contributionOwner("liquidations", change.liquidationId(), group.laneId());
                staged.addTyped(BusinessPatchStage.LIQUIDATION,
                        group.laneId(), previousOwner, null, change);
            }
            staged.addUserGroup(userStage);
        }
        RuntimeCommitPatch.GlobalOwnerGroup global = patch.globalOwnerGroup();
        for (RuntimeCommitPatch.InstrumentChange change : global.instruments()) {
            validateCachedBefore("instruments", change.symbol(), change.before(), value -> stable(value),
                    ignored -> true);
            staged.addTyped(BusinessPatchStage.INSTRUMENT,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, null, change);
        }
        for (RuntimeCommitPatch.MarkPriceChange change : patch.globalOwnerGroup().markPrices()) {
            String symbol = identities.symbol(change.symbolId());
            validateCachedBefore("marks", symbol, change.before(), this::stableMark, ignored -> true);
            staged.addTyped(BusinessPatchStage.MARK,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, symbol, change);
        }
        for (RuntimeCommitPatch.RiskScanChange change : patch.globalOwnerGroup().riskScans()) {
            String symbol = identities.symbol(change.symbolId());
            validateCachedBefore("scans", symbol, change.before(), this::stableRiskScan, ignored -> true);
            staged.addTyped(BusinessPatchStage.RISK_SCAN,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, symbol, change);
        }
        for (RuntimeCommitPatch.TreasuryAssetChange change : patch.globalOwnerGroup().treasuryAssets()) {
            identities.asset(change.assetId());
            if (!java.util.Objects.equals(runtimeTreasury.get(change.assetId()), change.before())) {
                throw new IllegalArgumentException("business treasury before-value mismatch");
            }
            staged.addTyped(BusinessPatchStage.TREASURY_ASSET,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, null, change);
        }
        for (RuntimeCommitPatch.TreasuryFundingChange change : global.treasuryFunding()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryFundingValue before = change.before();
            validateCachedBefore("fundingSettlements", symbol,
                    before == null || before.settlementId() == 0 ? null : before.settlementId(),
                    value -> stable(value), ignored -> true);
            validateCachedBefore("fundingProgress", symbol, before == null ? null : before.progress(),
                    this::stableFundingProgress, ignored -> true);
            staged.addTyped(BusinessPatchStage.TREASURY_FUNDING,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, null, change);
        }
        for (RuntimeCommitPatch.TreasuryLifecycleChange change : global.treasuryLifecycle()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryLifecycleValue before = change.before();
            validateCachedBefore("lifecycleSettlements", symbol,
                    before == null || before.settlementId() == 0 ? null : before.settlementId(),
                    value -> stable(value), ignored -> true);
            validateCachedBefore("lifecycleProgress", symbol, before == null ? null : before.progress(),
                    this::stableLifecycleProgress, ignored -> true);
            staged.addTyped(BusinessPatchStage.TREASURY_LIFECYCLE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, null, change);
        }
        if (global.nextLiquidationId() != null
                && global.nextLiquidationId().before() != nextLiquidationId) {
            throw new IllegalArgumentException("business next-liquidation-id before-value mismatch");
        }
        if (global.riskScanControl() != null
                && stable(global.riskScanControl().before()) != riskScanControlHash) {
            throw new IllegalArgumentException("business risk-scan-control before-value mismatch");
        }
        if (global.nextLiquidationId() != null) {
            staged.addTyped(BusinessPatchStage.NEXT_LIQUIDATION_ID,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, null, global.nextLiquidationId());
        }
        if (global.riskScanControl() != null) {
            long after = stable(global.riskScanControl().after());
            long before = riskScanControlHash;
            staged.addTyped(BusinessPatchStage.RISK_SCAN_CONTROL,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, before, after);
        }
        return staged;
    }

    private <K, V> void validateCachedBefore(String domain, K key, V before,
                                             java.util.function.ToLongFunction<V> stableValue,
                                             Predicate<V> included) {
        OwnedContribution owned = contributions.get(contributionKey(domain, key));
        Long actual = owned == null ? null : owned.value();
        Long expected = before == null || !included.test(before) ? null
                : entryHashStable(key, stableValue.applyAsLong(before));
        requireContribution(actual, expected, domain);
    }

    private int contributionOwner(String domain, Object key, int fallback) {
        OwnedContribution owned = contributions.get(contributionKey(domain, key));
        return owned == null ? fallback : owned.owner();
    }

    private static void requireContribution(Long actual, Long expected, String domain) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new IllegalArgumentException("business " + domain + " before-value mismatch");
        }
    }

    public void restore(TradingCoreState state) {
        long nextGeneration = Math.incrementExact(ownerGeneration);
        revision = state.revision();
        nextLiquidationId = state.riskState().nextLiquidationId();
        riskScanControlHash = stable(state.riskState().scanControl());
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
        long hash = CoreStateHash.start();
        hash = CoreStateHash.mix(hash, productLine);
        hash = CoreStateHash.mix(hash, revision);
        hash = mixOwnerDomain(hash, "users", Domain.USERS);
        hash = mixOwnerDomain(hash, "orders", Domain.ORDERS);
        hash = mixOwnerDomain(hash, "instruments", Domain.INSTRUMENTS);
        hash = mixOwnerDomain(hash, "leverages", Domain.LEVERAGES);
        hash = mixOwnerDomain(hash, "algo", Domain.ALGO);
        hash = mixOwnerDomain(hash, "timers", Domain.TIMERS);
        hash = mixOwnerDomain(hash, "triggers", Domain.TRIGGERS);
        hash = mixOwnerDomain(hash, "markPrices", Domain.MARKS);
        hash = mixOwnerDomain(hash, "riskSnapshots", Domain.SNAPSHOTS);
        hash = mixOwnerDomain(hash, "liquidations", Domain.LIQUIDATIONS);
        hash = mixOwnerDomain(hash, "riskScans", Domain.SCANS);
        hash = CoreStateHash.mix(hash, nextLiquidationId);
        hash = CoreStateHash.mix(hash, riskScanControlHash);
        hash = mixOwnerDomain(hash, "feeBalances", Domain.FEES);
        hash = mixOwnerDomain(hash, "insuranceBalances", Domain.INSURANCE);
        hash = mixOwnerDomain(hash, "insuranceDeficits", Domain.DEFICITS);
        hash = mixOwnerDomain(hash, "liquidationFeeBalances", Domain.LIQUIDATION_FEES);
        hash = mixOwnerDomain(hash, "fundingResidualBalances", Domain.FUNDING_RESIDUALS);
        hash = mixOwnerDomain(hash, "roundingResidualBalances", Domain.ROUNDING_RESIDUALS);
        hash = mixOwnerDomain(hash, "clearingPnlBalances", Domain.CLEARING_PNL);
        hash = mixOwnerDomain(hash, "fundingSettlements", Domain.FUNDING_SETTLEMENTS);
        hash = mixOwnerDomain(hash, "lifecycleSettlements", Domain.LIFECYCLE_SETTLEMENTS);
        hash = mixOwnerDomain(hash, "fundingProgress", Domain.FUNDING_PROGRESS);
        cachedValue = mixOwnerDomain(hash, "lifecycleProgress", Domain.LIFECYCLE_PROGRESS);
        valueDirty = false;
        return cachedValue;
    }

    private void rebuild(TradingCoreState state) {
        contributions.clear();
        clearOwnerDomains();
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
        setOwnerAggregate(Integer.MAX_VALUE, Domain.FEES, feeBalances);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.INSURANCE, insuranceBalances);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.DEFICITS, insuranceDeficits);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.LIQUIDATION_FEES, liquidationFeeBalances);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.FUNDING_RESIDUALS, fundingResidualBalances);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.ROUNDING_RESIDUALS, roundingResidualBalances);
        setOwnerAggregate(Integer.MAX_VALUE, Domain.CLEARING_PNL, clearingPnlBalances);
        rebuildCached("fundingSettlements", fundingSettlements,
                state.treasuryState().fundingSettlements(), ignored -> true);
        rebuildCached("lifecycleSettlements", lifecycleSettlements,
                state.treasuryState().lifecycleSettlements(), ignored -> true);
        rebuildCached("fundingProgress", fundingProgress,
                state.treasuryState().fundingProgress(), ignored -> true);
        rebuildCached("lifecycleProgress", lifecycleProgress,
                state.treasuryState().lifecycleProgress(), ignored -> true);
        rebuildRuntimeTreasury(state.treasuryState());
        lastCoreSequence = Long.MIN_VALUE;
        valueDirty = true;
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
            int owner = restoredOwner(domain(domain));
            contributions.put(contributionKey(domain, key), new OwnedContribution(owner, contribution));
            target.add(contribution);
            addOwner(owner, domain(domain), contribution);
        });
    }

    private <K, V> void updateCachedValue(int owner, String domain, Aggregate target, K key, V current,
                                          java.util.function.ToLongFunction<V> stableValue,
                                          Predicate<V> included) {
        OwnedContribution previous = contributions.remove(contributionKey(domain, key));
        Domain typedDomain = domain(domain);
        if (previous != null) {
            target.remove(previous.value());
            removeOwner(previous.owner(), typedDomain, previous.value());
        }
        if (current != null && included.test(current)) {
            long contribution = entryHashStable(key, stableValue.applyAsLong(current));
            contributions.put(contributionKey(domain, key), new OwnedContribution(owner, contribution));
            target.add(contribution);
            addOwner(owner, typedDomain, contribution);
        }
    }

    private <K, V> void updateRuntimeContribution(int owner, String domain, Aggregate target, K key, V current,
                                                   java.util.function.ToLongFunction<V> stableValue,
                                                   Predicate<V> included) {
        updateCachedValue(owner, domain, target, key, current, stableValue, included);
    }

    private static ContributionKey contributionKey(String domain, Object key) {
        Object typedKey = key;
        if ("snapshots".equals(domain) && key instanceof String text) {
            int separator = text.indexOf(':');
            if (separator > 0) {
                typedKey = new PositionContributionKey(Long.parseLong(text.substring(0, separator)),
                        text.substring(separator + 1));
            }
        }
        return new ContributionKey(domain, typedKey);
    }

    private record ContributionKey(String domain, Object key) {}
    private record PositionContributionKey(long userId, String positionKey) {}

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
            long contribution = entryHash(userId, hash.value());
            users.add(contribution);
            addOwner(-1, Domain.USERS, contribution);
        });
    }

    private UserHash beginUserChange(
            org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserHash> changed,
            long userId) {
        if (changed.containsKey(userId)) return changed.get(userId);
        UserHash hash = userHashes.get(userId);
        if (hash != null) {
            long contribution = entryHash(userId, hash.value());
            users.remove(contribution);
            removeOwner(hash.owner, Domain.USERS, contribution);
            hash = hash.copy();
        }
        changed.put(userId, hash);
        return hash;
    }

    private void rebuildOrders(Map<Long, CoreOrderState> values) {
        orders.clear();
        orderContributions.clear();
        values.forEach((orderId, order) -> {
            if (order.status().terminal()) return;
            long contribution = entryHash(orderId, order);
            orderContributions.put(orderId, new OwnedContribution(-1, contribution));
            orders.add(contribution);
            addOwner(-1, Domain.ORDERS, contribution);
        });
    }

    private void updateOrder(int owner, RuntimeCommitPatch.OrderChange change, boolean visible,
                             boolean reverse) {
        OwnedContribution previous = orderContributions.remove(change.orderId());
        if (previous != null) {
            orders.remove(previous.value());
            removeOwner(previous.owner(), Domain.ORDERS, previous.value());
        }
        OrderRuntime current = reverse ? change.before() : change.after();
        if (current != null && !current.status().terminal() && visible) {
            long contribution = entryHashStable(change.orderId(), stableOrder(current, identities));
            orderContributions.put(change.orderId(), new OwnedContribution(owner, contribution));
            orders.add(contribution);
            addOwner(owner, Domain.ORDERS, contribution);
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
            runtimeTreasury.put(identities.assetId(asset), new RuntimeCommitPatch.TreasuryAssetValue(
                    treasury.feeBalances().getOrDefault(asset, 0L),
                    treasury.insuranceBalances().getOrDefault(asset, 0L),
                    treasury.insuranceDeficits().getOrDefault(asset, 0L),
                    treasury.liquidationFeeBalances().getOrDefault(asset, 0L),
                    treasury.fundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.roundingResidualBalances().getOrDefault(asset, 0L),
                    treasury.clearingPnlBalances().getOrDefault(asset, 0L)));
        }
    }

    private void updateTreasuryAsset(RuntimeCommitPatch.TreasuryAssetChange change, boolean reverse) {
        String asset = identities.asset(change.assetId());
        RuntimeCommitPatch.TreasuryAssetValue previous = reverse ? change.after() : change.before();
        RuntimeCommitPatch.TreasuryAssetValue current = reverse ? change.before() : change.after();
        update(feeBalances, Domain.FEES, asset, patchFee(previous), patchFee(current));
        update(insuranceBalances, Domain.INSURANCE, asset, patchInsurance(previous), patchInsurance(current));
        update(insuranceDeficits, Domain.DEFICITS, asset, patchDeficit(previous), patchDeficit(current));
        update(liquidationFeeBalances, Domain.LIQUIDATION_FEES, asset,
                patchLiquidationFee(previous), patchLiquidationFee(current));
        update(fundingResidualBalances, Domain.FUNDING_RESIDUALS, asset,
                patchFundingResidual(previous), patchFundingResidual(current));
        update(roundingResidualBalances, Domain.ROUNDING_RESIDUALS, asset,
                patchRoundingResidual(previous), patchRoundingResidual(current));
        update(clearingPnlBalances, Domain.CLEARING_PNL, asset,
                patchClearingPnl(previous), patchClearingPnl(current));
        if (current == null) runtimeTreasury.remove(change.assetId());
        else runtimeTreasury.put(change.assetId(), current);
    }

    private void updateTreasuryFunding(RuntimeCommitPatch.TreasuryFundingChange change, boolean reverse) {
        String symbol = identities.symbol(change.symbolId());
        RuntimeCommitPatch.TreasuryFundingValue current = reverse ? change.before() : change.after();
        updateRuntimeContribution(Integer.MAX_VALUE, "fundingSettlements", fundingSettlements, symbol,
                current == null || current.settlementId() == 0 ? null : current.settlementId(),
                number -> stable(number), ignored -> true);
        updateRuntimeContribution(Integer.MAX_VALUE, "fundingProgress", fundingProgress, symbol,
                current == null ? null : current.progress(), this::stableFundingProgress, ignored -> true);
    }

    private void updateTreasuryLifecycle(RuntimeCommitPatch.TreasuryLifecycleChange change, boolean reverse) {
        String symbol = identities.symbol(change.symbolId());
        RuntimeCommitPatch.TreasuryLifecycleValue current = reverse ? change.before() : change.after();
        updateRuntimeContribution(Integer.MAX_VALUE, "lifecycleSettlements", lifecycleSettlements, symbol,
                current == null || current.settlementId() == 0 ? null : current.settlementId(),
                number -> stable(number), ignored -> true);
        updateRuntimeContribution(Integer.MAX_VALUE, "lifecycleProgress", lifecycleProgress, symbol,
                current == null ? null : current.progress(), this::stableLifecycleProgress, ignored -> true);
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

    private static long patchFee(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.fee(); }
    private static long patchInsurance(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.insurance(); }
    private static long patchDeficit(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.deficit(); }
    private static long patchLiquidationFee(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.liquidationFee(); }
    private static long patchFundingResidual(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.fundingResidual(); }
    private static long patchRoundingResidual(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.roundingResidual(); }
    private static long patchClearingPnl(RuntimeCommitPatch.TreasuryAssetValue value) { return value == null ? 0 : value.clearingPnl(); }

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
        if (value instanceof PositionContributionKey key) return stablePositionContributionKey(key);
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

    private static long stablePositionContributionKey(PositionContributionKey key) {
        long base = CoreStateHash.mix(CoreStateHash.start(), String.class.getName());
        String user = Long.toString(key.userId());
        String position = key.positionKey();
        int characterCount = user.length() + 1 + position.length();
        boolean ascii = true;
        for (int index = 0; index < position.length(); index++) {
            if (position.charAt(index) > 0x7f) { ascii = false; break; }
        }
        long hash = CoreStateHash.mix(base, characterCount);
        for (int index = 0; index < user.length(); index++) hash = mixAscii(hash, user.charAt(index));
        hash = mixAscii(hash, ':');
        if (ascii) {
            for (int index = 0; index < position.length(); index++) hash = mixAscii(hash, position.charAt(index));
            return hash;
        }
        for (byte item : position.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash = (hash ^ Byte.toUnsignedInt(item)) * 0x100000001b3L;
        }
        return hash;
    }

    private static long mixAscii(long hash, char value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static long stableMark(CoreMarkPriceState value) {
        return canonical(CoreMarkPriceState.class).text(value.symbol()).number(value.instrumentVersion())
                .number(value.markPriceTicks()).number(value.priceSequence())
                .number(value.generatedAtEpochMillis()).value();
    }

    private long stableMark(MarkPriceRuntime value) {
        return canonical(CoreMarkPriceState.class).text(identities.symbol(value.symbolId()))
                .number(value.instrumentVersion()).number(value.markPriceTicks()).number(value.priceSequence())
                .number(value.generatedAtEpochMillis()).value();
    }

    private static long stableRiskSnapshot(CoreRiskSnapshot value) {
        return canonical(CoreRiskSnapshot.class).number(value.userId()).text(value.symbol())
                .enumeration(value.positionSide()).number(value.priceSequence()).number(value.equityUnits())
                .number(value.unrealizedPnlUnits()).number(value.maintenanceMarginUnits())
                .number(value.marginRatioPpm()).enumeration(value.status()).value();
    }

    private long stableRiskSnapshot(RiskSnapshotRuntime value) {
        return canonical(CoreRiskSnapshot.class).number(value.userId()).text(identities.symbol(value.symbolId()))
                .enumeration(value.positionSide()).number(value.priceSequence()).number(value.equityUnits())
                .number(value.unrealizedPnlUnits()).number(value.maintenanceMarginUnits())
                .number(value.marginRatioPpm()).enumeration(value.status()).value();
    }

    private static long stableLiquidation(CoreLiquidationState value) {
        return canonical(CoreLiquidationState.class).number(value.liquidationId()).number(value.userId())
                .text(value.symbol()).enumeration(value.marginMode()).enumeration(value.positionSide())
                .number(value.instrumentVersion()).number(value.triggerPriceSequence())
                .number(value.signedQuantitySteps()).number(value.closeQuantitySteps()).number(value.deficitUnits())
                .number(value.executionPriceTicks()).number(value.liquidationFeeRatePpm())
                .number(value.liquidationFeeUnits()).enumeration(value.status()).number(value.nextCancelOrderId())
                .value();
    }

    private long stableLiquidation(LiquidationRuntime value) {
        return canonical(CoreLiquidationState.class).number(value.liquidationId()).number(value.userId())
                .text(identities.symbol(value.symbolId())).enumeration(value.marginMode())
                .enumeration(value.positionSide()).number(value.instrumentVersion())
                .number(value.triggerPriceSequence()).number(value.signedQuantitySteps())
                .number(value.closeQuantitySteps()).number(value.deficitUnits()).number(value.executionPriceTicks())
                .number(value.liquidationFeeRatePpm()).number(value.liquidationFeeUnits())
                .enumeration(value.status()).number(value.nextCancelOrderId()).value();
    }

    private static boolean runtimeTerminal(LiquidationRuntime value) {
        return value.status() == CoreLiquidationState.Status.CANCELED
                || value.status() == CoreLiquidationState.Status.COMPLETED && value.deficitUnits() == 0;
    }

    private static long stableRiskScan(CoreRiskState.RiskScan value) {
        return riskScanHasher(value.symbol(), value.accountLaneId(), value.priceSequence(),
                value.scanStartPriceSequence(), value.lastUserId(), value.riskComplete(), value.riskUserId(),
                value.riskPhase(), value.riskPositionCursor(), value.riskReservationCursor(),
                value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(), value.riskIsolatedMarginUnits(),
                value.riskIsolatedReservationUnits(), value.triggerComplete(), value.triggerPhase(),
                value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private long stableRiskScan(RiskScanRuntime value) {
        return riskScanHasher(identities.symbol(value.symbolId()), value.accountLaneId(), value.priceSequence(),
                value.scanStartPriceSequence(), value.lastUserId(), value.riskComplete(), value.riskUserId(),
                value.riskPhase(), value.riskPositionCursor(), value.riskReservationCursor(),
                value.riskUnrealizedPnlUnits(), value.riskMaintenanceMarginUnits(), value.riskIsolatedMarginUnits(),
                value.riskIsolatedReservationUnits(), value.triggerComplete(), value.triggerPhase(),
                value.triggerPriceCursor(), value.triggerOrderCursor(), value.triggerUpperId(),
                value.triggerMarkPriceTicks(), value.triggerGeneratedAtEpochMillis(), value.triggerOcoOrderId(),
                value.triggerOcoCursor());
    }

    private static long riskScanHasher(String symbol, int accountLaneId, long priceSequence,
                                       long scanStartPriceSequence, long lastUserId, boolean riskComplete,
                                       long riskUserId, int riskPhase, String riskPositionCursor,
                                       long riskReservationCursor, long riskUnrealizedPnlUnits,
                                       long riskMaintenanceMarginUnits, long riskIsolatedMarginUnits,
                                       long riskIsolatedReservationUnits, boolean triggerComplete,
                                       int triggerPhase, long triggerPriceCursor, long triggerOrderCursor,
                                       long triggerUpperId, long triggerMarkPriceTicks,
                                       long triggerGeneratedAtEpochMillis, long triggerOcoOrderId,
                                       long triggerOcoCursor) {
        return canonical(CoreRiskState.RiskScan.class).text(symbol).number(accountLaneId).number(priceSequence)
                .number(scanStartPriceSequence).number(lastUserId).flag(riskComplete).number(riskUserId)
                .number(riskPhase).text(riskPositionCursor).number(riskReservationCursor)
                .number(riskUnrealizedPnlUnits).number(riskMaintenanceMarginUnits)
                .number(riskIsolatedMarginUnits).number(riskIsolatedReservationUnits).flag(triggerComplete)
                .number(triggerPhase).number(triggerPriceCursor).number(triggerOrderCursor)
                .number(triggerUpperId).number(triggerMarkPriceTicks).number(triggerGeneratedAtEpochMillis)
                .number(triggerOcoOrderId).number(triggerOcoCursor).value();
    }

    private static long stableFundingProgress(CoreTreasuryState.FundingProgress value) {
        return fundingProgressHasher(value.settlementId(), value.instrumentVersion(), value.fundingRatePpm(),
                value.accountLaneId(), value.nextCursorUserId(), value.commandId());
    }

    private long stableFundingProgress(TreasuryRuntime.FundingProgressRuntime value) {
        return fundingProgressHasher(value.settlementId(), value.instrumentVersion(), value.fundingRatePpm(),
                value.accountLaneId(), value.nextCursorUserId(), value.commandId());
    }

    private static long fundingProgressHasher(long settlementId, long instrumentVersion, long fundingRatePpm,
                                              int accountLaneId, long nextCursorUserId, Object commandId) {
        return canonical(CoreTreasuryState.FundingProgress.class).number(settlementId).number(instrumentVersion)
                .number(fundingRatePpm).number(accountLaneId).number(nextCursorUserId).text(String.valueOf(commandId))
                .value();
    }

    private static long stableLifecycleProgress(CoreTreasuryState.LifecycleProgress value) {
        return lifecycleProgressHasher(value.settlementId(), value.instrumentVersion(), value.settlementPriceTicks(),
                value.optionCashUnitsPerContract(), value.ordersComplete(), value.accountLaneId(),
                value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId());
    }

    private long stableLifecycleProgress(TreasuryRuntime.LifecycleProgressRuntime value) {
        return lifecycleProgressHasher(value.settlementId(), value.instrumentVersion(), value.settlementPriceTicks(),
                value.optionCashUnitsPerContract(), value.ordersComplete(), value.accountLaneId(),
                value.nextCursorOrderId(), value.nextCursorUserId(), value.commandId());
    }

    private static long lifecycleProgressHasher(long settlementId, long instrumentVersion,
                                                long settlementPriceTicks, long optionCashUnitsPerContract,
                                                boolean ordersComplete, int accountLaneId, long nextCursorOrderId,
                                                long nextCursorUserId, Object commandId) {
        return canonical(CoreTreasuryState.LifecycleProgress.class).number(settlementId).number(instrumentVersion)
                .number(settlementPriceTicks).number(optionCashUnitsPerContract).flag(ordersComplete)
                .number(accountLaneId).number(nextCursorOrderId).number(nextCursorUserId)
                .text(String.valueOf(commandId)).value();
    }

    private static CanonicalHasher canonical(Class<?> type) {
        return new CanonicalHasher(CoreStateHash.mix(CoreStateHash.start(), type.getName()));
    }

    private static final class CanonicalHasher {
        private long hash;

        private CanonicalHasher(long hash) { this.hash = hash; }
        private CanonicalHasher number(long value) { hash = CoreStateHash.mix(hash, value); return this; }
        private CanonicalHasher flag(boolean value) { hash = CoreStateHash.mix(hash, value); return this; }
        private CanonicalHasher enumeration(Enum<?> value) {
            hash = CoreStateHash.mix(hash, value.ordinal());
            return this;
        }
        private CanonicalHasher text(String value) { hash = CoreStateHash.mix(hash, value); return this; }
        private long value() { return hash; }
    }

    private static long mixAggregate(long hash, String name, Aggregate aggregate) {
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

    private void setOwnerAggregate(int owner, Domain domain, Aggregate source) {
        Aggregate target = ownerDomains[ownerIndex(owner)].aggregate(domain);
        target.count = source.count;
        target.sum = source.sum;
        target.xor = source.xor;
    }

    private void removeOwner(int owner, Domain domain, long contribution) {
        ownerDomains[ownerIndex(owner)].aggregate(domain).remove(contribution);
    }

    private static int ownerIndex(int owner) {
        if (owner == -1) return RESTORED_OWNER;
        if (owner == Integer.MAX_VALUE) return GLOBAL_OWNER;
        if (owner < 0 || owner >= GLOBAL_OWNER) throw new IllegalArgumentException("invalid hash owner");
        return owner;
    }

    private static OwnerDomains[] ownerDomains() {
        OwnerDomains[] result = new OwnerDomains[RESTORED_OWNER + 1];
        for (int index = 0; index < result.length; index++) result[index] = new OwnerDomains();
        return result;
    }

    private void clearOwnerDomains() {
        for (OwnerDomains owner : ownerDomains) owner.clear();
    }

    private static Domain domain(String name) {
        return switch (name) {
            case "instruments" -> Domain.INSTRUMENTS;
            case "leverages" -> Domain.LEVERAGES;
            case "algo" -> Domain.ALGO;
            case "timers" -> Domain.TIMERS;
            case "triggers" -> Domain.TRIGGERS;
            case "marks" -> Domain.MARKS;
            case "snapshots" -> Domain.SNAPSHOTS;
            case "liquidations" -> Domain.LIQUIDATIONS;
            case "scans" -> Domain.SCANS;
            case "fundingSettlements" -> Domain.FUNDING_SETTLEMENTS;
            case "lifecycleSettlements" -> Domain.LIFECYCLE_SETTLEMENTS;
            case "fundingProgress" -> Domain.FUNDING_PROGRESS;
            case "lifecycleProgress" -> Domain.LIFECYCLE_PROGRESS;
            default -> throw new IllegalArgumentException("unknown hash domain: " + name);
        };
    }

    private static int restoredOwner(Domain domain) {
        return switch (domain) {
            case INSTRUMENTS, MARKS, SCANS, FEES, INSURANCE, DEFICITS, LIQUIDATION_FEES,
                    FUNDING_RESIDUALS, ROUNDING_RESIDUALS, CLEARING_PNL, FUNDING_SETTLEMENTS,
                    LIFECYCLE_SETTLEMENTS, FUNDING_PROGRESS, LIFECYCLE_PROGRESS -> Integer.MAX_VALUE;
            default -> -1;
        };
    }

    private static final class OwnerDomains {
        private final Aggregate[] domains = new Aggregate[Domain.values().length];

        private OwnerDomains() {
            for (int index = 0; index < domains.length; index++) domains[index] = new Aggregate();
        }

        private Aggregate aggregate(Domain domain) { return domains[domain.ordinal()]; }
        private void clear() { for (Aggregate aggregate : domains) aggregate.clear(); }
    }

    private final class BusinessPatchStage {
        private static final byte ORDER = 1;
        private static final byte USER_GROUP = 2;
        private static final byte LEVERAGE = 3;
        private static final byte ALGO = 4;
        private static final byte TIMER = 5;
        private static final byte TRIGGER = 6;
        private static final byte RISK_SNAPSHOT = 7;
        private static final byte LIQUIDATION = 8;
        private static final byte INSTRUMENT = 9;
        private static final byte MARK = 10;
        private static final byte RISK_SCAN = 11;
        private static final byte TREASURY_ASSET = 12;
        private static final byte TREASURY_FUNDING = 13;
        private static final byte TREASURY_LIFECYCLE = 14;
        private static final byte NEXT_LIQUIDATION_ID = 15;
        private static final byte RISK_SCAN_CONTROL = 16;

        private byte[] operationTypes = new byte[16];
        private Object[] values = new Object[16];
        private Object[] keys = new Object[16];
        private int[] owners = new int[16];
        private int[] rollbackOwners = new int[16];
        private boolean[] included = new boolean[16];
        private boolean[] rollbackIncluded = new boolean[16];
        private int operationCount;
        private int appliedCount;

        private void addTyped(byte type, int owner, int rollbackOwner, Object key, Object value) {
            ensureCapacity();
            operationTypes[operationCount] = type;
            owners[operationCount] = owner;
            rollbackOwners[operationCount] = rollbackOwner;
            keys[operationCount] = key;
            values[operationCount++] = value;
        }

        private void addOrder(int owner, RuntimeCommitPatch.OrderChange change, boolean include,
                              int rollbackOwner, boolean includeBefore) {
            int index = operationCount++;
            ensureCapacity();
            operationTypes[index] = ORDER;
            values[index] = change;
            owners[index] = owner;
            rollbackOwners[index] = rollbackOwner;
            included[index] = include;
            rollbackIncluded[index] = includeBefore;
        }

        private void addUserGroup(UserGroupUpdate update) {
            int index = operationCount++;
            ensureCapacity();
            operationTypes[index] = USER_GROUP;
            values[index] = update;
        }

        private int size() { return operationCount; }

        private long preview(java.util.function.LongSupplier value) {
            int applied = 0;
            try {
                for (; applied < operationCount; applied++) {
                    apply(applied);
                }
                return value.getAsLong();
            } finally {
                while (applied > 0) rollback(--applied);
                valueDirty = true;
            }
        }

        private void apply() {
            int applied = 0;
            try {
                for (int index = 0; index < operationCount; index++) {
                    apply(index);
                    applied++;
                    if (index == failAfterStagedOperation) {
                        failAfterStagedOperation = -1;
                        throw new IllegalStateException("injected mid-stage business hash apply failure");
                    }
                }
                appliedCount = applied;
            } catch (RuntimeException failure) {
                while (applied > 0) rollback(--applied);
                valueDirty = true;
                throw failure;
            }
        }

        private void rollbackApplied() {
            while (appliedCount > 0) rollback(--appliedCount);
            valueDirty = true;
        }

        private void apply(int index) {
            switch (operationTypes[index]) {
                case ORDER -> updateOrder(owners[index],
                        (RuntimeCommitPatch.OrderChange) values[index], included[index], false);
                case USER_GROUP -> ((UserGroupUpdate) values[index]).apply(false);
                case LEVERAGE -> applyLeverage(index, false);
                case ALGO -> applyAlgo(index, false);
                case TIMER -> applyTimer(index, false);
                case TRIGGER -> applyTrigger(index, false);
                case RISK_SNAPSHOT -> applyRiskSnapshot(index, false);
                case LIQUIDATION -> applyLiquidation(index, false);
                case INSTRUMENT -> applyInstrument(index, false);
                case MARK -> applyMark(index, false);
                case RISK_SCAN -> applyRiskScan(index, false);
                case TREASURY_ASSET -> updateTreasuryAsset(
                        (RuntimeCommitPatch.TreasuryAssetChange) values[index], false);
                case TREASURY_FUNDING -> updateTreasuryFunding(
                        (RuntimeCommitPatch.TreasuryFundingChange) values[index], false);
                case TREASURY_LIFECYCLE -> updateTreasuryLifecycle(
                        (RuntimeCommitPatch.TreasuryLifecycleChange) values[index], false);
                case NEXT_LIQUIDATION_ID -> nextLiquidationId =
                        ((RuntimeCommitPatch.NextLiquidationIdChange) values[index]).after();
                case RISK_SCAN_CONTROL -> riskScanControlHash = (long) values[index];
                default -> throw new IllegalStateException("unknown staged business hash operation");
            }
        }

        private void rollback(int index) {
            switch (operationTypes[index]) {
                case ORDER -> updateOrder(rollbackOwners[index],
                        (RuntimeCommitPatch.OrderChange) values[index], rollbackIncluded[index], true);
                case USER_GROUP -> ((UserGroupUpdate) values[index]).apply(true);
                case LEVERAGE -> applyLeverage(index, true);
                case ALGO -> applyAlgo(index, true);
                case TIMER -> applyTimer(index, true);
                case TRIGGER -> applyTrigger(index, true);
                case RISK_SNAPSHOT -> applyRiskSnapshot(index, true);
                case LIQUIDATION -> applyLiquidation(index, true);
                case INSTRUMENT -> applyInstrument(index, true);
                case MARK -> applyMark(index, true);
                case RISK_SCAN -> applyRiskScan(index, true);
                case TREASURY_ASSET -> updateTreasuryAsset(
                        (RuntimeCommitPatch.TreasuryAssetChange) values[index], true);
                case TREASURY_FUNDING -> updateTreasuryFunding(
                        (RuntimeCommitPatch.TreasuryFundingChange) values[index], true);
                case TREASURY_LIFECYCLE -> updateTreasuryLifecycle(
                        (RuntimeCommitPatch.TreasuryLifecycleChange) values[index], true);
                case NEXT_LIQUIDATION_ID -> nextLiquidationId =
                        ((RuntimeCommitPatch.NextLiquidationIdChange) values[index]).before();
                case RISK_SCAN_CONTROL -> riskScanControlHash = (long) keys[index];
                default -> throw new IllegalStateException("unknown staged business hash rollback operation");
            }
        }

        private int owner(int index, boolean reverse) {
            return reverse ? rollbackOwners[index] : owners[index];
        }

        private void applyLeverage(int index, boolean reverse) {
            RuntimeCommitPatch.LeverageChange change = (RuntimeCommitPatch.LeverageChange) values[index];
            updateCachedValue(owner(index, reverse), "leverages", leverages, change.key(),
                    reverse ? change.before() : change.after(), value -> stable(value), ignored -> true);
        }

        private void applyAlgo(int index, boolean reverse) {
            RuntimeCommitPatch.AlgoOrderChange change = (RuntimeCommitPatch.AlgoOrderChange) values[index];
            updateCachedValue(owner(index, reverse), "algo", algoOrders, change.algoOrderId(),
                    reverse ? change.before() : change.after(), value -> stable(value), value -> !value.terminal());
        }

        private void applyTimer(int index, boolean reverse) {
            RuntimeCommitPatch.TimerChange change = (RuntimeCommitPatch.TimerChange) values[index];
            updateCachedValue(owner(index, reverse), "timers", timers, change.key(),
                    reverse ? change.before() : change.after(), value -> stable(value), ignored -> true);
        }

        private void applyTrigger(int index, boolean reverse) {
            RuntimeCommitPatch.TriggerOrderChange change = (RuntimeCommitPatch.TriggerOrderChange) values[index];
            updateCachedValue(owner(index, reverse), "triggers", triggers, change.triggerOrderId(),
                    reverse ? change.before() : change.after(), value -> stable(value), value -> value.status().open());
        }

        private void applyRiskSnapshot(int index, boolean reverse) {
            RuntimeCommitPatch.RiskSnapshotChange change = (RuntimeCommitPatch.RiskSnapshotChange) values[index];
            updateRuntimeContribution(owner(index, reverse), "snapshots", riskSnapshots,
                    (PositionContributionKey) keys[index], reverse ? change.before() : change.after(),
                    RollingBusinessStateHash.this::stableRiskSnapshot, ignored -> true);
        }

        private void applyLiquidation(int index, boolean reverse) {
            RuntimeCommitPatch.LiquidationChange change = (RuntimeCommitPatch.LiquidationChange) values[index];
            updateRuntimeContribution(owner(index, reverse), "liquidations", liquidations,
                    change.liquidationId(), reverse ? change.before() : change.after(),
                    RollingBusinessStateHash.this::stableLiquidation, value -> !runtimeTerminal(value));
        }

        private void applyInstrument(int index, boolean reverse) {
            RuntimeCommitPatch.InstrumentChange change = (RuntimeCommitPatch.InstrumentChange) values[index];
            updateCachedValue(Integer.MAX_VALUE, "instruments", instruments, change.symbol(),
                    reverse ? change.before() : change.after(), value -> stable(value), ignored -> true);
        }

        private void applyMark(int index, boolean reverse) {
            RuntimeCommitPatch.MarkPriceChange change = (RuntimeCommitPatch.MarkPriceChange) values[index];
            updateRuntimeContribution(Integer.MAX_VALUE, "marks", markPrices, (String) keys[index],
                    reverse ? change.before() : change.after(), RollingBusinessStateHash.this::stableMark,
                    ignored -> true);
        }

        private void applyRiskScan(int index, boolean reverse) {
            RuntimeCommitPatch.RiskScanChange change = (RuntimeCommitPatch.RiskScanChange) values[index];
            updateRuntimeContribution(Integer.MAX_VALUE, "scans", riskScans, (String) keys[index],
                    reverse ? change.before() : change.after(), RollingBusinessStateHash.this::stableRiskScan,
                    ignored -> true);
        }

        private void ensureCapacity() {
            if (operationCount < operationTypes.length) return;
            int capacity = Math.multiplyExact(operationTypes.length, 2);
            operationTypes = java.util.Arrays.copyOf(operationTypes, capacity);
            values = java.util.Arrays.copyOf(values, capacity);
            keys = java.util.Arrays.copyOf(keys, capacity);
            owners = java.util.Arrays.copyOf(owners, capacity);
            rollbackOwners = java.util.Arrays.copyOf(rollbackOwners, capacity);
            included = java.util.Arrays.copyOf(included, capacity);
            rollbackIncluded = java.util.Arrays.copyOf(rollbackIncluded, capacity);
        }
    }

    private final class UserGroupUpdate {
        private static final byte USER = 1;
        private static final byte BALANCE = 2;
        private static final byte RESERVATION = 3;
        private static final byte POSITION = 4;

        private final int laneId;
        private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserHash> changed =
                new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap rollbackOwners =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap rollbackPendingCounts =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap();
        private Object[] operations = new Object[8];
        private byte[] operationTypes = new byte[8];
        private int operationCount;

        private UserGroupUpdate(int laneId) { this.laneId = laneId; }
        private void append(RuntimeCommitPatch.UserChange change) { append(USER, change); }
        private void append(RuntimeCommitPatch.BalanceChange change) { append(BALANCE, change); }
        private void append(RuntimeCommitPatch.ReservationChange change) { append(RESERVATION, change); }
        private void append(RuntimeCommitPatch.PositionChange change) { append(POSITION, change); }
        private void rollbackOwner(long userId, int owner, int pendingReservationCount) {
            rollbackOwners.put(userId, owner);
            rollbackPendingCounts.put(userId, pendingReservationCount);
        }

        private void append(byte type, Object change) {
            ensureOperationCapacity();
            operationTypes[operationCount] = type;
            operations[operationCount++] = change;
        }

        private void ensureOperationCapacity() {
            if (operationCount < operations.length) return;
            int capacity = Math.multiplyExact(operations.length, 2);
            operations = java.util.Arrays.copyOf(operations, capacity);
            operationTypes = java.util.Arrays.copyOf(operationTypes, capacity);
        }

        private void apply(RuntimeCommitPatch.UserChange change, boolean reverse) {
            UserHash hash = beginUserChange(changed, change.userId());
            UserRuntime current = reverse ? change.before() : change.after();
            if (current == null) changed.put(change.userId(), null);
            else if (hash == null) changed.put(change.userId(), UserHash.create(current));
            else hash.updateUser(current, reverse
                    ? rollbackPendingCounts.get(change.userId()) : change.pendingReservationCountAfter());
        }

        private void apply(RuntimeCommitPatch.BalanceChange change, boolean reverse) {
            UserHash hash = beginUserChange(changed, change.key().userId());
            if (hash != null) hash.updateBalance(change, identities, reverse);
        }

        private void apply(RuntimeCommitPatch.ReservationChange change, boolean reverse) {
            ReservationRuntime previous = reverse ? change.after() : change.before();
            ReservationRuntime current = reverse ? change.before() : change.after();
            long previousOwner = previous == null ? 0 : previous.userId();
            long currentOwner = current == null ? 0 : current.userId();
            if (previousOwner != 0) {
                UserHash hash = beginUserChange(changed, previousOwner);
                if (hash != null) hash.updateReservation(previousOwner, change, identities, reverse);
            }
            if (currentOwner != 0 && currentOwner != previousOwner) {
                UserHash hash = beginUserChange(changed, currentOwner);
                if (hash != null) hash.updateReservation(currentOwner, change, identities, reverse);
            }
        }

        private void apply(RuntimeCommitPatch.PositionChange change, boolean reverse) {
            PositionRuntime previous = reverse ? change.after() : change.before();
            PositionRuntime current = reverse ? change.before() : change.after();
            long previousOwner = previous == null ? 0 : previous.userId();
            long currentOwner = current == null ? 0 : current.userId();
            if (previousOwner != 0) {
                UserHash hash = beginUserChange(changed, previousOwner);
                if (hash != null) hash.updatePosition(previousOwner, change, identities, reverse);
            }
            if (currentOwner != 0 && currentOwner != previousOwner) {
                UserHash hash = beginUserChange(changed, currentOwner);
                if (hash != null) hash.updatePosition(currentOwner, change, identities, reverse);
            }
        }

        private void apply(boolean reverse) {
            int index = reverse ? operationCount - 1 : 0;
            int boundary = reverse ? -1 : operationCount;
            int increment = reverse ? -1 : 1;
            for (; index != boundary; index += increment) {
                switch (operationTypes[index]) {
                    case USER -> apply((RuntimeCommitPatch.UserChange) operations[index], reverse);
                    case BALANCE -> apply((RuntimeCommitPatch.BalanceChange) operations[index], reverse);
                    case RESERVATION -> apply((RuntimeCommitPatch.ReservationChange) operations[index], reverse);
                    case POSITION -> apply((RuntimeCommitPatch.PositionChange) operations[index], reverse);
                    default -> throw new IllegalStateException("unknown staged user hash operation");
                }
            }
            changed.forEachKeyValue((userId, hash) -> {
                if (hash == null) userHashes.remove(userId);
                else {
                    int owner = reverse ? rollbackOwners.getIfAbsent(userId, laneId) : laneId;
                    hash.owner = owner;
                    userHashes.put(userId, hash);
                    long contribution = entryHash(userId, hash.value());
                    users.add(contribution);
                    addOwner(owner, Domain.USERS, contribution);
                }
            });
            changed.clear();
        }
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

        private void copyFrom(Aggregate source) {
            count = source.count;
            sum = source.sum;
            xor = source.xor;
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

    }

    private static final class UserHash {
        private int owner = -1;
        private int productLine;
        private final long userId;
        private long revision;
        private int positionMode;
        private final Aggregate balances = new Aggregate();
        private final Aggregate reservations = new Aggregate();
        private final Aggregate positions = new Aggregate();
        private org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap balanceContributions =
                new org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap();
        private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap reservationContributions =
                new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap();
        private org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap positionContributions =
                new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap();
        private boolean balancesShared;
        private boolean reservationsShared;
        private boolean positionsShared;

        private UserHash(UserRuntime user) {
            productLine = user.productLine().ordinal();
            userId = user.userId();
            revision = user.revision();
            positionMode = user.positionMode().wireCode();
        }

        private UserHash(UserHash source) {
            owner = source.owner;
            productLine = source.productLine;
            userId = source.userId;
            revision = source.revision;
            positionMode = source.positionMode;
            balances.copyFrom(source.balances);
            reservations.copyFrom(source.reservations);
            positions.copyFrom(source.positions);
            balanceContributions = source.balanceContributions;
            reservationContributions = source.reservationContributions;
            positionContributions = source.positionContributions;
            balancesShared = true;
            reservationsShared = true;
            positionsShared = true;
        }

        private UserHash copy() { return new UserHash(this); }

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

        private void updateUser(UserRuntime user, int pendingReservationCount) {
            productLine = user.productLine().ordinal();
            revision = Math.subtractExact(user.revision(), pendingReservationCount);
            positionMode = user.positionMode().wireCode();
        }

        private void updateBalance(RuntimeCommitPatch.BalanceChange change,
                                   RuntimeIdentityRegistry identities, boolean reverse) {
            int assetId = change.key().assetId();
            ensureBalancesOwned();
            if (balanceContributions.containsKey(assetId)) {
                long previous = balanceContributions.get(assetId);
                balanceContributions.removeKey(assetId);
                balances.remove(previous);
            }
            RuntimeCommitPatch.UserBalance current = reverse ? change.before() : change.after();
            if (current != null) {
                String asset = identities.asset(assetId);
                long contribution = entryHashStable(asset, stableBalance(asset, current));
                balanceContributions.put(assetId, contribution);
                balances.add(contribution);
            }
        }

        private void updateReservation(long ownerId, RuntimeCommitPatch.ReservationChange change,
                                       RuntimeIdentityRegistry identities, boolean reverse) {
            boolean hadPrevious = reservationContributions.containsKey(change.orderId());
            long previous = hadPrevious ? reservationContributions.get(change.orderId()) : 0;
            ReservationRuntime current = reverse ? change.before() : change.after();
            boolean pendingCurrent = reverse ? change.pendingBefore() : change.pendingAfter();
            boolean includeCurrent = current != null && current.userId() == ownerId
                    && current.reservedUnits() > 0 && !pendingCurrent;
            if (!hadPrevious && !includeCurrent) return;
            ensureReservationsOwned();
            if (hadPrevious) {
                reservationContributions.remove(change.orderId());
                reservations.remove(previous);
            }
            if (includeCurrent) {
                long contribution = entryHashStable(change.orderId(), stableReservation(current, identities));
                reservationContributions.put(change.orderId(), contribution);
                reservations.add(contribution);
            }
        }

        private void updatePosition(long ownerId, RuntimeCommitPatch.PositionChange change,
                                    RuntimeIdentityRegistry identities, boolean reverse) {
            boolean hadPrevious = positionContributions.containsKey(change.positionKey());
            long previous = hadPrevious ? positionContributions.get(change.positionKey()) : 0;
            PositionRuntime current = reverse ? change.before() : change.after();
            if (!hadPrevious && (current == null || current.userId() != ownerId)) return;
            ensurePositionsOwned();
            if (hadPrevious) {
                positionContributions.remove(change.positionKey());
                positions.remove(previous);
            }
            if (current != null && current.userId() == ownerId) {
                String identity = identities.positionIdentity(change.positionKey()).positionKey();
                long contribution = entryHashStable(identity, stablePosition(current, identities));
                positionContributions.put(change.positionKey(), contribution);
                positions.add(contribution);
            }
        }

        private Long balanceContribution(int assetId) {
            return balanceContributions.containsKey(assetId) ? balanceContributions.get(assetId) : null;
        }

        private Long reservationContribution(long orderId) {
            return reservationContributions.containsKey(orderId) ? reservationContributions.get(orderId) : null;
        }

        private Long positionContribution(long positionKey) {
            return positionContributions.containsKey(positionKey) ? positionContributions.get(positionKey) : null;
        }

        private void ensureBalancesOwned() {
            if (!balancesShared) return;
            org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap copy =
                    new org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap(
                            Math.max(1, balanceContributions.size()));
            copy.putAll(balanceContributions);
            balanceContributions = copy;
            balancesShared = false;
        }

        private void ensureReservationsOwned() {
            if (!reservationsShared) return;
            org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap copy =
                    new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(
                            Math.max(1, reservationContributions.size()));
            copy.putAll(reservationContributions);
            reservationContributions = copy;
            reservationsShared = false;
        }

        private void ensurePositionsOwned() {
            if (!positionsShared) return;
            org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap copy =
                    new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap(
                            Math.max(1, positionContributions.size()));
            copy.putAll(positionContributions);
            positionContributions = copy;
            positionsShared = false;
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

    private static long stableBalance(String asset, RuntimeCommitPatch.UserBalance balance) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), AssetBalance.class.getName());
        hash = CoreStateHash.mix(hash, asset);
        hash = CoreStateHash.mix(hash, Math.addExact(balance.availableUnits(), balance.pendingReservedUnits()));
        return CoreStateHash.mix(hash, Math.subtractExact(balance.lockedUnits(), balance.pendingReservedUnits()));
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
