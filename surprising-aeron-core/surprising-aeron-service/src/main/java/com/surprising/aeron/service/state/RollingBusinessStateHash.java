package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.function.Predicate;

public final class RollingBusinessStateHash {

    private static final long HASH_TAG = 0x9e3779b97f4a7c15L;
    private final Aggregate users = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserHash> userHashes =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final Aggregate orders = new Aggregate();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap orderContributions =
            new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap();
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
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<
            RuntimeCommitPatch.ReservationChange> reservationChangesScratch =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private long[] reservationChangeKeys = new long[8];
    private int reservationChangeKeyCount;
    private final int productLine;
    private long revision;
    private long nextLiquidationId;
    private long riskScanControlHash;
    private long lastCoreSequence = Long.MIN_VALUE;
    private long cachedValue;
    private boolean valueDirty = true;
    private long ownerGeneration;
    private RuntimeIdentityRegistry identities;
    private final UserHashUpdater userHashUpdater = new UserHashUpdater();

    private record OwnedContribution(long value) {}

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
        long nextGeneration = Math.incrementExact(ownerGeneration);
        applyPatch(entry);
        revision = entry.revision();
        lastCoreSequence = entry.coreSequence();
        valueDirty = true;
        ownerGeneration = nextGeneration;
    }

    /**
     * Applies an already-mutated authoritative command without constructing a reversible hash
     * transaction. Any exception is fail-stop; recovery starts from snapshot plus command log.
     */
    public long applyFailStop(RuntimeCommitPatch.PreparedChanges changes) {
        if (changes == null) throw new IllegalArgumentException("prepared changes are required");
        applyPatch(changes);
        revision = changes.afterRevision();
        lastCoreSequence = changes.coreSequence();
        valueDirty = true;
        ownerGeneration = Math.incrementExact(ownerGeneration);
        return value();
    }

    private void applyPatch(RuntimeCommitView patch) {
        if (patch == null || patch.productLine().ordinal() != productLine) {
            throw new IllegalArgumentException("invalid business hash commit");
        }
        if (lastCoreSequence != Long.MIN_VALUE && patch.previousCoreSequence() != lastCoreSequence) {
            throw new IllegalArgumentException("non-contiguous business hash commit sequence: last "
                    + lastCoreSequence + ", previous " + patch.previousCoreSequence()
                    + ", current " + patch.coreSequence());
        }
        if (patch.beforeRevision() != revision) {
            throw new IllegalArgumentException("business hash commit before-value mismatch");
        }
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            UserHashUpdater userUpdater = userHashUpdater.reset();
            resetStageScratch();
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                putReservationChange(change.orderId(), change);
            }
            for (RuntimeCommitPatch.UserChange change : group.users()) {
                UserHash current = userHashes.get(change.userId());
                if ((current != null) != (change.before() != null)) {
                    throw new IllegalArgumentException("business user before-value mismatch");
                }
                userUpdater.apply(change);
            }
            for (RuntimeCommitPatch.BalanceChange change : group.balances()) {
                String asset = identities.asset(change.key().assetId());
                UserHash user = userHashes.get(change.key().userId());
                boolean actualPresent = user != null && user.hasBalanceContribution(change.key().assetId());
                boolean expectedPresent = change.before() != null;
                long actual = actualPresent ? user.balanceContribution(change.key().assetId()) : 0;
                long expected = expectedPresent
                        ? entryHashStable(asset, stableBalance(asset, change.before())) : 0;
                requireContribution(actualPresent, actual, expectedPresent, expected, "balance");
                if (change.after() != null) stableBalance(asset, change.after());
                userUpdater.apply(change);
            }
            for (RuntimeCommitPatch.ReservationChange change : group.reservations()) {
                ReservationRuntime before = change.before();
                UserHash user = before == null ? null : userHashes.get(before.userId());
                boolean actualPresent = user != null && user.hasReservationContribution(change.orderId());
                boolean expectedPresent = before != null && before.reservedUnits() != 0 && !change.pendingBefore();
                long actual = actualPresent ? user.reservationContribution(change.orderId()) : 0;
                long expected = expectedPresent
                        ? entryHashStable(change.orderId(), stableReservation(before, identities)) : 0;
                requireContribution(actualPresent, actual, expectedPresent, expected, "reservation");
                if (change.after() != null) stableReservation(change.after(), identities);
                userUpdater.apply(change);
            }
            for (RuntimeCommitPatch.PositionChange change : group.positions()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.positionKey());
                PositionRuntime before = change.before();
                UserHash user = before == null ? null : userHashes.get(before.userId());
                boolean actualPresent = user != null && user.hasPositionContribution(change.positionKey());
                boolean expectedPresent = before != null;
                long actual = actualPresent ? user.positionContribution(change.positionKey()) : 0;
                long expected = expectedPresent ? entryHashStable(
                        identity.positionKey(), stablePosition(before, identities)) : 0;
                requireContribution(actualPresent, actual, expectedPresent, expected, "position");
                if (change.after() != null) stablePosition(change.after(), identities);
                userUpdater.apply(change);
            }
            for (RuntimeCommitPatch.OrderChange change : group.orders()) {
                RuntimeCommitPatch.ReservationChange reservationChange =
                        reservationChangesScratch.get(change.orderId());
                boolean pendingBefore = reservationChange != null && reservationChange.pendingBefore();
                boolean pendingAfter = reservationChange != null && reservationChange.pendingAfter();
                boolean actualPresent = orderContributions.containsKey(change.orderId());
                boolean expectedPresent = change.before() != null
                        && !change.before().status().terminal() && !pendingBefore;
                long actual = actualPresent ? orderContributions.get(change.orderId()) : 0;
                long expected = expectedPresent
                        ? entryHashStable(change.orderId(), stableOrder(change.before(), identities)) : 0;
                if (actualPresent != expectedPresent || actualPresent && actual != expected) {
                    throw new IllegalArgumentException("business order before-value mismatch");
                }
                if (change.after() != null) stableOrder(change.after(), identities);
                updateOrder(change, !pendingAfter);
            }
            for (RuntimeCommitPatch.LeverageChange change : group.leverages()) {
                validateCachedBefore("leverages", change.key(), change.before(), value -> stable(value),
                        ignored -> true);
                updateCachedValue("leverages", leverages, change.key(), change.after(),
                        value -> stable(value), ignored -> true);
            }
            for (RuntimeCommitPatch.AlgoOrderChange change : group.algoOrders()) {
                validateCachedBefore("algo", change.algoOrderId(), change.before(), value -> stable(value),
                        value -> !value.terminal());
                updateCachedValue("algo", algoOrders, change.algoOrderId(), change.after(),
                        value -> stable(value), value -> !value.terminal());
            }
            for (RuntimeCommitPatch.TimerChange change : group.timers()) {
                validateCachedBefore("timers", change.key(), change.before(), value -> stable(value),
                        ignored -> true);
                updateCachedValue("timers", timers, change.key(), change.after(),
                        value -> stable(value), ignored -> true);
            }
            for (RuntimeCommitPatch.TriggerOrderChange change : group.triggerOrders()) {
                validateCachedBefore("triggers", change.triggerOrderId(), change.before(), value -> stable(value),
                        value -> value.status().open());
                updateCachedValue("triggers", triggers, change.triggerOrderId(), change.after(),
                        value -> stable(value), value -> value.status().open());
            }
            for (RuntimeCommitPatch.RiskSnapshotChange change : group.riskSnapshots()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(change.riskKey());
                validateCachedBefore("snapshots",
                        new PositionContributionKey(identity.userId(), identity.positionKey()),
                        change.before(), this::stableRiskSnapshot, ignored -> true);
                PositionContributionKey key = new PositionContributionKey(identity.userId(), identity.positionKey());
                updateRuntimeContribution("snapshots", riskSnapshots, key, change.after(),
                        this::stableRiskSnapshot, ignored -> true);
            }
            for (RuntimeCommitPatch.LiquidationChange change : group.liquidations()) {
                validateCachedBefore("liquidations", change.liquidationId(), change.before(),
                        this::stableLiquidation, value -> !runtimeTerminal(value));
                updateRuntimeContribution("liquidations", liquidations, change.liquidationId(), change.after(),
                        this::stableLiquidation, value -> !runtimeTerminal(value));
            }
            userUpdater.publish();
        }
        RuntimeCommitPatch.GlobalOwnerGroup global = patch.globalOwnerGroup();
        for (RuntimeCommitPatch.InstrumentChange change : global.instruments()) {
            validateCachedBefore("instruments", change.symbol(), change.before(), value -> stable(value),
                    ignored -> true);
            updateCachedValue("instruments", instruments, change.symbol(), change.after(),
                    value -> stable(value), ignored -> true);
        }
        for (RuntimeCommitPatch.MarkPriceChange change : patch.globalOwnerGroup().markPrices()) {
            String symbol = identities.symbol(change.symbolId());
            validateCachedBefore("marks", symbol, change.before(), this::stableMark, ignored -> true);
            updateRuntimeContribution("marks", markPrices, symbol, change.after(), this::stableMark,
                    ignored -> true);
        }
        for (RuntimeCommitPatch.RiskScanChange change : patch.globalOwnerGroup().riskScans()) {
            String symbol = identities.symbol(change.symbolId());
            validateCachedBefore("scans", symbol, change.before(), this::stableRiskScan, ignored -> true);
            updateRuntimeContribution("scans", riskScans, symbol, change.after(), this::stableRiskScan,
                    ignored -> true);
        }
        for (RuntimeCommitPatch.TreasuryAssetChange change : patch.globalOwnerGroup().treasuryAssets()) {
            identities.asset(change.assetId());
            if (!java.util.Objects.equals(runtimeTreasury.get(change.assetId()), change.before())) {
                throw new IllegalArgumentException("business treasury before-value mismatch");
            }
            updateTreasuryAsset(change);
        }
        for (RuntimeCommitPatch.TreasuryFundingChange change : global.treasuryFunding()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryFundingValue before = change.before();
            validateCachedBefore("fundingSettlements", symbol,
                    before == null || before.settlementId() == 0 ? null : before.settlementId(),
                    value -> stable(value), ignored -> true);
            validateCachedBefore("fundingProgress", symbol, before == null ? null : before.progress(),
                    this::stableFundingProgress, ignored -> true);
            updateTreasuryFunding(change);
        }
        for (RuntimeCommitPatch.TreasuryLifecycleChange change : global.treasuryLifecycle()) {
            String symbol = identities.symbol(change.symbolId());
            RuntimeCommitPatch.TreasuryLifecycleValue before = change.before();
            validateCachedBefore("lifecycleSettlements", symbol,
                    before == null || before.settlementId() == 0 ? null : before.settlementId(),
                    value -> stable(value), ignored -> true);
            validateCachedBefore("lifecycleProgress", symbol, before == null ? null : before.progress(),
                    this::stableLifecycleProgress, ignored -> true);
            updateTreasuryLifecycle(change);
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
            nextLiquidationId = global.nextLiquidationId().after();
        }
        if (global.riskScanControl() != null) {
            riskScanControlHash = stable(global.riskScanControl().after());
        }
    }

    private void resetStageScratch() {
        for (int index = 0; index < reservationChangeKeyCount; index++) {
            reservationChangesScratch.removeKey(reservationChangeKeys[index]);
        }
        reservationChangeKeyCount = 0;
    }

    private void putReservationChange(long orderId, RuntimeCommitPatch.ReservationChange change) {
        if (!reservationChangesScratch.containsKey(orderId)) {
            if (reservationChangeKeyCount == reservationChangeKeys.length) {
                reservationChangeKeys = java.util.Arrays.copyOf(
                        reservationChangeKeys, Math.multiplyExact(reservationChangeKeyCount, 2));
            }
            reservationChangeKeys[reservationChangeKeyCount++] = orderId;
        }
        reservationChangesScratch.put(orderId, change);
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

    private static void requireContribution(Long actual, Long expected, String domain) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new IllegalArgumentException("business " + domain + " before-value mismatch");
        }
    }

    private static void requireContribution(boolean actualPresent, long actual,
                                            boolean expectedPresent, long expected,
                                            String domain) {
        if (actualPresent != expectedPresent || actualPresent && actual != expected) {
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
        cachedValue = mixAggregate(hash, "lifecycleProgress", lifecycleProgress);
        valueDirty = false;
        return cachedValue;
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
            contributions.put(contributionKey(domain, key), new OwnedContribution(contribution));
            target.add(contribution);
        });
    }

    private <K, V> void updateCachedValue(String domain, Aggregate target, K key, V current,
                                          java.util.function.ToLongFunction<V> stableValue,
                                          Predicate<V> included) {
        OwnedContribution previous = contributions.remove(contributionKey(domain, key));
        if (previous != null) {
            target.remove(previous.value());
        }
        if (current != null && included.test(current)) {
            long contribution = entryHashStable(key, stableValue.applyAsLong(current));
            contributions.put(contributionKey(domain, key), new OwnedContribution(contribution));
            target.add(contribution);
        }
    }

    private <K, V> void updateRuntimeContribution(String domain, Aggregate target, K key, V current,
                                                   java.util.function.ToLongFunction<V> stableValue,
                                                   Predicate<V> included) {
        updateCachedValue(domain, target, key, current, stableValue, included);
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
            long contribution = entryHash(userId.longValue(), hash.value());
            users.add(contribution);
        });
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

    private void updateOrder(RuntimeCommitPatch.OrderChange change, boolean visible) {
        if (orderContributions.containsKey(change.orderId())) {
            long previous = orderContributions.get(change.orderId());
            orderContributions.remove(change.orderId());
            orders.remove(previous);
        }
        OrderRuntime current = change.after();
        if (current != null && !current.status().terminal() && visible) {
            long contribution = entryHashStable(change.orderId(), stableOrder(current, identities));
            orderContributions.put(change.orderId(), contribution);
            orders.add(contribution);
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

    private void updateTreasuryAsset(RuntimeCommitPatch.TreasuryAssetChange change) {
        String asset = identities.asset(change.assetId());
        RuntimeCommitPatch.TreasuryAssetValue previous = change.before();
        RuntimeCommitPatch.TreasuryAssetValue current = change.after();
        update(feeBalances, asset, patchFee(previous), patchFee(current));
        update(insuranceBalances, asset, patchInsurance(previous), patchInsurance(current));
        update(insuranceDeficits, asset, patchDeficit(previous), patchDeficit(current));
        update(liquidationFeeBalances, asset, patchLiquidationFee(previous), patchLiquidationFee(current));
        update(fundingResidualBalances, asset, patchFundingResidual(previous), patchFundingResidual(current));
        update(roundingResidualBalances, asset, patchRoundingResidual(previous), patchRoundingResidual(current));
        update(clearingPnlBalances, asset, patchClearingPnl(previous), patchClearingPnl(current));
        if (current == null) runtimeTreasury.remove(change.assetId());
        else runtimeTreasury.put(change.assetId(), current);
    }

    private void updateTreasuryFunding(RuntimeCommitPatch.TreasuryFundingChange change) {
        String symbol = identities.symbol(change.symbolId());
        RuntimeCommitPatch.TreasuryFundingValue current = change.after();
        updateRuntimeContribution("fundingSettlements", fundingSettlements, symbol,
                current == null || current.settlementId() == 0 ? null : current.settlementId(),
                number -> stable(number), ignored -> true);
        updateRuntimeContribution("fundingProgress", fundingProgress, symbol,
                current == null ? null : current.progress(), this::stableFundingProgress, ignored -> true);
    }

    private void updateTreasuryLifecycle(RuntimeCommitPatch.TreasuryLifecycleChange change) {
        String symbol = identities.symbol(change.symbolId());
        RuntimeCommitPatch.TreasuryLifecycleValue current = change.after();
        updateRuntimeContribution("lifecycleSettlements", lifecycleSettlements, symbol,
                current == null || current.settlementId() == 0 ? null : current.settlementId(),
                number -> stable(number), ignored -> true);
        updateRuntimeContribution("lifecycleProgress", lifecycleProgress, symbol,
                current == null ? null : current.progress(), this::stableLifecycleProgress, ignored -> true);
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

    private static long entryHash(long key, Object value) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, stable(value));
    }

    private static long entryHash(long key, long stableValue) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), HASH_TAG);
        hash = CoreStateHash.mix(hash, key);
        return CoreStateHash.mix(hash, stableValue);
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

    private final class UserHashUpdater {
        private long[] userIds = new long[4];
        private UserHash[] hashes = new UserHash[4];
        private int size;

        private UserHashUpdater reset() {
            for (int index = 0; index < size; index++) hashes[index] = null;
            size = 0;
            return this;
        }

        private void apply(RuntimeCommitPatch.UserChange change) {
            UserHash hash = begin(change.userId());
            UserRuntime current = change.after();
            if (current == null) set(change.userId(), null);
            else if (hash == null) set(change.userId(), UserHash.create(current));
            else hash.updateUser(current, change.pendingReservationCountAfter());
        }

        private void apply(RuntimeCommitPatch.BalanceChange change) {
            UserHash hash = begin(change.key().userId());
            if (hash != null) hash.updateBalance(change, identities);
        }

        private void apply(RuntimeCommitPatch.ReservationChange change) {
            ReservationRuntime previous = change.before();
            ReservationRuntime current = change.after();
            long previousOwner = previous == null ? 0 : previous.userId();
            long currentOwner = current == null ? 0 : current.userId();
            if (previousOwner != 0) {
                UserHash hash = begin(previousOwner);
                if (hash != null) hash.updateReservation(previousOwner, change, identities);
            }
            if (currentOwner != 0 && currentOwner != previousOwner) {
                UserHash hash = begin(currentOwner);
                if (hash != null) hash.updateReservation(currentOwner, change, identities);
            }
        }

        private void apply(RuntimeCommitPatch.PositionChange change) {
            PositionRuntime previous = change.before();
            PositionRuntime current = change.after();
            long previousOwner = previous == null ? 0 : previous.userId();
            long currentOwner = current == null ? 0 : current.userId();
            if (previousOwner != 0) {
                UserHash hash = begin(previousOwner);
                if (hash != null) hash.updatePosition(previousOwner, change, identities);
            }
            if (currentOwner != 0 && currentOwner != previousOwner) {
                UserHash hash = begin(currentOwner);
                if (hash != null) hash.updatePosition(currentOwner, change, identities);
            }
        }

        private UserHash begin(long userId) {
            int index = indexOf(userId);
            if (index >= 0) return hashes[index];
            ensureCapacity();
            UserHash hash = userHashes.get(userId);
            if (hash != null) users.remove(entryHash(userId, hash.value()));
            userIds[size] = userId;
            hashes[size] = hash;
            size++;
            return hash;
        }

        private void set(long userId, UserHash hash) {
            int index = indexOf(userId);
            if (index < 0) throw new IllegalStateException("user hash was not opened");
            hashes[index] = hash;
        }

        private int indexOf(long userId) {
            for (int index = 0; index < size; index++) {
                if (userIds[index] == userId) return index;
            }
            return -1;
        }

        private void publish() {
            for (int index = 0; index < size; index++) {
                long userId = userIds[index];
                UserHash hash = hashes[index];
                if (hash == null) userHashes.remove(userId);
                else {
                    userHashes.put(userId, hash);
                    users.add(entryHash(userId, hash.value()));
                }
            }
        }

        private void ensureCapacity() {
            if (size < userIds.length) return;
            int capacity = Math.multiplyExact(userIds.length, 2);
            userIds = java.util.Arrays.copyOf(userIds, capacity);
            hashes = java.util.Arrays.copyOf(hashes, capacity);
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

        private void updateUser(UserRuntime user, int pendingReservationCount) {
            productLine = user.productLine().ordinal();
            revision = Math.subtractExact(user.revision(), pendingReservationCount);
            positionMode = user.positionMode().wireCode();
        }

        private void updateBalance(RuntimeCommitPatch.BalanceChange change,
                                   RuntimeIdentityRegistry identities) {
            int assetId = change.key().assetId();
            if (balanceContributions.containsKey(assetId)) {
                long previous = balanceContributions.get(assetId);
                balanceContributions.removeKey(assetId);
                balances.remove(previous);
            }
            RuntimeCommitPatch.UserBalance current = change.after();
            if (current != null) {
                String asset = identities.asset(assetId);
                long contribution = entryHashStable(asset, stableBalance(asset, current));
                balanceContributions.put(assetId, contribution);
                balances.add(contribution);
            }
        }

        private void updateReservation(long ownerId, RuntimeCommitPatch.ReservationChange change,
                                       RuntimeIdentityRegistry identities) {
            boolean hadPrevious = reservationContributions.containsKey(change.orderId());
            long previous = hadPrevious ? reservationContributions.get(change.orderId()) : 0;
            ReservationRuntime current = change.after();
            boolean pendingCurrent = change.pendingAfter();
            boolean includeCurrent = current != null && current.userId() == ownerId
                    && current.reservedUnits() > 0 && !pendingCurrent;
            if (!hadPrevious && !includeCurrent) return;
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
                                    RuntimeIdentityRegistry identities) {
            boolean hadPrevious = positionContributions.containsKey(change.positionKey());
            long previous = hadPrevious ? positionContributions.get(change.positionKey()) : 0;
            PositionRuntime current = change.after();
            if (!hadPrevious && (current == null || current.userId() != ownerId)) return;
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

        private boolean hasBalanceContribution(int assetId) { return balanceContributions.containsKey(assetId); }
        private long balanceContribution(int assetId) { return balanceContributions.get(assetId); }
        private boolean hasReservationContribution(long orderId) {
            return reservationContributions.containsKey(orderId);
        }
        private long reservationContribution(long orderId) { return reservationContributions.get(orderId); }
        private boolean hasPositionContribution(long positionKey) {
            return positionContributions.containsKey(positionKey);
        }
        private long positionContribution(long positionKey) { return positionContributions.get(positionKey); }

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
