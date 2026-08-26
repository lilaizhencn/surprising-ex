package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RuntimeMutationDelta {

    private final ProductLine productLine;
    private final long revision;
    private final int pendingReservationCount;
    private final ValueChanges<Long, UserValue> users;
    private final ValueChanges<Long, OrderRuntime> orders;
    private final ValueChanges<Long, ReservationRuntime> reservations;
    private final Set<Long> pendingReservations;
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
                         Set<Long> pendingReservations,
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
        this.pendingReservations = Collections.unmodifiableSet(new TreeSet<>(pendingReservations));
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
    Set<Long> pendingReservations() { return pendingReservations; }
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

    record ValueChanges<K, V>(Set<K> changedKeys, Map<K, V> currentValues) {
        ValueChanges {
            if (changedKeys == null || currentValues == null || !changedKeys.containsAll(currentValues.keySet())) {
                throw new IllegalArgumentException("invalid runtime value changes");
            }
            TreeSet<K> keys = new TreeSet<>(changedKeys);
            TreeMap<K, V> values = new TreeMap<>(currentValues);
            if (values.values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("runtime values cannot contain null");
            }
            changedKeys = Collections.unmodifiableSet(keys);
            currentValues = Collections.unmodifiableMap(values);
        }

        static <K, V> ValueChanges<K, V> of(Set<K> changedKeys, Map<K, V> currentValues) {
            return new ValueChanges<>(changedKeys, currentValues);
        }
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

    record CaptureRequest(long[] userIds, Map<Long, int[]> balanceAssetIds,
                          long[] orderIds, long[] reservationIds, long[] positionKeys,
                          long[] liquidationIds, long[] riskSnapshotKeys,
                          Set<CoreLeverageKey> leverageKeys, long[] algoOrderIds,
                          long[] triggerOrderIds, Map<Long, long[]> clientKeysByUser) {
    }

    record LaneValues(int pendingReservationCount,
                      Map<Long, UserValue> users,
                      Map<Long, OrderRuntime> orders,
                      Map<Long, ReservationRuntime> reservations,
                      Set<Long> pendingReservations,
                      Map<Long, PositionRuntime> positions,
                      Map<Long, LiquidationRuntime> liquidations,
                      Map<Long, RiskSnapshotRuntime> riskSnapshots,
                      Map<CoreLeverageKey, Long> leverages,
                      Map<Long, CoreAlgoOrderState> algoOrders,
                      Map<Long, CoreTriggerOrderState> triggerOrders,
                      Map<RuntimeClientKey, Long> clientOrders) {
    }

    static LaneValues captureLane(AccountLaneState lane, CaptureRequest request) {
        TreeMap<Long, UserValue> users = new TreeMap<>();
        for (long userId : request.userIds()) {
            if (!lane.owns(userId)) continue;
            UserRuntime user = lane.users.get(userId);
            if (user == null) continue;
            TreeSet<Integer> changedAssets = new TreeSet<>();
            TreeMap<Integer, BalanceValue> balances = new TreeMap<>();
            int[] assetIds = request.balanceAssetIds().get(userId);
            if (assetIds != null) {
                for (int assetId : assetIds) {
                    changedAssets.add(assetId);
                    BalanceRuntime balance = lane.balances.get(userId) == null
                            ? null : lane.balances.get(userId).get(assetId);
                    if (balance != null) {
                        balances.put(assetId, new BalanceValue(balance.availableUnits(), balance.lockedUnits(),
                                lane.pendingReservedUnits(userId, assetId)));
                    }
                }
            }
            users.put(userId, new UserValue(user, lane.pendingReservationCount(userId),
                    ValueChanges.of(changedAssets, balances)));
        }

        TreeMap<Long, OrderRuntime> orders = present(request.orderIds(), lane.orders);
        TreeMap<Long, ReservationRuntime> reservations = present(request.reservationIds(), lane.reservations);
        TreeSet<Long> pendingReservations = new TreeSet<>();
        for (long orderId : request.reservationIds()) {
            if (lane.pendingReservationSequences.containsKey(orderId)) pendingReservations.add(orderId);
        }
        TreeMap<Long, PositionRuntime> positions = present(request.positionKeys(), lane.positions);
        TreeMap<Long, LiquidationRuntime> liquidations = present(request.liquidationIds(), lane.liquidations);
        TreeMap<Long, RiskSnapshotRuntime> riskSnapshots = present(request.riskSnapshotKeys(), lane.riskSnapshots);
        TreeMap<CoreLeverageKey, Long> leverages = new TreeMap<>();
        for (CoreLeverageKey key : request.leverageKeys()) {
            if (!lane.owns(key.userId())) continue;
            Long value = lane.leverages.get(key);
            if (value != null) leverages.put(key, value);
        }
        TreeMap<Long, CoreAlgoOrderState> algoOrders = present(request.algoOrderIds(), lane.algoOrders);
        TreeMap<Long, CoreTriggerOrderState> triggerOrders = present(request.triggerOrderIds(), lane.triggerOrders);
        TreeMap<RuntimeClientKey, Long> clientOrders = new TreeMap<>();
        request.clientKeysByUser().forEach((userId, clientKeys) -> {
            if (!lane.owns(userId)) return;
            var values = lane.clientOrderIndex.get(userId);
            if (values == null) return;
            for (long clientKey : clientKeys) {
                Long orderId = values.get(clientKey);
                if (orderId != null && !lane.pendingReservation(orderId)) {
                    clientOrders.put(new RuntimeClientKey(userId, clientKey), orderId);
                }
            }
        });
        return new LaneValues(lane.pendingReservationSequences.size(), users, orders, reservations,
                pendingReservations, positions, liquidations, riskSnapshots, leverages, algoOrders,
                triggerOrders, clientOrders);
    }

    private static <V> TreeMap<Long, V> present(
            long[] keys, org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<V> values) {
        TreeMap<Long, V> result = new TreeMap<>();
        for (long key : keys) {
            V value = values.get(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }

    private static <V> TreeMap<Long, V> present(long[] keys, Map<Long, V> values) {
        TreeMap<Long, V> result = new TreeMap<>();
        for (long key : keys) {
            V value = values.get(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }
}
