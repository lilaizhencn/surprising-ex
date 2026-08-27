package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record RuntimeCommitEntry(
        long sequence,
        ProductLine productLine,
        long revision,
        Changes<Long, CoreUserState> users,
        Changes<Long, CoreOrderState> orders,
        Changes<String, CoreInstrumentState> instruments,
        Changes<CoreLeverageKey, Long> leverages,
        Changes<Long, CoreAlgoOrderState> algoOrders,
        Changes<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers,
        Changes<Long, CoreTriggerOrderState> triggers,
        Changes<String, CoreMarkPriceState> markPrices,
        Changes<String, CoreRiskSnapshot> riskSnapshots,
        Changes<Long, CoreLiquidationState> liquidations,
        Changes<String, CoreRiskState.RiskScan> riskScans,
        Changes<TradingCoreState.ClientOrderKey, Long> clientOrders,
        CoreTreasuryState beforeTreasury,
        CoreTreasuryState afterTreasury,
        long beforeNextLiquidationId,
        long afterNextLiquidationId,
        CoreRiskScanControlView beforeRiskScanControl,
        CoreRiskScanControlView afterRiskScanControl) {

    public RuntimeCommitEntry {
        if (sequence <= 0 || productLine == null || revision < 0 || users == null || orders == null
                || instruments == null || leverages == null || algoOrders == null || timers == null
                || triggers == null || markPrices == null || riskSnapshots == null || liquidations == null
                || riskScans == null || clientOrders == null || beforeTreasury == null || afterTreasury == null
                || beforeNextLiquidationId <= 0 || afterNextLiquidationId <= 0
                || beforeRiskScanControl == null || afterRiskScanControl == null) {
            throw new IllegalArgumentException("invalid runtime commit entry");
        }
    }

    public TradingCoreState project(TradingCoreState previous) {
        if (previous == null || previous.productLine() != productLine || previous.revision() > revision) {
            throw new IllegalStateException("runtime commit projection is out of order");
        }
        Map<Long, CoreUserState> nextUsers = apply(previous.users(), users);
        Map<Long, CoreOrderState> nextOrders = apply(previous.orders(), orders);
        Map<String, CoreInstrumentState> nextInstruments = apply(previous.instruments(), instruments);
        Map<CoreLeverageKey, Long> nextLeverages = apply(previous.leverages(), leverages);
        Map<Long, CoreAlgoOrderState> nextAlgos = apply(previous.algoOrders(), algoOrders);
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> nextTimers =
                apply(previous.cancelAllAfterTimers(), timers);
        Map<Long, CoreTriggerOrderState> nextTriggers = apply(previous.triggerOrders(), triggers);
        Map<TradingCoreState.ClientOrderKey, Long> nextClients =
                apply(previous.clientOrderIndex(), clientOrders);
        CoreRiskState risk = new CoreRiskState(
                apply(previous.riskState().markPrices(), markPrices),
                apply(previous.riskState().snapshots(), riskSnapshots),
                apply(previous.riskState().liquidations(), liquidations),
                apply(previous.riskState().scans(), riskScans),
                afterNextLiquidationId,
                afterRiskScanControl);
        return new TradingCoreState(productLine, revision, nextUsers, nextOrders, nextInstruments,
                risk, afterTreasury, nextLeverages, nextAlgos, nextTimers, nextClients, nextTriggers);
    }

    public TradingCoreState transitionView(TradingCoreState previous) {
        if (previous == null || previous.productLine() != productLine || previous.revision() > revision) {
            throw new IllegalStateException("runtime commit view is out of order");
        }
        Map<Long, CoreUserState> nextUsers = lazy(previous.users(), users);
        Map<Long, CoreOrderState> nextOrders = lazy(previous.orders(), orders);
        Map<String, CoreInstrumentState> nextInstruments = lazy(previous.instruments(), instruments);
        Map<CoreLeverageKey, Long> nextLeverages = lazy(previous.leverages(), leverages);
        Map<Long, CoreAlgoOrderState> nextAlgos = lazy(previous.algoOrders(), algoOrders);
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> nextTimers =
                lazy(previous.cancelAllAfterTimers(), timers);
        Map<Long, CoreTriggerOrderState> nextTriggers = lazy(previous.triggerOrders(), triggers);
        Map<TradingCoreState.ClientOrderKey, Long> nextClients =
                lazy(previous.clientOrderIndex(), clientOrders);
        CoreRiskState risk = new CoreRiskState(
                lazy(previous.riskState().markPrices(), markPrices),
                lazy(previous.riskState().snapshots(), riskSnapshots),
                lazy(previous.riskState().liquidations(), liquidations),
                lazy(previous.riskState().scans(), riskScans),
                afterNextLiquidationId,
                afterRiskScanControl);
        return new TradingCoreState(productLine, revision, nextUsers, nextOrders, nextInstruments,
                risk, afterTreasury, nextLeverages, nextAlgos, nextTimers, nextClients, nextTriggers);
    }

    public boolean changesBusinessState() {
        return !users.isEmpty() || !orders.isEmpty() || !instruments.isEmpty() || !leverages.isEmpty()
                || !algoOrders.isEmpty() || !timers.isEmpty() || !triggers.isEmpty() || !markPrices.isEmpty()
                || !riskSnapshots.isEmpty() || !liquidations.isEmpty() || !riskScans.isEmpty()
                || !clientOrders.isEmpty() || !beforeTreasury.equals(afterTreasury)
                || beforeNextLiquidationId != afterNextLiquidationId
                || !beforeRiskScanControl.equals(afterRiskScanControl);
    }

    static RuntimeCommitEntry coalesce(List<RuntimeCommitEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("typed commit batch is required");
        }
        RuntimeCommitEntry first = entries.getFirst();
        RuntimeCommitEntry last = entries.getLast();
        long expectedSequence = first.sequence();
        for (RuntimeCommitEntry entry : entries) {
            if (entry.productLine() != first.productLine() || entry.sequence() != expectedSequence) {
                throw new IllegalStateException("typed commit batch is not contiguous");
            }
            expectedSequence = Math.incrementExact(expectedSequence);
        }
        return new RuntimeCommitEntry(last.sequence(), last.productLine(), last.revision(),
                merge(entries, RuntimeCommitEntry::users),
                merge(entries, RuntimeCommitEntry::orders),
                merge(entries, RuntimeCommitEntry::instruments),
                merge(entries, RuntimeCommitEntry::leverages),
                merge(entries, RuntimeCommitEntry::algoOrders),
                merge(entries, RuntimeCommitEntry::timers),
                merge(entries, RuntimeCommitEntry::triggers),
                merge(entries, RuntimeCommitEntry::markPrices),
                merge(entries, RuntimeCommitEntry::riskSnapshots),
                merge(entries, RuntimeCommitEntry::liquidations),
                merge(entries, RuntimeCommitEntry::riskScans),
                merge(entries, RuntimeCommitEntry::clientOrders),
                first.beforeTreasury(), last.afterTreasury(),
                first.beforeNextLiquidationId(), last.afterNextLiquidationId(),
                first.beforeRiskScanControl(), last.afterRiskScanControl());
    }

    private static <K extends Comparable<? super K>, V> Changes<K, V> merge(
            List<RuntimeCommitEntry> entries,
            java.util.function.Function<RuntimeCommitEntry, Changes<K, V>> selector) {
        TreeSet<K> keys = new TreeSet<>();
        TreeMap<K, V> before = new TreeMap<>();
        TreeMap<K, V> after = new TreeMap<>();
        for (RuntimeCommitEntry entry : entries) {
            Changes<K, V> changes = selector.apply(entry);
            for (K key : changes.keys()) {
                if (keys.add(key)) {
                    V initial = changes.before(key);
                    if (initial != null) before.put(key, initial);
                }
                V current = changes.after(key);
                if (current == null) after.remove(key); else after.put(key, current);
            }
        }
        keys.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        before.keySet().retainAll(keys);
        after.keySet().retainAll(keys);
        return new Changes<>(keys, before, after);
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> apply(
            Map<K, V> previous, Changes<K, V> changes) {
        if (changes.isEmpty()) return previous;
        Map<K, V> next = StateMapSupport.delta(previous);
        for (K key : changes.keys()) {
            V value = changes.after(key);
            if (value == null) next.remove(key); else next.put(key, value);
        }
        return next;
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> lazy(
            Map<K, V> previous, Changes<K, V> changes) {
        return changes.isEmpty() ? previous : StateMapSupport.lazyDelta(previous, changes);
    }

    public record Changes<K extends Comparable<? super K>, V>(
            Set<K> keys, Map<K, V> beforeValues, Map<K, V> afterValues) {

        private static final Changes<String, Object> EMPTY = new Changes<String, Object>(
                Set.<String>of(), Map.<String, Object>of(), Map.<String, Object>of());

        public Changes {
            if (keys == null || beforeValues == null || afterValues == null
                    || !keys.containsAll(beforeValues.keySet()) || !keys.containsAll(afterValues.keySet())) {
                throw new IllegalArgumentException("invalid typed commit changes");
            }
            keys = Collections.unmodifiableSet(new TreeSet<>(keys));
            beforeValues = Collections.unmodifiableMap(new TreeMap<>(beforeValues));
            afterValues = Collections.unmodifiableMap(new TreeMap<>(afterValues));
        }

        public V before(K key) {
            return beforeValues.get(key);
        }

        public V after(K key) {
            return afterValues.get(key);
        }

        public boolean isEmpty() {
            return keys.isEmpty();
        }

        static <K extends Comparable<? super K>, V> Changes<K, V> capture(
                Set<K> keys, Map<K, V> before, Map<K, V> after) {
            if (keys.isEmpty()) return empty();
            TreeMap<K, V> beforeValues = new TreeMap<>();
            TreeMap<K, V> afterValues = new TreeMap<>();
            for (K key : keys) {
                V previous = before.get(key);
                V current = after.get(key);
                if (previous != null) beforeValues.put(key, previous);
                if (current != null) afterValues.put(key, current);
            }
            return new Changes<>(keys, beforeValues, afterValues);
        }

        @SuppressWarnings("unchecked")
        private static <K extends Comparable<? super K>, V> Changes<K, V> empty() {
            return (Changes<K, V>) (Changes<?, ?>) EMPTY;
        }
    }
}
