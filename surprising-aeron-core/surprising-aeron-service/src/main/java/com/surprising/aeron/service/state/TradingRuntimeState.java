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

    private final LongObjectHashMap<UserRuntime> users = new LongObjectHashMap<>();
    private final LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances = new LongObjectHashMap<>();
    private final LongObjectHashMap<OrderRuntime> orders = new LongObjectHashMap<>();
    private final LongObjectHashMap<ReservationRuntime> reservations = new LongObjectHashMap<>();
    private final LongObjectHashMap<LongHashSet> reservationIdsByUser = new LongObjectHashMap<>();
    private final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>();
    private final LongObjectHashMap<LongHashSet> positionKeysByUser = new LongObjectHashMap<>();
    private final IntObjectHashMap<LongObjectHashMap<TreeSet<Long>>> positionKeysBySymbolAndUser
            = new IntObjectHashMap<>();
    private final LongObjectHashMap<LiquidationRuntime> liquidations = new LongObjectHashMap<>();
    private final LongObjectHashMap<IntObjectHashMap<LongObjectHashMap<Long>>> activeLiquidationIndex
            = new LongObjectHashMap<>();
    private final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    private final LongObjectHashMap<RiskSnapshotRuntime> riskSnapshots = new LongObjectHashMap<>();
    private final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    private final LongObjectHashMap<LongObjectHashMap<Long>> clientOrderIndex = new LongObjectHashMap<>();
    private final TreasuryRuntime treasury = new TreasuryRuntime();
    private final Map<String, CoreInstrumentState> instruments = new TreeMap<>();
    private final Map<CoreLeverageKey, Long> leverages = new TreeMap<>();
    private final LongObjectHashMap<TreeSet<CoreLeverageKey>> leverageKeysByUser = new LongObjectHashMap<>();
    private final Map<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
    private final Map<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
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
        balances.forEachValue(values -> values.forEachValue(BalanceRuntime::releaseOwnerForHandoff));
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
        return users.get(userId);
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
        IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
        return userBalances == null ? null : userBalances.get(assetId);
    }

    IntObjectHashMap<BalanceRuntime> balancesForUser(long userId) {
        assertOwner();
        return balances.get(userId);
    }

    LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        LongHashSet orderIds = reservationIdsByUser.get(userId);
        return orderIds == null ? new LongHashSet() : new LongHashSet(orderIds);
    }

    int reservationCountForUser(long userId) {
        assertOwner();
        LongHashSet orderIds = reservationIdsByUser.get(userId);
        return orderIds == null ? 0 : orderIds.size();
    }

    LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        LongHashSet positionKeys = positionKeysByUser.get(userId);
        return positionKeys == null ? new LongHashSet() : new LongHashSet(positionKeys);
    }

    int positionCountForUser(long userId) {
        assertOwner();
        LongHashSet positionKeys = positionKeysByUser.get(userId);
        return positionKeys == null ? 0 : positionKeys.size();
    }

    NavigableSet<CoreLeverageKey> leverageKeysForUser(long userId) {
        assertOwner();
        TreeSet<CoreLeverageKey> keys = leverageKeysByUser.get(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(keys);
    }

    public OrderRuntime order(long orderId) {
        assertOwner();
        return orders.get(orderId);
    }

    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        return reservations.get(orderId);
    }

    public PositionRuntime position(long positionKey) {
        assertOwner();
        return positions.get(positionKey);
    }

    public NavigableSet<Long> positionKeysForUserAndSymbol(long userId, int symbolId) {
        assertOwner();
        LongObjectHashMap<TreeSet<Long>> byUser = positionKeysBySymbolAndUser.get(symbolId);
        TreeSet<Long> keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(keys);
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        return liquidations.get(liquidationId);
    }

    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = activeLiquidationIndex.get(userId);
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
        Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
        return liquidationId == null ? null : liquidations.get(liquidationId);
    }

    public boolean hasActiveLiquidationConflict(long userId, int symbolId, long excludedLiquidationId) {
        assertOwner();
        boolean[] conflict = new boolean[1];
        liquidations.forEachValue(liquidation -> {
            if (!conflict[0] && liquidation.liquidationId() != excludedLiquidationId
                    && (liquidation.status() == CoreLiquidationState.Status.PLANNED
                    || liquidation.status() == CoreLiquidationState.Status.ORDERED)
                    && (userId == 0 || liquidation.userId() == userId)
                    && (symbolId < 0 || liquidation.symbolId() == symbolId)) {
                conflict[0] = true;
            }
        });
        return conflict[0];
    }

    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        return riskSnapshots.get(positionKey);
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
        return leverages.get(key);
    }

    void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        leverages.put(key, leveragePpm);
        TreeSet<CoreLeverageKey> userKeys = leverageKeysByUser.get(key.userId());
        if (userKeys == null) {
            userKeys = new TreeSet<>();
            leverageKeysByUser.put(key.userId(), userKeys);
        }
        userKeys.add(key);
        changedLeverages.add(key);
    }

    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        return algoOrders.get(algoOrderId);
    }

    void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        algoOrders.put(algoOrder.algoOrderId(), algoOrder);
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        algoOrders.remove(algoOrderId);
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
        return triggerOrders.get(triggerOrderId);
    }

    void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        triggerOrders.put(triggerOrder.triggerOrderId(), triggerOrder);
        changedTriggerOrders.add(triggerOrder.triggerOrderId());
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        triggerOrders.remove(triggerOrderId);
        changedTriggerOrders.add(triggerOrderId);
    }

    Map<String, CoreInstrumentState> instrumentsForRuntime() {
        assertOwner();
        return instruments;
    }

    Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        return leverages;
    }

    Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        return algoOrders;
    }

    Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        return triggerOrders;
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
        leverages.clear();
        leverages.putAll(source.leverages());
        algoOrders.clear();
        algoOrders.putAll(source.algoOrders());
        cancelAllAfterTimers.clear();
        cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        triggerOrders.clear();
        triggerOrders.putAll(source.triggerOrders());
    }

    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        LongObjectHashMap<Long> userClientOrders = clientOrderIndex.get(userId);
        return userClientOrders == null ? null : userClientOrders.get(clientKey);
    }

    public void putUser(UserRuntime user) {
        assertOwner();
        users.put(user.userId(), user);
        changedUsers.add(user.userId());
    }

    public void advanceUserRevision(long userId) {
        assertOwner();
        UserRuntime current = requireUser(userId);
        users.put(userId, new UserRuntime(current.productLine(), userId,
                Math.incrementExact(current.revision()), current.positionMode()));
        changedUsers.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        LongObjectHashMap<Long> userClientOrders = clientOrderIndex.get(userId);
        if (userClientOrders != null) {
            userClientOrders.forEachKey(clientKey -> {
                changedClientOrders.add(clientKey);
                changedClientOrder(userId, clientKey);
            });
        }
        users.remove(userId);
        balances.remove(userId);
        clientOrderIndex.remove(userId);
        reservationIdsByUser.remove(userId);
        positionKeysByUser.remove(userId);
        leverageKeysByUser.remove(userId);
        changedUsers.add(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        balance.bindOwner();
        IntObjectHashMap<BalanceRuntime> userBalances = balances.get(balance.userId());
        if (userBalances == null) {
            userBalances = new IntObjectHashMap<>();
            balances.put(balance.userId(), userBalances);
        }
        userBalances.put(balance.assetId(), balance);
        changedBalance(balance.userId(), balance.assetId());
        changedUsers.add(balance.userId());
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        orders.put(order.orderId(), order);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservations.put(reservation.orderId(), reservation);
        if (previous != null) removeUserEntity(reservationIdsByUser, previous.userId(), previous.orderId());
        addUserEntity(reservationIdsByUser, reservation.userId(), reservation.orderId());
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        orders.put(order.orderId(), order);
        changedOrders.add(order.orderId());
        changedUsers.add(order.userId());
    }

    public void removeOrder(long orderId) {
        assertOwner();
        OrderRuntime previous = orders.remove(orderId);
        if (previous != null) {
            changedOrders.add(orderId);
            changedUsers.add(previous.userId());
        }
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime previous = reservations.put(reservation.orderId(), reservation);
        if (previous != null) removeUserEntity(reservationIdsByUser, previous.userId(), previous.orderId());
        addUserEntity(reservationIdsByUser, reservation.userId(), reservation.orderId());
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        ReservationRuntime current = reservations.get(orderId);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        }
        reservations.remove(orderId);
        removeUserEntity(reservationIdsByUser, userId, orderId);
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
        IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
        if (userBalances == null || userBalances.remove(assetId) == null) {
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + '/' + assetId);
        }
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        PositionRuntime previous = positions.get(positionKey);
        if (previous != null) unindexPosition(positionKey, previous);
        positions.put(positionKey, position);
        indexPosition(positionKey, position);
        changedPositions.add(positionKey);
        changedUsers.add(position.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        if (liquidations.containsKey(liquidation.liquidationId())) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        liquidations.put(liquidation.liquidationId(), liquidation);
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
        riskSnapshots.put(positionKey, snapshot);
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
        LiquidationRuntime previous = liquidations.get(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        removeActiveLiquidation(previous);
        liquidations.put(liquidation.liquidationId(), liquidation);
        indexActiveLiquidation(liquidation);
        changedLiquidations.add(liquidation.liquidationId());
        changedUsers.add(liquidation.userId());
        changedUsers.add(previous.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        PositionRuntime current = positions.get(positionKey);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
        }
        positions.remove(positionKey);
        unindexPosition(positionKey, current);
        changedPositions.add(positionKey);
        changedUsers.add(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        LiquidationRuntime previous = liquidations.remove(liquidationId);
        if (previous != null) {
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
        riskSnapshots.remove(positionKey);
        changedRiskSnapshots.add(positionKey);
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        assertOwner();
        OrderRuntime order = orders.get(orderId);
        ReservationRuntime reservation = reservations.get(orderId);
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
        orders.put(orderId, order.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(order.revision())));
        reservations.put(orderId, reservation.release(releaseUnits));
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        changedBalance(userId, reservation.assetId());
        advanceUserRevision(userId);
    }

    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        OrderRuntime order = orders.get(orderId);
        ReservationRuntime reservation = reservations.get(orderId);
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        }
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return;
        BalanceRuntime balance = balance(order.userId(), reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
        balance.release(releaseUnits);
        reservations.put(orderId, reservation.release(releaseUnits));
        changedReservations.add(orderId);
        changedUsers.add(order.userId());
        changedBalance(order.userId(), reservation.assetId());
    }

    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        LongObjectHashMap<Long> userClientOrders = clientOrderIndex.get(userId);
        if (userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            clientOrderIndex.put(userId, userClientOrders);
        }
        userClientOrders.put(clientKey, orderId);
        changedClientOrders.add(clientKey);
        changedClientOrder(userId, clientKey);
        changedUsers.add(userId);
    }

    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        LongObjectHashMap<Long> userClientOrders = clientOrderIndex.get(userId);
        if (userClientOrders != null) {
            userClientOrders.remove(clientKey);
            if (userClientOrders.isEmpty()) clientOrderIndex.remove(userId);
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
        return users;
    }

    LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        return balances;
    }

    LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        return orders;
    }

    LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        return reservations;
    }

    LongObjectHashMap<LongObjectHashMap<Long>> clientOrderIndexForSnapshot() {
        return clientOrderIndex;
    }

    LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        return positions;
    }

    LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        return liquidations;
    }

    IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        return riskSnapshots;
    }

    IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    public void reserveOrder(long orderId, long userId, long clientKey, int symbolId,
                             long quantitySteps, int assetId, long reservedUnits) {
        assertOwner();
        if (orders.containsKey(orderId)) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        LongObjectHashMap<Long> userClientOrders = clientOrderIndex.get(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
        }
        UserRuntime user = users.get(userId);
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
        orders.put(orderId, order);
        reservations.put(orderId, reservation);
        addUserEntity(reservationIdsByUser, userId, orderId);
        if (clientKey != 0 && userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            clientOrderIndex.put(userId, userClientOrders);
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
        PositionRuntime previous = positions.get(positionKey);
        if (previous != null) unindexPosition(positionKey, previous);
        positions.put(positionKey, position);
        indexPosition(positionKey, position);
        changedPositions.add(positionKey);
        changedUsers.add(position.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    private void indexPosition(long positionKey, PositionRuntime position) {
        addUserEntity(positionKeysByUser, position.userId(), positionKey);
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<TreeSet<Long>> byUser = positionKeysBySymbolAndUser.get(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            positionKeysBySymbolAndUser.put(position.symbolId(), byUser);
        }
        TreeSet<Long> keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new TreeSet<>();
            byUser.put(position.userId(), keys);
        }
        keys.add(positionKey);
    }

    private void unindexPosition(long positionKey, PositionRuntime position) {
        removeUserEntity(positionKeysByUser, position.userId(), positionKey);
        LongObjectHashMap<TreeSet<Long>> byUser = positionKeysBySymbolAndUser.get(position.symbolId());
        TreeSet<Long> keys = byUser == null ? null : byUser.get(position.userId());
        if (keys == null || !keys.remove(positionKey)) return;
        if (keys.isEmpty()) byUser.remove(position.userId());
        if (byUser.isEmpty()) positionKeysBySymbolAndUser.remove(position.symbolId());
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
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = activeLiquidationIndex.get(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            activeLiquidationIndex.put(liquidation.userId(), bySymbol);
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
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = activeLiquidationIndex.get(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) activeLiquidationIndex.remove(liquidation.userId());
    }

    private static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }
}
