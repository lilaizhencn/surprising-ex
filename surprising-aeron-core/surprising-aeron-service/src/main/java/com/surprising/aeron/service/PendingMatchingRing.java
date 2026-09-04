package com.surprising.aeron.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

final class PendingMatchingRing {
    private final int[] nextSlots;
    private final int[] previousSlots;
    private final int[] submissionShards;
    private final int[] nextSubmissionSlots;
    private final int[] previousSubmissionSlots;
    private final int[] submissionHeads;
    private final int[] submissionTails;
    private final Map<UUID, PendingMatching> entriesByCommandId;
    private final LongIntHashMap pendingByUser;
    private final LaneCommandContextRing contexts;
    private final int mask;
    private int head = -1;
    private int tail = -1;
    private int dispatchHead = -1;
    private int size;

    PendingMatchingRing(int requestedCapacity, int matcherShardCount, int laneCount) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        if (matcherShardCount <= 0) throw new IllegalArgumentException("matcher shard count must be positive");
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        nextSlots = new int[capacity];
        previousSlots = new int[capacity];
        submissionShards = new int[capacity];
        nextSubmissionSlots = new int[capacity];
        previousSubmissionSlots = new int[capacity];
        submissionHeads = new int[matcherShardCount];
        submissionTails = new int[matcherShardCount];
        java.util.Arrays.fill(nextSlots, -1);
        java.util.Arrays.fill(previousSlots, -1);
        java.util.Arrays.fill(submissionShards, -1);
        java.util.Arrays.fill(nextSubmissionSlots, -1);
        java.util.Arrays.fill(previousSubmissionSlots, -1);
        java.util.Arrays.fill(submissionHeads, -1);
        java.util.Arrays.fill(submissionTails, -1);
        mask = capacity - 1;
        contexts = new LaneCommandContextRing(capacity, laneCount);
        entriesByCommandId = new java.util.HashMap<>(capacity);
        pendingByUser = new LongIntHashMap(capacity);
    }

    void put(PendingMatching pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        int index = slot(pending.sequence());
        PendingMatching existing = pendingAt(index);
        if (existing != null) {
            if (existing.sequence() != pending.sequence()) {
                throw new IllegalStateException("pending matching sequence window is full");
            }
            requireAvailableCommandId(pending, existing);
            removeIndexes(existing);
            contexts.required(pending.sequence()).pending(pending);
            addIndexes(pending);
            return;
        }
        if (size == contexts.capacity()) {
            throw new IllegalStateException("pending matching ring is full");
        }
        requireAvailableCommandId(pending, null);
        contexts.claim(pending.sequence()).pending(pending);
        previousSlots[index] = tail;
        nextSlots[index] = -1;
        if (tail == -1) head = index;
        else nextSlots[tail] = index;
        tail = index;
        if (dispatchHead == -1) dispatchHead = index;
        addIndexes(pending);
        size++;
    }

    PendingMatching get(long sequence) {
        PendingMatching pending = pendingAt(slot(sequence));
        return pending != null && pending.sequence() == sequence ? pending : null;
    }

    boolean contains(long sequence) {
        return get(sequence) != null;
    }

    PendingMatching remove(long sequence) {
        int entryIndex = indexOf(sequence);
        if (entryIndex < 0) return null;
        PendingMatching removed = pendingAt(entryIndex);
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        int previous = previousSlots[entryIndex];
        int next = nextSlots[entryIndex];
        if (dispatchHead == entryIndex) dispatchHead = next;
        if (previous == -1) head = next;
        else nextSlots[previous] = next;
        if (next == -1) tail = previous;
        else previousSlots[next] = previous;
        previousSlots[entryIndex] = -1;
        nextSlots[entryIndex] = -1;
        removeFromSubmissionOrder(entryIndex);
        removeIndexes(removed);
        size--;
        if (contexts.claimed(sequence)) {
            LaneCommandContextRing.Context context = contexts.required(sequence);
            if (context.complete()) contexts.release(sequence);
            else contexts.discard(sequence);
        }
        return removed;
    }

    long firstSequence() {
        return head == -1 ? 0 : pendingAt(head).sequence();
    }

    PendingMatching findFirst(Predicate<PendingMatching> predicate) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            PendingMatching pending = pendingAt(slot);
            if (pending != null && predicate.test(pending)) return pending;
        }
        return null;
    }

    void markReady(long sequence) {
        int index = indexOf(sequence);
        if (index < 0) return;
        contexts.required(sequence).markPendingReady();
    }

    PendingMatching pollReadyHead() {
        if (head < 0) return null;
        int index = head;
        PendingMatching pending = pendingAt(index);
        LaneCommandContextRing.Context context = contexts.required(pending.sequence());
        if (!context.pendingReady()) return null;
        context.takePendingReady();
        return pending;
    }

    PendingMatching dispatchHead() {
        return dispatchHead < 0 ? null : pendingAt(dispatchHead);
    }

    void completeDispatch(long sequence) {
        int index = indexOf(sequence);
        if (index < 0 || dispatchHead != index) {
            throw new IllegalStateException("matcher settlement dispatch is out of order");
        }
        dispatchHead = nextSlots[dispatchHead];
    }

    void registerSubmission(long sequence, int matcherShard) {
        if (matcherShard < 0 || matcherShard >= submissionHeads.length) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        int index = indexOf(sequence);
        if (index < 0) throw new IllegalStateException("pending matching sequence is missing");
        int registeredShard = submissionShards[index];
        if (registeredShard == matcherShard) return;
        if (registeredShard >= 0) throw new IllegalStateException("matching submission shard changed");
        int tailSlot = submissionTails[matcherShard];
        submissionShards[index] = matcherShard;
        previousSubmissionSlots[index] = tailSlot;
        nextSubmissionSlots[index] = -1;
        if (tailSlot < 0) submissionHeads[matcherShard] = index;
        else nextSubmissionSlots[tailSlot] = index;
        submissionTails[matcherShard] = index;
    }

    boolean isSubmissionHead(long sequence, int matcherShard) {
        int index = indexOf(sequence);
        return index >= 0 && submissionHeads[matcherShard] == index;
    }

    PendingMatching submissionHead(int matcherShard) {
        int index = submissionHeads[matcherShard];
        return index < 0 ? null : pendingAt(index);
    }

    void completeSubmission(long sequence) {
        int index = indexOf(sequence);
        if (index >= 0) removeFromSubmissionOrder(index);
    }

    PendingMatching findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    void forEach(Consumer<PendingMatching> consumer) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            PendingMatching pending = pendingAt(slot);
            if (pending != null) consumer.accept(pending);
        }
    }

    Map<Long, PendingMatching> snapshot() {
        LinkedHashMap<Long, PendingMatching> snapshot = new LinkedHashMap<>(size);
        forEach(pending -> snapshot.put(pending.sequence(), pending));
        return Collections.unmodifiableMap(snapshot);
    }

    void clear() {
        while (size != 0) remove(firstSequence());
    }

    private void addIndexes(PendingMatching pending) {
        UUID commandId = pending.command().header().commandId();
        entriesByCommandId.put(commandId, pending);
        long userId = pending.command().header().userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    private void removeIndexes(PendingMatching pending) {
        entriesByCommandId.remove(pending.command().header().commandId(), pending);
        long userId = pending.command().header().userId();
        if (userId <= 0) return;
        int remaining = pendingByUser.addToValue(userId, -1);
        if (remaining == 0) pendingByUser.removeKey(userId);
        else if (remaining < 0) throw new IllegalStateException("pending matching user count underflow");
    }

    private void removeFromSubmissionOrder(int index) {
        int shard = submissionShards[index];
        if (shard < 0) return;
        int previous = previousSubmissionSlots[index];
        int next = nextSubmissionSlots[index];
        if (previous < 0) submissionHeads[shard] = next;
        else nextSubmissionSlots[previous] = next;
        if (next < 0) submissionTails[shard] = previous;
        else previousSubmissionSlots[next] = previous;
        submissionShards[index] = -1;
        previousSubmissionSlots[index] = -1;
        nextSubmissionSlots[index] = -1;
    }

    private void requireAvailableCommandId(PendingMatching pending, PendingMatching replaced) {
        PendingMatching duplicate = entriesByCommandId.get(pending.command().header().commandId());
        if (duplicate != null && duplicate != replaced) {
            throw new IllegalStateException("duplicate pending matching commandId");
        }
    }

    private int indexOf(long sequence) {
        int index = slot(sequence);
        PendingMatching pending = pendingAt(index);
        return pending != null && pending.sequence() == sequence ? index : -1;
    }

    private int slot(long sequence) {
        return (int) sequence & mask;
    }

    private PendingMatching pendingAt(int index) {
        return contexts.contextAt(index).pending();
    }

    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    int capacity() { return contexts.capacity(); }
    LaneCommandContextRing contexts() { return contexts; }
}
