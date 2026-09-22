package com.surprising.aeron.service.state;

import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Owner-local sampled timings, never retained in business state or passed to another thread. */
@Name("surprising.OwnerSettlementMerge")
@StackTrace(false)
final class OwnerSettlementMergeEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(OwnerSettlementMergeEvent.class);
    public long sequence;
    public String scope;
    public int laneId;
    public int terminalOrders;
    public boolean completed;
    public long totalNanos;
    public long publicationNanos;
    public long usersNanos;
    public long ordersNanos;
    public long reservationsNanos;
    public long positionsNanos;
    public long removalsNanos;
    public long terminalIndexNanos;
    public long trimNanos;
    public long releaseNanos;
    public long changedIndexNanos;
    // Collection-only exclusive intervals; laneMerge encloses nested lane events.
    public long prepareNanos, admissionNanos, fundsNanos, identitiesNanos, laneMergeNanos, balancesNanos, pendingNanos;
    // Disjoint 1/2048 streams: shape inspection must not warm timed map operations.
    public boolean mapTiming;
    public boolean mapShape;
    public int ordersVisited, ordersSkipped, orderGets, orderEquals, orderEqualHits, orderSameReference, orderPuts;
    public long orderGetNanos, orderEqualsNanos, orderPutNanos;
    public int removals, removalMisses;
    public long removalNanos;
    public int orderRemovals, orderRemovalMisses, reservationRemovals, reservationRemovalMisses;
    public long orderRemovalNanos, reservationRemovalNanos;
    public int shapeRemovals, shapeMisses, shapeCensored, shapeSearchSlots, shapeScanSlots, shapeMoves;
    public int shapeMaxSearch, shapeMaxScan, shapeMaxSize, shapeMaxCapacity;
    private long started;

    static OwnerSettlementMergeEvent sample(long sequence, String scope, int laneId) {
        if (!MatcherSettlementEvent.LATENCY_DIAGNOSTICS
                || (Long.hashCode(sequence * 0x9e3779b97f4a7c15L) & 63) != 0 || !TYPE.isEnabled()) return null;
        var event = new OwnerSettlementMergeEvent();
        event.sequence = sequence;
        event.scope = scope;
        event.laneId = laneId;
        int bucket = Long.hashCode(sequence * 0x9e3779b97f4a7c15L) & 2047;
        event.mapTiming = bucket == 0;
        event.mapShape = bucket == 1024;
        event.started = System.nanoTime();
        event.begin();
        return event;
    }

    void finish() {
        totalNanos = System.nanoTime() - started;
        end();
        commit();
    }
}
