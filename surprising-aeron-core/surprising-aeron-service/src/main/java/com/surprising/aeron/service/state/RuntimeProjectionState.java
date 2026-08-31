package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class RuntimeProjectionState {

    private final ProductLine productLine;
    private final TreeMap<Long, MutableUser> users = new TreeMap<>();
    private final TreeMap<Long, CoreOrderState> orders;
    private final TreeMap<String, CoreInstrumentState> instruments;
    private final TreeMap<String, CoreMarkPriceState> marks;
    private final TreeMap<String, CoreRiskSnapshot> riskSnapshots;
    private final TreeMap<Long, CoreLiquidationState> liquidations;
    private final TreeMap<String, CoreRiskState.RiskScan> riskScans;
    private final TreeMap<String, Long> fees;
    private final TreeMap<String, Long> insurance;
    private final TreeMap<String, Long> deficits;
    private final TreeMap<String, Long> liquidationFees;
    private final TreeMap<String, Long> fundingResiduals;
    private final TreeMap<String, Long> roundingResiduals;
    private final TreeMap<String, Long> clearingPnl;
    private final TreeMap<String, Long> fundingSettlements;
    private final TreeMap<String, Long> lifecycleSettlements;
    private final TreeMap<String, CoreTreasuryState.FundingProgress> fundingProgress;
    private final TreeMap<String, CoreTreasuryState.LifecycleProgress> lifecycleProgress;
    private final TreeMap<CoreLeverageKey, Long> leverages;
    private final TreeMap<Long, CoreAlgoOrderState> algoOrders;
    private final TreeMap<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers;
    private final TreeMap<TradingCoreState.ClientOrderKey, Long> clientOrders;
    private final TreeMap<Long, CoreTriggerOrderState> triggerOrders;
    private long sequence;
    private long revision;
    private long businessStateHash;
    private long fundsStateHash;
    private long nextLiquidationId;
    private CoreRiskScanControlView riskScanControl;
    private TradingCoreState cachedFreeze;
    private long cachedFreezeSequence;
    private long freezeCount;
    private Throwable failure;
    private volatile long failOnSequence = -1;
    private volatile int failAfterMutations = -1;

    public RuntimeProjectionState(TradingCoreState initial, long businessStateHash, long fundsStateHash) {
        this(initial, businessStateHash, fundsStateHash, 0);
    }

    RuntimeProjectionState(TradingCoreState initial, long businessStateHash, long fundsStateHash,
                           long initialSequence) {
        if (initial == null) throw new IllegalArgumentException("initial projection state required");
        if (initialSequence < 0) throw new IllegalArgumentException("initial projection sequence is negative");
        productLine = initial.productLine();
        sequence = initialSequence;
        revision = initial.revision();
        this.businessStateHash = businessStateHash;
        this.fundsStateHash = fundsStateHash;
        initial.users().forEach((id, user) -> users.put(id, new MutableUser(user)));
        orders = new TreeMap<>(initial.orders());
        instruments = new TreeMap<>(initial.instruments());
        marks = new TreeMap<>(initial.riskState().markPrices());
        riskSnapshots = new TreeMap<>(initial.riskState().snapshots());
        liquidations = new TreeMap<>(initial.riskState().liquidations());
        riskScans = new TreeMap<>(initial.riskState().scans());
        nextLiquidationId = initial.riskState().nextLiquidationId();
        riskScanControl = initial.riskState().scanControl();
        CoreTreasuryState treasury = initial.treasuryState();
        fees = new TreeMap<>(treasury.feeBalances());
        insurance = new TreeMap<>(treasury.insuranceBalances());
        deficits = new TreeMap<>(treasury.insuranceDeficits());
        liquidationFees = new TreeMap<>(treasury.liquidationFeeBalances());
        fundingResiduals = new TreeMap<>(treasury.fundingResidualBalances());
        roundingResiduals = new TreeMap<>(treasury.roundingResidualBalances());
        clearingPnl = new TreeMap<>(treasury.clearingPnlBalances());
        fundingSettlements = new TreeMap<>(treasury.fundingSettlements());
        lifecycleSettlements = new TreeMap<>(treasury.lifecycleSettlements());
        fundingProgress = new TreeMap<>(treasury.fundingProgress());
        lifecycleProgress = new TreeMap<>(treasury.lifecycleProgress());
        leverages = new TreeMap<>(initial.leverages());
        algoOrders = new TreeMap<>(initial.algoOrders());
        timers = new TreeMap<>(initial.cancelAllAfterTimers());
        clientOrders = new TreeMap<>(initial.clientOrderIndex());
        triggerOrders = new TreeMap<>(initial.triggerOrders());
        cachedFreeze = initial;
        cachedFreezeSequence = initialSequence;
    }

    public void apply(List<RuntimeCommitPatch> patches) {
        if (patches == null || patches.isEmpty()) throw new IllegalArgumentException("projection batch required");
        for (RuntimeCommitPatch patch : patches) apply(patch);
    }

    public void apply(RuntimeCommitPatch patch) {
        requireHealthy();
        int injectedFailureCount = patch != null && patch.projectionSequence() == failOnSequence
                ? failAfterMutations : -1;
        if (injectedFailureCount > 0) {
            failOnSequence = -1;
            failAfterMutations = -1;
        }
        MutationJournal inverse = new MutationJournal(injectedFailureCount);
        try {
            if (patch == null || patch.productLine() != productLine
                    || patch.previousProjectionSequence() != sequence
                    || patch.projectionSequence() != Math.incrementExact(sequence)
                    || patch.beforeRevision() != revision
                    || patch.beforeBusinessStateHash() != businessStateHash
                    || patch.beforeFundsStateHash() != fundsStateHash) {
                throw new IllegalStateException("projection patch is not contiguous with mutable replica");
            }
            prevalidate(patch);
            applyAccountLanes(patch, inverse);
            applyGlobal(patch.globalOwnerGroup(), patch.identities(), inverse);
            long previousRevision = revision;
            inverse.mutate(() -> revision = previousRevision, () -> revision = patch.afterRevision());
            long previousBusinessStateHash = businessStateHash;
            inverse.mutate(() -> businessStateHash = previousBusinessStateHash,
                    () -> businessStateHash = patch.businessStateHash());
            long previousFundsStateHash = fundsStateHash;
            inverse.mutate(() -> fundsStateHash = previousFundsStateHash,
                    () -> fundsStateHash = patch.fundsStateHash());
            long previousSequence = sequence;
            inverse.mutate(() -> sequence = previousSequence, () -> sequence = patch.projectionSequence());
            cachedFreeze = null;
            cachedFreezeSequence = -1;
        } catch (Throwable applyFailure) {
            try {
                inverse.rollback();
            } catch (Throwable rollbackFailure) {
                applyFailure.addSuppressed(rollbackFailure);
            }
            failure = applyFailure;
            throw applyFailure;
        }
    }

    public TradingCoreState freeze(long requestedSequence) {
        requireHealthy();
        return freezeInternal(requestedSequence);
    }

    TradingCoreState freezeLastCompleteAfterFailure(long requestedSequence) {
        if (failure == null) throw new IllegalStateException("projection replica has not failed");
        return freezeInternal(requestedSequence);
    }

    void failAfterMutationsForTest(long projectionSequence, int mutationCount) {
        requireHealthy();
        if (projectionSequence <= sequence || mutationCount <= 0) {
            throw new IllegalArgumentException("future sequence and positive mutation count required");
        }
        failOnSequence = projectionSequence;
        failAfterMutations = mutationCount;
    }

    private TradingCoreState freezeInternal(long requestedSequence) {
        if (requestedSequence != sequence) throw new IllegalArgumentException("projection freeze requires exact sequence");
        if (cachedFreeze != null && cachedFreezeSequence == sequence) return cachedFreeze;
        TreeMap<Long, CoreUserState> frozenUsers = new TreeMap<>();
        users.forEach((id, user) -> frozenUsers.put(id, user.freeze(productLine, id)));
        CoreRiskState risk = new CoreRiskState(marks, riskSnapshots, liquidations, riskScans,
                nextLiquidationId, riskScanControl);
        CoreTreasuryState treasury = new CoreTreasuryState(fees, insurance, deficits, liquidationFees,
                fundingResiduals, roundingResiduals, clearingPnl, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
        cachedFreeze = new TradingCoreState(productLine, revision, frozenUsers, orders, instruments, risk, treasury,
                leverages, algoOrders, timers, clientOrders, triggerOrders);
        cachedFreezeSequence = sequence;
        freezeCount = Math.incrementExact(freezeCount);
        return cachedFreeze;
    }

    public long sequence() { return sequence; }
    public long businessStateHash() { return businessStateHash; }
    public long fundsStateHash() { return fundsStateHash; }
    long freezeCount() { return freezeCount; }

    void rebaseInitialBusinessStateHash(long expectedBefore, long after) {
        requireHealthy();
        if (sequence != 0 || businessStateHash != expectedBefore || after == 0) {
            throw new IllegalStateException("invalid initial projection business hash rebase");
        }
        businessStateHash = after;
    }

    private void applyAccountLanes(RuntimeCommitPatch patch, MutationJournal inverse) {
        RuntimeCommitPatch.IdentityView identities = patch.identities();
        LongHashSet changedUsers = new LongHashSet();
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            group.users().forEach(change -> changedUsers.add(change.userId()));
            group.balances().forEach(change -> changedUsers.add(change.key().userId()));
            group.reservations().forEach(change -> changedUsers.add(userId(change.before(), change.after())));
            group.positions().forEach(change -> changedUsers.add(userId(change.before(), change.after())));
            for (RuntimeCommitPatch.OrderChange change : group.orders()) {
                boolean pending = reservationPendingAfter(patch.accountLaneGroups(), change.orderId());
                inverse.putOrRemove(orders, change.orderId(), change.after() == null || pending
                        ? null : change.businessAfter());
            }
            for (RuntimeCommitPatch.RiskSnapshotChange change : group.riskSnapshots()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.riskKey());
                inverse.putOrRemove(riskSnapshots, identity.userId() + ":" + identity.positionKey(),
                        change.after() == null ? null : RuntimeStateMaterializer.riskSnapshot(change.after(), identities));
            }
            for (RuntimeCommitPatch.LiquidationChange change : group.liquidations()) {
                inverse.putOrRemove(liquidations, change.liquidationId(), change.after() == null
                        ? null : RuntimeStateMaterializer.liquidation(change.after(), identities));
            }
            applyChanges(inverse, leverages, group.leverages(), RuntimeCommitPatch.LeverageChange::key,
                    RuntimeCommitPatch.LeverageChange::after);
            applyChanges(inverse, algoOrders, group.algoOrders(), RuntimeCommitPatch.AlgoOrderChange::algoOrderId,
                    RuntimeCommitPatch.AlgoOrderChange::after);
            applyChanges(inverse, timers, group.timers(), RuntimeCommitPatch.TimerChange::key,
                    RuntimeCommitPatch.TimerChange::after);
            applyChanges(inverse, triggerOrders, group.triggerOrders(), RuntimeCommitPatch.TriggerOrderChange::triggerOrderId,
                    RuntimeCommitPatch.TriggerOrderChange::after);
            for (RuntimeCommitPatch.ClientOrderChange change : group.clientOrders()) {
                TradingCoreState.ClientOrderKey key = new TradingCoreState.ClientOrderKey(change.key().userId(),
                        identities.clientOrderId(change.key().userId(), change.key().clientKey()));
                inverse.putOrRemove(clientOrders, key, change.afterOrderId());
            }
        }
        changedUsers.remove(0L);
        changedUsers.forEach(userId -> applyUser(patch.accountLaneGroups(),
                userChange(patch.accountLaneGroups(), userId), userId, identities, inverse));
    }

    private void applyUser(List<RuntimeCommitPatch.AccountLaneOwnerGroup> groups,
                           RuntimeCommitPatch.UserChange userChange, long userId,
                           RuntimeCommitPatch.IdentityView identities, MutationJournal inverse) {
        if (userChange != null && userChange.after() == null) {
            inverse.putOrRemove(users, userId, null);
            return;
        }
        MutableUser user = users.get(userId);
        UserRuntime runtimeUser = userChange == null ? null : userChange.after();
        if (user == null) {
            if (runtimeUser == null) throw new IllegalStateException("typed patch lacks current user state: " + userId);
            user = new MutableUser(runtimeUser.productLine(), runtimeUser.positionMode());
            inverse.putOrRemove(users, userId, user);
        }
        int pendingRevisionDelta = 0;
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : groups) {
            for (RuntimeCommitPatch.BalanceChange change : group.balances()) {
                if (change.key().userId() != userId) continue;
                RuntimeCommitPatch.UserBalance value = change.after();
                String asset = identities.asset(change.key().assetId());
                inverse.putOrRemove(user.balances, asset, value == null ? null : new AssetBalance(asset,
                        Math.addExact(value.availableUnits(), value.pendingReservedUnits()),
                        Math.subtractExact(value.lockedUnits(), value.pendingReservedUnits())));
            }
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                if (userId(change.before(), change.after()) != userId) continue;
                if (change.pendingBefore()) pendingRevisionDelta = Math.incrementExact(pendingRevisionDelta);
                if (change.pendingAfter()) pendingRevisionDelta = Math.decrementExact(pendingRevisionDelta);
                inverse.putOrRemove(user.reservations, change.orderId(), change.after() == null || change.pendingAfter()
                        ? null : RuntimeStateMaterializer.reservation(change.after(), identities));
            }
            for (RuntimeCommitPatch.PositionChange change : group.positions()) {
                if (userId(change.before(), change.after()) != userId) continue;
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.positionKey());
                inverse.putOrRemove(user.positions, identity.positionKey(), change.after() == null
                        ? null : RuntimeStateMaterializer.position(change.positionKey(), change.after(), identities));
            }
        }
        long previousRevision = user.revision;
        long nextRevision = runtimeUser == null ? Math.addExact(user.revision, pendingRevisionDelta)
                : Math.subtractExact(runtimeUser.revision(), userChange.pendingReservationCountAfter());
        MutableUser mutableUser = user;
        inverse.mutate(() -> mutableUser.revision = previousRevision, () -> mutableUser.revision = nextRevision);
        if (runtimeUser != null) {
            CorePositionMode previousMode = user.positionMode;
            inverse.mutate(() -> mutableUser.positionMode = previousMode,
                    () -> mutableUser.positionMode = runtimeUser.positionMode());
        }
    }

    private void applyGlobal(RuntimeCommitPatch.GlobalOwnerGroup global, RuntimeCommitPatch.IdentityView identities,
                             MutationJournal inverse) {
        for (RuntimeCommitPatch.MarkPriceChange change : global.markPrices()) {
            String symbol = identities.symbol(change.symbolId());
            MarkPriceRuntime value = change.after();
            inverse.putOrRemove(marks, symbol, value == null ? null : new CoreMarkPriceState(symbol,
                    value.instrumentVersion(), value.markPriceTicks(), value.priceSequence(), value.generatedAtEpochMillis()));
        }
        for (RuntimeCommitPatch.RiskScanChange change : global.riskScans()) {
            inverse.putOrRemove(riskScans, identities.symbol(change.symbolId()), change.after() == null
                    ? null : RuntimeStateMaterializer.riskScan(change.after(), identities));
        }
        global.instruments().forEach(change -> inverse.putOrRemove(instruments, change.symbol(), change.after()));
        if (global.nextLiquidationId() != null) {
            long previous = nextLiquidationId;
            inverse.mutate(() -> nextLiquidationId = previous,
                    () -> nextLiquidationId = global.nextLiquidationId().after());
        }
        if (global.riskScanControl() != null) {
            CoreRiskScanControlView previous = riskScanControl;
            inverse.mutate(() -> riskScanControl = previous,
                    () -> riskScanControl = global.riskScanControl().after());
        }
        for (RuntimeCommitPatch.TreasuryAssetChange change : global.treasuryAssets()) {
            String asset = identities.asset(change.assetId());
            RuntimeCommitPatch.TreasuryAssetValue value = change.after();
            inverse.putZeroOrRemove(fees, asset, value == null ? 0 : value.fee());
            inverse.putZeroOrRemove(insurance, asset, value == null ? 0 : value.insurance());
            inverse.putZeroOrRemove(deficits, asset, value == null ? 0 : value.deficit());
            inverse.putZeroOrRemove(liquidationFees, asset, value == null ? 0 : value.liquidationFee());
            inverse.putZeroOrRemove(fundingResiduals, asset, value == null ? 0 : value.fundingResidual());
            inverse.putZeroOrRemove(roundingResiduals, asset, value == null ? 0 : value.roundingResidual());
            inverse.putZeroOrRemove(clearingPnl, asset, value == null ? 0 : value.clearingPnl());
        }
        for (RuntimeCommitPatch.TreasuryFundingChange change : global.treasuryFunding()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryFundingValue value = change.after();
            inverse.putZeroOrRemove(fundingSettlements, symbol, value == null ? 0 : value.settlementId());
            inverse.putOrRemove(fundingProgress, symbol, value == null || value.progress() == null ? null
                    : RuntimeStateMaterializer.fundingProgress(value.progress()));
        }
        for (RuntimeCommitPatch.TreasuryLifecycleChange change : global.treasuryLifecycle()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryLifecycleValue value = change.after();
            inverse.putZeroOrRemove(lifecycleSettlements, symbol, value == null ? 0 : value.settlementId());
            inverse.putOrRemove(lifecycleProgress, symbol, value == null || value.progress() == null ? null
                    : RuntimeStateMaterializer.lifecycleProgress(value.progress()));
        }
    }

    private void requireHealthy() {
        if (failure != null) throw new IllegalStateException("mutable projection replica failed", failure);
    }

    private static long userId(ReservationRuntime before, ReservationRuntime after) {
        return after != null ? after.userId() : before == null ? 0 : before.userId();
    }

    private static long userId(PositionRuntime before, PositionRuntime after) {
        return after != null ? after.userId() : before == null ? 0 : before.userId();
    }

    private static RuntimeCommitPatch.UserChange userChange(
            List<RuntimeCommitPatch.AccountLaneOwnerGroup> groups, long userId) {
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : groups) {
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                if (change.userId() == userId) return change;
            }
        }
        return null;
    }

    private static boolean reservationPendingAfter(List<RuntimeCommitPatch.AccountLaneOwnerGroup> groups, long orderId) {
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : groups) {
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                if (change.orderId() == orderId) return change.pendingAfter();
            }
        }
        return false;
    }

    private static <K, V, C> void applyChanges(MutationJournal inverse, Map<K, V> values, List<C> changes,
                                                Function<C, K> key, Function<C, V> after) {
        for (C change : changes) inverse.putOrRemove(values, key.apply(change), after.apply(change));
    }

    private void prevalidate(RuntimeCommitPatch patch) {
        RuntimeCommitPatch.IdentityView identities = patch.identities();
        LongHashSet changedUsers = new LongHashSet();
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            group.users().forEach(change -> changedUsers.add(change.userId()));
            group.balances().forEach(change -> changedUsers.add(change.key().userId()));
            group.reservations().forEach(change -> changedUsers.add(userId(change.before(), change.after())));
            group.positions().forEach(change -> changedUsers.add(userId(change.before(), change.after())));
            for (RuntimeCommitPatch.OrderChange change : group.orders()) {
                if (change.after() != null && change.businessAfter() == null) {
                    throw new IllegalStateException("order business value is missing from commit patch");
                }
            }
            for (RuntimeCommitPatch.RiskSnapshotChange change : group.riskSnapshots()) {
                identities.positionIdentity(change.riskKey());
                if (change.after() != null) RuntimeStateMaterializer.riskSnapshot(change.after(), identities);
            }
            for (RuntimeCommitPatch.LiquidationChange change : group.liquidations()) {
                if (change.after() != null) RuntimeStateMaterializer.liquidation(change.after(), identities);
            }
            for (RuntimeCommitPatch.ClientOrderChange change : group.clientOrders()) {
                identities.clientOrderId(change.key().userId(), change.key().clientKey());
            }
        }
        changedUsers.remove(0L);
        changedUsers.forEach(userId -> prevalidateUser(patch.accountLaneGroups(),
                userChange(patch.accountLaneGroups(), userId), userId, identities));
        RuntimeCommitPatch.GlobalOwnerGroup global = patch.globalOwnerGroup();
        global.markPrices().forEach(change -> {
            String symbol = identities.symbol(change.symbolId());
            MarkPriceRuntime value = change.after();
            if (value != null) {
                new CoreMarkPriceState(symbol, value.instrumentVersion(), value.markPriceTicks(),
                        value.priceSequence(), value.generatedAtEpochMillis());
            }
        });
        global.riskScans().forEach(change -> {
            identities.symbol(change.symbolId());
            if (change.after() != null) RuntimeStateMaterializer.riskScan(change.after(), identities);
        });
        global.treasuryAssets().forEach(change -> identities.asset(change.assetId()));
        global.treasuryFunding().forEach(change -> {
            identities.symbol(change.symbolId());
            if (change.after() != null && change.after().progress() != null) {
                RuntimeStateMaterializer.fundingProgress(change.after().progress());
            }
        });
        global.treasuryLifecycle().forEach(change -> {
            identities.symbol(change.symbolId());
            if (change.after() != null && change.after().progress() != null) {
                RuntimeStateMaterializer.lifecycleProgress(change.after().progress());
            }
        });
    }

    private void prevalidateUser(List<RuntimeCommitPatch.AccountLaneOwnerGroup> groups,
                                 RuntimeCommitPatch.UserChange userChange, long userId,
                                 RuntimeCommitPatch.IdentityView identities) {
        if (userChange != null && userChange.after() == null) return;
        MutableUser user = users.get(userId);
        UserRuntime runtimeUser = userChange == null ? null : userChange.after();
        if (user == null && runtimeUser == null) {
            throw new IllegalStateException("typed patch lacks current user state: " + userId);
        }
        if (runtimeUser != null && runtimeUser.productLine() != productLine) {
            throw new IllegalStateException("projection user product line mismatch");
        }
        int pendingRevisionDelta = 0;
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : groups) {
            for (RuntimeCommitPatch.BalanceChange change : group.balances()) {
                if (change.key().userId() != userId) continue;
                String asset = identities.asset(change.key().assetId());
                RuntimeCommitPatch.UserBalance value = change.after();
                if (value != null) {
                    new AssetBalance(asset, Math.addExact(value.availableUnits(), value.pendingReservedUnits()),
                            Math.subtractExact(value.lockedUnits(), value.pendingReservedUnits()));
                }
            }
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                if (userId(change.before(), change.after()) != userId) continue;
                if (change.pendingBefore()) pendingRevisionDelta = Math.incrementExact(pendingRevisionDelta);
                if (change.pendingAfter()) pendingRevisionDelta = Math.decrementExact(pendingRevisionDelta);
                if (change.after() != null && !change.pendingAfter()) {
                    RuntimeStateMaterializer.reservation(change.after(), identities);
                }
            }
            for (RuntimeCommitPatch.PositionChange change : group.positions()) {
                if (userId(change.before(), change.after()) != userId) continue;
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.positionKey());
                if (change.after() != null) {
                    RuntimeStateMaterializer.position(change.positionKey(), change.after(), identities);
                }
                if (identity.userId() != userId) throw new IllegalStateException("projection position owner mismatch");
            }
        }
        long baseRevision = user == null ? 0 : user.revision;
        if (runtimeUser == null) Math.addExact(baseRevision, pendingRevisionDelta);
        else Math.subtractExact(runtimeUser.revision(), userChange.pendingReservationCountAfter());
    }

    private static final class MutationJournal {
        private final ArrayList<Runnable> inverse = new ArrayList<>();
        private int remainingUntilFailure;

        private MutationJournal(int remainingUntilFailure) {
            this.remainingUntilFailure = remainingUntilFailure;
        }

        private <K, V> void putOrRemove(Map<K, V> values, K key, V value) {
            boolean present = values.containsKey(key);
            V previous = values.get(key);
            inverse.add(() -> { if (present) values.put(key, previous); else values.remove(key); });
            if (value == null) values.remove(key); else values.put(key, value);
            afterMutation();
        }

        private void putZeroOrRemove(Map<String, Long> values, String key, long value) {
            putOrRemove(values, key, value == 0 ? null : value);
        }

        private void mutate(Runnable rollback, Runnable mutation) {
            inverse.add(rollback);
            mutation.run();
            afterMutation();
        }

        private void afterMutation() {
            if (remainingUntilFailure > 0 && --remainingUntilFailure == 0) {
                throw new IllegalStateException("injected mutable projection failure");
            }
        }

        private void rollback() {
            for (int index = inverse.size() - 1; index >= 0; index--) inverse.get(index).run();
        }
    }

    private static final class MutableUser {
        private final ProductLine productLine;
        private long revision;
        private final TreeMap<String, AssetBalance> balances;
        private final TreeMap<Long, OrderReservation> reservations;
        private final TreeMap<String, CorePositionState> positions;
        private CorePositionMode positionMode;

        private MutableUser(CoreUserState source) {
            productLine = source.productLine();
            revision = source.revision();
            balances = new TreeMap<>(source.balances());
            reservations = new TreeMap<>(source.reservations());
            positions = new TreeMap<>(source.positions());
            positionMode = source.positionMode();
        }

        private MutableUser(ProductLine productLine, CorePositionMode positionMode) {
            this.productLine = productLine;
            this.positionMode = positionMode;
            balances = new TreeMap<>();
            reservations = new TreeMap<>();
            positions = new TreeMap<>();
        }

        private CoreUserState freeze(ProductLine expectedProductLine, long userId) {
            if (productLine != expectedProductLine) throw new IllegalStateException("projection user product line mismatch");
            return new CoreUserState(productLine, userId, revision, balances, reservations, positions, positionMode);
        }
    }
}
