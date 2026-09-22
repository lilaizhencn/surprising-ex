package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import io.aeron.cluster.service.ClientSession;

/**
 * Owner-only bounded FIFO for in-flight command responses.
 *
 * <p>The window no longer builds a speculative counterparty scope. The only
 * routing information captured at ingress is the submitting user's Lane and
 * the static Matcher shard. Exact order IDs are retained solely to fence a
 * retry or cancellation until the older command leaves the FIFO.</p>
 */
public final class ClusterCommandWindow {
    public static final int DEFAULT_CAPACITY = 256;
    /** Exact order identities retained by an admitted entry for cancellation fencing. */
    private static final int MAX_TRACKED_ORDER_IDS = Math.max(
            PlaceOrderBatchCommand.MAX_ORDERS, CancelOrderBatchCommand.MAX_ORDERS);

    /** Owner ingress fences are limited to exact orders and the submitting account Lane. */
    public enum Conflict { NONE, ORDER, ACCOUNT }

    private Conflict conflict = Conflict.NONE;
    private final Entry[] entries;
    private final int indexMask;
    private int head;
    private int size;
    /** Order ID to newest physical slot + 1. */
    private final org.agrona.collections.Long2LongHashMap orderSlots =
            new org.agrona.collections.Long2LongHashMap(0);
    private long lastMatchingSequence;
    private int lastMatchingPhysical = -1;

    private long candidateUser;
    private long candidateUserLaneBit;
    private int candidateMatcherShard = -1;
    private boolean candidateRouted;
    /** Request whose decoded value produced the candidate route; prevents stale scratch reuse. */
    private CoreMessage routedSource;
    private CoreMessage decodedSource;
    private DecodedMatchingCommand decoded;

    public ClusterCommandWindow() {
        this(Integer.parseInt(System.getProperty("surprising.aeron.owner-command-window",
                Integer.toString(DEFAULT_CAPACITY))));
    }

    public ClusterCommandWindow(int capacity) {
        if (capacity < 64 || capacity > 1024 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("owner command window must be a power of two in [64,1024]");
        entries = new Entry[capacity];
        indexMask = capacity - 1;
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    public Conflict conflict() { return conflict; }

    /** Reset the reusable route scratch for the next ingress command. */
    public void resetCandidate(long userId) {
        candidateUser = userId;
        candidateUserLaneBit = 0;
        candidateMatcherShard = -1;
        candidateRouted = false;
        routedSource = null;
        conflict = Conflict.NONE;
    }

    /** Capture the static route. Maker lanes are discovered by Matcher. */
    public void route(int matcherShard, long userLaneBit) {
        if (candidateUser <= 0 || matcherShard < 0 || userLaneBit == 0)
            throw new IllegalArgumentException("invalid ingress route");
        routedSource = decodedSource;
        candidateMatcherShard = matcherShard;
        candidateUserLaneBit = userLaneBit;
        candidateRouted = true;
    }

    public boolean hasRoute() { return candidateRouted; }
    public long routeUserId() { return candidateRouted ? candidateUser : 0; }
    public int routeMatcherShard() { return candidateRouted ? candidateMatcherShard : -1; }
    public long routeUserLaneBit() { return candidateRouted ? candidateUserLaneBit : 0; }

    public boolean conflicts() { return conflictingPrefixSize() != 0; }

    /**
     * Return the FIFO prefix that must drain before this command can be admitted.
     * Dependency checks are limited to the user's physical Lane and exact order
     * identities; no instrument, order-book or counterparty index is read.
     */
    public int conflictingPrefixSize() {
        conflict = Conflict.NONE;
        int exactPrefix = 0;
        exactPrefix = exactOrderPrefix(exactPrefix);
        // A Lane is a FIFO executor shared by many users.  It is not a command
        // dependency: fencing the whole lane here reduces a 256-slot window to
        // roughly one in-flight command per lane.  Only the same user's Owner
        // preparation must wait for its prior terminal state; the Lane queue
        // already serializes different users on the same lane.
        long candidateUserId = candidateRouted ? candidateUser : 0;
        if (candidateUserId != 0) {
            for (int i = size - 1; i >= exactPrefix; i--) {
                Entry route = get(i);
                if (route.routePresent && route.routeUserId == candidateUserId) {
                    conflict = Conflict.ACCOUNT;
                    return i + 1;
                }
            }
        }
        if (exactPrefix != 0) conflict = Conflict.ORDER;
        return exactPrefix;
    }

    /**
     * Exact cancellation/retry fencing is derived from the already decoded command.  The window
     * no longer keeps a second candidate-order scratch collection for the current ingress item.
     */
    private int exactOrderPrefix(int prefix) {
        if (routedSource == null || routedSource != decodedSource || decoded == null) return prefix;
        CoreMessageType type = decodedSource.header().messageType();
        switch (type) {
            case PLACE_ORDER -> prefix = maxOrderPrefix(prefix, decoded.placeOrder().orderId());
            case CANCEL_ORDER -> prefix = maxOrderPrefix(prefix, decoded.cancelOrder().orderId());
            case PLACE_ORDER_BATCH -> {
                for (var order : decoded.placeOrderBatch().orders())
                    prefix = maxOrderPrefix(prefix, order.orderId());
            }
            case CANCEL_ORDER_BATCH -> {
                for (var order : decoded.cancelOrderBatch().orders())
                    prefix = maxOrderPrefix(prefix, order.orderId());
            }
            default -> { }
        }
        return prefix;
    }

    private int maxOrderPrefix(int prefix, long orderId) {
        long slot = orderSlots.get(orderId);
        return slot == 0 ? prefix : Math.max(prefix, (((int) slot - 1 - head) & indexMask) + 1);
    }

    public DecodedMatchingCommand decoded(CoreMessage request) {
        if (decodedSource != request) {
            decodedSource = request;
            decoded = null;
        }
        if (decoded == null) decoded = DecodedMatchingCommand.decode(request);
        return decoded;
    }

    public DecodedMatchingCommand decodedIfPresent(CoreMessage request) {
        return decodedSource == request ? decoded : null;
    }

    public void releaseDecoded() {
        decodedSource = null;
        decoded = null;
    }

    /** Keep decoding only while the same request remains at the blocked ingress head. */
    public void retainDecoded(CoreMessage pendingHead) {
        if (pendingHead == null || decodedSource != pendingHead) releaseDecoded();
    }

    public long lastMatchingSequence() { return lastMatchingSequence; }

    public Entry add(ClientSession session, CoreMessage request, long timestamp, long position) {
        if (size == entries.length) throw new IllegalStateException("cluster command window is full");
        if (!candidateRouted) throw new IllegalStateException("ingress route is missing");
        Entry entry = get(size++);
        entry.session = session;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.routeUserId = candidateUser;
        entry.routeMatcherShard = candidateMatcherShard;
        entry.routeUserLaneBit = candidateUserLaneBit;
        entry.routePresent = true;
        int physical = (head + size - 1) & indexMask;
        entry.orderCount = copyTrackedOrderIds(entry, physical);
        entry.physicalSlot = physical;
        return entry;
    }

    /** Store only exact identities belonging to this admitted command entry. */
    private int copyTrackedOrderIds(Entry entry, int physical) {
        if (routedSource == null || routedSource != decodedSource || decoded == null) return 0;
        int count = 0;
        switch (decodedSource.header().messageType()) {
            case PLACE_ORDER -> count = trackOrderId(entry, count, decoded.placeOrder().orderId(), physical);
            case CANCEL_ORDER -> count = trackOrderId(entry, count, decoded.cancelOrder().orderId(), physical);
            case PLACE_ORDER_BATCH -> {
                for (var order : decoded.placeOrderBatch().orders())
                    count = trackOrderId(entry, count, order.orderId(), physical);
            }
            case CANCEL_ORDER_BATCH -> {
                for (var order : decoded.cancelOrderBatch().orders())
                    count = trackOrderId(entry, count, order.orderId(), physical);
            }
            default -> { }
        }
        return count;
    }

    private int trackOrderId(Entry entry, int count, long orderId, int physical) {
        if (orderId <= 0) return count;
        if (count >= MAX_TRACKED_ORDER_IDS) throw new IllegalArgumentException("too many tracked order IDs");
        entry.orders[count] = orderId;
        orderSlots.put(orderId, physical + 1L);
        return count + 1;
    }

    /** Bind the assigned core sequence. Ordered heads receive their response directly. */
    public void bindSequence(Entry entry, long sequence) {
        if (entry == null || entry.physicalSlot < 0 || sequence < 0)
            throw new IllegalArgumentException("invalid command window sequence binding");
        entry.sequence = sequence;
        if (sequence == 0) return;
        lastMatchingSequence = sequence;
        lastMatchingPhysical = entry.physicalSlot;
        if (entry == get(0)) CoreMatchingPhaseMetrics.recordOwnerHead(entry, false);
    }

    /** Compatibility callback for control/replay paths; normal ordered heads bypass this scan. */
    public void complete(long sequence, CoreResponse response) {
        for (int i = 0; i < size; i++) {
            Entry entry = get(i);
            if (entry.sequence != sequence) continue;
            if (entry.response != null) throw new IllegalStateException("duplicate pipeline completion");
            entry.response = response;
            return;
        }
        throw new IllegalStateException("pipeline completion has no request context");
    }

    public int size() { return size; }
    public int capacity() { return entries.length; }
    public Entry get(int index) { return entries[(head + index) & indexMask]; }

    public void clear() {
        removePrefix(size);
        resetCandidate(0);
        releaseDecoded();
    }

    public void removePrefix(int count) {
        if (count < 0 || count > size) throw new IllegalArgumentException("invalid window prefix");
        boolean removedLastMatching = false;
        for (int i = 0; i < count; i++) {
            Entry entry = get(i);
            CoreMatchingPhaseMetrics.finishOwnerHead(entry);
            int physical = (head + i) & indexMask;
            for (int k = 0; k < entry.orderCount; k++) {
                long id = entry.orders[k];
                long removedSlot = orderSlots.remove(id);
                if (removedSlot != 0 && removedSlot != physical + 1L) orderSlots.put(id, removedSlot);
                entry.orders[k] = 0;
            }
            if (entry.sequence != 0) {
                removedLastMatching |= physical == lastMatchingPhysical;
            }
            entry.orderCount = 0;
            entry.session = null;
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = 0;
            entry.routeUserId = entry.routeUserLaneBit = 0;
            entry.routeMatcherShard = -1;
            entry.routePresent = false;
            entry.physicalSlot = -1;
        }
        head = (head + count) & indexMask;
        size -= count;
        if (removedLastMatching) recomputeLastMatching();
        if (count != 0 && size != 0) CoreMatchingPhaseMetrics.recordOwnerHead(get(0), true);
    }

    private void recomputeLastMatching() {
        lastMatchingSequence = 0;
        lastMatchingPhysical = -1;
        for (int i = size - 1; i >= 0; i--) {
            Entry entry = get(i);
            if (entry.sequence == 0) continue;
            lastMatchingSequence = entry.sequence;
            lastMatchingPhysical = entry.physicalSlot;
            return;
        }
    }

    public static final class Entry {
        /** Optional JFR state for the current sampled head; never read by business logic. */
        CoreMatchingPhaseMetrics.OwnerHead headTiming;
        /** Only exact identities needed for cancellation/retry fencing. */
        public final long[] orders = new long[MAX_TRACKED_ORDER_IDS];
        public int orderCount;
        public long routeUserId;
        public long routeUserLaneBit;
        public int routeMatcherShard = -1;
        public boolean routePresent;
        public ClientSession session;
        public CoreMessage request;
        public CoreResponse response;
        public long sequence, timestamp, position;
        public int physicalSlot = -1;
    }
}
