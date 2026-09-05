package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class PositionUserIndex {

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

    public NavigableSet<Long> users(String symbol) {
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (lanes == null) return Collections.emptyNavigableSet();
        TreeSet<Long> sorted = new TreeSet<>();
        for (LongArrayList users : lanes) users.forEach(sorted::add);
        return Collections.unmodifiableNavigableSet(sorted);
    }

    public Long higherUser(String symbol, long cursorUserId) {
        long higher = higherUserId(symbol, cursorUserId);
        return higher == 0 ? null : higher;
    }

    /** Returns zero at end; the online risk path stays primitive and allocation-free. */
    public long higherUserId(String symbol, long cursorUserId) {
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (lanes == null) return 0;
        long higher = 0;
        for (LongArrayList users : lanes) {
            long candidate = higherUserId(users, cursorUserId);
            if (candidate != 0 && (higher == 0 || candidate < higher)) higher = candidate;
        }
        return higher;
    }

    /** Returns the next user owned by one Account Lane without inspecting other lanes. */
    public long higherUserId(String symbol, int accountLaneId, long cursorUserId) {
        if (accountLaneId < 0 || accountLaneId >= topology.accountLaneCount()) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        LongArrayList[] lanes = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return lanes == null ? 0 : higherUserId(lanes[accountLaneId], cursorUserId);
    }

    private static long higherUserId(LongArrayList users, long cursorUserId) {
        if (users.isEmpty()) return 0;
        int index = users.binarySearch(cursorUserId);
        index = index >= 0 ? index + 1 : -index - 1;
        return index == users.size() ? 0 : users.get(index);
    }

    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        if (previous != null) removePosition(previous);
        if (current != null) addPosition(current);
    }

    public void rebuild(TradingCoreState state) {
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> addPosition(RuntimePositionIndexValue.from(user.userId(), position))));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        usersBySymbol.clear();
        positionCountsBySymbol.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            addPosition(indexed);
        }));
    }

    private void addPosition(RuntimePositionIndexValue position) {
        LongIntHashMap counts = positionCountsBySymbol.computeIfAbsent(
                position.symbol(), ignored -> new LongIntHashMap());
        int count = counts.addToValue(position.userId(), 1);
        if (count == 1) add(position.symbol(), position.userId());
    }

    private void removePosition(RuntimePositionIndexValue position) {
        LongIntHashMap counts = positionCountsBySymbol.get(position.symbol());
        if (counts == null) throw new IllegalStateException("position user count is missing");
        int count = counts.addToValue(position.userId(), -1);
        if (count == 0) {
            counts.removeKey(position.userId());
            if (counts.isEmpty()) positionCountsBySymbol.remove(position.symbol());
            remove(position.symbol(), position.userId());
        } else if (count < 0) throw new IllegalStateException("negative position user count");
    }

    private void add(String symbol, long userId) {
        LongArrayList[] lanes = usersBySymbol.computeIfAbsent(symbol, ignored -> newLaneLists());
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) return;
        users.addAtIndex(-index - 1, userId);
    }

    private void remove(String symbol, long userId) {
        LongArrayList[] lanes = usersBySymbol.get(symbol);
        if (lanes == null) return;
        LongArrayList users = lanes[topology.accountLaneId(userId)];
        int index = users.binarySearch(userId);
        if (index >= 0) users.removeAtIndex(index);
        boolean empty = true;
        for (LongArrayList lane : lanes) empty &= lane.isEmpty();
        if (empty) usersBySymbol.remove(symbol);
    }

    private LongArrayList[] newLaneLists() {
        LongArrayList[] lanes = new LongArrayList[topology.accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++) lanes[laneId] = new LongArrayList();
        return lanes;
    }
}
