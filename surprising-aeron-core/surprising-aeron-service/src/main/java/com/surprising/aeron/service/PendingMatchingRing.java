package com.surprising.aeron.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class PendingMatchingRing {
    private final PendingMatching[] entries;
    private final long[] sequences;
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
        mask = capacity - 1;
    }

    void put(PendingMatching pending) {
        if (pending == null) throw new IllegalArgumentException("pending matching is required");
        for (int offset = 0; offset < size; offset++) {
            int index = (head + offset) & mask;
            if (sequences[index] == pending.sequence()) {
                entries[index] = pending;
                return;
            }
        }
        if (size == entries.length) throw new IllegalStateException("pending matching ring is full");
        int index = tail++ & mask;
        entries[index] = pending;
        sequences[index] = pending.sequence();
        size++;
    }

    PendingMatching get(long sequence) {
        for (int offset = 0; offset < size; offset++) {
            int index = (head + offset) & mask;
            if (sequences[index] == sequence) return entries[index];
        }
        return null;
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

    PendingMatching findByCommandId(UUID commandId) {
        if (commandId == null) return null;
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
            if (pending != null && commandId.equals(pending.command().header().commandId())) return pending;
        }
        return null;
    }

    boolean hasUser(long userId) {
        if (userId <= 0) return false;
        for (int offset = 0; offset < size; offset++) {
            PendingMatching pending = entries[(head + offset) & mask];
            if (pending != null && pending.command().header().userId() == userId) return true;
        }
        return false;
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

    int size() { return size; }
    boolean isEmpty() { return size == 0; }
    int capacity() { return entries.length; }
}
