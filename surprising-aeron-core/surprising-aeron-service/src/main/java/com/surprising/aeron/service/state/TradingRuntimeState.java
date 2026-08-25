package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;

public final class TradingRuntimeState {

    public static final int MAX_PENDING_TRANSFERS = 131_072;

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private long revision;
    private final LaneTopology topology;
    private final AccountLaneState[] accountLanes;

    private final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    private final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    private final TreasuryRuntime treasury = new TreasuryRuntime();
    private final Map<String, CoreInstrumentState> instruments = new TreeMap<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
    private final Map<Long, CoreFeePolicyState> feePolicies = new TreeMap<>();
    private final Map<Long, TransferRuntime> pendingTransfers = new TreeMap<>();
    private long nextLiquidationId = 1;
    private CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    private final LongHashSet changedUsers = new LongHashSet();
    private final LongObjectHashMap<IntHashSet> changedBalances = new LongObjectHashMap<>();
    private final LongHashSet changedOrders = new LongHashSet();
    private final LongHashSet changedReservations = new LongHashSet();
    private final LongHashSet changedPositions = new LongHashSet();
    private final LongHashSet changedLiquidations = new LongHashSet();
    private final IntHashSet changedMarkPrices = new IntHashSet();
    private final LongHashSet changedRiskSnapshots = new LongHashSet();
    private final IntHashSet changedRiskScans = new IntHashSet();
    private final LongHashSet changedClientOrders = new LongHashSet();
    private final LongObjectHashMap<LongHashSet> changedClientOrdersByUser = new LongObjectHashMap<>();
    private final TreeSet<String> changedInstruments = new TreeSet<>();
    private final TreeSet<CoreLeverageKey> changedLeverages = new TreeSet<>();
    private final LongHashSet changedAlgoOrders = new LongHashSet();
    private final TreeSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new TreeSet<>();
    private final LongHashSet changedTriggerOrders = new LongHashSet();
    private final LongHashSet changedFeePolicies = new LongHashSet();
    private Thread owner;

    public TradingRuntimeState() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public TradingRuntimeState(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        this.accountLanes = new AccountLaneState[topology.accountLaneCount()];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
        }
    }

    public LaneTopology topology() {
        return topology;
    }

    public AccountLaneState accountLane(long userId) {
        assertOwner();
        return lane(userId);
    }

    private AccountLaneState lane(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return accountLanes[topology.accountLaneId(userId)];
    }

    public AccountLaneState[] accountLanes() {
        assertOwner();
        return accountLanes.clone();
    }

    public AccountLaneState accountLaneById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        return accountLanes[laneId];
    }

    public void markPendingReservation(long userId, long orderId, long coreSequence) {
        assertOwner();
        ReservationRuntime reservation = lane(userId).reservations.get(orderId);
        if (reservation == null || reservation.userId() != userId) {
            throw new IllegalStateException("pending reservation is missing");
        }
        lane(userId).markPendingReservation(orderId, coreSequence);
    }

    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        assertOwner();
        AccountLaneState accountLane = lane(userId);
        ReservationRuntime reservation = accountLane.reservations.get(orderId);
        accountLane.completePendingReservation(orderId, coreSequence);
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        if (reservation != null) changedBalance(userId, reservation.assetId());
        LongObjectHashMap<Long> clients = accountLane.clientOrderIndex.get(userId);
        if (clients != null) clients.forEachKeyValue((clientKey, indexedOrderId) -> {
            if (indexedOrderId == orderId) {
                changedClientOrders.add(clientKey);
                changedClientOrder(userId, clientKey);
            }
        });
    }

    public void completePendingReservations(long coreSequence) {
        assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        for (AccountLaneState lane : accountLanes) {
            long[] orderIds = lane.pendingReservationSequences.keySet().toArray();
            for (long orderId : orderIds) {
                if (lane.pendingReservationSequences.get(orderId) == coreSequence) {
                    ReservationRuntime reservation = lane.reservations.get(orderId);
                    OrderRuntime order = lane.orders.get(orderId);
                    long userId = reservation != null ? reservation.userId()
                            : order == null ? 0 : order.userId();
                    if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
                    completePendingReservation(userId, orderId, coreSequence);
                }
            }
        }
    }

    boolean pendingReservation(long orderId, long userId) {
        assertOwner();
        return lane(userId).pendingReservation(orderId);
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
        return lane(userId).pendingReservedUnits(userId, assetId);
    }

    int pendingReservationCount(long userId) {
        assertOwner();
        return lane(userId).pendingReservationCount(userId);
    }

    int pendingReservationCount() {
        assertOwner();
        int count = 0;
        for (AccountLaneState lane : accountLanes) {
            count = Math.addExact(count, lane.pendingReservationSequences.size());
        }
        return count;
    }

    public boolean hasPendingReservations() {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            if (lane.hasPendingReservations()) return true;
        }
        return false;
    }

    public long applyLaneSequence(long coreSequence, Iterable<Long> userIds,
                                  long stateContribution, long fundsContribution) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) throw new IllegalArgumentException("invalid lane apply");
        long mask = 0;
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            AccountLaneState lane = accountLanes[topology.accountLaneId(userId)];
            if (!lane.owns(userId)) lane.registerUser(userId);
            lane.applied(coreSequence, userId, stateContribution, fundsContribution);
            mask |= 1L << lane.laneId();
        }
        return mask;
    }

    public void commitLaneSequence(long coreSequence, long laneMask) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if (coreSequence <= 0 || (laneMask & ~validMask) != 0) {
            throw new IllegalArgumentException("invalid account lane commit");
        }
        for (AccountLaneState lane : accountLanes) {
            if ((laneMask & (1L << lane.laneId())) != 0) lane.committed(coreSequence);
        }
    }

    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        java.util.List<AccountLaneSnapshot> snapshots = new java.util.ArrayList<>(accountLanes.length);
        for (AccountLaneState lane : accountLanes) {
            snapshots.add(lane.snapshot(fenceSequence,
                    laneSnapshotState(globalState, lane.laneId(), lane.revision())));
        }
        return java.util.List.copyOf(snapshots);
    }

    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        if (snapshots == null || snapshots.size() != accountLanes.length || globalState == null) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        boolean[] restored = new boolean[accountLanes.length];
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= accountLanes.length || restored[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            for (Long userId : snapshot.userIds()) {
                if (topology.accountLaneId(userId) != laneId
                        || accountLanes[laneId].users.get(userId) == null) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
            if (!snapshot.state().equals(
                    laneSnapshotState(globalState, laneId, snapshot.state().revision()))) {
                throw new IllegalArgumentException("account lane payload differs from global state");
            }
            accountLanes[laneId].restore(snapshot);
            restored[laneId] = true;
        }
        for (AccountLaneState lane : accountLanes) {
            lane.users.forEachKey(userId -> {
                if (!lane.owns(userId)) {
                    throw new IllegalArgumentException("runtime user is absent from its account lane");
                }
            });
        }
    }

    private TradingCoreState laneSnapshotState(TradingCoreState state, int laneId, long laneRevision) {
        java.util.Map<Long, CoreUserState> users = new java.util.TreeMap<>();
        state.users().forEach((userId, user) -> {
            if (topology.accountLaneId(userId) == laneId) users.put(userId, user);
        });
        java.util.Map<Long, CoreOrderState> orders = new java.util.TreeMap<>();
        state.orders().forEach((orderId, order) -> {
            if (topology.accountLaneId(order.userId()) == laneId) orders.put(orderId, order);
        });
        java.util.Map<String, CoreRiskSnapshot> risks = new java.util.TreeMap<>();
        state.riskState().snapshots().forEach((key, value) -> {
            if (topology.accountLaneId(value.userId()) == laneId) risks.put(key, value);
        });
        java.util.Map<Long, CoreLiquidationState> liquidations = new java.util.TreeMap<>();
        state.riskState().liquidations().forEach((id, value) -> {
            if (topology.accountLaneId(value.userId()) == laneId) liquidations.put(id, value);
        });
        CoreRiskState riskState = new CoreRiskState(java.util.Map.of(), risks, liquidations,
                java.util.Map.of(), state.riskState().nextLiquidationId(), state.riskState().scanControl());
        java.util.Map<CoreLeverageKey, Long> leverages = new java.util.TreeMap<>();
        state.leverages().forEach((key, value) -> {
            if (topology.accountLaneId(key.userId()) == laneId) leverages.put(key, value);
        });
        java.util.Map<Long, CoreAlgoOrderState> algos = new java.util.TreeMap<>();
        state.algoOrders().forEach((id, value) -> {
            if (topology.accountLaneId(value.userId()) == laneId) algos.put(id, value);
        });
        java.util.Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> timers = new java.util.TreeMap<>();
        state.cancelAllAfterTimers().forEach((key, value) -> {
            if (topology.accountLaneId(key.userId()) == laneId) timers.put(key, value);
        });
        java.util.Map<TradingCoreState.ClientOrderKey, Long> clients = new java.util.TreeMap<>();
        state.clientOrderIndex().forEach((key, value) -> {
            if (topology.accountLaneId(key.userId()) == laneId) clients.put(key, value);
        });
        java.util.Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        state.triggerOrders().forEach((id, value) -> {
            if (topology.accountLaneId(value.userId()) == laneId) triggers.put(id, value);
        });
        return new TradingCoreState(productLine, laneRevision, users, orders,
                java.util.Map.of(), riskState, CoreTreasuryState.empty(), leverages, algos, timers, clients, triggers);
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    public void assertOwner() {
        bindOwner();
    }

    void releaseOwnerForHandoff() {
        for (AccountLaneState lane : accountLanes) {
            lane.balances.forEachValue(values -> values.forEachValue(BalanceRuntime::releaseOwnerForHandoff));
        }
        treasury.releaseOwnerForHandoff();
        owner = null;
    }

    public ProductLine productLine() {
        assertOwner();
        return productLine;
    }

    public long revision() {
        assertOwner();
        return revision;
    }

    public void setMetadata(ProductLine productLine, long revision) {
        assertOwner();
        if (productLine == null || revision < 0) throw new IllegalArgumentException("invalid runtime metadata");
        this.productLine = productLine;
        this.revision = revision;
    }

    public CoreRiskScanControlView riskScanControl() {
        assertOwner();
        return riskScanControl;
    }

    public void setRiskScanControl(CoreRiskScanControlView riskScanControl) {
        assertOwner();
        if (riskScanControl == null) throw new IllegalArgumentException("risk scan control is required");
        this.riskScanControl = riskScanControl;
    }

    public UserRuntime user(long userId) {
        assertOwner();
        return lane(userId).users.get(userId);
    }

    public UserRuntime requireUser(long userId) {
        UserRuntime user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    public BalanceRuntime balance(long userId, int assetId) {
        assertOwner();
        IntObjectHashMap<BalanceRuntime> userBalances = lane(userId).balances.get(userId);
        return userBalances == null ? null : userBalances.get(assetId);
    }

    IntObjectHashMap<BalanceRuntime> balancesForUser(long userId) {
        assertOwner();
        return lane(userId).balances.get(userId);
    }

    LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        LongHashSet orderIds = lane(userId).reservationIdsByUser.get(userId);
        return orderIds == null ? new LongHashSet() : new LongHashSet(orderIds);
    }

    int reservationCountForUser(long userId) {
        assertOwner();
        LongHashSet orderIds = lane(userId).reservationIdsByUser.get(userId);
        return orderIds == null ? 0 : orderIds.size();
    }

    LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        LongHashSet positionKeys = lane(userId).positionKeysByUser.get(userId);
        return positionKeys == null ? new LongHashSet() : new LongHashSet(positionKeys);
    }

    int positionCountForUser(long userId) {
        assertOwner();
        LongHashSet positionKeys = lane(userId).positionKeysByUser.get(userId);
        return positionKeys == null ? 0 : positionKeys.size();
    }

    NavigableSet<CoreLeverageKey> leverageKeysForUser(long userId) {
        assertOwner();
        TreeSet<CoreLeverageKey> keys = lane(userId).leverageKeysByUser.get(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(keys);
    }

    public OrderRuntime order(long orderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            OrderRuntime order = lane.orders.get(orderId);
            if (order != null) return order;
        }
        return null;
    }

    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation != null) return reservation;
        }
        return null;
    }

    public PositionRuntime position(long positionKey) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            PositionRuntime position = lane.positions.get(positionKey);
            if (position != null) return position;
        }
        return null;
    }

    public NavigableSet<Long> positionKeysForUserAndSymbol(long userId, int symbolId) {
        assertOwner();
        LongObjectHashMap<TreeSet<Long>> byUser = lane(userId).positionKeysBySymbolAndUser.get(symbolId);
        TreeSet<Long> keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(keys);
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            LiquidationRuntime liquidation = lane.liquidations.get(liquidationId);
            if (liquidation != null) return liquidation;
        }
        return null;
    }

    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(userId);
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
        Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
        return liquidationId == null ? null : lane.liquidations.get(liquidationId);
    }

    public boolean hasActiveLiquidationConflict(long userId, int symbolId, long excludedLiquidationId) {
        assertOwner();
        boolean[] conflict = new boolean[1];
        for (AccountLaneState lane : accountLanes) {
            if (userId != 0 && lane.laneId() != topology.accountLaneId(userId)) continue;
            lane.liquidations.forEachValue(liquidation -> {
                if (!conflict[0] && liquidation.liquidationId() != excludedLiquidationId
                        && (liquidation.status() == CoreLiquidationState.Status.PLANNED
                        || liquidation.status() == CoreLiquidationState.Status.ORDERED)
                        && (userId == 0 || liquidation.userId() == userId)
                        && (symbolId < 0 || liquidation.symbolId() == symbolId)) {
                    conflict[0] = true;
                }
            });
        }
        return conflict[0];
    }

    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            RiskSnapshotRuntime snapshot = lane.riskSnapshots.get(positionKey);
            if (snapshot != null) return snapshot;
        }
        return null;
    }

    public RiskScanRuntime riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    public RiskScanRuntime firstIncompleteRiskScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachKeyValue((symbolId, scan) -> {
            if (!scan.complete()
                    && (selected[0] == null || symbolId < selected[0].symbolId())) {
                selected[0] = scan;
            }
        });
        return selected[0];
    }

    public RiskScanRuntime firstRiskIncompleteScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachKeyValue((symbolId, scan) -> {
            if (!scan.riskComplete()
                    && (selected[0] == null || symbolId < selected[0].symbolId())) {
                selected[0] = scan;
            }
        });
        return selected[0];
    }

    public int incompleteRiskScanCount() {
        assertOwner();
        int[] count = new int[1];
        riskScans.forEachValue(scan -> {
            if (!scan.complete()) count[0]++;
        });
        return count[0];
    }

    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    public TreasuryRuntime treasury() {
        assertOwner();
        return treasury;
    }

    public CoreInstrumentState instrument(String symbol) {
        assertOwner();
        return instruments.get(OrderReservation.normalizeSymbol(symbol));
    }

    void putInstrument(CoreInstrumentState instrument) {
        assertOwner();
        if (instrument == null) {
            throw new IllegalArgumentException("invalid runtime instrument");
        }
        instruments.put(instrument.symbol(), instrument);
        changedInstruments.add(instrument.symbol());
    }

    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return lane(key.userId()).leverages.get(key);
    }

    void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        AccountLaneState lane = lane(key.userId());
        lane.leverages.put(key, leveragePpm);
        TreeSet<CoreLeverageKey> userKeys = lane.leverageKeysByUser.get(key.userId());
        if (userKeys == null) {
            userKeys = new TreeSet<>();
            lane.leverageKeysByUser.put(key.userId(), userKeys);
        }
        userKeys.add(key);
        changedLeverages.add(key);
    }

    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            CoreAlgoOrderState order = lane.algoOrders.get(algoOrderId);
            if (order != null) return order;
        }
        return null;
    }

    void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        lane(algoOrder.userId()).algoOrders.put(algoOrder.algoOrderId(), algoOrder);
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) lane.algoOrders.remove(algoOrderId);
        changedAlgoOrders.add(algoOrderId);
    }

    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            CoreTriggerOrderState order = lane.triggerOrders.get(triggerOrderId);
            if (order != null) return order;
        }
        return null;
    }

    void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        lane(triggerOrder.userId()).triggerOrders.put(triggerOrder.triggerOrderId(), triggerOrder);
        changedTriggerOrders.add(triggerOrder.triggerOrderId());
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) lane.triggerOrders.remove(triggerOrderId);
        changedTriggerOrders.add(triggerOrderId);
    }

    Map<String, CoreInstrumentState> instrumentsForRuntime() {
        assertOwner();
        return instruments;
    }

    Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.leverages);
        return values;
    }

    Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.algoOrders);
        return values;
    }

    Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.triggerOrders);
        return values;
    }

    Map<Long, CoreFeePolicyState> feePoliciesForRuntime() {
        assertOwner();
        return feePolicies;
    }

    public TransferRuntime pendingTransfer(long transferId) {
        assertOwner();
        return pendingTransfers.get(transferId);
    }

    public Map<Long, TransferRuntime> pendingTransfersSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(pendingTransfers));
    }

    public List<TransferRuntime> pendingTransfers(int limit) {
        assertOwner();
        if (limit <= 0 || limit > com.surprising.aeron.protocol.CorePendingTransferCodec.MAX_RESULTS) {
            throw new IllegalArgumentException("invalid pending transfer limit");
        }
        return pendingTransfers.values().stream().limit(limit).toList();
    }

    public void restorePendingTransfers(Map<Long, TransferRuntime> restored) {
        assertOwner();
        if (restored == null || restored.size() > MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId())) {
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        }
        pendingTransfers.clear();
        pendingTransfers.putAll(restored);
    }

    boolean hasPendingTransferCapacity() {
        assertOwner();
        return pendingTransfers.size() < MAX_PENDING_TRANSFERS;
    }

    void putPendingTransfer(TransferRuntime transfer) {
        assertOwner();
        if (!hasPendingTransferCapacity()) {
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL",
                    "pending transfer runtime capacity is full");
        }
        if (transfer == null || pendingTransfers.putIfAbsent(transfer.transferId(), transfer) != null) {
            throw new IllegalArgumentException("pending transfer already exists");
        }
    }

    boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        TransferRuntime current = pendingTransfers.get(transferId);
        if (current == null) return false;
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        }
        pendingTransfers.remove(transferId);
        return true;
    }

    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(feePolicies));
    }

    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (restored == null) throw new IllegalArgumentException("fee policy snapshot is required");
        feePolicies.clear();
        feePolicies.putAll(restored);
        changedFeePolicies.clear();
    }

    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        CoreFeePolicyState current = feePolicies.get(next.policyId());
        if (current != null && next.policyRevision() < current.policyRevision()) {
            throw new CoreStateRejectedException("STALE_FEE_POLICY_VERSION", "fee policy version must increase");
        }
        if (current != null && next.policyRevision() == current.policyRevision()) {
            if (!current.equals(next)) {
                throw new CoreStateRejectedException("FEE_POLICY_VERSION_CONFLICT",
                        "fee policy version contains different data");
            }
            return;
        }
        feePolicies.put(next.policyId(), next);
        changedFeePolicies.add(next.policyId());
        setMetadata(productLine, Math.incrementExact(revision));
    }

    public CoreFeeRate resolveFee(long userId, String symbol, long clusterTimestamp,
                                  CoreInstrumentState instrument) {
        assertOwner();
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        CoreFeePolicyState selected = feePolicies.values().stream()
                .filter(policy -> policy.effective(userId, normalizedSymbol, clusterTimestamp))
                .min(CoreFeePolicyState::compareTo)
                .orElse(null);
        return selected == null
                ? new CoreFeeRate(instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm(), 0)
                : new CoreFeeRate(selected.makerFeeRatePpm(), selected.takerFeeRatePpm(),
                selected.policyRevision());
    }

    public void replaceAuxiliaryState(TradingCoreState source) {
        assertOwner();
        setMetadata(source.productLine(), source.revision());
        setRiskScanControl(source.riskState().scanControl());
        instruments.clear();
        instruments.putAll(source.instruments());
        for (AccountLaneState lane : accountLanes) {
            lane.leverages.clear();
            lane.leverageKeysByUser.clear();
            lane.algoOrders.clear();
            lane.triggerOrders.clear();
        }
        source.leverages().forEach(this::putLeverage);
        source.algoOrders().values().forEach(this::putAlgoOrder);
        cancelAllAfterTimers.clear();
        cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(this::putTriggerOrder);
    }

    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        LongObjectHashMap<Long> userClientOrders = lane(userId).clientOrderIndex.get(userId);
        return userClientOrders == null ? null : userClientOrders.get(clientKey);
    }

    public void putUser(UserRuntime user) {
        assertOwner();
        AccountLaneState lane = lane(user.userId());
        lane.users.put(user.userId(), user);
        lane.registerUser(user.userId());
        changedUsers.add(user.userId());
    }

    public void advanceUserRevision(long userId) {
        assertOwner();
        UserRuntime current = requireUser(userId);
        lane(userId).users.put(userId, new UserRuntime(current.productLine(), userId,
                Math.incrementExact(current.revision()), current.positionMode()));
        changedUsers.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders != null) {
            userClientOrders.forEachKey(clientKey -> {
                changedClientOrders.add(clientKey);
                changedClientOrder(userId, clientKey);
            });
        }
        lane.users.remove(userId);
        lane.removeUser(userId);
        lane.balances.remove(userId);
        lane.clientOrderIndex.remove(userId);
        lane.reservationIdsByUser.remove(userId);
        lane.positionKeysByUser.remove(userId);
        lane.leverageKeysByUser.remove(userId);
        changedUsers.add(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        balance.bindOwner();
        AccountLaneState lane = lane(balance.userId());
        IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(balance.userId());
        if (userBalances == null) {
            userBalances = new IntObjectHashMap<>();
            lane.balances.put(balance.userId(), userBalances);
        }
        userBalances.put(balance.assetId(), balance);
        changedBalance(balance.userId(), balance.assetId());
        changedUsers.add(balance.userId());
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        lane(order.userId()).orders.put(order.orderId(), order);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null) {
            AccountLaneState previousLane = lane(previous.userId());
            previousLane.reservations.remove(previous.orderId());
            removeUserEntity(previousLane.reservationIdsByUser, previous.userId(), previous.orderId());
        }
        AccountLaneState lane = lane(reservation.userId());
        lane.reservations.put(reservation.orderId(), reservation);
        addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        OrderRuntime previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        lane(order.userId()).orders.put(order.orderId(), order);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void removeOrder(long orderId) {
        assertOwner();
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            lane(previous.userId()).orders.remove(orderId);
            changedOrders.add(orderId);
            changedUsers.add(previous.userId());
        }
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null) {
            AccountLaneState previousLane = lane(previous.userId());
            previousLane.reservations.remove(previous.orderId());
            removeUserEntity(previousLane.reservationIdsByUser, previous.userId(), previous.orderId());
        }
        AccountLaneState lane = lane(reservation.userId());
        lane.reservations.put(reservation.orderId(), reservation);
        addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        ReservationRuntime current = lane.reservations.get(orderId);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        }
        lane.reservations.remove(orderId);
        removeUserEntity(lane.reservationIdsByUser, userId, orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
    }

    public void replaceBalance(BalanceRuntime balance) {
        assertOwner();
        BalanceRuntime current = balance(balance.userId(), balance.assetId());
        if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
        current.replace(balance.availableUnits(), balance.lockedUnits());
        changedBalance(balance.userId(), balance.assetId());
        changedUsers.add(balance.userId());
    }

    public void removeBalance(long userId, int assetId) {
        assertOwner();
        IntObjectHashMap<BalanceRuntime> userBalances = lane(userId).balances.get(userId);
        if (userBalances == null || userBalances.remove(assetId) == null) {
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + '/' + assetId);
        }
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        PositionRuntime previous = position(positionKey);
        if (previous != null) unindexPosition(positionKey, previous);
        lane(position.userId()).positions.put(positionKey, position);
        indexPosition(positionKey, position);
        changedPositions.add(positionKey);
        changedUsers.add(position.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        if (liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        lane(liquidation.userId()).liquidations.put(liquidation.liquidationId(), liquidation);
        indexActiveLiquidation(liquidation);
        changedLiquidations.add(liquidation.liquidationId());
        changedUsers.add(liquidation.userId());
    }

    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        lane(snapshot.userId()).riskSnapshots.put(positionKey, snapshot);
        changedRiskSnapshots.add(positionKey);
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        this.nextLiquidationId = nextLiquidationId;
    }

    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        LiquidationRuntime previous = liquidation(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        removeActiveLiquidation(previous);
        if (previous.userId() != liquidation.userId()) {
            lane(previous.userId()).liquidations.remove(previous.liquidationId());
        }
        lane(liquidation.userId()).liquidations.put(liquidation.liquidationId(), liquidation);
        indexActiveLiquidation(liquidation);
        changedLiquidations.add(liquidation.liquidationId());
        changedUsers.add(liquidation.userId());
        changedUsers.add(previous.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        PositionRuntime current = lane.positions.get(positionKey);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
        }
        lane.positions.remove(positionKey);
        unindexPosition(positionKey, current);
        changedPositions.add(positionKey);
        changedUsers.add(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
            lane(previous.userId()).liquidations.remove(liquidationId);
            removeActiveLiquidation(previous);
            changedLiquidations.add(liquidationId);
            changedUsers.add(previous.userId());
        }
    }

    public void removeMarkPrice(int symbolId) {
        assertOwner();
        markPrices.remove(symbolId);
        changedMarkPrices.add(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        for (AccountLaneState lane : accountLanes) lane.riskSnapshots.remove(positionKey);
        changedRiskSnapshots.add(positionKey);
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        OrderRuntime order = lane.orders.get(orderId);
        ReservationRuntime reservation = lane.reservations.get(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        if (releaseUnits != reservation.reservedUnits()) {
            throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
        }
        BalanceRuntime balance = balance(userId, reservation.assetId());
        if (balance == null) {
            throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
        }
        balance.release(releaseUnits);
        lane.orders.put(orderId, order.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(order.revision())));
        lane.reservations.put(orderId, reservation.release(releaseUnits));
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        changedBalance(userId, reservation.assetId());
        advanceUserRevision(userId);
    }

    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        OrderRuntime order = order(orderId);
        AccountLaneState lane = order == null ? null : lane(order.userId());
        ReservationRuntime reservation = lane == null ? null : lane.reservations.get(orderId);
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        }
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return;
        BalanceRuntime balance = balance(order.userId(), reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
        balance.release(releaseUnits);
        lane.reservations.put(orderId, reservation.release(releaseUnits));
        changedReservations.add(orderId);
        changedUsers.add(order.userId());
        changedBalance(order.userId(), reservation.assetId());
    }

    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            lane.clientOrderIndex.put(userId, userClientOrders);
        }
        userClientOrders.put(clientKey, orderId);
        changedClientOrders.add(clientKey);
        changedClientOrder(userId, clientKey);
        changedUsers.add(userId);
    }

    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders != null) {
            userClientOrders.remove(clientKey);
            if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
        }
        changedClientOrders.add(clientKey);
        changedClientOrder(userId, clientKey);
        changedUsers.add(userId);
    }

    public LongHashSet changedUsers() {
        assertOwner();
        return new LongHashSet(changedUsers);
    }

    public IntHashSet changedBalances(long userId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets == null ? new IntHashSet() : new IntHashSet(assets);
    }

    public boolean hasChangedBalance(long userId, int assetId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets != null && assets.contains(assetId);
    }

    void markBalanceChanged(long userId, int assetId) {
        assertOwner();
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public LongHashSet changedOrders() {
        assertOwner();
        return new LongHashSet(changedOrders);
    }

    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    LongHashSet changedPositions() {
        assertOwner();
        return new LongHashSet(changedPositions);
    }

    LongHashSet changedLiquidations() {
        assertOwner();
        return new LongHashSet(changedLiquidations);
    }

    IntHashSet changedMarkPrices() {
        assertOwner();
        return new IntHashSet(changedMarkPrices);
    }

    LongHashSet changedRiskSnapshots() {
        assertOwner();
        return new LongHashSet(changedRiskSnapshots);
    }

    IntHashSet changedRiskScans() {
        assertOwner();
        return new IntHashSet(changedRiskScans);
    }

    public LongHashSet changedClientOrders() {
        assertOwner();
        return new LongHashSet(changedClientOrders);
    }

    LongObjectHashMap<LongHashSet> changedClientOrdersByUser() {
        assertOwner();
        LongObjectHashMap<LongHashSet> result = new LongObjectHashMap<>();
        changedClientOrdersByUser.forEachKeyValue((userId, keys) -> result.put(userId, new LongHashSet(keys)));
        return result;
    }

    TreeSet<String> changedInstruments() {
        assertOwner();
        return new TreeSet<>(changedInstruments);
    }

    TreeSet<CoreLeverageKey> changedLeverages() {
        assertOwner();
        return new TreeSet<>(changedLeverages);
    }

    LongHashSet changedAlgoOrders() {
        assertOwner();
        return new LongHashSet(changedAlgoOrders);
    }

    TreeSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers() {
        assertOwner();
        return new TreeSet<>(changedCancelAllAfterTimers);
    }

    LongHashSet changedTriggerOrders() {
        assertOwner();
        return new LongHashSet(changedTriggerOrders);
    }

    LongHashSet changedFeePolicies() {
        assertOwner();
        return new LongHashSet(changedFeePolicies);
    }

    public void clearChangedKeys() {
        assertOwner();
        changedUsers.clear();
        changedBalances.clear();
        changedOrders.clear();
        changedReservations.clear();
        changedPositions.clear();
        changedLiquidations.clear();
        changedMarkPrices.clear();
        changedRiskSnapshots.clear();
        changedRiskScans.clear();
        changedClientOrders.clear();
        changedClientOrdersByUser.clear();
        changedInstruments.clear();
        changedLeverages.clear();
        changedAlgoOrders.clear();
        changedCancelAllAfterTimers.clear();
        changedTriggerOrders.clear();
        changedFeePolicies.clear();
        treasury.clearChangedKeys();
    }

    TradingRuntimeSnapshot snapshot(long revision) {
        assertOwner();
        return RuntimeSnapshotBuilder.capture(this, revision);
    }

    LongObjectHashMap<UserRuntime> usersForSnapshot() {
        LongObjectHashMap<UserRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.users);
        return values;
    }

    LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.balances);
        return values;
    }

    LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        LongObjectHashMap<OrderRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.orders);
        return values;
    }

    LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        LongObjectHashMap<ReservationRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.reservations);
        return values;
    }

    LongObjectHashMap<LongObjectHashMap<Long>> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongObjectHashMap<Long>> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.clientOrderIndex);
        return values;
    }

    LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        LongObjectHashMap<PositionRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.positions);
        return values;
    }

    LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        LongObjectHashMap<LiquidationRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.liquidations);
        return values;
    }

    IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskSnapshotRuntime> values = new LongObjectHashMap<>();
        for (AccountLaneState lane : accountLanes) values.putAll(lane.riskSnapshots);
        return values;
    }

    IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    public void reserveOrder(long orderId, long userId, long clientKey, int symbolId,
                             long quantitySteps, int assetId, long reservedUnits) {
        assertOwner();
        AccountLaneState lane = lane(userId);
        if (order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        LongObjectHashMap<Long> userClientOrders = lane.clientOrderIndex.get(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
        }
        UserRuntime user = lane.users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        BalanceRuntime balance = balance(userId, assetId);
        if (balance == null) {
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
        }
        OrderRuntime order = new OrderRuntime(orderId, userId, symbolId, quantitySteps);
        ReservationRuntime reservation = new ReservationRuntime(orderId, userId, assetId, reservedUnits);
        balance.reserve(reservedUnits);
        lane.orders.put(orderId, order);
        lane.reservations.put(orderId, reservation);
        addUserEntity(lane.reservationIdsByUser, userId, orderId);
        if (clientKey != 0 && userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            lane.clientOrderIndex.put(userId, userClientOrders);
        }
        if (clientKey != 0) {
            userClientOrders.put(clientKey, orderId);
        }
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        if (clientKey != 0) {
            changedClientOrders.add(clientKey);
            changedClientOrder(userId, clientKey);
        }
        changedUsers.add(userId);
        changedBalance(userId, assetId);
    }

    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        PositionRuntime previous = position(positionKey);
        if (previous != null) unindexPosition(positionKey, previous);
        lane(position.userId()).positions.put(positionKey, position);
        indexPosition(positionKey, position);
        changedPositions.add(positionKey);
        changedUsers.add(position.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    private void indexPosition(long positionKey, PositionRuntime position) {
        AccountLaneState lane = lane(position.userId());
        addUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<TreeSet<Long>> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.positionKeysBySymbolAndUser.put(position.symbolId(), byUser);
        }
        TreeSet<Long> keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new TreeSet<>();
            byUser.put(position.userId(), keys);
        }
        keys.add(positionKey);
    }

    private void unindexPosition(long positionKey, PositionRuntime position) {
        AccountLaneState lane = lane(position.userId());
        removeUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        LongObjectHashMap<TreeSet<Long>> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        TreeSet<Long> keys = byUser == null ? null : byUser.get(position.userId());
        if (keys == null || !keys.remove(positionKey)) return;
        if (keys.isEmpty()) byUser.remove(position.userId());
        if (byUser.isEmpty()) lane.positionKeysBySymbolAndUser.remove(position.symbolId());
    }

    private void changedBalance(long userId, int assetId) {
        IntHashSet assets = changedBalances.get(userId);
        if (assets == null) {
            assets = new IntHashSet();
            changedBalances.put(userId, assets);
        }
        assets.add(assetId);
    }

    private static void addUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) {
            entities = new LongHashSet();
            index.put(userId, entities);
        }
        entities.add(entityId);
    }

    private static void removeUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) return;
        entities.remove(entityId);
        if (entities.isEmpty()) index.remove(userId);
    }

    private void changedClientOrder(long userId, long clientKey) {
        LongHashSet keys = changedClientOrdersByUser.get(userId);
        if (keys == null) {
            keys = new LongHashSet();
            changedClientOrdersByUser.put(userId, keys);
        }
        keys.add(clientKey);
    }

    private void indexActiveLiquidation(LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        AccountLaneState lane = lane(liquidation.userId());
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.activeLiquidationIndex.put(liquidation.userId(), bySymbol);
        }
        LongObjectHashMap<Long> bySide = bySymbol.get(liquidation.symbolId());
        if (bySide == null) {
            bySide = new LongObjectHashMap<>();
            bySymbol.put(liquidation.symbolId(), bySide);
        }
        Long current = bySide.get(liquidation.positionSide().ordinal());
        if (current != null && current != liquidation.liquidationId()) {
            throw new IllegalStateException("multiple active runtime liquidations for one position");
        }
        bySide.put(liquidation.positionSide().ordinal(), liquidation.liquidationId());
    }

    private void removeActiveLiquidation(LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        AccountLaneState lane = lane(liquidation.userId());
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.activeLiquidationIndex.remove(liquidation.userId());
    }

    private static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }
}
