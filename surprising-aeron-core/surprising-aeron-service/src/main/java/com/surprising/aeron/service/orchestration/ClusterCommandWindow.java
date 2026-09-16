package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
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
    private static final int MAX_CANDIDATE_ORDERS = CancelOrderBatchCommand.MAX_ORDERS;

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
    /** Core sequence to physical slot + 1 for completion callbacks. */
    private final org.agrona.collections.Long2LongHashMap completionSlots;
    private long lastMatchingSequence;
    private int lastMatchingPhysical = -1;

    private final long[] candidateOrders = new long[MAX_CANDIDATE_ORDERS];
    private long candidateUser;
    private IngressRoute candidateRoute;
    private CoreMessage decodedSource;
    private DecodedMatchingCommand decoded;
    private int candidateOrderCount;

    public ClusterCommandWindow() {
        this(Integer.parseInt(System.getProperty("surprising.aeron.owner-command-window",
                Integer.toString(DEFAULT_CAPACITY))));
    }

    public ClusterCommandWindow(int capacity) {
        if (capacity < 64 || capacity > 1024 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("owner command window must be a power of two in [64,1024]");
        entries = new Entry[capacity];
        indexMask = capacity - 1;
        completionSlots = new org.agrona.collections.Long2LongHashMap(capacity, 0.65f, 0);
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    public Conflict conflict() { return conflict; }

    /** Reset the reusable route scratch for the next ingress command. */
    public void resetCandidate(long userId) {
        candidateUser = userId;
        candidateRoute = null;
        candidateOrderCount = 0;
        conflict = Conflict.NONE;
    }

    /** Capture the static route. Maker lanes are discovered by Matcher. */
    public IngressRoute route(int matcherShard, long userLaneBit) {
        if (candidateUser <= 0 || matcherShard < 0 || userLaneBit == 0)
            throw new IllegalArgumentException("invalid ingress route");
        candidateRoute = new IngressRoute(candidateUser, matcherShard, userLaneBit, 0);
        return candidateRoute;
    }

    public IngressRoute route() { return candidateRoute; }

    /** Keep exact order identities for cancellation and idempotent retry fencing. */
    public void candidateOrder(long orderId) {
        if (orderId <= 0) return;
        if (candidateOrderCount >= MAX_CANDIDATE_ORDERS)
            throw new IllegalArgumentException("too many candidate orders");
        candidateOrders[candidateOrderCount++] = orderId;
    }

    public boolean conflicts() { return conflictingPrefixSize() != 0; }

    /**
     * Return the FIFO prefix that must drain before this command can be admitted.
     * Dependency checks are limited to the user's physical Lane and exact order
     * identities; no instrument, order-book or counterparty index is read.
     */
    public int conflictingPrefixSize() {
        conflict = Conflict.NONE;
        int exactPrefix = 0;
        for (int i = 0; i < candidateOrderCount; i++) {
            long slot = orderSlots.get(candidateOrders[i]);
            if (slot != 0)
                exactPrefix = Math.max(exactPrefix, (((int) slot - 1 - head) & indexMask) + 1);
        }
        long candidateLane = candidateRoute == null ? 0 : candidateRoute.userLaneBit();
        if (candidateLane != 0) {
            for (int i = size - 1; i >= exactPrefix; i--) {
                IngressRoute route = get(i).route;
                if (route != null && (route.userLaneBit() & candidateLane) != 0) {
                    conflict = Conflict.ACCOUNT;
                    return i + 1;
                }
            }
        }
        if (exactPrefix != 0) conflict = Conflict.ORDER;
        return exactPrefix;
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
        if (candidateRoute == null) throw new IllegalStateException("ingress route is missing");
        Entry entry = get(size++);
        entry.session = session;
        entry.request = request;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.route = candidateRoute;
        int physical = (head + size - 1) & indexMask;
        entry.orderCount = candidateOrderCount;
        for (int i = 0; i < candidateOrderCount; i++) {
            long orderId = candidateOrders[i];
            entry.orders[i] = orderId;
            orderSlots.put(orderId, physical + 1L);
        }
        entry.physicalSlot = physical;
        return entry;
    }

    /** Bind the assigned core sequence and index the response callback. */
    public void bindSequence(Entry entry, long sequence) {
        if (entry == null || entry.physicalSlot < 0 || sequence < 0)
            throw new IllegalArgumentException("invalid command window sequence binding");
        if (entry.sequence != 0) completionSlots.remove(entry.sequence);
        entry.sequence = sequence;
        if (entry.route != null) entry.route = entry.route.withCommandSequence(sequence);
        if (sequence == 0) return;
        long slot = entry.physicalSlot + 1L;
        long existing = completionSlots.get(sequence);
        if (existing == 0) completionSlots.put(sequence, slot);
        lastMatchingSequence = sequence;
        lastMatchingPhysical = entry.physicalSlot;
    }

    public void complete(long sequence, CoreResponse response) {
        long slot = completionSlots.get(sequence);
        if (slot != 0) {
            Entry entry = entries[(int) slot - 1];
            if (entry.physicalSlot == (int) slot - 1 && entry.sequence == sequence) {
                if (entry.response != null) throw new IllegalStateException("duplicate pipeline completion");
                entry.response = response;
                return;
            }
            completionSlots.remove(sequence);
        }
        for (int i = 0; i < size; i++) {
            Entry entry = get(i);
            if (entry.sequence != sequence) continue;
            if (completionSlots.get(sequence) == 0)
                completionSlots.put(sequence, entry.physicalSlot + 1L);
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
            int physical = (head + i) & indexMask;
            for (int k = 0; k < entry.orderCount; k++) {
                long id = entry.orders[k];
                long removedSlot = orderSlots.remove(id);
                if (removedSlot != 0 && removedSlot != physical + 1L) orderSlots.put(id, removedSlot);
                entry.orders[k] = 0;
            }
            if (entry.sequence != 0) {
                long removedSlot = completionSlots.remove(entry.sequence);
                if (removedSlot != 0 && removedSlot != physical + 1L)
                    completionSlots.put(entry.sequence, removedSlot);
                removedLastMatching |= physical == lastMatchingPhysical;
            }
            entry.orderCount = 0;
            entry.session = null;
            entry.request = null;
            entry.response = null;
            entry.sequence = entry.timestamp = entry.position = 0;
            entry.route = null;
            entry.physicalSlot = -1;
        }
        head = (head + count) & indexMask;
        size -= count;
        if (removedLastMatching) recomputeLastMatching();
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
        /** Only exact identities needed for cancellation/retry fencing. */
        public final long[] orders = new long[MAX_CANDIDATE_ORDERS];
        public int orderCount;
        public IngressRoute route;
        public ClientSession session;
        public CoreMessage request;
        public CoreResponse response;
        public long sequence, timestamp, position;
        public int physicalSlot = -1;
    }
}
