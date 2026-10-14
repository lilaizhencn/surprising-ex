package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class PositionUserIndex {
    private Thread businessOwner;
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires SystemLedgerLane");
    }

    // Membership changes are much less frequent than mark/risk cursor reads. Keep the
    // authoritative users ordered in a primitive array so online scans never materialize a
    // boxed TreeSet or linearly rescan a hash set for every successor.
    private final LaneTopology topology;
    private final Map<String, LongArrayList[]> usersBySymbol = new HashMap<>();
    private final Map<String, LongIntHashMap> positionCountsBySymbol = new HashMap<>();

    public PositionUserIndex(TradingCoreState state) {
        this.topology = LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization"));
        rebuild(state);
    }

    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        this(state, identities,
                LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities, LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        rebuild(state, identities);
    }

    public NavigableSet<Long> users(String instrumentId) {
        assertBusinessOwner();
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        if (lanes == null) return Collections.emptyNavigableSet();
        TreeSet<Long> sorted = new TreeSet<>();
        for (LongArrayList users : lanes) users.forEach(sorted::add);
        return Collections.unmodifiableNavigableSet(sorted);
    }

    /** Ledger查询路由：只遍历既有索引，数量门槛包含零数量持仓，不读取Lane账户表。 */
    public RiskQueryRoutes riskQueryRoutes(long userId, int maximumPositions) {
        assertBusinessOwner();
        if (userId < 0 || maximumPositions < 1) throw new IllegalArgumentException("invalid risk query route");
        TreeSet<Long> users = new TreeSet<>();
        TreeSet<String> instruments = new TreeSet<>();
        long total = 0;
        for (var entry : positionCountsBySymbol.entrySet()) {
            LongIntHashMap counts = entry.getValue();
            long count = userId == 0 ? counts.sum() : counts.get(userId);
            total = Math.addExact(total, count);
            if (total > maximumPositions)
                throw new com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException();
            if (count == 0) continue;
            instruments.add(entry.getKey());
            if (userId == 0) counts.forEachKey(users::add);
        }
        if (userId != 0) users.add(userId);
        long[] selected = new long[users.size()];
        int index = 0;
        for (long user : users) selected[index++] = user;
        return new RiskQueryRoutes(selected, java.util.List.copyOf(instruments));
    }

    /** 仅本次查询的路由IDs和市场输入名称；无持仓、资金或数量副本。 */
    public record RiskQueryRoutes(long[] userIds, java.util.List<String> instrumentIds) { }

    public Long higherUser(String instrumentId, long cursorUserId) {
        long higher = higherUserId(instrumentId, cursorUserId);
        return higher == 0 ? null : higher;
    }

    /** Returns zero at end; the online risk path stays primitive and allocation-free. */
    public long higherUserId(String instrumentId, long cursorUserId) {
        assertBusinessOwner();
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        if (lanes == null) return 0;
        long higher = 0;
        for (LongArrayList users : lanes) {
            long candidate = higherUserId(users, cursorUserId);
            if (candidate != 0 && (higher == 0 || candidate < higher)) higher = candidate;
        }
        return higher;
    }

    /** Returns the next user owned by one Account Lane without inspecting other lanes. */
    public long higherUserId(String instrumentId, int accountLaneId, long cursorUserId) {
        assertBusinessOwner();
        if (accountLaneId < 0 || accountLaneId >= topology.accountLaneCount()) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        return lanes == null ? 0 : higherUserId(lanes[accountLaneId], cursorUserId);
    }

    private static long higherUserId(LongArrayList users, long cursorUserId) {
        if (users.isEmpty()) return 0;
        int index = users.binarySearch(cursorUserId);
        index = index >= 0 ? index + 1 : -index - 1;
        return index == users.size() ? 0 : users.get(index);
    }

    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(),
                current == null ? 0 : current.userId(), current == null ? null : current.instrumentId());
    }

    void applyRoutes(long previousUser, String previousSymbol, long currentUser, String currentSymbol) {
        assertBusinessOwner();
        if (previousUser == currentUser && (previousUser == 0 || previousSymbol.equals(currentSymbol))) return;
        if (previousUser != 0) removePosition(previousUser, previousSymbol);
        if (currentUser != 0) addPosition(currentUser, currentSymbol);
    }

    /** Bounded online pagination: merges existing primitive Lane indexes without a full TreeSet copy. */
    public Iterable<Long> usersAfter(String instrumentId, long afterUserId) {
        String normalized = OrderReservation.requireInstrumentId(instrumentId);
        return () -> new java.util.Iterator<>() {
            private long next = higherUserId(normalized, afterUserId);
            public boolean hasNext() { return next != 0; }
            public Long next() {
                if (next == 0) throw new java.util.NoSuchElementException();
                long result = next;
                next = higherUserId(normalized, result);
                return result;
            }
        };
    }

    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> addPosition(RuntimePositionIndexValue.from(user.userId(), position))));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        assertBusinessOwner();
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            addPosition(indexed);
        }));
    }

    private void addPosition(RuntimePositionIndexValue position) {
        addPosition(position.userId(), position.instrumentId());
    }

    private void addPosition(long userId, String instrumentId) {
        LongIntHashMap counts = positionCountsBySymbol.computeIfAbsent(instrumentId, ignored -> new LongIntHashMap());
        int count = counts.addToValue(userId, 1);
        if (count == 1) add(instrumentId, userId);
    }

    private void removePosition(long userId, String instrumentId) {
        LongIntHashMap counts = positionCountsBySymbol.get(instrumentId);
        if (counts == null) throw new IllegalStateException("position user count is missing");
        int count = counts.addToValue(userId, -1);
        if (count == 0) {
            counts.removeKey(userId);
            if (counts.isEmpty()) positionCountsBySymbol.remove(instrumentId);
            remove(instrumentId, userId);
        } else if (count < 0) throw new IllegalStateException("negative position user count");
    }

    private void add(String instrumentId, long userId) {
        LongArrayList[] lanes = usersBySymbol.computeIfAbsent(instrumentId, ignored -> newLaneLists());
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) return;
        users.addAtIndex(-index - 1, userId);
    }

    private void remove(String instrumentId, long userId) {
        LongArrayList[] lanes = usersBySymbol.get(instrumentId);
        if (lanes == null) return;
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) users.removeAtIndex(index);
        boolean empty = true;
        for (LongArrayList lane : lanes) empty &= lane.isEmpty();
        if (empty) usersBySymbol.remove(instrumentId);
    }

    private LongArrayList[] newLaneLists() {
        LongArrayList[] lanes = new LongArrayList[topology.accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++) lanes[laneId] = new LongArrayList();
        return lanes;
    }
}
