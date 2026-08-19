package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;

public final class TradingRuntimeState {

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private long revision;

    private final LongObjectHashMap<UserRuntime> users = new LongObjectHashMap<>();
    private final LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances = new LongObjectHashMap<>();
    private final LongObjectHashMap<OrderRuntime> orders = new LongObjectHashMap<>();
    private final LongObjectHashMap<ReservationRuntime> reservations = new LongObjectHashMap<>();
    private final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>();
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
    private final Map<Long, CoreAlgoOrderState> algoOrders = new TreeMap<>();
    private final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new TreeMap<>();
    private final Map<Long, CoreTriggerOrderState> triggerOrders = new TreeMap<>();
    private long nextLiquidationId = 1;
    private CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    private final LongHashSet changedUsers = new LongHashSet();
    private final LongObjectHashMap<IntHashSet> changedBalances = new LongObjectHashMap<>();
    private final LongHashSet changedOrders = new LongHashSet();
    private final LongHashSet changedReservations = new LongHashSet();
    private final LongHashSet changedClientOrders = new LongHashSet();
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

    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    public TreasuryRuntime treasury() {
        assertOwner();
        return treasury;
    }

    public Map<String, CoreInstrumentState> instrumentsForRuntime() {
        assertOwner();
        return instruments;
    }

    public Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        return leverages;
    }

    public Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        return algoOrders;
    }

    public Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    public Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        return triggerOrders;
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
        users.remove(userId);
        balances.remove(userId);
        clientOrderIndex.remove(userId);
        changedUsers.add(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
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
        reservations.put(reservation.orderId(), reservation);
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
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
        reservations.put(reservation.orderId(), reservation);
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        ReservationRuntime current = reservations.get(orderId);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        }
        reservations.remove(orderId);
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
        positions.put(positionKey, position);
        changedUsers.add(position.userId());
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        if (liquidations.containsKey(liquidation.liquidationId())) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        liquidations.put(liquidation.liquidationId(), liquidation);
        indexActiveLiquidation(liquidation);
        changedUsers.add(liquidation.userId());
    }

    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        markPrices.put(markPrice.symbolId(), markPrice);
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        riskSnapshots.put(positionKey, snapshot);
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        riskScans.put(scan.symbolId(), scan);
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
        changedUsers.add(liquidation.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        PositionRuntime current = positions.get(positionKey);
        if (current == null || current.userId() != userId) {
            throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
        }
        positions.remove(positionKey);
        changedUsers.add(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        LiquidationRuntime previous = liquidations.remove(liquidationId);
        if (previous != null) {
            removeActiveLiquidation(previous);
            changedUsers.add(previous.userId());
        }
    }

    public void removeMarkPrice(int symbolId) {
        assertOwner();
        markPrices.remove(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        riskSnapshots.remove(positionKey);
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        riskScans.remove(symbolId);
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

    public LongHashSet changedOrders() {
        assertOwner();
        return new LongHashSet(changedOrders);
    }

    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    public LongHashSet changedClientOrders() {
        assertOwner();
        return new LongHashSet(changedClientOrders);
    }

    public void clearChangedKeys() {
        assertOwner();
        changedUsers.clear();
        changedBalances.clear();
        changedOrders.clear();
        changedReservations.clear();
        changedClientOrders.clear();
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
        if (clientKey != 0 && userClientOrders == null) {
            userClientOrders = new LongObjectHashMap<>();
            clientOrderIndex.put(userId, userClientOrders);
        }
        if (clientKey != 0) {
            userClientOrders.put(clientKey, orderId);
        }
        changedOrders.add(orderId);
        changedReservations.add(orderId);
        if (clientKey != 0) changedClientOrders.add(clientKey);
        changedUsers.add(userId);
        changedBalance(userId, assetId);
    }

    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        positions.put(positionKey, position);
        changedUsers.add(position.userId());
    }

    private void changedBalance(long userId, int assetId) {
        IntHashSet assets = changedBalances.get(userId);
        if (assets == null) {
            assets = new IntHashSet();
            changedBalances.put(userId, assets);
        }
        assets.add(assetId);
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
