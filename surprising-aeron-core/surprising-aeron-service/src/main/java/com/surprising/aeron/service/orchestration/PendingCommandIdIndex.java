package com.surprising.aeron.service.orchestration;

import java.util.UUID;

/**
 * Fixed open-addressed command-id index for the bounded pending window.
 *
 * <p>The pending window is already bounded, so a HashMap adds only entry/node
 * allocations and a second object graph. This index keeps the UUID bits and
 * slot reference in preallocated arrays and never allocates during put/get/remove.</p>
 */
final class PendingCommandIdIndex {
    private final long[] mostSignificantBits;
    private final long[] leastSignificantBits;
    private final CommandSlot[] values;
    private final int mask;
    private int size;

    PendingCommandIdIndex(int expectedEntries) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expected entries must be positive");
        int capacity = 1;
        while (capacity < expectedEntries * 2) capacity <<= 1;
        mostSignificantBits = new long[capacity];
        leastSignificantBits = new long[capacity];
        values = new CommandSlot[capacity];
        mask = capacity - 1;
    }

    CommandSlot get(UUID commandId) {
        if (commandId == null) return null;
        int index = index(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
        while (values[index] != null) {
            if (same(index, commandId)) return values[index];
            index = (index + 1) & mask;
        }
        return null;
    }

    void put(UUID commandId, CommandSlot value) {
        if (commandId == null || value == null) throw new IllegalArgumentException("command id and value are required");
        long most = commandId.getMostSignificantBits();
        long least = commandId.getLeastSignificantBits();
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
        if (size * 2 > values.length) throw new IllegalStateException("pending command-id index is full");
    }

    void remove(UUID commandId, CommandSlot expected) {
        if (commandId == null) return;
        long most = commandId.getMostSignificantBits();
        long least = commandId.getLeastSignificantBits();
        int index = index(most, least);
        while (values[index] != null) {
            if (mostSignificantBits[index] == most && leastSignificantBits[index] == least) {
                if (expected != null && values[index] != expected) return;
                removeAt(index);
                return;
            }
            index = (index + 1) & mask;
        }
    }

    void clear() {
        java.util.Arrays.fill(values, null);
        java.util.Arrays.fill(mostSignificantBits, 0);
        java.util.Arrays.fill(leastSignificantBits, 0);
        size = 0;
    }

    private void removeAt(int removed) {
        values[removed] = null;
        mostSignificantBits[removed] = 0;
        leastSignificantBits[removed] = 0;
        size--;
        int index = (removed + 1) & mask;
        while (values[index] != null) {
            CommandSlot value = values[index];
            long most = mostSignificantBits[index];
            long least = leastSignificantBits[index];
            values[index] = null;
            mostSignificantBits[index] = 0;
            leastSignificantBits[index] = 0;
            size--;
            insert(most, least, value);
            index = (index + 1) & mask;
        }
    }

    private void insert(long most, long least, CommandSlot value) {
        int index = index(most, least);
        while (values[index] != null) index = (index + 1) & mask;
        mostSignificantBits[index] = most;
        leastSignificantBits[index] = least;
        values[index] = value;
        size++;
    }

    private boolean same(int index, UUID commandId) {
        return mostSignificantBits[index] == commandId.getMostSignificantBits()
                && leastSignificantBits[index] == commandId.getLeastSignificantBits();
    }

    private int index(long most, long least) {
        long hash = most ^ Long.rotateLeft(least, 23);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdl;
        hash ^= hash >>> 33;
        return (int) hash & mask;
    }
}
