package com.surprising.aeron.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

final class PendingMatchingRing {
    private final PendingMatching[] entries;
    private final long[] sequences;
    private final int[] nextSlots;
    private final int[] previousSlots;
    private final int[] freeSlots;
    private final LongIntHashMap slotsBySequence;
    private final Map<UUID, PendingMatching> entriesByCommandId;
    private final LongIntHashMap pendingByUser;
    private int head = -1;
    private int tail = -1;
    private int freeHead;
    private int size;

    PendingMatchingRing(int requestedCapacity) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        entries = new PendingMatching[capacity];
        sequences = new long[capacity];
        nextSlots = new int[capacity];
        previousSlots = new int[capacity];
        freeSlots = new int[capacity];
        java.util.Arrays.fill(nextSlots, -1);
        java.util.Arrays.fill(previousSlots, -1);
        for (int index = 0; index < capacity - 1; index++) freeSlots[index] = index + 1;
        freeSlots[capacity - 1] = -1;
        slotsBySequence = new LongIntHashMap(capacity);
        entriesByCommandId = new java.util.HashMap<>(capacity);
        pendingByUser = new LongIntHashMap(capacity);
    }

    void put(PendingMatching pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        int encodedIndex = slotsBySequence.get(pending.sequence());
        if (encodedIndex != 0) {
            int index = encodedIndex - 1;
            PendingMatching existing = entries[index];
            if (existing == null || existing.sequence() != pending.sequence()) {
                throw new IllegalStateException("pending matching index is corrupted");
            }
            requireAvailableCommandId(pending, existing);
            removeIndexes(existing);
            entries[index] = pending;
            addIndexes(pending, index);
            return;
        }
        if (size == entries.length || freeHead < 0) {
            throw new IllegalStateException("pending matching ring is full");
        }
        int index = freeHead;
        freeHead = freeSlots[index];
        freeSlots[index] = -1;
        requireAvailableCommandId(pending, null);
        entries[index] = pending;
        sequences[index] = pending.sequence();
        previousSlots[index] = tail;
        nextSlots[index] = -1;
        if (tail == -1) head = index;
        else nextSlots[tail] = index;
        tail = index;
        addIndexes(pending, index);
        size++;
    }

    PendingMatching get(long sequence) {
        int encodedIndex = slotsBySequence.get(sequence);
        return encodedIndex == 0 ? null : entries[encodedIndex - 1];
    }

    boolean contains(long sequence) {
        return get(sequence) != null;
    }

    PendingMatching remove(long sequence) {
        int encodedIndex = slotsBySequence.get(sequence);
        if (encodedIndex == 0) return null;
        int entryIndex = encodedIndex - 1;
        PendingMatching removed = entries[entryIndex];
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        entries[entryIndex] = null;
        sequences[entryIndex] = 0;
        int previous = previousSlots[entryIndex];
        int next = nextSlots[entryIndex];
        if (previous == -1) head = next;
        else nextSlots[previous] = next;
        if (next == -1) tail = previous;
        else previousSlots[next] = previous;
        previousSlots[entryIndex] = -1;
        nextSlots[entryIndex] = -1;
        freeSlots[entryIndex] = freeHead;
        freeHead = entryIndex;
        removeIndexes(removed);
        size--;
        return removed;
    }

    long firstSequence() {
        return head == -1 ? 0 : sequences[head];
    }

    PendingMatching findFirst(Predicate<PendingMatching> predicate) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            PendingMatching pending = entries[slot];
            if (pending != null && predicate.test(pending)) return pending;
        }
        return null;
    }

    PendingMatching firstCompletedUnsubmittedPlaceAdmission(LongIntHashMap rejections) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            PendingMatching pending = entries[slot];
            if (pending != null && pending.placeAdmission() != null && !pending.isMatchingSubmitted()
                    && pending.placeAdmission().complete() && !rejections.containsKey(pending.sequence())) {
                return pending;
            }
        }
        return null;
    }

    PendingMatching findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    void forEach(Consumer<PendingMatching> consumer) {
        for (int slot = head; slot != -1; slot = nextSlots[slot]) {
            PendingMatching pending = entries[slot];
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

    private void addIndexes(PendingMatching pending, int index) {
        slotsBySequence.put(pending.sequence(), index + 1);
        UUID commandId = pending.command().header().commandId();
        entriesByCommandId.put(commandId, pending);
        long userId = pending.command().header().userId();
        if (userId > 0) pendingByUser.addToValue(userId, 1);
    }

    private void removeIndexes(PendingMatching pending) {
        slotsBySequence.removeKey(pending.sequence());
        entriesByCommandId.remove(pending.command().header().commandId(), pending);
        long userId = pending.command().header().userId();
        if (userId <= 0) return;
        int remaining = pendingByUser.addToValue(userId, -1);
        if (remaining == 0) pendingByUser.removeKey(userId);
        else if (remaining < 0) throw new IllegalStateException("pending matching user count underflow");
    }

    private void requireAvailableCommandId(PendingMatching pending, PendingMatching replaced) {
        PendingMatching duplicate = entriesByCommandId.get(pending.command().header().commandId());
        if (duplicate != null && duplicate != replaced) {
            throw new IllegalStateException("duplicate pending matching commandId");
        }
    }

    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    int capacity() { return entries.length; }
}
