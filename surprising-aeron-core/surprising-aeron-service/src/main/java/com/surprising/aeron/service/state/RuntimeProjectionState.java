package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private final MutationJournal mutationJournal = new MutationJournal();

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

    public void apply(List<RuntimeFactFrame> patches) {
        if (patches == null || patches.isEmpty()) throw new IllegalArgumentException("projection batch required");
        requireHealthy();
        MutationJournal inverse = mutationJournal.reset(-1);
        try {
            for (RuntimeFactFrame patch : patches) {
                armInjectedFailure(patch, inverse);
                applyPatch(patch, inverse);
            }
            cachedFreeze = null;
            cachedFreezeSequence = -1;
            inverse.release();
        } catch (Throwable applyFailure) {
            rollbackFailedApplication(inverse, applyFailure);
            throw applyFailure;
        }
    }

    public void apply(RuntimeFactFrame patch) {
        requireHealthy();
        MutationJournal inverse = mutationJournal.reset(-1);
        try {
            armInjectedFailure(patch, inverse);
            applyPatch(patch, inverse);
            cachedFreeze = null;
            cachedFreezeSequence = -1;
            inverse.release();
        } catch (Throwable applyFailure) {
            rollbackFailedApplication(inverse, applyFailure);
            throw applyFailure;
        }
    }

    private void applyPatch(RuntimeFactFrame patch, MutationJournal inverse) {
        if (patch == null || patch.productLine() != productLine
                || patch.previousProjectionSequence() != sequence
                || patch.projectionSequence() != Math.incrementExact(sequence)
                || patch.beforeRevision() != revision
                || patch.beforeBusinessStateHash() != businessStateHash
                || patch.beforeFundsStateHash() != fundsStateHash) {
            throw new IllegalStateException("projection patch is not contiguous with mutable replica");
        }
        applyAccountLanes(patch, inverse);
        applyGlobal(patch.globalOwnerGroup(), patch.identities(), inverse);
        inverse.setRevision(patch.afterRevision());
        inverse.setBusinessStateHash(patch.businessStateHash());
        inverse.setFundsStateHash(patch.fundsStateHash());
        inverse.setSequence(patch.projectionSequence());
    }

    private void armInjectedFailure(RuntimeFactFrame patch, MutationJournal inverse) {
        int injectedFailureCount = patch != null && patch.projectionSequence() == failOnSequence
                ? failAfterMutations : -1;
        if (injectedFailureCount <= 0) return;
        failOnSequence = -1;
        failAfterMutations = -1;
        inverse.armFailure(injectedFailureCount);
    }

    private void rollbackFailedApplication(MutationJournal inverse, Throwable applyFailure) {
        try {
            inverse.rollback();
        } catch (Throwable rollbackFailure) {
            applyFailure.addSuppressed(rollbackFailure);
        }
        inverse.release();
        failure = applyFailure;
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

    private void applyAccountLanes(RuntimeFactFrame patch, MutationJournal inverse) {
        RuntimeFactFrame.IdentityView identities = patch.identities();
        for (RuntimeFactFrame.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            prepareChangedUsers(group.users(), inverse);
            for (RuntimeFactFrame.OrderChange change : group.orders()) {
                boolean pending = pendingAfter(group.reservations(), change.orderId());
                inverse.putOrRemove(orders, change.orderId(), change.after() == null || pending
                        ? null : RuntimeStateMaterializer.orderSnapshot(change.after(), identities));
            }
            for (RuntimeFactFrame.RiskSnapshotChange change : group.riskSnapshots()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.riskKey());
                inverse.putOrRemove(riskSnapshots, identity.userId() + ":" + identity.positionKey(),
                        change.after() == null ? null : RuntimeStateMaterializer.riskSnapshot(change.after(), identities));
            }
            for (RuntimeFactFrame.LiquidationChange change : group.liquidations()) {
                inverse.putOrRemove(liquidations, change.liquidationId(), change.after() == null
                        ? null : RuntimeStateMaterializer.liquidation(change.after(), identities));
            }
            for (RuntimeFactFrame.LeverageChange change : group.leverages()) {
                inverse.putOrRemove(leverages, change.key(), change.after());
            }
            for (RuntimeFactFrame.AlgoOrderChange change : group.algoOrders()) {
                inverse.putOrRemove(algoOrders, change.algoOrderId(), change.after());
            }
            for (RuntimeFactFrame.TimerChange change : group.timers()) {
                inverse.putOrRemove(timers, change.key(), change.after());
            }
            for (RuntimeFactFrame.TriggerOrderChange change : group.triggerOrders()) {
                inverse.putOrRemove(triggerOrders, change.triggerOrderId(), change.after());
            }
            for (RuntimeFactFrame.ClientOrderChange change : group.clientOrders()) {
                TradingCoreState.ClientOrderKey key = new TradingCoreState.ClientOrderKey(change.key().userId(),
                        identities.clientOrderId(change.key().userId(), change.key().clientKey()));
                inverse.putOrRemove(clientOrders, key, change.afterOrderId());
            }
            applyUserValues(group, identities, inverse);
            finishChangedUsers(group.users(), inverse);
        }
    }

    private void prepareChangedUsers(List<RuntimeFactFrame.UserChange> changes, MutationJournal inverse) {
        for (RuntimeFactFrame.UserChange change : changes) {
            UserRuntime runtimeUser = change.after();
            if (runtimeUser == null) continue;
            if (runtimeUser.productLine() != productLine) {
                throw new IllegalStateException("projection user product line mismatch");
            }
            if (users.get(change.userId()) == null) {
                inverse.putOrRemove(users, change.userId(),
                        new MutableUser(runtimeUser.productLine(), runtimeUser.positionMode()));
            }
        }
    }

    private void applyUserValues(RuntimeFactFrame.AccountLaneOwnerGroup group,
                                 RuntimeFactFrame.IdentityView identities, MutationJournal inverse) {
        for (RuntimeFactFrame.BalanceChange change : group.balances()) {
            long userId = change.key().userId();
            MutableUser user = mutableUser(group.users(), userId);
            if (user == null) continue;
            RuntimeFactFrame.UserBalance value = change.after();
            String asset = identities.asset(change.key().assetId());
            inverse.putOrRemove(user.balances, asset, value == null ? null : new AssetBalance(asset,
                    Math.addExact(value.availableUnits(), value.pendingReservedUnits()),
                    Math.subtractExact(value.lockedUnits(), value.pendingReservedUnits())));
        }
        for (RuntimeFactFrame.ReservationChange change : group.reservations()) {
            long userId = userId(change.before(), change.after());
            if (userId == 0) continue;
            MutableUser user = mutableUser(group.users(), userId);
            if (user == null) continue;
            inverse.putOrRemove(user.reservations, change.orderId(), change.after() == null || change.pendingAfter()
                    ? null : RuntimeStateMaterializer.reservation(change.after(), identities));
            if (findUserChange(group.users(), userId) == null) {
                int revisionDelta = (change.pendingBefore() ? 1 : 0) - (change.pendingAfter() ? 1 : 0);
                if (revisionDelta != 0) {
                    inverse.setUserRevision(user, Math.addExact(user.revision, revisionDelta));
                }
            }
        }
        for (RuntimeFactFrame.PositionChange change : group.positions()) {
            long userId = userId(change.before(), change.after());
            if (userId == 0) continue;
            MutableUser user = mutableUser(group.users(), userId);
            if (user == null) continue;
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.positionKey());
            if (identity.userId() != userId) {
                throw new IllegalStateException("projection position owner mismatch");
            }
            inverse.putOrRemove(user.positions, identity.positionKey(), change.after() == null
                    ? null : RuntimeStateMaterializer.position(change.positionKey(), change.after(), identities));
        }
    }

    private void finishChangedUsers(List<RuntimeFactFrame.UserChange> changes, MutationJournal inverse) {
        for (RuntimeFactFrame.UserChange change : changes) {
            UserRuntime runtimeUser = change.after();
            if (runtimeUser == null) {
                inverse.putOrRemove(users, change.userId(), null);
                continue;
            }
            MutableUser user = users.get(change.userId());
            if (user == null) throw new IllegalStateException("typed patch lacks current user state: " + change.userId());
            inverse.setUserRevision(user,
                    Math.subtractExact(runtimeUser.revision(), change.pendingReservationCountAfter()));
            inverse.setUserPositionMode(user, runtimeUser.positionMode());
        }
    }

    private MutableUser mutableUser(List<RuntimeFactFrame.UserChange> changes, long userId) {
        RuntimeFactFrame.UserChange change = findUserChange(changes, userId);
        if (change != null && change.after() == null) return null;
        MutableUser user = users.get(userId);
        if (user == null) throw new IllegalStateException("typed patch lacks current user state: " + userId);
        return user;
    }

    private static RuntimeFactFrame.UserChange findUserChange(
            List<RuntimeFactFrame.UserChange> changes, long userId) {
        int low = 0;
        int high = changes.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            RuntimeFactFrame.UserChange change = changes.get(middle);
            int comparison = Long.compare(change.userId(), userId);
            if (comparison == 0) return change;
            if (comparison < 0) low = middle + 1; else high = middle - 1;
        }
        return null;
    }

    private static boolean pendingAfter(List<RuntimeFactFrame.ReservationChange> changes, long orderId) {
        int low = 0;
        int high = changes.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            RuntimeFactFrame.ReservationChange change = changes.get(middle);
            int comparison = Long.compare(change.orderId(), orderId);
            if (comparison == 0) return change.pendingAfter();
            if (comparison < 0) low = middle + 1; else high = middle - 1;
        }
        return false;
    }

    private void applyGlobal(RuntimeFactFrame.GlobalOwnerGroup global, RuntimeFactFrame.IdentityView identities,
                             MutationJournal inverse) {
        for (RuntimeFactFrame.MarkPriceChange change : global.markPrices()) {
            String symbol = identities.symbol(change.symbolId());
            MarkPriceRuntime value = change.after();
            inverse.putOrRemove(marks, symbol, value == null ? null : new CoreMarkPriceState(symbol,
                    value.instrumentVersion(), value.markPriceTicks(), value.priceSequence(), value.generatedAtEpochMillis()));
        }
        for (RuntimeFactFrame.RiskScanChange change : global.riskScans()) {
            inverse.putOrRemove(riskScans, identities.symbol(change.symbolId()), change.after() == null
                    ? null : RuntimeStateMaterializer.riskScan(change.after(), identities));
        }
        global.instruments().forEach(change -> inverse.putOrRemove(instruments, change.symbol(), change.after()));
        if (global.nextLiquidationId() != null) {
            inverse.setNextLiquidationId(global.nextLiquidationId().after());
        }
        if (global.riskScanControl() != null) {
            inverse.setRiskScanControl(global.riskScanControl().after());
        }
        for (RuntimeFactFrame.TreasuryAssetChange change : global.treasuryAssets()) {
            String asset = identities.asset(change.assetId());
            RuntimeFactFrame.TreasuryAssetValue value = change.after();
            inverse.putZeroOrRemove(fees, asset, value == null ? 0 : value.fee());
            inverse.putZeroOrRemove(insurance, asset, value == null ? 0 : value.insurance());
            inverse.putZeroOrRemove(deficits, asset, value == null ? 0 : value.deficit());
            inverse.putZeroOrRemove(liquidationFees, asset, value == null ? 0 : value.liquidationFee());
            inverse.putZeroOrRemove(fundingResiduals, asset, value == null ? 0 : value.fundingResidual());
            inverse.putZeroOrRemove(roundingResiduals, asset, value == null ? 0 : value.roundingResidual());
            inverse.putZeroOrRemove(clearingPnl, asset, value == null ? 0 : value.clearingPnl());
        }
        for (RuntimeFactFrame.TreasuryFundingChange change : global.treasuryFunding()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeFactFrame.TreasuryFundingValue value = change.after();
            inverse.putZeroOrRemove(fundingSettlements, symbol, value == null ? 0 : value.settlementId());
            inverse.putOrRemove(fundingProgress, symbol, value == null || value.progress() == null ? null
                    : RuntimeStateMaterializer.fundingProgress(value.progress()));
        }
        for (RuntimeFactFrame.TreasuryLifecycleChange change : global.treasuryLifecycle()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeFactFrame.TreasuryLifecycleValue value = change.after();
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

    private final class MutationJournal {
        private static final byte MAP = 1;
        private static final byte ROOT_REVISION = 2;
        private static final byte ROOT_BUSINESS_HASH = 3;
        private static final byte ROOT_FUNDS_HASH = 4;
        private static final byte ROOT_SEQUENCE = 5;
        private static final byte USER_REVISION = 6;
        private static final byte USER_POSITION_MODE = 7;
        private static final byte NEXT_LIQUIDATION_ID = 8;
        private static final byte RISK_SCAN_CONTROL = 9;

        private byte[] types = new byte[32];
        private Object[] targets = new Object[32];
        private Object[] keys = new Object[32];
        private Object[] previousValues = new Object[32];
        private long[] previousLongs = new long[32];
        private boolean[] previousPresence = new boolean[32];
        private int size;
        private int remainingUntilFailure;

        private MutationJournal reset(int remainingUntilFailure) {
            release();
            this.remainingUntilFailure = remainingUntilFailure;
            return this;
        }

        private void release() {
            java.util.Arrays.fill(targets, 0, size, null);
            java.util.Arrays.fill(keys, 0, size, null);
            java.util.Arrays.fill(previousValues, 0, size, null);
            size = 0;
        }

        private <K, V> void putOrRemove(Map<K, V> values, K key, V value) {
            V current = values.get(key);
            boolean present = current != null || values.containsKey(key);
            if (value == null ? !present : value.equals(current)) return;
            int index = append(MAP);
            targets[index] = values;
            keys[index] = key;
            previousValues[index] = current;
            previousPresence[index] = present;
            if (value == null) values.remove(key); else values.put(key, value);
            afterMutation();
        }

        private void armFailure(int mutationCount) {
            if (mutationCount <= 0 || remainingUntilFailure > 0) {
                throw new IllegalStateException("projection mutation failure is already armed");
            }
            remainingUntilFailure = mutationCount;
        }

        private void putZeroOrRemove(Map<String, Long> values, String key, long value) {
            putOrRemove(values, key, value == 0 ? null : value);
        }

        private void setRevision(long value) {
            int index = append(ROOT_REVISION);
            previousLongs[index] = revision;
            revision = value;
            afterMutation();
        }

        private void setBusinessStateHash(long value) {
            int index = append(ROOT_BUSINESS_HASH);
            previousLongs[index] = businessStateHash;
            businessStateHash = value;
            afterMutation();
        }

        private void setFundsStateHash(long value) {
            int index = append(ROOT_FUNDS_HASH);
            previousLongs[index] = fundsStateHash;
            fundsStateHash = value;
            afterMutation();
        }

        private void setSequence(long value) {
            int index = append(ROOT_SEQUENCE);
            previousLongs[index] = sequence;
            sequence = value;
            afterMutation();
        }

        private void setUserRevision(MutableUser user, long value) {
            int index = append(USER_REVISION);
            targets[index] = user;
            previousLongs[index] = user.revision;
            user.revision = value;
            afterMutation();
        }

        private void setUserPositionMode(MutableUser user, CorePositionMode value) {
            int index = append(USER_POSITION_MODE);
            targets[index] = user;
            previousValues[index] = user.positionMode;
            user.positionMode = value;
            afterMutation();
        }

        private void setNextLiquidationId(long value) {
            int index = append(NEXT_LIQUIDATION_ID);
            previousLongs[index] = nextLiquidationId;
            nextLiquidationId = value;
            afterMutation();
        }

        private void setRiskScanControl(CoreRiskScanControlView value) {
            int index = append(RISK_SCAN_CONTROL);
            previousValues[index] = riskScanControl;
            riskScanControl = value;
            afterMutation();
        }

        private int append(byte type) {
            if (size == types.length) grow();
            types[size] = type;
            return size++;
        }

        private void grow() {
            int capacity = Math.multiplyExact(types.length, 2);
            types = java.util.Arrays.copyOf(types, capacity);
            targets = java.util.Arrays.copyOf(targets, capacity);
            keys = java.util.Arrays.copyOf(keys, capacity);
            previousValues = java.util.Arrays.copyOf(previousValues, capacity);
            previousLongs = java.util.Arrays.copyOf(previousLongs, capacity);
            previousPresence = java.util.Arrays.copyOf(previousPresence, capacity);
        }

        private void afterMutation() {
            if (remainingUntilFailure > 0 && --remainingUntilFailure == 0) {
                throw new IllegalStateException("injected mutable projection failure");
            }
        }

        private void rollback() {
            for (int index = size - 1; index >= 0; index--) {
                switch (types[index]) {
                    case MAP -> rollbackMap(index);
                    case ROOT_REVISION -> revision = previousLongs[index];
                    case ROOT_BUSINESS_HASH -> businessStateHash = previousLongs[index];
                    case ROOT_FUNDS_HASH -> fundsStateHash = previousLongs[index];
                    case ROOT_SEQUENCE -> sequence = previousLongs[index];
                    case USER_REVISION -> ((MutableUser) targets[index]).revision = previousLongs[index];
                    case USER_POSITION_MODE -> ((MutableUser) targets[index]).positionMode =
                            (CorePositionMode) previousValues[index];
                    case NEXT_LIQUIDATION_ID -> nextLiquidationId = previousLongs[index];
                    case RISK_SCAN_CONTROL -> riskScanControl = (CoreRiskScanControlView) previousValues[index];
                    default -> throw new IllegalStateException("unknown projection rollback operation");
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void rollbackMap(int index) {
            Map values = (Map) targets[index];
            if (previousPresence[index]) values.put(keys[index], previousValues[index]);
            else values.remove(keys[index]);
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
