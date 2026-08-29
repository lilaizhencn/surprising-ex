package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.Arrays;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class RuntimeMutationDelta {

    private static final LaneValues EMPTY_LANE_VALUES = new LaneValues(
            0, new LongObjectHashMap<>(), new LongObjectHashMap<>(), new LongObjectHashMap<>(), new LongHashSet(),
            new LongObjectHashMap<>(), new LongObjectHashMap<>(), new LongObjectHashMap<>(), Map.of(),
            new LongObjectHashMap<>(), new LongObjectHashMap<>(), Map.of());

    private final ProductLine productLine;
    private final long revision;
    private final int pendingReservationCount;
    private final ValueChanges<Long, UserValue> users;
    private final ValueChanges<Long, OrderRuntime> orders;
    private final ValueChanges<Long, ReservationRuntime> reservations;
    private final LongHashSet pendingReservations;
    private final ValueChanges<Long, PositionRuntime> positions;
    private final ValueChanges<Long, LiquidationRuntime> liquidations;
    private final ValueChanges<Long, RiskSnapshotRuntime> riskSnapshots;
    private final ValueChanges<CoreLeverageKey, Long> leverages;
    private final ValueChanges<Long, CoreAlgoOrderState> algoOrders;
    private final ValueChanges<Long, CoreTriggerOrderState> triggerOrders;
    private final ValueChanges<RuntimeClientKey, Long> clientOrders;
    private final ValueChanges<Integer, MarkPriceRuntime> markPrices;
    private final ValueChanges<Integer, RiskScanRuntime> riskScans;
    private final ValueChanges<String, CoreInstrumentState> instruments;
    private final ValueChanges<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers;
    private final TreasuryValues treasury;
    private final long nextLiquidationId;
    private final CoreRiskScanControlView riskScanControl;

    RuntimeMutationDelta(ProductLine productLine, long revision, int pendingReservationCount,
                         ValueChanges<Long, UserValue> users,
                         ValueChanges<Long, OrderRuntime> orders,
                         ValueChanges<Long, ReservationRuntime> reservations,
                         LongHashSet pendingReservations,
                         ValueChanges<Long, PositionRuntime> positions,
                         ValueChanges<Long, LiquidationRuntime> liquidations,
                         ValueChanges<Long, RiskSnapshotRuntime> riskSnapshots,
                         ValueChanges<CoreLeverageKey, Long> leverages,
                         ValueChanges<Long, CoreAlgoOrderState> algoOrders,
                         ValueChanges<Long, CoreTriggerOrderState> triggerOrders,
                         ValueChanges<RuntimeClientKey, Long> clientOrders,
                         ValueChanges<Integer, MarkPriceRuntime> markPrices,
                         ValueChanges<Integer, RiskScanRuntime> riskScans,
                         ValueChanges<String, CoreInstrumentState> instruments,
                         ValueChanges<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers,
                         TreasuryValues treasury, long nextLiquidationId,
                         CoreRiskScanControlView riskScanControl) {
        if (productLine == null || revision < 0 || pendingReservationCount < 0 || users == null
                || orders == null || reservations == null || pendingReservations == null || positions == null
                || liquidations == null || riskSnapshots == null || leverages == null || algoOrders == null
                || triggerOrders == null || clientOrders == null || markPrices == null || riskScans == null
                || instruments == null || timers == null || treasury == null || nextLiquidationId <= 0
                || riskScanControl == null) {
            throw new IllegalArgumentException("invalid runtime mutation delta");
        }
        this.productLine = productLine;
        this.revision = revision;
        this.pendingReservationCount = pendingReservationCount;
        this.users = users;
        this.orders = orders;
        this.reservations = reservations;
        this.pendingReservations = new LongHashSet(pendingReservations);
        this.positions = positions;
        this.liquidations = liquidations;
        this.riskSnapshots = riskSnapshots;
        this.leverages = leverages;
        this.algoOrders = algoOrders;
        this.triggerOrders = triggerOrders;
        this.clientOrders = clientOrders;
        this.markPrices = markPrices;
        this.riskScans = riskScans;
        this.instruments = instruments;
        this.timers = timers;
        this.treasury = treasury;
        this.nextLiquidationId = nextLiquidationId;
        this.riskScanControl = riskScanControl;
    }

    public ProductLine productLine() { return productLine; }
    public long revision() { return revision; }
    public int pendingReservationCount() { return pendingReservationCount; }
    ValueChanges<Long, UserValue> users() { return users; }
    ValueChanges<Long, OrderRuntime> orders() { return orders; }
    ValueChanges<Long, ReservationRuntime> reservations() { return reservations; }
    LongHashSet pendingReservations() { return pendingReservations; }
    ValueChanges<Long, PositionRuntime> positions() { return positions; }
    ValueChanges<Long, LiquidationRuntime> liquidations() { return liquidations; }
    ValueChanges<Long, RiskSnapshotRuntime> riskSnapshots() { return riskSnapshots; }
    ValueChanges<CoreLeverageKey, Long> leverages() { return leverages; }
    ValueChanges<Long, CoreAlgoOrderState> algoOrders() { return algoOrders; }
    ValueChanges<Long, CoreTriggerOrderState> triggerOrders() { return triggerOrders; }
    ValueChanges<RuntimeClientKey, Long> clientOrders() { return clientOrders; }
    ValueChanges<Integer, MarkPriceRuntime> markPrices() { return markPrices; }
    ValueChanges<Integer, RiskScanRuntime> riskScans() { return riskScans; }
    ValueChanges<String, CoreInstrumentState> instruments() { return instruments; }
    ValueChanges<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers() { return timers; }
    TreasuryValues treasury() { return treasury; }
    long nextLiquidationId() { return nextLiquidationId; }
    CoreRiskScanControlView riskScanControl() { return riskScanControl; }

    record BalanceValue(long availableUnits, long lockedUnits, long pendingReservedUnits) {
        BalanceValue {
            if (availableUnits < 0 || lockedUnits < 0 || pendingReservedUnits < 0
                    || pendingReservedUnits > lockedUnits) {
                throw new IllegalArgumentException("invalid captured runtime balance");
            }
        }
    }

    record UserValue(UserRuntime user, int pendingReservationCount,
                     ValueChanges<Integer, BalanceValue> balances) {
        UserValue {
            if (user == null || pendingReservationCount < 0 || balances == null) {
                throw new IllegalArgumentException("invalid captured runtime user");
            }
        }
    }

    static final class ValueChanges<K extends Comparable<? super K>, V> {
        private static final ValueChanges<?, ?> EMPTY = new ValueChanges<>(new Object[0], new Object[0], 0);
        private final CompactKeyList<K> changedKeys;
        private final CompactValueMap<K, V> currentValues;

        private ValueChanges(Object keys, Object[] values, int presentValues) {
            this.changedKeys = new CompactKeyList<>(keys);
            this.currentValues = new CompactValueMap<>(keys, values, presentValues);
        }

        private ValueChanges(Collection<K> changedKeys, Map<K, V> currentValues) {
            if (changedKeys == null || currentValues == null) {
                throw new IllegalArgumentException("invalid runtime value changes");
            }
            ArrayList<K> orderedKeys = new ArrayList<>(changedKeys);
            orderedKeys.sort(null);
            int uniqueSize = 0;
            for (K key : orderedKeys) {
                if (key == null) throw new IllegalArgumentException("runtime change key cannot be null");
                if (uniqueSize == 0 || !key.equals(orderedKeys.get(uniqueSize - 1))) {
                    orderedKeys.set(uniqueSize++, key);
                }
            }
            for (V value : currentValues.values()) {
                if (value == null) throw new IllegalArgumentException("runtime values cannot contain null");
            }
            Object[] keys = orderedKeys.subList(0, uniqueSize).toArray();
            Object[] values = new Object[uniqueSize];
            int presentValues = 0;
            for (int index = 0; index < uniqueSize; index++) {
                @SuppressWarnings("unchecked") K key = (K) keys[index];
                V value = currentValues.get(key);
                values[index] = value;
                if (value != null) presentValues++;
            }
            if (presentValues != currentValues.size()) {
                throw new IllegalArgumentException("runtime values must belong to changed keys");
            }
            this.changedKeys = new CompactKeyList<>(keys);
            this.currentValues = new CompactValueMap<>(keys, values, presentValues);
        }

        static <K extends Comparable<? super K>, V> ValueChanges<K, V> of(
                Collection<K> changedKeys, Map<K, V> currentValues) {
            if (changedKeys == null || currentValues == null) {
                throw new IllegalArgumentException("invalid runtime value changes");
            }
            if (changedKeys.isEmpty()) {
                if (!currentValues.isEmpty()) {
                    throw new IllegalArgumentException("runtime values must belong to changed keys");
                }
                return empty();
            }
            return new ValueChanges<>(changedKeys, currentValues);
        }

        static <V> ValueChanges<Long, V> ofLong(long[] changedKeys, Map<Long, V> currentValues) {
            if (changedKeys == null || currentValues == null) {
                throw new IllegalArgumentException("invalid runtime long value changes");
            }
            if (changedKeys.length == 0) {
                if (!currentValues.isEmpty()) {
                    throw new IllegalArgumentException("runtime values must belong to changed keys");
                }
                return empty();
            }
            long[] ordered = changedKeys.clone();
            Arrays.sort(ordered);
            int uniqueSize = 0;
            for (long key : ordered) {
                if (uniqueSize == 0 || key != ordered[uniqueSize - 1]) ordered[uniqueSize++] = key;
            }
            ordered = Arrays.copyOf(ordered, uniqueSize);
            Object[] values = new Object[uniqueSize];
            int presentValues = 0;
            for (int index = 0; index < uniqueSize; index++) {
                V value = currentValues.get(ordered[index]);
                values[index] = value;
                if (value != null) presentValues++;
            }
            if (presentValues != currentValues.size()) {
                throw new IllegalArgumentException("runtime values must belong to changed keys");
            }
            return new ValueChanges<>(ordered, values, presentValues);
        }

        static <V> ValueChanges<Long, V> ofLong(
                long[] changedKeys, LongObjectHashMap<V> currentValues) {
            if (changedKeys == null || currentValues == null) {
                throw new IllegalArgumentException("invalid runtime primitive long value changes");
            }
            if (changedKeys.length == 0) {
                if (!currentValues.isEmpty()) {
                    throw new IllegalArgumentException("runtime values must belong to changed keys");
                }
                return empty();
            }
            long[] ordered = changedKeys.clone();
            Arrays.sort(ordered);
            int uniqueSize = 0;
            for (long key : ordered) {
                if (uniqueSize == 0 || key != ordered[uniqueSize - 1]) ordered[uniqueSize++] = key;
            }
            ordered = Arrays.copyOf(ordered, uniqueSize);
            Object[] values = new Object[uniqueSize];
            int presentValues = 0;
            for (int index = 0; index < uniqueSize; index++) {
                V value = currentValues.get(ordered[index]);
                values[index] = value;
                if (value != null) presentValues++;
            }
            if (presentValues != currentValues.size()) {
                throw new IllegalArgumentException("runtime values must belong to changed keys");
            }
            return new ValueChanges<>(ordered, values, presentValues);
        }

        static <V> ValueChanges<Integer, V> ofInt(int[] changedKeys, Map<Integer, V> currentValues) {
            if (changedKeys == null || currentValues == null) {
                throw new IllegalArgumentException("invalid runtime int value changes");
            }
            if (changedKeys.length == 0) {
                if (!currentValues.isEmpty()) {
                    throw new IllegalArgumentException("runtime values must belong to changed keys");
                }
                return empty();
            }
            int[] ordered = changedKeys.clone();
            Arrays.sort(ordered);
            int uniqueSize = 0;
            for (int key : ordered) {
                if (uniqueSize == 0 || key != ordered[uniqueSize - 1]) ordered[uniqueSize++] = key;
            }
            ordered = Arrays.copyOf(ordered, uniqueSize);
            Object[] values = new Object[uniqueSize];
            int presentValues = 0;
            for (int index = 0; index < uniqueSize; index++) {
                V value = currentValues.get(ordered[index]);
                values[index] = value;
                if (value != null) presentValues++;
            }
            if (presentValues != currentValues.size()) {
                throw new IllegalArgumentException("runtime values must belong to changed keys");
            }
            return new ValueChanges<>(ordered, values, presentValues);
        }

        @SuppressWarnings("unchecked")
        static <K extends Comparable<? super K>, V> ValueChanges<K, V> empty() {
            return (ValueChanges<K, V>) EMPTY;
        }

        java.util.List<K> changedKeys() { return changedKeys; }
        Map<K, V> currentValues() { return currentValues; }

        V currentValue(K key) { return currentValues.get(key); }

        boolean containsCurrent(K key) { return currentValues.containsKey(key); }

        void forEachCurrentValue(java.util.function.Consumer<? super V> consumer) {
            currentValues.forEachValue(consumer);
        }
    }

    private static final class CompactKeyList<K> extends AbstractList<K> implements RandomAccess {
        private final Object keys;

        private CompactKeyList(Object keys) {
            this.keys = keys;
        }

        @Override
        public K get(int index) {
            Objects.checkIndex(index, keyCount(keys));
            @SuppressWarnings("unchecked") K key = (K) keyAt(keys, index);
            return key;
        }

        @Override
        public int size() {
            return keyCount(keys);
        }
    }

    private static final class CompactValueMap<K, V> extends AbstractMap<K, V> {
        private final Object keys;
        private final Object[] values;
        private final int[] indexTable;
        private final int indexMask;
        private final int size;

        private CompactValueMap(Object keys, Object[] values, int size) {
            this.keys = keys;
            this.values = values;
            int keyCount = keyCount(keys);
            if (keyCount <= 4) {
                indexTable = null;
                indexMask = 0;
            } else {
                int tableSize = 2;
                while (tableSize < keyCount * 2) tableSize <<= 1;
                indexTable = new int[tableSize];
                indexMask = tableSize - 1;
                for (int index = 0; index < keyCount; index++) {
                    int slot = spread(keyHash(keys, index)) & indexMask;
                    while (indexTable[slot] != 0) slot = (slot + 1) & indexMask;
                    indexTable[slot] = index + 1;
                }
            }
            this.size = size;
        }

        @Override
        public V get(Object key) {
            if (key == null) return null;
            if (indexTable == null) {
                for (int index = 0; index < keyCount(keys); index++) {
                    if (keyEquals(keys, index, key)) {
                        @SuppressWarnings("unchecked") V value = (V) values[index];
                        return value;
                    }
                }
                return null;
            }
            int slot = spread(key.hashCode()) & indexMask;
            int encodedIndex;
            while ((encodedIndex = indexTable[slot]) != 0) {
                int index = encodedIndex - 1;
                if (keyEquals(keys, index, key)) {
                    @SuppressWarnings("unchecked") V value = (V) values[index];
                    return value;
                }
                slot = (slot + 1) & indexMask;
            }
            return null;
        }

        private static int spread(int hash) {
            return hash ^ hash >>> 16;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public int size() {
            return size;
        }

        private void forEachValue(java.util.function.Consumer<? super V> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            for (Object value : values) {
                if (value == null) continue;
                @SuppressWarnings("unchecked") V current = (V) value;
                consumer.accept(current);
            }
        }

        @Override
        public void forEach(java.util.function.BiConsumer<? super K, ? super V> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            for (int index = 0; index < keyCount(keys); index++) {
                if (values[index] == null) continue;
                @SuppressWarnings("unchecked") K key = (K) keyAt(keys, index);
                @SuppressWarnings("unchecked") V value = (V) values[index];
                consumer.accept(key, value);
            }
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<K, V>> iterator() {
                    return new Iterator<>() {
                        private int next = advance(0);

                        @Override
                        public boolean hasNext() {
                            return next < keyCount(keys);
                        }

                        @Override
                        public Entry<K, V> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            int current = next;
                            next = advance(current + 1);
                            @SuppressWarnings("unchecked") K key = (K) keyAt(keys, current);
                            @SuppressWarnings("unchecked") V value = (V) values[current];
                            return Map.entry(key, value);
                        }

                        private int advance(int index) {
                            while (index < values.length && values[index] == null) index++;
                            return index;
                        }
                    };
                }

                @Override
                public int size() {
                    return size;
                }
            };
        }
    }

    private static int keyCount(Object keys) {
        if (keys instanceof long[] values) return values.length;
        if (keys instanceof int[] values) return values.length;
        return ((Object[]) keys).length;
    }

    private static Object keyAt(Object keys, int index) {
        if (keys instanceof long[] values) return values[index];
        if (keys instanceof int[] values) return values[index];
        return ((Object[]) keys)[index];
    }

    private static int keyHash(Object keys, int index) {
        if (keys instanceof long[] values) return Long.hashCode(values[index]);
        if (keys instanceof int[] values) return Integer.hashCode(values[index]);
        return ((Object[]) keys)[index].hashCode();
    }

    private static boolean keyEquals(Object keys, int index, Object key) {
        if (keys instanceof long[] values) return key instanceof Long value && values[index] == value;
        if (keys instanceof int[] values) return key instanceof Integer value && values[index] == value;
        return ((Object[]) keys)[index].equals(key);
    }

    record TreasuryValues(ValueChanges<Integer, AssetLedger> assets,
                          ValueChanges<Integer, FundingLedger> funding,
                          ValueChanges<Integer, LifecycleLedger> lifecycle) {
        TreasuryValues {
            if (assets == null || funding == null || lifecycle == null) {
                throw new IllegalArgumentException("invalid captured treasury values");
            }
        }
    }

    record AssetLedger(long fee, long insurance, long deficit, long liquidationFee,
                       long fundingResidual, long roundingResidual, long clearingPnl) {
    }

    record FundingLedger(long settlementId, TreasuryRuntime.FundingProgressRuntime progress) {
    }

    record LifecycleLedger(long settlementId, TreasuryRuntime.LifecycleProgressRuntime progress) {
    }

    record RuntimeClientKey(long userId, long clientKey) implements Comparable<RuntimeClientKey> {
        RuntimeClientKey {
            if (userId <= 0 || clientKey <= 0) throw new IllegalArgumentException("invalid runtime client key");
        }

        @Override
        public int compareTo(RuntimeClientKey other) {
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Long.compare(clientKey, other.clientKey);
        }
    }

    record CaptureRequest(long[] userIds, LongObjectHashMap<int[]> balanceAssetIds,
                          long[] orderIds, long[] reservationIds, long[] positionKeys,
                          long[] liquidationIds, long[] riskSnapshotKeys,
                          Set<CoreLeverageKey> leverageKeys, long[] algoOrderIds,
                          long[] triggerOrderIds, LongObjectHashMap<long[]> clientKeysByUser) {
    }

    record LaneValues(int pendingReservationCount,
                      LongObjectHashMap<UserValue> users,
                      LongObjectHashMap<OrderRuntime> orders,
                      LongObjectHashMap<ReservationRuntime> reservations,
                      LongHashSet pendingReservations,
                      LongObjectHashMap<PositionRuntime> positions,
                      LongObjectHashMap<LiquidationRuntime> liquidations,
                      LongObjectHashMap<RiskSnapshotRuntime> riskSnapshots,
                      Map<CoreLeverageKey, Long> leverages,
                      LongObjectHashMap<CoreAlgoOrderState> algoOrders,
                      LongObjectHashMap<CoreTriggerOrderState> triggerOrders,
                      Map<RuntimeClientKey, Long> clientOrders) {
    }

    static LaneValues emptyLaneValues() {
        return EMPTY_LANE_VALUES;
    }

    static LaneValues captureLane(AccountLaneState lane, CaptureRequest request) {
        LongObjectHashMap<UserValue> users = null;
        for (long userId : request.userIds()) {
            if (!lane.owns(userId)) continue;
            UserRuntime user = lane.users.get(userId);
            if (user == null) continue;
            int[] assetIds = request.balanceAssetIds().get(userId);
            int assetCount = assetIds == null ? 0 : assetIds.length;
            Map<Integer, BalanceValue> balances = assetCount == 0 ? Map.of() : HashMap.newHashMap(assetCount);
            if (assetIds != null) {
                for (int assetId : assetIds) {
                    BalanceRuntime balance = lane.balances.get(userId) == null
                            ? null : lane.balances.get(userId).get(assetId);
                    if (balance != null) {
                        balances.put(assetId, new BalanceValue(balance.availableUnits(), balance.lockedUnits(),
                                lane.pendingReservedUnits(userId, assetId)));
                    }
                }
            }
            if (users == null) users = new LongObjectHashMap<>(request.userIds().length);
            users.put(userId, new UserValue(user, lane.pendingReservationCount(userId),
                    ValueChanges.ofInt(assetIds == null ? new int[0] : assetIds, balances)));
        }

        LongObjectHashMap<OrderRuntime> orders = present(request.orderIds(), lane.orders);
        LongObjectHashMap<ReservationRuntime> reservations = present(request.reservationIds(), lane.reservations);
        LongHashSet pendingReservations = null;
        for (long orderId : request.reservationIds()) {
            if (lane.pendingReservationSequences.containsKey(orderId)) {
                if (pendingReservations == null) {
                    pendingReservations = new LongHashSet(request.reservationIds().length);
                }
                pendingReservations.add(orderId);
            }
        }
        LongObjectHashMap<PositionRuntime> positions = present(request.positionKeys(), lane.positions);
        LongObjectHashMap<LiquidationRuntime> liquidations = present(request.liquidationIds(), lane.liquidations);
        LongObjectHashMap<RiskSnapshotRuntime> riskSnapshots = present(request.riskSnapshotKeys(), lane.riskSnapshots);
        Map<CoreLeverageKey, Long> leverages = null;
        for (CoreLeverageKey key : request.leverageKeys()) {
            if (!lane.owns(key.userId())) continue;
            Long value = lane.leverages.get(key);
            if (value != null) {
                if (leverages == null) leverages = HashMap.newHashMap(request.leverageKeys().size());
                leverages.put(key, value);
            }
        }
        LongObjectHashMap<CoreAlgoOrderState> algoOrders = present(request.algoOrderIds(), lane.algoOrders);
        LongObjectHashMap<CoreTriggerOrderState> triggerOrders = present(request.triggerOrderIds(), lane.triggerOrders);
        Map<RuntimeClientKey, Long> clientOrders = null;
        for (long userId : request.clientKeysByUser().keySet().toArray()) {
            long[] clientKeys = request.clientKeysByUser().get(userId);
            if (!lane.owns(userId)) continue;
            var values = lane.clientOrderIndex.get(userId);
            if (values == null) continue;
            for (long clientKey : clientKeys) {
                Long orderId = values.get(clientKey);
                if (orderId != null && !lane.pendingReservation(orderId)) {
                    if (clientOrders == null) {
                        clientOrders = HashMap.newHashMap(request.clientKeysByUser().size());
                    }
                    clientOrders.put(new RuntimeClientKey(userId, clientKey), orderId);
                }
            }
        }
        return new LaneValues(lane.pendingReservationSequences.size(), emptyLongMap(users), orders, reservations,
                emptyLongSet(pendingReservations), positions, liquidations, riskSnapshots, emptyIfNull(leverages), algoOrders,
                triggerOrders, emptyIfNull(clientOrders));
    }

    private static <K, V> Map<K, V> emptyIfNull(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static <V> LongObjectHashMap<V> emptyLongMap(LongObjectHashMap<V> values) {
        return values == null ? new LongObjectHashMap<>() : values;
    }

    private static LongHashSet emptyLongSet(LongHashSet values) {
        return values == null ? new LongHashSet() : values;
    }

    private static <V> LongObjectHashMap<V> present(
            long[] keys, org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<V> values) {
        LongObjectHashMap<V> result = null;
        for (long key : keys) {
            V value = values.get(key);
            if (value != null) {
                if (result == null) result = new LongObjectHashMap<>(keys.length);
                result.put(key, value);
            }
        }
        return emptyLongMap(result);
    }

    private static <V> LongObjectHashMap<V> present(long[] keys, Map<Long, V> values) {
        LongObjectHashMap<V> result = null;
        for (long key : keys) {
            V value = values.get(key);
            if (value != null) {
                if (result == null) result = new LongObjectHashMap<>(keys.length);
                result.put(key, value);
            }
        }
        return emptyLongMap(result);
    }
}
