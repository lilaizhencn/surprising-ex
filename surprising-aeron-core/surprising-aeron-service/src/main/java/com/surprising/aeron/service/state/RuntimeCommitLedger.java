package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RuntimeCommitLedger {

    private final ProductLine productLine;
    private final Map<Long, CoreUserState> users;
    private final Map<Long, CoreOrderState> orders;
    private final Map<String, CoreInstrumentState> instruments;
    private final Map<CoreLeverageKey, Long> leverages;
    private final Map<Long, CoreAlgoOrderState> algoOrders;
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers;
    private final Map<Long, CoreTriggerOrderState> triggers;
    private final Map<String, CoreMarkPriceState> markPrices;
    private final Map<String, CoreRiskSnapshot> riskSnapshots;
    private final Map<Long, CoreLiquidationState> liquidations;
    private final Map<String, CoreRiskState.RiskScan> riskScans;
    private final Map<TradingCoreState.ClientOrderKey, Long> clientOrders;
    private CoreTreasuryState treasury;
    private long revision;
    private long sequence;
    private long nextLiquidationId;
    private com.surprising.aeron.protocol.CoreRiskScanControlView riskScanControl;

    public RuntimeCommitLedger(TradingCoreState initial) {
        if (initial == null) throw new IllegalArgumentException("initial commit state is required");
        productLine = initial.productLine();
        users = new TreeMap<>(initial.users());
        orders = new TreeMap<>(initial.orders());
        instruments = new TreeMap<>(initial.instruments());
        leverages = new TreeMap<>(initial.leverages());
        algoOrders = new TreeMap<>(initial.algoOrders());
        timers = new TreeMap<>(initial.cancelAllAfterTimers());
        triggers = new TreeMap<>(initial.triggerOrders());
        markPrices = new TreeMap<>(initial.riskState().markPrices());
        riskSnapshots = new TreeMap<>(initial.riskState().snapshots());
        liquidations = new TreeMap<>(initial.riskState().liquidations());
        riskScans = new TreeMap<>(initial.riskState().scans());
        clientOrders = new TreeMap<>(initial.clientOrderIndex());
        treasury = initial.treasuryState();
        revision = initial.revision();
        nextLiquidationId = initial.riskState().nextLiquidationId();
        riskScanControl = initial.riskState().scanControl();
    }

    public RuntimeCommitEntry capture(long sequence, RuntimeMutationDelta delta,
                                      RuntimeIdentityRegistry identities) {
        if (sequence <= 0 || delta == null || identities == null || delta.productLine() != productLine) {
            throw new IllegalArgumentException("invalid typed runtime commit capture");
        }
        Map<Long, CoreUserState> nextUsers = captureUsers(delta, identities);
        Map<Long, CoreOrderState> nextOrders = changedMap(delta.orders().changedKeys());
        for (Long orderId : delta.orders().changedKeys()) {
            OrderRuntime runtimeOrder = delta.orders().currentValues().get(orderId);
            if (runtimeOrder != null && !delta.pendingReservations().contains(orderId)) {
                nextOrders.put(orderId, RuntimeStateMaterializer.orderSnapshot(runtimeOrder, identities));
            }
        }
        Map<String, CoreMarkPriceState> nextMarks = changedMap(delta.markPrices().changedKeys());
        TreeSet<String> markKeys = new TreeSet<>();
        for (Integer symbolId : delta.markPrices().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            markKeys.add(symbol);
            MarkPriceRuntime value = delta.markPrices().currentValues().get(symbolId);
            if (value != null) {
                nextMarks.put(symbol, new CoreMarkPriceState(symbol, value.instrumentVersion(),
                        value.markPriceTicks(), value.priceSequence(), value.generatedAtEpochMillis()));
            }
        }
        Map<String, CoreRiskSnapshot> nextRiskSnapshots = changedMap(delta.riskSnapshots().changedKeys());
        TreeSet<String> riskSnapshotKeys = new TreeSet<>();
        for (Long positionKey : delta.riskSnapshots().changedKeys()) {
            RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
            String key = identity.userId() + ":" + identity.positionKey();
            riskSnapshotKeys.add(key);
            RiskSnapshotRuntime value = delta.riskSnapshots().currentValues().get(positionKey);
            if (value != null) nextRiskSnapshots.put(key, RuntimeStateMaterializer.riskSnapshot(value, identities));
        }
        Map<Long, CoreLiquidationState> nextLiquidations = changedMap(delta.liquidations().changedKeys());
        delta.liquidations().currentValues().forEach((key, value) ->
                nextLiquidations.put(key, RuntimeStateMaterializer.liquidation(value, identities)));
        Map<String, CoreRiskState.RiskScan> nextRiskScans = changedMap(delta.riskScans().changedKeys());
        TreeSet<String> riskScanKeys = new TreeSet<>();
        for (Integer symbolId : delta.riskScans().changedKeys()) {
            String symbol = identities.symbol(symbolId);
            riskScanKeys.add(symbol);
            RiskScanRuntime value = delta.riskScans().currentValues().get(symbolId);
            if (value != null) nextRiskScans.put(symbol, RuntimeStateMaterializer.riskScan(value, identities));
        }
        Map<TradingCoreState.ClientOrderKey, Long> nextClients = changedMap(
                delta.clientOrders().changedKeys());
        TreeSet<TradingCoreState.ClientOrderKey> clientKeys = new TreeSet<>();
        for (RuntimeMutationDelta.RuntimeClientKey runtimeKey : delta.clientOrders().changedKeys()) {
            TradingCoreState.ClientOrderKey key = new TradingCoreState.ClientOrderKey(runtimeKey.userId(),
                    identities.clientOrderId(runtimeKey.userId(), runtimeKey.clientKey()));
            clientKeys.add(key);
            Long orderId = delta.clientOrders().currentValues().get(runtimeKey);
            if (orderId != null) nextClients.put(key, orderId);
        }
        CoreTreasuryState nextTreasury = RuntimeStateMaterializer.treasuryTransition(
                delta.treasury(), identities, treasury);
        long committedRevision = Math.subtractExact(delta.revision(), delta.pendingReservationCount());
        RuntimeCommitEntry entry = new RuntimeCommitEntry(sequence, productLine, committedRevision,
                changes(delta.users().changedKeys(), users, nextUsers),
                changes(delta.orders().changedKeys(), orders, nextOrders),
                changes(delta.instruments().changedKeys(), instruments, delta.instruments().currentValues()),
                changes(delta.leverages().changedKeys(), leverages, delta.leverages().currentValues()),
                changes(delta.algoOrders().changedKeys(), algoOrders, delta.algoOrders().currentValues()),
                changes(delta.timers().changedKeys(), timers, delta.timers().currentValues()),
                changes(delta.triggerOrders().changedKeys(), triggers, delta.triggerOrders().currentValues()),
                changes(markKeys, markPrices, nextMarks),
                changes(riskSnapshotKeys, riskSnapshots, nextRiskSnapshots),
                changes(delta.liquidations().changedKeys(), liquidations, nextLiquidations),
                changes(riskScanKeys, riskScans, nextRiskScans),
                changes(clientKeys, clientOrders, nextClients),
                treasury, nextTreasury, nextLiquidationId, delta.nextLiquidationId(),
                riskScanControl, delta.riskScanControl());
        return entry;
    }

    public void commit(RuntimeCommitEntry entry) {
        if (entry == null || entry.productLine() != productLine
                || entry.sequence() != Math.incrementExact(sequence)) {
            throw new IllegalStateException("typed runtime commit sequence gap");
        }
        apply(entry);
        sequence = entry.sequence();
    }

    private Map<Long, CoreUserState> captureUsers(RuntimeMutationDelta delta,
                                                   RuntimeIdentityRegistry identities) {
        Map<Long, CoreUserState> result = changedMap(delta.users().changedKeys());
        for (Long userId : delta.users().changedKeys()) {
            RuntimeMutationDelta.UserValue runtimeUser = delta.users().currentValues().get(userId);
            if (runtimeUser == null) continue;
            CoreUserState before = users.get(userId);
            Map<String, AssetBalance> balances = StateMapSupport.delta(before == null ? Map.of() : before.balances());
            for (Integer assetId : runtimeUser.balances().changedKeys()) {
                String asset = identities.asset(assetId);
                RuntimeMutationDelta.BalanceValue value = runtimeUser.balances().currentValues().get(assetId);
                if (value == null) balances.remove(asset);
                else balances.put(asset, new AssetBalance(asset,
                        Math.addExact(value.availableUnits(), value.pendingReservedUnits()),
                        Math.subtractExact(value.lockedUnits(), value.pendingReservedUnits())));
            }
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(
                    before == null ? Map.of() : before.reservations());
            for (Long orderId : delta.reservations().changedKeys()) {
                ReservationRuntime value = delta.reservations().currentValues().get(orderId);
                OrderReservation prior = before == null ? null : before.reservations().get(orderId);
                if (value != null && value.userId() == userId && !delta.pendingReservations().contains(orderId)) {
                    reservations.put(orderId, RuntimeStateMaterializer.reservation(value, identities));
                } else if (prior != null) {
                    reservations.remove(orderId);
                }
            }
            Map<String, CorePositionState> positions = StateMapSupport.delta(
                    before == null ? Map.of() : before.positions());
            for (Long positionKey : delta.positions().changedKeys()) {
                RuntimeIdentityRegistry.PositionIdentity identity = identities.positionIdentity(positionKey);
                if (identity.userId() != userId) continue;
                PositionRuntime value = delta.positions().currentValues().get(positionKey);
                if (value == null) positions.remove(identity.positionKey());
                else positions.put(identity.positionKey(),
                        RuntimeStateMaterializer.position(positionKey, value, identities));
            }
            UserRuntime current = runtimeUser.user();
            long nextRevision = Math.subtractExact(current.revision(), runtimeUser.pendingReservationCount());
            CoreUserState user = before == null
                    ? new CoreUserState(current.productLine(), userId, nextRevision,
                    balances, reservations, positions, current.positionMode())
                    : before.transition(nextRevision, balances, reservations, positions, current.positionMode());
            result.put(userId, user);
        }
        return result;
    }

    public CoreUserState user(long userId) {
        return users.get(userId);
    }

    public CoreOrderState order(long orderId) {
        return orders.get(orderId);
    }

    public CoreTreasuryState treasury() {
        return treasury;
    }

    public TradingCoreState snapshot() {
        CoreRiskState risk = new CoreRiskState(new TreeMap<>(markPrices), new TreeMap<>(riskSnapshots),
                new TreeMap<>(liquidations), new TreeMap<>(riskScans), nextLiquidationId, riskScanControl);
        return new TradingCoreState(productLine, revision, new TreeMap<>(users), new TreeMap<>(orders),
                new TreeMap<>(instruments), risk, treasury, new TreeMap<>(leverages), new TreeMap<>(algoOrders),
                new TreeMap<>(timers), new TreeMap<>(clientOrders), new TreeMap<>(triggers));
    }

    private void apply(RuntimeCommitEntry entry) {
        apply(users, entry.users());
        apply(orders, entry.orders());
        apply(instruments, entry.instruments());
        apply(leverages, entry.leverages());
        apply(algoOrders, entry.algoOrders());
        apply(timers, entry.timers());
        apply(triggers, entry.triggers());
        apply(markPrices, entry.markPrices());
        apply(riskSnapshots, entry.riskSnapshots());
        apply(liquidations, entry.liquidations());
        apply(riskScans, entry.riskScans());
        apply(clientOrders, entry.clientOrders());
        treasury = entry.afterTreasury();
        revision = entry.revision();
        nextLiquidationId = entry.afterNextLiquidationId();
        riskScanControl = entry.afterRiskScanControl();
    }

    private static <K extends Comparable<? super K>, V> RuntimeCommitEntry.Changes<K, V> changes(
            java.util.Set<K> keys, Map<K, V> before, Map<K, V> after) {
        return RuntimeCommitEntry.Changes.capture(keys, before, after);
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> changedMap(java.util.Set<?> keys) {
        return keys.isEmpty() ? Map.of() : new TreeMap<>();
    }

    private static <K extends Comparable<? super K>, V> void apply(
            Map<K, V> target, RuntimeCommitEntry.Changes<K, V> changes) {
        for (K key : changes.keys()) {
            V value = changes.after(key);
            if (value == null) target.remove(key); else target.put(key, value);
        }
    }
}
