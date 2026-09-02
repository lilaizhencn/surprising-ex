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
    private final LongIntHashMap slotsBySequence;
    private final Map<UUID, PendingMatching> entriesByCommandId;
    private final LongIntHashMap pendingByUser;
    private final int mask;
    private int head;
    private int tail;
    private int size;

    PendingMatchingRing(int requestedCapacity) {
        if (requestedCapacity <= 0 || requestedCapacity > 1 << 30) {
            throw new IllegalArgumentException("pending matching capacity must be positive");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1;
        entries = new PendingMatching[capacity];
        sequences = new long[capacity];
        slotsBySequence = new LongIntHashMap(capacity);
        entriesByCommandId = new java.util.HashMap<>(capacity);
        pendingByUser = new LongIntHashMap(capacity);
        mask = capacity - 1;
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
        if (size == entries.length) throw new IllegalStateException("pending matching ring is full");
        int index = tail++ & mask;
        requireAvailableCommandId(pending, null);
        entries[index] = pending;
        sequences[index] = pending.sequence();
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
        if (size == 0 || sequences[head & mask] != sequence) return null;
        int entryIndex = head & mask;
        PendingMatching removed = entries[entryIndex];
        if (removed == null || removed.sequence() != sequence) {
            throw new IllegalStateException("pending matching order is corrupted");
        }
        entries[entryIndex] = null;
        sequences[head++ & mask] = 0;
        removeIndexes(removed);
        size--;
        return removed;
    }

    long firstSequence() {
        return size == 0 ? 0 : sequences[head & mask];
    }

    PendingMatching findFirst(Predicate<PendingMatching> predicate) {
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
            if (pending != null && predicate.test(pending)) return pending;
        }
        return null;
    }

    PendingMatching firstUnsubmittedPlaceAdmission(LongIntHashMap rejections) {
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
            if (pending != null && pending.placeAdmission() != null && !pending.isMatchingSubmitted()
                    && !rejections.containsKey(pending.sequence())) {
                return pending;
            }
        }
        return null;
    }

    boolean hasUnsubmittedPlaceAdmissionBefore(long sequence, LongIntHashMap rejections) {
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
            if (pending == null || pending.sequence() >= sequence) continue;
            if (pending.placeAdmission() != null && !pending.isMatchingSubmitted()
                    && !rejections.containsKey(pending.sequence())) {
                return true;
            }
        }
        return false;
    }

    PendingMatching findByCommandId(UUID commandId) {
        return commandId == null ? null : entriesByCommandId.get(commandId);
    }

    boolean hasUser(long userId) {
        return userId > 0 && pendingByUser.get(userId) > 0;
    }

    void forEach(Consumer<PendingMatching> consumer) {
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
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
        head = 0;
        tail = 0;
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
